package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolRegistry;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.datapack.CompileResult;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.world.Base;
import com.moddingfromamod.world.Bases;
import com.moddingfromamod.world.Region;
import com.moddingfromamod.world.WorldModuleHost;
import com.moddingfromamod.world.WorldRules;
import com.moddingfromamod.world.WorldRulesService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;

/**
 * <b> where it RUNS: a wish never makes a command block, structure block or jigsaw — on either world, with
 * {@code enable-command-block} TRUE — and on a world with bases no child's sidebar or team is on another child's
 * screen.</b>
 *
 * <p>Each path is driven through its real entry point, not the helper: the agent's {@code run_command} through
 * {@link McpProtocol}'s {@code tools/call} (so {@code BaseReach} judges it first on a world with bases, then the tool's
 * own {@code CommandGuard}), a learned button through {@link WishExecutor#runOne}, a build palette through
 * {@link Builder#submit} (where {@code build_structure} and a replayed {@code wishbuild} both land), and a
 * datapack through {@link CbScriptCompiler#compile} (where {@code author_datapack} and every {@code make_item}
 * pack land). Every refusal has its control beside it: the ordinary block, chest, sign, build or pack in the
 * same call runs, and a world with no rules keeps its sidebar and its player teams.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class CommandBlockGuardGameTest {

    private static final String BATCH = "commandblockguard";
    private static final int TIMEOUT = 2_000;

    /** A world with bases, its shape of rules: world-wide rules denied, wishes fenced to his base. */
    private static final String CLOSED_RULES = "{\"store\":\"world-rules\",\"v\":1,\"world\":\"closed\",\"rules\":["
            + "{\"id\":\"no-world-rules\",\"type\":\"deny_world_effect\",\"effects\":[\"worldwide_datapacks_standing_rules\"],"
            + "\"message\":{\"rule\":\"a wish can't make a rule for the whole world\",\"why\":\"it would change every other island too\"}},"
            + "{\"id\":\"your-own-island\",\"type\":\"base_reach\",\"effects\":[\"outside_own_base\",\"others_bases\","
            + "\"other_dimensions\"],\"message\":{\"rule\":\"a wish stays on your own island\",\"why\":\"every island "
            + "belongs to the player who lives there\"}}]}";

    private CommandBlockGuardGameTest() {
    }

    // ── the rig: a base around him, the real run_command behind the real tools/call ─────────────────────

    static Bases baseAround(ServerPlayer him) {
        int x = him.getBlockX();
        int z = him.getBlockZ();
        UUID owner = him.getUUID();
        Base base = new Base(owner, Bases.OVERWORLD, x, him.getBlockY(), z, him.getBlockY(),
                new Region(x - 48, x + 48, z - 48, z + 48), new Region(x - 40, x + 40, z - 40, z + 40));
        return new Bases() {
            @Override public String noun() {
                return "island";
            }

            @Override public Base baseOf(UUID player) {
                return owner.equals(player) ? base : null;
            }

            @Override public UUID ownerAt(String dimension, double px, double pz) {
                return base.area().contains(px, pz) ? owner : null;
            }

            @Override public Bases.Pos positionOf(UUID player) {
                return new Bases.Pos(Bases.OVERWORLD, him.getX(), him.getY(), him.getZ());
            }
        };
    }

    static WorldRules closedRules() {
        WorldRules.Parsed parsed = WorldRules.parse(CLOSED_RULES, "closed");
        if (!parsed.ok()) {
            throw new IllegalStateException("rules: " + parsed.errors());
        }
        return parsed.rules();
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

    private static McpTool planRecorder() {
        return new McpTool() {
            @Override public String name() {
                return "plan_wish";
            }

            @Override public String description() {
                return "records";
            }

            @Override public JsonObject inputSchema() {
                return Schemas.object();
            }

            @Override public ToolResult call(JsonObject args) {
                return ToolResult.ok("planned");
            }
        };
    }

    /** A wish of his, bound to a session of a protocol that judges with {@code world} and {@code rules}. */
    record Wish(McpProtocol protocol, String session, UUID wishId) {
        /** One {@code run_command} through the real {@code tools/call}: {isError, text}. */
        String[] run(String command) {
            JsonObject args = new JsonObject();
            JsonArray cmds = new JsonArray();
            cmds.add(command);
            args.add("command", cmds);
            return call("run_command", args);
        }

        /** Any tool through the real {@code tools/call}: {isError, text}. */
        String[] call(String tool, JsonObject args) {
            JsonObject params = new JsonObject();
            params.addProperty("name", tool);
            params.add("arguments", args);
            JsonObject rpc = new JsonObject();
            rpc.addProperty("jsonrpc", "2.0");
            rpc.addProperty("id", 2);
            rpc.addProperty("method", "tools/call");
            rpc.add("params", params);
            AgentContext.bind(session);
            try {
                var reply = protocol.handlePost(headers(session), rpc.toString(), 0L);
                JsonObject result = JsonParser.parseString(reply.body()).getAsJsonObject().getAsJsonObject("result");
                return new String[]{String.valueOf(result.get("isError").getAsBoolean()), result.toString()};
            } finally {
                AgentContext.unbind();
            }
        }

        void end() {
            Marshal.global().finish(wishId);
        }
    }

    static Wish wish(ServerPlayer him, Bases world, WorldRules rules) {
        McpProtocol protocol = new McpProtocol(new ToolRegistry().add(planRecorder()).add(new RunCommandTool())
                .add(new com.moddingfromamod.agent.tools.GiveItemsTool()),
                new McpSessions(), Marshal.global(), () -> false, () -> world, () -> rules);
        String session = protocol.handlePost(headers(null),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L)
                .headers().get("Mcp-Session-Id");
        UUID id = UUID.randomUUID();
        Marshal.global().submit(id, him.getUUID(), "gt command block guard");
        Marshal.global().assign(session, id);
        protocol.handlePost(headers(session), "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"plan_wish\",\"arguments\":{}}}", 0L);
        return new Wish(protocol, session, id);
    }

    /** What was refused that should have been, and what was refused that should have run. */
    record Tally(List<String> wrong) {
        Tally() {
            this(new ArrayList<>());
        }

        void refused(String world, String cmd, String[] r) {
            if (!Boolean.parseBoolean(r[0]) || !r[1].contains("#2466")) {
                wrong.add(world + ": NOT refused (as #2466): " + cmd + " → " + r[1]);
            }
        }

        void fenced(String world, String cmd, String[] r, String words) {
            if (!Boolean.parseBoolean(r[0]) || !r[1].contains(words)) {
                wrong.add(world + ": NOT fenced: " + cmd + " → " + r[1]);
            }
        }

        void ran(String world, String cmd, String[] r) {
            if (Boolean.parseBoolean(r[0])) {
                wrong.add(world + ": refused but should have RUN: " + cmd + " → " + r[1]);
            }
        }
    }

    private static void inBackground(GameTestHelper helper, Runnable body, AtomicReference<Throwable> failed,
                                     AtomicReference<Boolean> done) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                failed.set(e);
            } finally {
                done.set(true);
            }
        }, "gametest-commandblockguard");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isCommandBlock(GameTestHelper helper, BlockPos p) {
        var s = helper.getLevel().getBlockState(p);
        return s.is(Blocks.COMMAND_BLOCK) || s.is(Blocks.CHAIN_COMMAND_BLOCK) || s.is(Blocks.REPEATING_COMMAND_BLOCK)
                || s.is(Blocks.STRUCTURE_BLOCK) || s.is(Blocks.JIGSAW);
    }

    private static int count(ServerPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (var stack : p.getInventory().getNonEquipmentItems()) {
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    // ── run_command, both worlds ─────────────────────────────────────────────────────────────────────────

    /**
     * The refusal list from the issue, through the real {@code tools/call} on a world with no rules and on a
     * world with bases (his base around him, so BaseReach lets it past and the tool's own guard is what answers), with the
     * server ALLOWING command blocks — the property the refusal must not depend on. Beside them, ordinary
     * blocks, a chest with items and a sign with text run unchanged on both.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void runCommandNeverMakesACommandBlockOnEitherWorld(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer him = MockPlayerNetwork.inLevel(helper, "mfam-cmdblock");
        var server = helper.getLevel().getServer();
        helper.assertTrue(server.overworld().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.COMMAND_BLOCKS_WORK),
                Component.literal("the test must run with command blocks ENABLED, or it proves nothing"));
        BlockPos at = him.blockPosition();
        // An existing command block for `data merge` to aim at — put there by the test, not a wish.
        BlockPos existing = at.below().east(3);
        helper.getLevel().setBlockAndUpdate(existing, Blocks.COMMAND_BLOCK.defaultBlockState());
        Tally tally = new Tally();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        Bases bases = baseAround(him);
        WorldRules rules = closedRules();
        inBackground(helper, () -> {
            for (int w = 0; w < 2; w++) {
                String world = w == 0 ? "open" : "closed";
                Wish wish = w == 0 ? wish(him, null, null) : wish(him, bases, rules);
                try {
                    for (String c : new String[]{
                            "setblock ~ ~1 ~ command_block{Command:\"say hi\",auto:1b}",
                            "setblock ~ ~1 ~ minecraft:repeating_command_block{auto:1b}",
                            "fill ~ ~1 ~ ~1 ~2 ~1 minecraft:chain_command_block",
                            "fill ~ ~1 ~ ~1 ~1 ~1 structure_block",
                            "fill ~ ~1 ~ ~1 ~1 ~1 minecraft:jigsaw",
                            "clone ~ ~-1 ~ ~ ~-1 ~ ~ ~1 ~ filtered minecraft:command_block",
                            "give @s command_block",
                            "give @s minecraft:structure_block 2",
                            "item replace entity @s weapon.mainhand with command_block",
                            "summon command_block_minecart ~ ~1 ~",
                            "data merge block ~3 ~-1 ~ {Command:\"op @a\",auto:1b}",
                            "execute as @s at @s run setblock ~ ~1 ~ command_block"}) {
                        tally.refused(world, c, wish.run(c));
                    }
                    JsonObject gift = new JsonObject();
                    gift.addProperty("item", "minecraft:command_block");
                    tally.refused(world, "give_items command_block", wish.call("give_items", gift));
                    int dx = w == 0 ? 5 : 8;
                    for (String c : new String[]{
                            "setblock ~" + dx + " ~1 ~ stone",
                            "fill ~" + dx + " ~2 ~ ~" + (dx + 1) + " ~2 ~1 oak_planks",
                            "setblock ~" + dx + " ~1 ~2 chest{Items:[{Slot:0b,id:\"minecraft:apple\",count:3}]}",
                            "setblock ~" + dx + " ~1 ~-2 oak_sign{front_text:{messages:[\"hi\",\"\",\"\",\"\"]}}"}) {
                        tally.ran(world, c, wish.run(c));
                    }
                } finally {
                    wish.end();
                }
            }
        }, failed, done);
        helper.succeedWhen(() -> {
            helper.assertTrue(done.get(), Component.literal("waiting for the calls"));
            helper.assertTrue(failed.get() == null, Component.literal("the rig threw: " + failed.get()));
            helper.assertTrue(tally.wrong().isEmpty(), Component.literal(String.join("\n", tally.wrong())));
            helper.assertFalse(isCommandBlock(helper, at.above()), Component.literal("a command block was placed"));
            helper.assertTrue(count(him, Items.COMMAND_BLOCK) == 0 && count(him, Items.STRUCTURE_BLOCK) == 0,
                    Component.literal("he was handed a command or structure block"));
            var be = helper.getLevel().getBlockEntity(existing);
            helper.assertTrue(be instanceof CommandBlockEntity cbe && cbe.getCommandBlock().getCommand().isEmpty(),
                    Component.literal("the existing command block was given a command"));
            for (int dx : new int[]{5, 8}) {
                helper.assertTrue(helper.getLevel().getBlockState(at.east(dx).above()).is(Blocks.STONE),
                        Component.literal("an ordinary setblock did not run (+" + dx + ")"));
                helper.assertTrue(helper.getLevel().getBlockState(at.east(dx).above().south(2)).is(Blocks.CHEST),
                        Component.literal("the chest with items did not run (+" + dx + ")"));
            }
            helper.getLevel().setBlockAndUpdate(existing, Blocks.AIR.defaultBlockState());
            MockPlayerNetwork.remove(him);
        });
    }

    // ── a world with bases: the shared screen ─────────────────────────────────────────────────────────────────────────

    /**
     * On a world with bases: a sidebar and a player in a team are refused; his own scores, a bossbar shown only to him and
     * a team for his own creatures run. On a world with no rules the same sidebar and {@code team join @s} still run.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = TIMEOUT)
    public static void theClosedWorldFencesTheSidebarAndPlayerTeamsAndTheOpenWorldDoesNot(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer him = MockPlayerNetwork.inLevel(helper, "mfam-sidebar");
        Tally tally = new Tally();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        Bases bases = baseAround(him);
        WorldRules rules = closedRules();
        String obj = "gtcoins" + (System.nanoTime() % 100000);
        String team = "gtblue" + (System.nanoTime() % 100000);
        inBackground(helper, () -> {
            Wish onClosed = wish(him, bases, rules);
            try {
                for (String c : new String[]{"scoreboard objectives add " + obj + " dummy",
                        "scoreboard players set @s " + obj + " 5", "scoreboard players add @s " + obj + " 1",
                        "bossbar add moddingfromamod:" + obj + " \"my dragon\"",
                        "bossbar set moddingfromamod:" + obj + " players @s",
                        "team add " + team, "team modify " + team + " color blue",
                        "summon pig ~2 ~ ~ {Tags:[\"" + obj + "_pet\"]}",
                        "team join " + team + " @e[tag=" + obj + "_pet,distance=..20]"}) {
                    tally.ran("closed", c, onClosed.run(c));
                }
                String words = "EVERY player's screen";
                tally.fenced("closed", "setdisplay sidebar", onClosed.run("scoreboard objectives setdisplay sidebar " + obj),
                        words);
                for (String who : new String[]{"@s", "@a", "Bob"}) {
                    tally.fenced("closed", "team join " + who, onClosed.run("team join " + team + " " + who), "tab list");
                }
            } finally {
                onClosed.end();
            }
            Wish inOpen = wish(him, null, null);
            try {
                tally.ran("open", "setdisplay sidebar",
                        inOpen.run("scoreboard objectives setdisplay sidebar " + obj));
                tally.ran("open", "team join @s", inOpen.run("team join " + team + " @s"));
                inOpen.run("scoreboard objectives setdisplay sidebar");
            } finally {
                inOpen.end();
            }
        }, failed, done);
        helper.succeedWhen(() -> {
            helper.assertTrue(done.get(), Component.literal("waiting for the calls"));
            helper.assertTrue(failed.get() == null, Component.literal("the rig threw: " + failed.get()));
            helper.assertTrue(tally.wrong().isEmpty(), Component.literal(String.join("\n", tally.wrong())));
            var sb = helper.getLevel().getServer().getScoreboard();
            helper.assertTrue(sb.getPlayerTeam(team) != null, Component.literal("his creature team was not made"));
            helper.assertTrue(sb.getPlayersTeam(him.getScoreboardName()) != null,
                    Component.literal("the open world's team join @s did not run"));
            sb.removePlayerTeam(sb.getPlayerTeam(team));
            var o = sb.getObjective(obj);
            if (o != null) {
                sb.removeObjective(o);
            }
            var bar = helper.getLevel().getServer().getCustomBossEvents()
                    .get(net.minecraft.resources.Identifier.fromNamespaceAndPath("moddingfromamod", obj));
            if (bar != null) {
                bar.removeAllPlayers();
                helper.getLevel().getServer().getCustomBossEvents().remove(bar);
            }
            MockPlayerNetwork.remove(him);
        });
    }

    // ── a learned button ────────────────────────────────────────────────────────────────────────────────

    /** A button that places a command block is refused on replay, on both worlds; an ordinary one runs. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aReplayedButtonNeverMakesACommandBlock(GameTestHelper helper) {
        ServerPlayer him = MockPlayerNetwork.inLevel(helper, "mfam-cmdblock-replay");
        var server = helper.getLevel().getServer();
        BlockPos at = him.blockPosition();
        try {
            for (int w = 0; w < 2; w++) {
                if (w == 1) {
                    WorldModuleHost.forTest(baseAround(him));
                    WorldRulesService.forTest(closedRules());
                }
                var cb = WishExecutor.runOne(server, helper.getLevel(), him.position(), him,
                        "setblock ~ ~1 ~ command_block{Command:\"say hi\",auto:1b}");
                helper.assertTrue(!cb.success(), Component.literal("a command-block button ran (world " + w + ")"));
                helper.assertFalse(isCommandBlock(helper, at.above()), Component.literal("it placed one"));
                var give = WishExecutor.runOne(server, helper.getLevel(), him.position(), him, "give @s command_block");
                helper.assertTrue(!give.success() && count(him, Items.COMMAND_BLOCK) == 0,
                        Component.literal("a give-a-command-block button ran (world " + w + ")"));
                var ok = WishExecutor.runOne(server, helper.getLevel(), him.position(), him, "setblock ~2 ~1 ~ stone");
                helper.assertTrue(ok.success() && helper.getLevel().getBlockState(at.east(2).above()).is(Blocks.STONE),
                        Component.literal("an ordinary button must still run: " + ok.joined()));
                helper.getLevel().setBlockAndUpdate(at.east(2).above(), Blocks.AIR.defaultBlockState());
            }
        } finally {
            WorldModuleHost.forTest(null);
            WorldRulesService.forTest(null);
        }
        MockPlayerNetwork.remove(him);
        helper.succeed();
    }

    // ── a build palette ─────────────────────────────────────────────────────────────────────────────────

    /** {@code build_structure}'s landing ({@link Builder#submit}): a command-block palette is refused, stone builds. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void aBuildPaletteWithACommandBlockIsRefused(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 2, 1));
        for (String block : new String[]{"minecraft:command_block", "repeating_command_block", "minecraft:jigsaw",
                "minecraft:structure_block"}) {
            BuildPlan bad = BuildPlan.fromJson(JsonParser.parseString("{\"id\":\"gt_cmdblock_build\",\"name\":\"x\","
                    + "\"shapes\":[{\"kind\":\"box\",\"dx\":0,\"dy\":0,\"dz\":0,\"width\":2,\"height\":1,\"depth\":1,"
                    + "\"block\":\"minecraft:stone\"},{\"kind\":\"box\",\"dx\":0,\"dy\":1,\"dz\":0,\"width\":1,"
                    + "\"height\":1,\"depth\":1,\"block\":\"" + block + "\"}]}").getAsJsonObject());
            String r = Builder.submit(level, anchor, bad, false);
            helper.assertTrue(r.contains("#2466"), Component.literal("a palette with " + block + " was not refused: " + r));
        }
        BuildPlan good = BuildPlan.fromJson(JsonParser.parseString("{\"id\":\"gt_cmdblock_build_ok\",\"name\":\"x\","
                + "\"shapes\":[{\"kind\":\"box\",\"dx\":0,\"dy\":0,\"dz\":0,\"width\":2,\"height\":1,\"depth\":1,"
                + "\"block\":\"minecraft:stone\"}]}").getAsJsonObject());
        String r = Builder.submit(level, anchor, good, false);
        helper.assertTrue(r.startsWith("Building"), Component.literal("an ordinary palette must build: " + r));
        Builder.drainNow(level);
        helper.assertTrue(level.getBlockState(anchor).is(Blocks.STONE), Component.literal("the ordinary build did not land"));
        level.setBlockAndUpdate(anchor, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(anchor.east(), Blocks.AIR.defaultBlockState());
        helper.succeed();
    }

    // ── a datapack ──────────────────────────────────────────────────────────────────────────────────────

    /** Every compile ({@code author_datapack}, {@code make_item}'s packs) refuses a pack that places one. */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void aDatapackThatPlacesACommandBlockIsRefusedAtCompile(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("COMMAND BLOCK DATAPACK GUARD: SKIPPED — no CBScript compiler; NOTHING was proved.");
            helper.succeed();
            return;
        }
        try {
            var tmp = java.nio.file.Files.createTempDirectory("mfam_cmdblock");
            CompileResult bad = CbScriptCompiler.compile(tmp, "gt_cmdblock", "gt",
                    "reset\n    /setblock ~ ~1 ~ minecraft:command_block{auto:1b}\nend\n", tmp.resolve("bad.zip"));
            helper.assertTrue(!bad.ok() && bad.failureSummary().contains("#2466"),
                    Component.literal("a pack placing a command block compiled: " + (bad.ok() ? "ok" : bad.failureSummary())));
            helper.assertFalse(java.nio.file.Files.exists(tmp.resolve("bad.zip")), Component.literal("its zip was kept"));
            CompileResult good = CbScriptCompiler.compile(tmp, "gt_cmdblock_ok", "gt",
                    "reset\n    /setblock ~ ~1 ~ minecraft:stone\nend\n", tmp.resolve("good.zip"));
            helper.assertTrue(good.ok(), Component.literal("an ordinary pack must compile: "
                    + (good.ok() ? "" : good.failureSummary())));
        } catch (java.io.IOException e) {
            helper.fail(Component.literal("temp dir: " + e));
        }
        helper.succeed();
    }
}
