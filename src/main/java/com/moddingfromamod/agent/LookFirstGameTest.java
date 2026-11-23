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
 * <b>A question is answered after LOOKING — through the real protocol</b>.
 *
 * <p>Live: <i>"spikor stopped following me, why?"</i> — the agent's whole run was
 * {@code notify_player → finish_wish → finish_building → finish_wish}, looked at nothing, and told
 * the child an invented cause. Here a question wish goes through the real {@link McpProtocol}
 * dispatch (where {@code LookFirst} is applied) with stub tools that only record whether they ran:
 * the answer is refused and never reaches the child, then accepted once a read has happened. An
 * ordinary wish is untouched, and a question that will not look still ends after
 * {@code LookFirst.MAX_REFUSALS}.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class LookFirstGameTest {

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

    private static McpTool recorder(String name, List<String> ran) {
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
                return ToolResult.ok("ok");
            }
        };
    }

    private record Rig(Marshal marshal, McpProtocol protocol, String session, WishRun run, List<String> ran) {
    }

    private static Rig rig(String wishText) {
        Marshal marshal = Marshal.forTest(com.moddingfromamod.marshal.Clock.system(), 1);
        List<String> ran = new ArrayList<>();
        ToolRegistry tools = new ToolRegistry()
                .add(recorder("notify_player", ran))
                .add(recorder("finish_wish", ran))
                .add(recorder("list_creatures", ran));
        McpProtocol protocol = new McpProtocol(tools, new McpSessions(), marshal);
        String session = open(protocol);
        marshal.submit(UUID.randomUUID(), UUID.nameUUIDFromBytes("gt-look-first".getBytes()), wishText);
        WishRun run = marshal.claim(session);
        return new Rig(marshal, protocol, session, run, ran);
    }

    @GameTest(template = "empty_test")
    public static void aQuestionIsAnsweredOnlyAfterLooking(GameTestHelper helper) {
        Rig r = rig("spikor stopped following me, why?");
        helper.assertTrue(r.run() != null, Component.literal("the question was not claimed"));
        JsonObject first = call(r.protocol(), r.session(), "notify_player");
        helper.assertTrue(isError(first) && first.toString().contains("list_creatures"),
                Component.literal("an answer before looking must be refused, naming the read tools: " + first));
        helper.assertTrue(r.ran().isEmpty(), Component.literal("the refused answer reached the child: " + r.ran()));

        helper.assertFalse(isError(call(r.protocol(), r.session(), "list_creatures")), Component.literal("the read failed"));
        helper.assertFalse(isError(call(r.protocol(), r.session(), "notify_player")),
                Component.literal("after looking, the answer must go through"));
        helper.assertFalse(isError(call(r.protocol(), r.session(), "finish_wish")), Component.literal("finish_wish after looking"));
        helper.assertTrue(r.ran().equals(List.of("list_creatures", "notify_player", "finish_wish")),
                Component.literal("what ran: " + r.ran()));
        helper.succeed();
    }

    @GameTest(template = "empty_test")
    public static void aWishThatIsNotAQuestionIsNotAskedToLook(GameTestHelper helper) {
        Rig r = rig("make me a spikor that follows me");
        helper.assertFalse(isError(call(r.protocol(), r.session(), "notify_player")),
                Component.literal("an ordinary wish must not be made to look before it speaks"));
        helper.succeed();
    }

    @GameTest(template = "empty_test")
    public static void aQuestionThatWillNotLookStillEnds(GameTestHelper helper) {
        Rig r = rig("why is the sky blue?");
        for (int i = 0; i < com.moddingfromamod.wish.LookFirst.MAX_REFUSALS; i++) {
            helper.assertTrue(isError(call(r.protocol(), r.session(), "finish_wish")), Component.literal("refusal " + (i + 1)));
        }
        helper.assertFalse(isError(call(r.protocol(), r.session(), "finish_wish")),
                Component.literal("after " + com.moddingfromamod.wish.LookFirst.MAX_REFUSALS + " refusals the wish must be able to end"));
        helper.succeed();
    }
}
