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
import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.wish.BuildGate;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>After the finish: polish in place, never replace — through the real protocol</b> (owner,
 * 2026-10-22). The rule is {@code AfterFinish}; this proves it is WIRED where tools run, with stub
 * tools that only record whether their body ran: a finished wish may still change what its own
 * creature does, and may not resize it, spawn, give or build.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class AfterFinishGameTest {

    private static McpProtocol.Headers headers(String session) {
        Map<String, String> m = new HashMap<>();
        m.put("Content-Type", "application/json");
        m.put("Accept", "application/json, text/event-stream");
        if (session != null) {
            m.put("Mcp-Session-Id", session);
        }
        return McpProtocol.Headers.of(m);
    }

    private static JsonObject call(McpProtocol protocol, String session, String tool, String args) {
        var reply = protocol.handlePost(headers(session),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\""
                        + tool + "\",\"arguments\":" + args + "}}", 0L);
        return JsonParser.parseString(reply.body()).getAsJsonObject().getAsJsonObject("result");
    }

    private static boolean ran(JsonObject result) {
        return !result.get("isError").getAsBoolean();
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

    @GameTest(template = "empty_test")
    public static void aFinishedWishPolishesItsOwnCreatureAndReplacesNothing(GameTestHelper helper) {
        Marshal marshal = Marshal.forTest(com.moddingfromamod.marshal.Clock.system(), 1);
        List<String> ran = new ArrayList<>();
        ToolRegistry tools = new ToolRegistry();
        for (String t : List.of("plan_wish", "modify_mob", "run_command", "build_structure",
                "give_items")) {
            tools.add(recorder(t, ran));
        }
        McpProtocol protocol = new McpProtocol(tools, new McpSessions(), marshal, () -> false);
        String session = protocol.handlePost(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L)
                .headers().get("Mcp-Session-Id");
        marshal.submit(UUID.randomUUID(), UUID.nameUUIDFromBytes("gt-after-finish".getBytes()),
                "make me a spikor that follows me");
        WishRun run = marshal.claim(session);
        helper.assertTrue(run != null, Component.literal("the wish was not claimed"));
        helper.assertTrue(ran(call(protocol, session, "plan_wish", "{}")), Component.literal("the plan failed"));
        String species = "gt_after_finish_" + helper.getLevel().getGameTime();
        run.made(MadeKind.CREATURE, species);
        BuildGate.forWish(run.wishId()).markFinishBuildingCalled();
        ran.clear();

        JsonObject polish = call(protocol, session, "modify_mob",
                "{\"scope\":\"one\",\"target\":\"@" + species + "\",\"follow\":true}");
        helper.assertTrue(ran(polish),
                Component.literal("a polish of what its own creature does was refused after the finish: " + polish));
        helper.assertTrue(!ran(call(protocol, session, "modify_mob",
                        "{\"scope\":\"one\",\"target\":\"@" + species + "\",\"scale\":2}")),
                Component.literal("a resize rebuilds the creature, so it must refuse after the finish"));
        helper.assertTrue(!ran(call(protocol, session, "modify_mob",
                        "{\"scope\":\"one\",\"target\":\"zombie\",\"follow\":true}")),
                Component.literal("a mob this wish did not make must not be changed after the finish"));
        helper.assertTrue(!ran(call(protocol, session, "run_command",
                        "{\"command\":[\"wishmob spawn " + species + "\"]}")),
                Component.literal("a second spawn after the finish ran"));
        helper.assertTrue(!ran(call(protocol, session, "give_items",
                        "{\"item\":\"minecraft:oak_boat\"}")),
                Component.literal("a gift after the finish ran"));
        helper.assertTrue(!ran(call(protocol, session, "build_structure", "{\"id\":\"volcano\"}")),
                Component.literal("a build after the finish ran"));
        helper.assertTrue(ran.equals(List.of("modify_mob")), Component.literal("what ran: " + ran));
        marshal.finish(run.wishId());
        helper.succeed();
    }
}
