package com.moddingfromamod.test.contract;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolRegistry;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.world.Landing;
import com.moddingfromamod.world.WorldMakes;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * #70: the contract world keeps a MAGICIAN wish. Driven through the real {@code McpProtocol} dispatch (the one every
 * tool call of the agent passes), with stub tool bodies, the {@code MagicianTouchGameTest} pattern: what is decided,
 * refused and handed over is the dispatch's, not the tools'. The world's answer is asked once at the first plan and
 * locked; a placing call is refused with why; the work is not; finishing puts the taught recipe on his hotbar and tells
 * the world. A picture lands (warned), and with no world nothing is asked. Test code: never in the jar.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ContractWorldKeptMagicianGameTest {

    private ContractWorldKeptMagicianGameTest() {
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

    private static McpTool stub(String name, Map<String, AtomicInteger> reached) {
        reached.put(name, new AtomicInteger());
        return new McpTool() {
            @Override public String name() {
                return name;
            }

            @Override public String description() {
                return "counts what reached it";
            }

            @Override public JsonObject inputSchema() {
                return Schemas.object();
            }

            @Override public ToolResult call(JsonObject args) {
                reached.get(name).incrementAndGet();
                return ToolResult.ok("done");
            }
        };
    }

    /** One wish of {@code owner}'s, claimed: its protocol, session and run. */
    private record Wish(McpProtocol protocol, String session, WishRun run, Map<String, AtomicInteger> reached) {
        JsonObject call(String tool, String args) {
            return ContractWorldKeptMagicianGameTest.call(protocol, session, tool, args);
        }
    }

    private static Wish wish(UUID owner, String words) {
        Marshal marshal = Marshal.forTest(com.moddingfromamod.marshal.Clock.system(), 1);
        Map<String, AtomicInteger> reached = new HashMap<>();
        ToolRegistry tools = new ToolRegistry();
        for (String t : new String[]{"plan_wish", "sculpt_creature", "run_command", "paint_surface", "add_template",
                "finish_wish"}) {
            tools.add(stub(t, reached));
        }
        McpProtocol protocol = new McpProtocol(tools, new McpSessions(), marshal);
        String session = protocol.handlePost(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L)
                .headers().get("Mcp-Session-Id");
        marshal.submit(UUID.randomUUID(), owner, words);
        return new Wish(protocol, session, marshal.claim(session), reached);
    }

    private static boolean error(JsonObject r) {
        return r.has("isError") && r.get("isError").getAsBoolean();
    }

    @GameTest(template = "empty_test", batch = "contract_world_kept_magician", timeoutTicks = 4000)
    public static void aKeptMagicianWishDoesItsWorkPlacesNothingAndIsHandedOver(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "gtcontractkeptmagic");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.getInventory().clearContent();
        Plugins mine = new Plugins();
        new ContractWorld().register(mine);
        Plugins.shared().replaceForTest(WorldMakes.class, mine.first(WorldMakes.class).orElseThrow());
        ContractWorld.keepFor(p.getUUID(), Landing.KEPT);
        MockPlayerNetwork.afterTest(helper, () -> {
            Plugins.shared().replaceForTest(WorldMakes.class, null);
            ContractWorld.keepFor(p.getUUID(), null);
        });
        UUID owner = p.getUUID();
        String recipe = "gt_kept_dragon_recipe";
        var run = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Wish w = wish(owner, "a dragon");
            if (w.run() == null) {
                return "the wish was not claimed";
            }
            JsonObject plan = w.call("plan_wish", "{\"kind\":\"creature\",\"about\":\"new\",\"steps\":[\"a dragon\"]}");
            if (error(plan) || !plan.toString().contains("THIS WISH IS KEPT BY THIS WORLD")
                    || w.run().landing() != Landing.KEPT) {
                return "the plan is told it is kept, and it is locked on the run: " + plan + " " + w.run().landing();
            }
            // the world changes its mind: the run keeps what it was told at the plan
            ContractWorld.keepFor(owner, null);
            JsonObject spawned = w.call("sculpt_creature", "{\"id\":\"gt_kept_dragon\"}");
            if (!error(spawned) || !spawned.toString().contains("spawn:false") || w.reached().get("sculpt_creature").get() != 0) {
                return "a sculpt that would spawn is refused, saying why, and never reaches the tool: " + spawned;
            }
            JsonObject summon = w.call("run_command", "{\"command\":[\"execute as @p run summon pig ~ ~ ~\"]}");
            if (!error(summon) || w.reached().get("run_command").get() != 0) {
                return "a placing command is refused: " + summon;
            }
            for (String[] ok : new String[][]{{"sculpt_creature", "{\"id\":\"gt_kept_dragon\",\"spawn\":false}"},
                    {"run_command", "{\"command\":[\"time set day\"]}"},
                    {"add_template", "{\"id\":\"" + recipe + "\",\"commands\":[\"wishmob spawn gt_kept_dragon\"]}"},
                    {"finish_wish", "{}"}}) {
                JsonObject r = w.call(ok[0], ok[1]);
                if (error(r) || w.reached().get(ok[0]).get() == 0) {
                    return "the work goes on: " + ok[0] + " " + r;
                }
            }
            // a picture has no recipe that hands it over: it lands (and the core warns)
            ContractWorld.keepFor(owner, Landing.KEPT);
            Wish pic = wish(owner, "a picture");
            JsonObject pp = pic.call("plan_wish", "{\"kind\":\"picture\",\"about\":\"new\",\"steps\":[\"a picture\"]}");
            JsonObject painted = pic.call("paint_surface", "{}");
            if (pic.run().landing() != Landing.LANDS || pp.toString().contains("KEPT") || error(painted)) {
                return "a picture lands: " + pic.run().landing() + " " + painted;
            }
            // with no world answering, nothing is asked and nothing is refused
            Plugins.shared().replaceForTest(WorldMakes.class, null);
            Plugins.shared().hideForTest(WorldMakes.class, true);
            try {
                Wish plain = wish(owner, "a dragon");
                plain.call("plan_wish", "{\"kind\":\"creature\",\"about\":\"new\",\"steps\":[\"a dragon\"]}");
                JsonObject s = plain.call("sculpt_creature", "{\"id\":\"gt_plain_dragon\"}");
                if (error(s) || plain.run().kept()) {
                    return "no world: the game as it is: " + s;
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
            boolean onHotbar = false;
            for (int i = 0; i < 9; i++) {
                onHotbar |= recipe.equals(com.moddingfromamod.wish.WishHotbar.recipeOf(p.getInventory().getItem(i)));
            }
            helper.assertTrue(onHotbar, Component.literal("finishing put the taught recipe on his hotbar"));
            var told = ContractWorld.handedOver(owner);
            helper.assertTrue(told.size() == 1 && told.get(0).recipe().equals(recipe) && told.get(0).kind().equals("creature")
                    && !told.get(0).realItem(), Component.literal("the world is told: " + told));
        });
    }
}
