package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>{@code plan_wish} is mandatory — through the real protocol</b> (D16).
 *
 * <p>The rule is {@code PlanFirst}; this proves it is WIRED where tools run. A wish goes through the
 * real {@link McpProtocol} dispatch with stub tools that only record whether their body ran: a build
 * tool is refused naming {@code plan_wish} and never runs, then runs once the wish has planned. A
 * call with no wish bound, a tool that changes nothing, and a teleport are untouched;
 * {@code run_command} needs a plan only in a budgeted world, and each test hands its protocol its OWN budget predicate,
 * because GameTests in one batch run concurrently and share every static.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class PlanFirstGameTest {

    private static McpProtocol.Headers headers(String session) {
        Map<String, String> m = new HashMap<>();
        m.put("Content-Type", "application/json");
        m.put("Accept", "application/json, text/event-stream");
        if (session != null) {
            m.put("Mcp-Session-Id", session);
        }
        return McpProtocol.Headers.of(m);
    }

    private static String open(McpProtocol protocol) {
        return protocol.handlePost(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L)
                .headers().get("Mcp-Session-Id");
    }

    private static JsonObject call(McpProtocol protocol, String session, String tool) {
        var reply = protocol.handlePost(headers(session),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\""
                        + tool + "\",\"arguments\":{}}}", 0L);
        return JsonParser.parseString(reply.body()).getAsJsonObject().getAsJsonObject("result");
    }

    private static boolean isError(JsonObject result) {
        return result.get("isError").getAsBoolean();
    }

    private static McpTool recorder(String name, List<String> ran, boolean fails) {
        return new McpTool() {
            @Override public String name() {
                return name;
            }

            @Override public String description() {
                return "records that it ran";
            }

            @Override public JsonObject inputSchema() {
                return Schemas.object();
            }

            @Override public ToolResult call(JsonObject args) {
                ran.add(name);
                return fails ? ToolResult.error("no") : ToolResult.ok("ok");
            }
        };
    }

    private record Rig(McpProtocol protocol, String session, WishRun run, List<String> ran) {
    }

    private static Rig rig(boolean bind, boolean budgeted, boolean planFails) {
        Marshal marshal = Marshal.forTest(com.moddingfromamod.marshal.Clock.system(), 1);
        List<String> ran = new ArrayList<>();
        ToolRegistry tools = new ToolRegistry()
                .add(recorder("plan_wish", ran, planFails))
                .add(recorder("make_item", ran, false))
                .add(recorder("build_structure", ran, false))
                .add(recorder("run_command", ran, false))
                .add(recorder("remove_build", ran, false))
                .add(recorder("take_me_to", ran, false))
                .add(recorder("notify_player", ran, false));
        McpProtocol protocol = new McpProtocol(tools, new McpSessions(), marshal, () -> budgeted);
        String session = open(protocol);
        WishRun run = null;
        if (bind) {
            marshal.submit(UUID.randomUUID(), UUID.nameUUIDFromBytes("gt-plan-first".getBytes()),
                    "make me a bow that shoots lasers");
            run = marshal.claim(session);
        }
        return new Rig(protocol, session, run, ran);
    }

    @GameTest(template = "empty_test")
    public static void aBuildToolIsRefusedUntilTheWishHasAPlan(GameTestHelper helper) {
        Rig r = rig(true, false, false);
        helper.assertTrue(r.run() != null, Component.literal("the wish was not claimed"));
        JsonObject first = call(r.protocol(), r.session(), "make_item");
        helper.assertTrue(isError(first) && first.toString().contains("plan_wish"),
                Component.literal("a build tool before a plan must be refused, naming plan_wish: " + first));
        helper.assertTrue(r.ran().isEmpty(), Component.literal("the refused tool's body ran anyway: " + r.ran()));
        helper.assertTrue(r.run().toolsUsed().isEmpty(),
                Component.literal("a refused call changed nothing, so it is not something the wish used: " + r.run().toolsUsed()));

        helper.assertFalse(isError(call(r.protocol(), r.session(), "plan_wish")), Component.literal("the plan failed"));
        helper.assertTrue(r.run().planned(), Component.literal("a successful plan_wish must mark the wish planned"));
        helper.assertFalse(isError(call(r.protocol(), r.session(), "make_item")),
                Component.literal("after the plan, the build must go through"));
        helper.assertFalse(isError(call(r.protocol(), r.session(), "build_structure")),
                Component.literal("and every build after it"));
        helper.assertTrue(r.ran().equals(List.of("plan_wish", "make_item", "build_structure")),
                Component.literal("what ran: " + r.ran()));
        helper.succeed();
    }

    @GameTest(template = "empty_test")
    public static void aPlanThatFailedIsNotAPlan(GameTestHelper helper) {
        // A plan the safety gate diverted, or one refused for bad arguments, did not happen: the
        // child was shown no plan and there is nothing to price.
        Rig r = rig(true, false, true);
        helper.assertTrue(isError(call(r.protocol(), r.session(), "plan_wish")), Component.literal("the stub plan must fail"));
        helper.assertFalse(r.run().planned(), Component.literal("a failed plan_wish marked the wish planned"));
        helper.assertTrue(isError(call(r.protocol(), r.session(), "make_item")),
                Component.literal("a build after a FAILED plan must still be refused"));
        helper.succeed();
    }

    @GameTest(template = "empty_test")
    public static void readingTalkingAndTakingAwayNeedNoPlan(GameTestHelper helper) {
        Rig r = rig(true, false, false);
        helper.assertFalse(isError(call(r.protocol(), r.session(), "notify_player")),
                Component.literal("telling the child something is not changing the world"));
        helper.assertFalse(isError(call(r.protocol(), r.session(), "remove_build")),
                Component.literal("taking a thing away is exempt"));
        helper.assertFalse(isError(call(r.protocol(), r.session(), "run_command")),
                Component.literal("run_command needs no plan in a world with no wish budget"));
        // a teleport moves him, not the world, and was refused first in 2 of 34 real wishes.
        helper.assertFalse(isError(call(r.protocol(), r.session(), "take_me_to")),
                Component.literal("take_me_to only moves the child: it needs no plan"));
        helper.assertTrue(r.ran().contains("take_me_to"), Component.literal("take_me_to's body did not run: " + r.ran()));
        helper.succeed();
    }

    @GameTest(template = "empty_test")
    public static void runCommandNeedsAPlanWhereWishesArePriced(GameTestHelper helper) {
        Rig r = rig(true, true, false);
        JsonObject first = call(r.protocol(), r.session(), "run_command");
        helper.assertTrue(isError(first) && first.toString().contains("plan_wish"),
                Component.literal("in a budgeted world run_command is refused before a plan, naming plan_wish: " + first));
        helper.assertTrue(r.ran().isEmpty(), Component.literal("the refused command ran anyway: " + r.ran()));
        helper.assertFalse(isError(call(r.protocol(), r.session(), "plan_wish")), Component.literal("the plan failed"));
        helper.assertFalse(isError(call(r.protocol(), r.session(), "run_command")),
                Component.literal("after the plan, the command runs"));
        helper.succeed();
    }

    @GameTest(template = "empty_test")
    public static void aCallWithNoWishBoundIsNotRefused(GameTestHelper helper) {
        Rig r = rig(false, true, false);
        helper.assertFalse(isError(call(r.protocol(), r.session(), "make_item")),
                Component.literal("with no wish bound there is nothing to plan for"));
        helper.assertTrue(r.ran().equals(List.of("make_item")), Component.literal("what ran: " + r.ran()));
        helper.succeed();
    }
}
