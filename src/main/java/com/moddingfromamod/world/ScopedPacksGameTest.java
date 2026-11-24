package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import com.moddingfromamod.agent.tools.AuthorDatapackTool;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.moddingfromamod.agent.tools.SetRuleTool;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.datapack.DatapackForge;
import com.moddingfromamod.datapack.DatapackReloader;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * <b> where it RUNS: on an island world, a hand-written pack or a standing rule scoped to his island or his pets is
 * granted; anything that reaches past them is refused; a standalone one has a lifetime; an attached one never
 * expires.</b>
 *
 * <p>{@code author_datapack} and {@code set_rule} through the real {@link McpProtocol} {@code tools/call} (so
 * {@code BaseReach} judges first, then the tool compiles and {@code DatapackScope} judges the compiled functions),
 * with his island around him and an island world's kind of rules in force; the no-rules arm has neither. His creature's tag
 * is fixed by {@link BaseReach#hisCreatureTags} for the test. The lifetime runs on {@link StandalonePacks}' clock seam.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ScopedPacksGameTest {

    private static final String RULES = "{\"store\":\"world-rules\",\"v\":1,\"world\":\"closed\",\"rules\":["
            + "{\"id\":\"same-time-for-all\",\"type\":\"deny_world_effect\",\"effects\":[\"time\"],\"message\":{\"rule\":\"r\",\"why\":\"w\"}},"
            + "{\"id\":\"same-game-for-all\",\"type\":\"deny_world_effect\",\"effects\":[\"gamerules_difficulty\"],\"message\":{\"rule\":\"r\",\"why\":\"w\"}},"
            + "{\"id\":\"no-world-rules\",\"type\":\"deny_world_effect\",\"effects\":[\"worldwide_datapacks_standing_rules\"],\"message\":{\"rule\":\"r\",\"why\":\"w\"}},"
            + "{\"id\":\"your-own-island\",\"type\":\"base_reach\",\"effects\":[\"outside_own_base\",\"others_bases\",\"other_dimensions\"],"
            + "\"message\":{\"rule\":\"a wish stays on your own island\",\"why\":\"w\"}},"
            + "{\"id\":\"scoped-rules-ok\",\"type\":\"allow_base_scoped\",\"params\":{\"scopes\":[\"own_base\",\"pets\"],"
            + "\"lifetimeDays\":7,\"renewByPlay\":true}}]}";

    private ScopedPacksGameTest() {
    }

    static WorldRules closedRules() {
        var p = WorldRules.parse(RULES, "closed");
        if (!p.ok()) {
            throw new IllegalStateException(p.errors().toString());
        }
        return p.rules();
    }

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

    /** His island and a NEIGHBOUR's, {@code dz} blocks south — an island world's shape, two children side by side. */
    static Bases twoIslands(ServerPlayer him, ServerPlayer neighbour) {
        Bases a = baseAround(him);
        Bases b = baseAround(neighbour);
        return new Bases() {
            @Override public String noun() {
                return "island";
            }

            @Override public Base baseOf(UUID player) {
                Base x = a.baseOf(player);
                return x != null ? x : b.baseOf(player);
            }

            @Override public UUID ownerAt(String dimension, double px, double pz) {
                UUID x = a.ownerAt(dimension, px, pz);
                return x != null ? x : b.ownerAt(dimension, px, pz);
            }

            @Override public Bases.Pos positionOf(UUID player) {
                return player.equals(him.getUUID()) ? a.positionOf(player) : b.positionOf(player);
            }
        };
    }

    static McpProtocol.Headers headers(String session) {
        Map<String, String> m = new HashMap<>();
        m.put("Content-Type", "application/json");
        m.put("Accept", "application/json, text/event-stream");
        if (session != null) {
            m.put("Mcp-Session-Id", session);
        }
        return McpProtocol.Headers.of(m);
    }

    /** The plan_wish stand-in, for another test's protocol. */
    static McpTool planStub() {
        return plan();
    }

    private static McpTool plan() {
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

    /** A wish of his on a protocol judging with {@code world}/{@code rules}; {@link #call} returns {isError, text}. */
    record Wish(McpProtocol protocol, String session, UUID id) implements AutoCloseable {
        /** Finished, whatever happened: a wish left WORKING in Marshal.global() is read as the wish in flight by
         *  a later test (— ProvenanceWiringGameTest read this one's words). */
        @Override public void close() {
            Marshal.global().finish(id);
        }

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
                JsonObject r = JsonParser.parseString(reply.body()).getAsJsonObject().getAsJsonObject("result");
                return new String[]{String.valueOf(r.get("isError").getAsBoolean()), r.toString()};
            } finally {
                AgentContext.unbind();
            }
        }
    }

    static Wish wish(ServerPlayer him, Bases world, WorldRules rules) {
        McpProtocol p = new McpProtocol(new ToolRegistry().add(plan()).add(new AuthorDatapackTool())
                .add(new SetRuleTool()).add(new RunCommandTool()), new McpSessions(), Marshal.global(), () -> false,
                () -> world, () -> rules);
        String session = p.handlePost(headers(null), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L)
                .headers().get("Mcp-Session-Id");
        UUID id = UUID.randomUUID();
        Marshal.global().submit(id, him.getUUID(), "gt scoped packs");
        Marshal.global().assign(session, id);
        p.handlePost(headers(session), "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"plan_wish\",\"arguments\":{}}}", 0L);
        return new Wish(p, session, id);
    }

    static JsonObject pack(String id, String... tickLines) {
        JsonObject o = new JsonObject();
        o.addProperty("feature_id", id);
        o.addProperty("description", "gt " + id);
        o.addProperty("note", "gt");
        StringBuilder s = new StringBuilder("clock tick\n");
        for (String l : tickLines) {
            s.append("    /").append(l).append('\n');
        }
        s.append("end\n");
        o.addProperty("cbscript", s.toString());
        return o;
    }

    /** Wait on the RESULT in wall-clock, then check once ({@code HomeOpsGameTest}'s helper, for its reasons). */
    private static void pollUntil(GameTestHelper helper, long deadlineMs, int attemptsLeft,
            java.util.function.BooleanSupplier ready, Runnable check) {
        if (ready.getAsBoolean() || attemptsLeft <= 0 || System.currentTimeMillis() > deadlineMs) {
            check.run();
            return;
        }
        helper.runAfterDelay(2, () -> pollUntil(helper, deadlineMs, attemptsLeft - 1, ready, check));
    }

    private static void inBackground(Runnable r, AtomicReference<Throwable> err, AtomicReference<Boolean> done) {
        Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (Throwable e) {
                err.set(e);
            } finally {
                done.set(true);
            }
        }, "gametest-scopedpacks");
        t.setDaemon(true);
        t.start();
    }

    // ── packs: accepted when scoped, refused when not, and a no-rules world unchanged ─────────────────────────

    @GameTest(template = "empty_test", batch = "scopedpacks", timeoutTicks = 2_000_000)
    public static void onTheClosedWorldScopedPacksAreGrantedAndEverythingElseIsRefused(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.fail(Component.literal("no CBScript compiler: this test would prove nothing"));
            return;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer him = MockPlayerNetwork.inLevel(helper, "mfam-scoped");
        ServerPlayer bob = MockPlayerNetwork.inLevel(helper, "BobOther");
        var server = helper.getLevel().getServer();
        long t = helper.getLevel().getGameTime();
        String pet = "wishmob_gt_pet_" + t;
        int x = him.getBlockX(), y = him.getBlockY(), z = him.getBlockZ();
        BlockPos onIsland = new BlockPos(x + 3, y + 5, z + 3);
        BlockPos offIsland = new BlockPos(x + 3, y + 5, z + 400);
        Bases bases = baseAround(him);
        WorldRules rules = closedRules();
        var hookBefore = BaseReach.hisCreatureTags;
        BaseReach.hisCreatureTags = c -> c.equals(him.getUUID()) ? pet::equals : tag -> false;
        WorldModuleHost.forTest(bases);
        WorldRulesService.forTest(rules);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput()
                .withPosition(him.position()), "summon minecraft:pig ~2 ~ ~ {NoAI:1b,Tags:[\"" + pet + "\"]}");
        Map<String, String[]> ok = new LinkedHashMap<>();
        Map<String, String[]> no = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        inBackground(() -> {
            try (Wish w = wish(him, bases, rules)) {
            String p = "gt_scope_" + t + "_";
            ok.put("tag", w.call("author_datapack", pack(p + "tag", "effect give @e[tag=" + pet + "] minecraft:glowing 5 0 true")));
            ok.put("distance", w.call("author_datapack", pack(p + "dist", "execute positioned " + (x + 1) + " " + y + " " + (z + 1)
                    + " run effect give @e[type=minecraft:pig,distance=..20] minecraft:speed 5 0 true")));
            ok.put("timer", w.call("author_datapack", pack(p + "timer", "setblock " + onIsland.getX() + " " + onIsland.getY()
                    + " " + onIsland.getZ() + " minecraft:glowstone")));
            ok.put("hostile", w.call("author_datapack", pack(p + "hostile", "execute positioned " + x + " " + y + " " + z
                    + " as @e[type=minecraft:zombie,distance=..15] run damage @s 1 minecraft:magic")));
            ok.put("show", w.call("author_datapack", pack(p + "show", "particle minecraft:end_rod " + x + " " + (y + 4) + " "
                    + z + " 1 1 1 0 3")));
            no.put("bare @e", w.call("author_datapack", pack(p + "r1", "effect give @e minecraft:glowing 5")));
            no.put("bare @a", w.call("author_datapack", pack(p + "r2", "effect give @a minecraft:glowing 5")));
            no.put("distance onto another island", w.call("author_datapack", pack(p + "r3", "execute positioned " + x + " "
                    + y + " " + z + " run kill @e[type=minecraft:zombie,distance=..5000]")));
            no.put("coordinates off his island", w.call("author_datapack", pack(p + "r4", "setblock " + offIsland.getX()
                    + " " + offIsland.getY() + " " + offIsland.getZ() + " minecraft:glowstone")));
            no.put("origin moved away", w.call("author_datapack", pack(p + "r5", "execute positioned " + x + " " + y + " "
                    + (z + 900) + " run setblock ~ ~ ~ minecraft:stone")));
            no.put("the nether", w.call("author_datapack", pack(p + "r6", "execute in minecraft:the_nether run setblock 0 64 0 minecraft:stone")));
            no.put("time", w.call("author_datapack", pack(p + "r7", "time set day")));
            no.put("gamerule", w.call("author_datapack", pack(p + "r8", "gamerule advance_time false")));
            no.put("another player named", w.call("author_datapack", pack(p + "r9", "effect give BobOther minecraft:glowing 5")));
            no.put("function out of the pack", w.call("author_datapack", pack(p + "r10", "function minecraft:nothing")));
            no.put("Pos in NBT", w.call("author_datapack", pack(p + "r11", "data merge entity @e[tag=" + pet + ",limit=1] {Motion:[9d,0d,0d]}")));
            no.put("hurts him", w.call("author_datapack", pack(p + "r12", "execute positioned " + x + " " + y + " " + z
                    + " as @a[distance=..20] at @s run summon minecraft:tnt ~ ~ ~")));
            for (String k : List.of("tag", "dist", "timer", "hostile", "show")) {
                ids.add(p + k);
            }
            }
        }, err, done);
        // Checked ONCE when authoring is done (wall clock): a succeedWhen would retry a failed assertion until its
        // tick timeout, which on an unbounded-fast GameTest server is half an hour of nothing.
        pollUntil(helper, System.currentTimeMillis() + 300_000L, 100_000, done::get, () -> {
            helper.assertTrue(done.get(), Component.literal("authoring never finished"));
            // Every call is done: the island world's bases, rules and creature tags go NOW, before any assertion can fail and
            // leave them on for the next batch (a GameTest has no teardown).
            WorldModuleHost.forTest(null);
            WorldRulesService.forTest(null);
            BaseReach.hisCreatureTags = hookBefore;
            boolean firedOnIsland = helper.getLevel().getBlockState(onIsland).is(Blocks.GLOWSTONE);
            boolean hadLifetime = !ids.isEmpty() && StandalonePacks.all().stream().anyMatch(e -> e.id().equals(ids.get(0)));
            // …and so do every pack this test made, accepted or (wrongly) accepted, before any assertion can fail.
            for (String id : ids) {
                DatapackReloader.disableAndReload(server, id);
                StandalonePacks.forget("datapack", id);
            }
            for (StandalonePacks.Entry e : StandalonePacks.all()) {     // a refusal that (wrongly) went through
                if (e.id().startsWith("gt_scope_" + t + "_r")) {
                    DatapackReloader.disableAndReload(server, e.id());
                    StandalonePacks.forget("datapack", e.id());
                }
            }
            helper.assertTrue(err.get() == null, Component.literal("the rig threw: " + err.get()));
            List<String> wrong = new ArrayList<>();
            ok.forEach((k, r) -> {
                if (Boolean.parseBoolean(r[0])) {
                    wrong.add("REFUSED but scoped (" + k + "): " + r[1]);
                }
            });
            no.forEach((k, r) -> {
                if (!Boolean.parseBoolean(r[0])) {
                    wrong.add("ACCEPTED but should be refused (" + k + "): " + r[1]);
                }
            });
            helper.assertTrue(wrong.isEmpty(), Component.literal(String.join("\n", wrong)));
            helper.assertTrue(no.get("coordinates off his island")[1].contains("line"),
                    Component.literal("a refusal names the function and line: " + no.get("coordinates off his island")[1]));
            helper.assertTrue(firedOnIsland,
                    Component.literal("the timer pack did not fire on his island"));
            helper.assertFalse(helper.getLevel().getBlockState(offIsland).is(Blocks.GLOWSTONE),
                    Component.literal("something changed OFF his island"));
            helper.assertTrue(hadLifetime,
                    Component.literal("a standalone island-world pack was given no lifetime"));
            helper.getLevel().setBlockAndUpdate(onIsland, Blocks.AIR.defaultBlockState());
            WorldModuleHost.forTest(null);
            WorldRulesService.forTest(null);
            BaseReach.hisCreatureTags = hookBefore;
            MockPlayerNetwork.remove(him);
            MockPlayerNetwork.remove(bob);
            helper.succeed();
        });
    }

    @GameTest(template = "empty_test", batch = "scopedpacks_open", timeoutTicks = 2_000_000)
    public static void onTheOpenWorldAnUnboundedPackIsStillAccepted(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.fail(Component.literal("no CBScript compiler: this test would prove nothing"));
            return;
        }
        ServerPlayer him = MockPlayerNetwork.inLevel(helper, "mfam-scoped-w");
        String id = "gt_scope_w_" + helper.getLevel().getGameTime();
        AtomicReference<String[]> r = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        WorldModuleHost.forTest(null);
        WorldRulesService.forTest(null);
        inBackground(() -> {
            try (Wish w = wish(him, null, null)) {
                r.set(w.call("author_datapack", pack(id, "effect give @e[type=minecraft:pig] minecraft:glowing 2 0 true",
                        "execute as @a run particle minecraft:heart ~ ~2 ~ 0 0 0 0 1")));
            }
        }, err, done);
        pollUntil(helper, System.currentTimeMillis() + 300_000L, 100_000, done::get, () -> {
            helper.assertTrue(done.get(), Component.literal("authoring never finished"));
            helper.assertTrue(err.get() == null && !Boolean.parseBoolean(r.get()[0]),
                    Component.literal("the no-rules world refused an unbounded pack: " + (r.get() == null ? err.get() : r.get()[1])));
            DatapackReloader.disableAndReload(helper.getLevel().getServer(), id);
            MockPlayerNetwork.remove(him);
            helper.succeed();
        });
    }

    // ── rules: scoped accepted, world-wide refused, and it fires on his island only ─────────────────────

    @GameTest(template = "empty_test", batch = "scopedrules", timeoutTicks = 400_000)
    public static void onTheClosedWorldAScopedRuleIsGrantedAndFiresOnHisIslandOnly(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer him = MockPlayerNetwork.inLevel(helper, "mfam-scoped-rule");
        // A neighbour on HIS OWN island 200 blocks south: a his-island rule must not fire there either — and with the
        // neighbour the nearest player, the replay backstop measures from the NEIGHBOUR's island and lets it through.
        ServerPlayer neighbour = MockPlayerNetwork.inLevel(helper, "mfam-neighbour");
        neighbour.teleportTo(helper.getLevel(), him.getX(), him.getY(), him.getZ() + 200, java.util.Set.of(), 0, 0, true);
        // Kept loaded from now on: a summon into a chunk nothing holds succeeds and makes nothing anyone can find.
        net.minecraft.world.level.ChunkPos nextDoor = net.minecraft.world.level.ChunkPos.containing(
                new BlockPos(him.getBlockX() + 4, him.getBlockY(), him.getBlockZ() + 200));
        helper.getLevel().setChunkForced(nextDoor.x(), nextDoor.z(), true);
        var server = helper.getLevel().getServer();
        long t = helper.getLevel().getGameTime();
        String pet = "wishmob_gt_rulepet_" + t;
        String mark = "gt_fired_" + t;
        Bases bases = twoIslands(him, neighbour);
        WorldRules rules = closedRules();
        var hookBefore = BaseReach.hisCreatureTags;
        BaseReach.hisCreatureTags = c -> c.equals(him.getUUID()) ? pet::equals : tag -> false;
        WorldModuleHost.forTest(bases);
        WorldRulesService.forTest(rules);
        Map<String, String[]> out = new LinkedHashMap<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        AtomicReference<Boolean> summoned = new AtomicReference<>(false);
        inBackground(() -> {
            try (Wish w = wish(him, bases, rules)) {
            JsonObject island = new JsonObject();
            island.addProperty("when", "mob_spawns");
            island.addProperty("who", "minecraft:husk");
            island.addProperty("where", "his_island");
            island.addProperty("name", "gt_island_" + t);
            JsonArray d = new JsonArray();
            d.add("tag @s add " + mark);
            island.add("do", d);
            out.put("island", w.call("set_rule", island));
            JsonObject pets = new JsonObject();
            pets.addProperty("when", "near_player");
            pets.addProperty("who", "tag=" + pet);
            pets.addProperty("name", "gt_pets_" + t);
            JsonArray d2 = new JsonArray();
            d2.add("effect give @s minecraft:glowing 5 0 true");
            pets.add("do", d2);
            out.put("pets", w.call("set_rule", pets));
            JsonObject wide = new JsonObject();
            wide.addProperty("when", "mob_spawns");
            wide.addProperty("who", "minecraft:zombie");
            wide.addProperty("name", "gt_wide_" + t);
            JsonArray d3 = new JsonArray();
            d3.add("tag @s add " + mark);
            wide.add("do", d3);
            out.put("wide", w.call("set_rule", wide));
            }
        }, err, done);
        int x = him.getBlockX(), y = him.getBlockY(), z = him.getBlockZ();
        long deadline = System.currentTimeMillis() + 120_000L;
        AtomicReference<String> last = new AtomicReference<>("");
        AtomicReference<String> verdict = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong settledFrom = new java.util.concurrent.atomic.AtomicLong(-1);
        helper.succeedWhen(() -> {
            if (verdict.get() != null) {
                helper.assertTrue(verdict.get().isEmpty(), Component.literal(verdict.get()));
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                WorldModuleHost.forTest(null);
                WorldRulesService.forTest(null);
                BaseReach.hisCreatureTags = hookBefore;
                StringBuilder said = new StringBuilder();
                out.forEach((k, v) -> said.append(k).append('=').append(java.util.Arrays.toString(v)).append("; "));
                helper.getLevel().setChunkForced(nextDoor.x(), nextDoor.z(), false);
                helper.fail(Component.literal("the rules test ran out of time (last: " + last.get() + "): " + said));
            }
            last.set("waiting on the calls");
            helper.assertTrue(done.get(), Component.literal("setting"));
            helper.assertTrue(err.get() == null, Component.literal("the rig threw: " + err.get()));
            helper.assertFalse(Boolean.parseBoolean(out.get("island")[0]), Component.literal("his-island rule refused: " + out.get("island")[1]));
            helper.assertFalse(Boolean.parseBoolean(out.get("pets")[0]), Component.literal("his-pets rule refused: " + out.get("pets")[1]));
            helper.assertTrue(Boolean.parseBoolean(out.get("wide")[0]), Component.literal("a world-wide rule was granted: " + out.get("wide")[1]));
            if (!summoned.get()) {
                summoned.set(true);
                var src = server.createCommandSourceStack().withSuppressedOutput();
                server.getCommands().performPrefixedCommand(src, "summon minecraft:husk " + (x + 4) + " " + y + " " + z
                        + " {NoAI:1b,Tags:[\"gt_on_" + t + "\"]}");
                server.getCommands().performPrefixedCommand(src, "summon minecraft:husk " + (x + 4) + " " + y + " " + (z + 200)
                        + " {NoAI:1b,Tags:[\"gt_off_" + t + "\"]}");
                server.getCommands().performPrefixedCommand(src, "summon minecraft:pig " + (x + 2) + " " + y + " " + z
                        + " {NoAI:1b,Tags:[\"" + pet + "\"]}");
            }
            var on = helper.getLevel().getEntities((net.minecraft.world.entity.Entity) null, him.getBoundingBox().inflate(400),
                    e -> e.entityTags().contains("gt_on_" + t));
            var off = helper.getLevel().getEntities((net.minecraft.world.entity.Entity) null, him.getBoundingBox().inflate(400),
                    e -> e.entityTags().contains("gt_off_" + t));
            last.set("on=" + on.size() + (on.isEmpty() ? "" : " tags " + on.get(0).entityTags()) + " off=" + off.size()
                    + " rules=" + com.moddingfromamod.rules.WorldRules.all(server).stream().map(r -> r.name()).toList()
                    + " err=" + com.moddingfromamod.rules.WorldRules.lastError("gt_island_" + t));
            helper.assertTrue(!on.isEmpty() && on.get(0).entityTags().contains(mark),
                    Component.literal("the rule did not fire on his island"));
            // Both husks' firings are queued; give the queue 20 more ticks so "it did not fire next door" is not
            // judged a tick before it would have.
            long now = helper.getLevel().getGameTime();
            if (settledFrom.get() < 0) {
                settledFrom.set(now);
            }
            helper.assertTrue(now >= settledFrom.get() + 20, Component.literal("settling"));
            var hisPets = helper.getLevel().getEntities((net.minecraft.world.entity.Entity) null,
                    him.getBoundingBox().inflate(20), e -> e.entityTags().contains(pet));
            helper.assertTrue(!hisPets.isEmpty() && hisPets.get(0) instanceof net.minecraft.world.entity.LivingEntity le
                    && le.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING),
                    Component.literal("the rule for his pets (who: tag=…) did not fire on his pet"));
            // It fired: everything this test set up goes NOW, before the assertions that could fail (no teardown).
            boolean offThere = !off.isEmpty();
            boolean firedNext = offThere && off.get(0).entityTags().contains(mark);
            org.slf4j.LoggerFactory.getLogger("gt-scoped").info("[gt] judged: {} offTags={} neighbourAt={} {}",
                    last.get(), offThere ? off.get(0).entityTags() : "-", neighbour.getX(), neighbour.getZ());
            on.forEach(net.minecraft.world.entity.Entity::discard);
            hisPets.forEach(net.minecraft.world.entity.Entity::discard);
            off.forEach(net.minecraft.world.entity.Entity::discard);
            com.moddingfromamod.rules.WorldRules.remove(server, "gt_island_" + t);
            com.moddingfromamod.rules.WorldRules.remove(server, "gt_pets_" + t);
            StandalonePacks.forget("rule", "gt_island_" + t);
            StandalonePacks.forget("rule", "gt_pets_" + t);
            WorldModuleHost.forTest(null);
            WorldRulesService.forTest(null);
            BaseReach.hisCreatureTags = hookBefore;
            MockPlayerNetwork.remove(him);
            MockPlayerNetwork.remove(neighbour);
            helper.getLevel().setChunkForced(nextDoor.x(), nextDoor.z(), false);
            verdict.set(!offThere ? "the neighbour's husk was never there to judge"
                    : firedNext ? "the his-island rule fired on the NEIGHBOUR's island" : "");
            helper.assertTrue(verdict.get().isEmpty(), Component.literal(verdict.get()));
        });
    }

    // ── the lifetime ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * A standalone pack ends after its lifetime (the clock seam moves 8 days) and he is told; playing on his island
     * renews it; reclaiming his island takes his and leaves another's; and ATTACHED packs (the pet dragon's breath,
     * a make_item effect) are never in the store and are still on after the lifetime passes.
     */
    @GameTest(template = "empty_test", batch = "scopedlifetime", timeoutTicks = 2_000)
    public static void aStandalonePackEndsAfterItsLifetimeAndPlayRenewsIt(GameTestHelper helper) {
        ServerPlayer him = MockPlayerNetwork.inLevel(helper, "mfam-lifetime");
        var server = helper.getLevel().getServer();
        long t = helper.getLevel().getGameTime();
        String standalone = "gt_life_" + t;
        String renewed = "gt_life_renew_" + t;
        String other = "gt_life_other_" + t;
        UUID stranger = UUID.randomUUID();
        Bases bases = baseAround(him);
        WorldRules rules = closedRules();
        var lifetime = StandalonePacks.of(rules);
        long[] now = {System.currentTimeMillis()};
        var clockBefore = StandalonePacks.clock;
        StandalonePacks.clock = () -> now[0];
        WorldModuleHost.forTest(bases);
        WorldRulesService.forTest(rules);
        try {
            helper.assertTrue(lifetime != null && lifetime.millis() == 7 * 86_400_000L && lifetime.renewByPlay(),
                    Component.literal("the island world's lifetime: " + lifetime));
            for (String id : List.of("breath_cone_pet_dragon", "lightning_sword_lightning")) {
                DatapackForge.activate(server, id);
            }
            for (String id : List.of(standalone, renewed, other)) {
                QuarantineFree.install(server, id);
            }
            StandalonePacks.register("datapack", standalone, him.getUUID(), lifetime);
            StandalonePacks.register("datapack", other, stranger, lifetime);
            // he plays on his island on day 6: his packs are renewed to day 13
            now[0] += 6 * 86_400_000L;
            StandalonePacks.register("datapack", renewed, him.getUUID(), new StandalonePacks.Lifetime(86_400_000L, true));
            StandalonePackService.tick(server);
            helper.assertTrue(StandalonePacks.all().stream().filter(e -> e.id().equals(renewed))
                    .allMatch(e -> e.expiresAtMs() >= now[0] + 6 * 86_400_000L), Component.literal("playing on his island did not renew"));
            // day 14: his first ends (renewed to day 13), the stranger's (never renewed, day 7) ended at day 7
            now[0] += 8 * 86_400_000L;
            // he is away now: no renewal this tick
            him.teleportTo(him.getX(), him.getY(), him.getZ() + 5_000);
            StandalonePackService.tick(server);
            helper.assertFalse(DatapackReloader.isEnabled(server, standalone) && !DatapackReloader.reloadInProgress(),
                    Component.literal("a standalone pack outlived its lifetime"));
            helper.assertTrue(StandalonePacks.all().stream().noneMatch(e -> e.id().equals(standalone)),
                    Component.literal("its lifetime row is still there"));
            helper.assertTrue(MockPlayerNetwork.chat(him).stream().anyMatch(l -> l.contains("has worn off")),
                    Component.literal("he was not told: " + MockPlayerNetwork.chat(him)));
            // attached packs never expire
            for (String id : List.of("breath_cone_pet_dragon", "lightning_sword_lightning")) {
                helper.assertTrue(DatapackReloader.isEnabled(server, id) || DatapackReloader.reloadInProgress(),
                        Component.literal("an ATTACHED pack expired: " + id));
                helper.assertTrue(StandalonePacks.all().stream().noneMatch(e -> e.id().equals(id)),
                        Component.literal("an attached pack was given a lifetime: " + id));
            }
            // reclaim: his go, a stranger's stay
            StandalonePacks.register("datapack", renewed, him.getUUID(), lifetime);
            StandalonePacks.register("datapack", other, stranger, lifetime);
            StandalonePackService.reclaimed(server, him.getUUID());
            helper.assertTrue(StandalonePacks.all().stream().noneMatch(e -> e.playerId().equals(him.getUUID().toString())),
                    Component.literal("reclaiming his island left his packs"));
            helper.assertTrue(StandalonePacks.all().stream().anyMatch(e -> e.id().equals(other)),
                    Component.literal("reclaiming his island took another's pack"));
        } catch (java.io.IOException e) {
            helper.fail(Component.literal("install: " + e));
        } finally {
            StandalonePacks.forget("datapack", other);
            StandalonePacks.clock = clockBefore;
            for (String id : List.of(standalone, renewed, other, "breath_cone_pet_dragon", "lightning_sword_lightning")) {
                DatapackReloader.disableAndReload(server, id);
            }
            WorldModuleHost.forTest(null);
            WorldRulesService.forTest(null);
            MockPlayerNetwork.remove(him);
        }
        helper.succeed();
    }

    /** A hand-written wish pack, installed as a wish installs one (no compiler needed). */
    static final class QuarantineFree {
        static void install(net.minecraft.server.MinecraftServer server, String ns) throws java.io.IOException {
            java.nio.file.Path dir = com.moddingfromamod.datapack.DatapackStore.featureDir(ns);
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path zip = com.moddingfromamod.datapack.DatapackStore.ownZipFor(ns);
            try (var z = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(zip))) {
                z.putNextEntry(new java.util.zip.ZipEntry("pack.mcmeta"));
                z.write(("{\"pack\":{\"pack_format\":61,\"description\":\"gt\"}}").getBytes());
                z.closeEntry();
                z.putNextEntry(new java.util.zip.ZipEntry("data/" + ns + "/function/noop.mcfunction"));
                z.write("scoreboard players add #n gt_noop 0\n".getBytes());
                z.closeEntry();
            }
            DatapackReloader.installAndReload(server, ns, zip);
        }
    }
}
