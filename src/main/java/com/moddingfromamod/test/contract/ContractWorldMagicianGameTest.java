package com.moddingfromamod.test.contract;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.ToolArgFit;
import com.moddingfromamod.agent.ToolRegistry;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.world.WorldMakes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * #69: the contract world covers the Magician. A making tool's arguments are fitted to this world's limits (a creature's stats held where it stands) in
 * {@code McpProtocol}'s {@code tools/call} (the one dispatch every tool call passes), after the safety layers and the
 * world's rules, and the result carries the core's note; what no fact measures is kept; the brief carries the limits as
 * data; with no world answering nothing is touched. Driven with stub tools, the {@code MagicianTouchGameTest} pattern:
 * the tool body is not the point, what reaches it is. Test code: never in the jar.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ContractWorldMagicianGameTest {

    private ContractWorldMagicianGameTest() {
    }

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

    private static McpTool recording(String name, AtomicReference<JsonObject> got) {
        return new McpTool() {
            @Override public String name() {
                return name;
            }

            @Override public String description() {
                return "records what reached it";
            }

            @Override public JsonObject inputSchema() {
                return Schemas.object();
            }

            @Override public ToolResult call(JsonObject args) {
                got.set(args.deepCopy());
                return ToolResult.ok("made");
            }
        };
    }

    /** A wish of {@code owner}'s, claimed, planned: then one sculpt_creature call; what reached the tool and the reply. */
    private static String sculpt(UUID owner, AtomicReference<JsonObject> got, AtomicReference<String> reply) {
        Marshal marshal = Marshal.forTest(com.moddingfromamod.marshal.Clock.system(), 1);
        ToolRegistry tools = new ToolRegistry().add(recording("plan_wish", new AtomicReference<>()))
                .add(recording("sculpt_creature", got));
        McpProtocol protocol = new McpProtocol(tools, new McpSessions(), marshal);
        String session = protocol.handlePost(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L)
                .headers().get("Mcp-Session-Id");
        marshal.submit(UUID.randomUUID(), owner, "the strongest dragon ever");
        WishRun run = marshal.claim(session);
        if (run == null) {
            return "the wish was not claimed";
        }
        call(protocol, session, "plan_wish", "{}");
        JsonObject r = call(protocol, session, "sculpt_creature",
                "{\"id\":\"gt_strongest_dragon\",\"health\":500,\"damage\":4,\"powers\":[\"a_custom_datapack_power\"],"
                        + "\"spawn\":false}");
        reply.set(r.toString());
        return r.get("isError").getAsBoolean() ? "the call was refused: " + r : null;
    }

    @GameTest(template = "empty_test", batch = "contract_world_magician", timeoutTicks = 4000)
    public static void aMagicianToolIsFittedToTheWorldsLimitsAndTheCoreTellsTheAgent(GameTestHelper helper) {
        Plugins mine = new Plugins();
        new ContractWorld().register(mine);
        Plugins.shared().replaceForTest(WorldMakes.class, mine.first(WorldMakes.class).orElseThrow());
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper,
                () -> Plugins.shared().replaceForTest(WorldMakes.class, null));
        UUID owner = UUID.randomUUID();
        var run = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            // the brief: this world's limits as data (fact keys and numbers only)
            String brief = ToolArgFit.brief(com.moddingfromamod.wish.MakeGate.limits(owner).limits());
            if (!brief.contains("healthHalfHearts=40") || !brief.contains("attackDamageHalfHearts=6")) {
                return "the brief does not carry the limits: " + brief;
            }
            AtomicReference<JsonObject> got = new AtomicReference<>();
            AtomicReference<String> reply = new AtomicReference<>();
            String problem = sculpt(owner, got, reply);
            if (problem != null) {
                return problem;
            }
            JsonObject args = got.get();
            if (args == null || args.get("health").getAsDouble() != 500 || args.get("damage").getAsDouble() != 4) {
                return "a creature's health is held where it stands, not written into its record (asked 500): " + args;
            }
            if (!args.get("powers").toString().contains("a_custom_datapack_power")) {
                return "what no fact measures is kept as it is: " + args;
            }
            if (!reply.get().contains("health is held to 40 where it stands: this world's limit")) {
                return "the core tells the agent: " + reply.get();
            }
            // with no world answering, nothing is touched
            Plugins.shared().replaceForTest(WorldMakes.class, null);
            Plugins.shared().hideForTest(WorldMakes.class, true);
            try {
                AtomicReference<JsonObject> plain = new AtomicReference<>();
                AtomicReference<String> plainReply = new AtomicReference<>();
                problem = sculpt(owner, plain, plainReply);
                if (problem != null) {
                    return "no world: " + problem;
                }
                if (plain.get() == null || plain.get().get("health").getAsDouble() != 500
                        || plainReply.get().contains("this world's limit")) {
                    return "with no world answering the call is as asked: " + plain.get() + " " + plainReply.get();
                }
            } finally {
                Plugins.shared().hideForTest(WorldMakes.class, false);
            }
            return null;
        });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(run.isDone(), Component.literal("the calls are still running"));
            String problem;
            try {
                problem = run.join();
            } catch (RuntimeException e) {
                problem = "threw: " + e;
            }
            helper.assertTrue(problem == null, Component.literal(String.valueOf(problem)));
        });
    }
}
