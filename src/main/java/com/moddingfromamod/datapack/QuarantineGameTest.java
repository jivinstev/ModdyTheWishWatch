package com.moddingfromamod.datapack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;

/**
 * <b> where it RUNS: a wish datapack eating the server is paused before it can freeze it, and an ordinary
 * one never is.</b>
 *
 * <p>Both sides, through the real path: packs installed the way a wish installs them
 * ({@link DatapackForge#activate} / {@link DatapackReloader#installAndReload}), run by the real server's tick
 * functions, measured by {@link PackCostMeter} from the real {@code ServerFunctionManager} and tick events, and
 * paused by {@link DatapackQuarantine}. Each STOP test carries its red control: the same runaway with the pausing
 * switched off ({@link PackCostMeter#quarantineForTest}) is judged a breach, and nothing pauses it — so a test
 * that passes is the quarantine working, not the runaway failing to run.
 *
 * <p>One batch each: these reload the server's datapacks, and two reloads overlapping in one batch are a known
 * trap ({@code TickGuardGameTest}).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class QuarantineGameTest {

    private static final long WALL = 150_000L;

    private QuarantineGameTest() {
    }

    // ── the rig ─────────────────────────────────────────────────────────────────────────────────────────

    /** A hand-written wish pack, in the store and switched on the way a wish's is. */
    public static void install(MinecraftServer server, String ns, Map<String, String> functions, boolean ticks)
            throws IOException {
        Path dir = DatapackStore.featureDir(ns);
        Files.createDirectories(dir);
        Path zip = DatapackStore.ownZipFor(ns);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(z, "pack.mcmeta", "{\"pack\":{\"pack_format\":61,\"description\":\"gt " + ns + "\"}}");
            if (ticks) {
                put(z, "data/minecraft/tags/function/tick.json", "{\"values\":[\"" + ns + ":tick\"]}");
            }
            for (var e : functions.entrySet()) {
                put(z, "data/" + ns + "/function/" + e.getKey() + ".mcfunction", e.getValue());
            }
        }
        DatapackReloader.installAndReload(server, ns, zip);
    }

    private static void put(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    static boolean ticks(MinecraftServer server, String ns) {
        return server.getFunctions().getTag(Identifier.withDefaultNamespace("tick")).stream()
                .anyMatch(f -> ns.equals(f.id().getNamespace()));
    }

    public static void cleanUp(MinecraftServer server, String ns) {
        try {
            DatapackQuarantine.restore(null, ns, "gametest");
        } finally {
            DatapackReloader.disableAndReload(server, ns);
        }
    }

    private static int tagged(ServerLevel level, String tag) {
        int n = 0;
        for (Entity e : level.getAllEntities()) {
            if (e != null && e.getTags().contains(tag)) {
                n++;
            }
        }
        return n;
    }

    private static void killTagged(ServerLevel level, String tag) {
        List<Entity> doomed = new java.util.ArrayList<>();
        for (Entity e : level.getAllEntities()) {      // collect first: discarding while walking throws
            if (e != null && e.getTags().contains(tag)) {
                doomed.add(e);
            }
        }
        doomed.forEach(Entity::discard);
    }

    /** His island around him: the island arm's bases. */
    static com.moddingfromamod.world.Bases baseAround(ServerPlayer him) {
        int x = him.getBlockX();
        int z = him.getBlockZ();
        java.util.UUID owner = him.getUUID();
        var base = new com.moddingfromamod.world.Base(owner, com.moddingfromamod.world.Bases.OVERWORLD, x,
                him.getBlockY(), z, him.getBlockY(), new com.moddingfromamod.world.Region(x - 48, x + 48, z - 48, z + 48),
                new com.moddingfromamod.world.Region(x - 40, x + 40, z - 40, z + 40));
        return new com.moddingfromamod.world.Bases() {
            @Override public String noun() {
                return "island";
            }

            @Override public com.moddingfromamod.world.Base baseOf(java.util.UUID player) {
                return owner.equals(player) ? base : null;
            }

            @Override public java.util.UUID ownerAt(String dimension, double px, double pz) {
                return base.area().contains(px, pz) ? owner : null;
            }

            @Override public com.moddingfromamod.world.Bases.Pos positionOf(java.util.UUID player) {
                return new com.moddingfromamod.world.Bases.Pos(com.moddingfromamod.world.Bases.OVERWORLD, him.getX(),
                        him.getY(), him.getZ());
            }
        };
    }

    /** An island world's shape of rules: world-wide rules denied, wishes fenced to his base. */
    static com.moddingfromamod.world.WorldRules closedRules() {
        var parsed = com.moddingfromamod.world.WorldRules.parse("{\"store\":\"world-rules\",\"v\":1,\"world\":\"closed\","
                + "\"rules\":[{\"id\":\"no-world-rules\",\"type\":\"deny_world_effect\",\"effects\":["
                + "\"worldwide_datapacks_standing_rules\"],\"message\":{\"rule\":\"r\",\"why\":\"w\"}},"
                + "{\"id\":\"your-own-island\",\"type\":\"base_reach\",\"effects\":[\"outside_own_base\","
                + "\"others_bases\",\"other_dimensions\"],\"message\":{\"rule\":\"r\",\"why\":\"w\"}}]}", "closed");
        if (!parsed.ok()) {
            throw new IllegalStateException("rules: " + parsed.errors());
        }
        return parsed.rules();
    }

    // ── what SHOULD still work ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>No false positives</b>: the starter packs a child really has — the pet dragon's breath, the T-Rex's
     * clock, a {@code make_item} effect, a colour cycle — with sixteen creatures wearing their tags, soak for
     * three thousand ticks, and NOTHING is paused or even judged a breach. The meter must have timed them, or the
     * soak proved nothing.
     */
    @GameTest(template = "empty_test", batch = "quarantine_soak", timeoutTicks = 20_000)
    public static void ordinaryPacksSoakForThousandsOfTicksWithNoQuarantine(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        List<String> packs = List.of("breath_cone_pet_dragon", "periodic_trex", "lightning_sword_lightning",
                "colour_cycle_rainbow_axolotl");
        try {
            for (String p : packs) {
                DatapackForge.activate(server, p);
            }
        } catch (IOException e) {
            helper.fail(Component.literal("could not install a starter pack: " + e));
            return;
        }
        for (int i = 0; i < 8; i++) {
            helper.getLevel().getServer().getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withPosition(helper.absoluteVec(new net.minecraft.world.phys.Vec3(2 + i % 4, 2, 2 + i / 4)))
                            .withSuppressedOutput(),
                    "summon minecraft:zombie ~ ~ ~ {NoAI:1b,PersistenceRequired:1b,Tags:[\"wishmob_pet_dragon\",\"gt_soak\"]}");
            helper.getLevel().getServer().getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withPosition(helper.absoluteVec(new net.minecraft.world.phys.Vec3(2 + i % 4, 2, 6 + i / 4)))
                            .withSuppressedOutput(),
                    "summon minecraft:pig ~ ~ ~ {NoAI:1b,PersistenceRequired:1b,Tags:[\"wishmob_trex\",\"gt_soak\"]}");
        }
        PackCostMeter.resetForTest();
        long start = helper.getLevel().getGameTime();
        AtomicBoolean everBlamed = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (PackCostMeter.lastVerdict().pack() != null) {
                everBlamed.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getGameTime() - start >= 3_000, Component.literal("soaking"));
            for (String p : packs) {
                helper.assertFalse(DatapackQuarantine.isQuarantined(p), Component.literal("an ordinary pack was paused: " + p));
            }
            helper.assertFalse(everBlamed.get(), Component.literal("the meter blamed a pack during the soak"));
            helper.assertTrue(PackCostMeter.seenForTest().containsAll(List.of("breath_cone_pet_dragon", "periodic_trex")),
                    Component.literal("the meter never timed the soaked packs: " + PackCostMeter.seenForTest()));
            for (String p : packs) {
                helper.assertTrue(DatapackReloader.isEnabled(server, p), Component.literal(p + " was switched off"));
            }
            killTagged(helper.getLevel(), "gt_soak");
            for (String p : packs) {
                DatapackReloader.disableAndReload(server, p);
            }
        });
    }

    /**
     * <b>One spiky tick is not a runaway</b>: a pack that does a big one-off job (fourteen 32k-block fills in one
     * tick, like a heavy reveal) has its spike measured — it must really be over the slow line, or this proves
     * nothing — and is not paused.
     */
    @GameTest(template = "empty_test", batch = "quarantine_spike", timeoutTicks = 20_000)
    public static void aPackWithOneSpikyTickIsNotQuarantined(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String ns = "gt_spike_" + helper.getLevel().getGameTime();
        BlockPos o = helper.absolutePos(new BlockPos(0, 40, 0));
        StringBuilder once = new StringBuilder("scoreboard players set #done gt_spike 1\n");
        for (int i = 0; i < 8; i++) {   // one tick of eight 32k fills: over the slow line
            once.append("fill ").append(o.getX()).append(' ').append(o.getY()).append(' ').append(o.getZ()).append(' ')
                    .append(o.getX() + 31).append(' ').append(o.getY() + 31).append(' ').append(o.getZ() + 31).append(' ')
                    .append(i % 2 == 0 ? "stone" : "air").append('\n');
        }
        Map<String, String> fns = new LinkedHashMap<>();
        fns.put("tick", "scoreboard objectives add gt_spike dummy\nexecute unless score #done gt_spike matches 1 run function "
                + ns + ":once\n");
        fns.put("once", once.toString());
        try {
            server.getScoreboard().getObjectiveNames();
            install(server, ns, fns, true);
        } catch (IOException e) {
            helper.fail(Component.literal("install: " + e));
            return;
        }
        PackCostMeter.resetForTest();
        long start = helper.getLevel().getGameTime();
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getGameTime() - start >= 400, Component.literal("watching"));
            helper.assertTrue(PackCostMeter.maxTickMsForTest() > QuarantineRule.SLOW_TICK_MS,
                    Component.literal("the spike was only " + (int) PackCostMeter.maxTickMsForTest()
                            + " ms: make it heavier, or this test proves nothing"));
            helper.assertFalse(DatapackQuarantine.isQuarantined(ns), Component.literal("one spiky tick paused the pack"));
            // (On a loaded machine the spike can run past the hard stop and be cut once: still ONE spiky tick, and one
            // stop alone must not pause it either — so either way it is the not-quarantined side being tested.)
            cleanUp(server, ns);
            var objective = server.getScoreboard().getObjective("gt_spike");
            if (objective != null) {
                server.getScoreboard().removeObjective(objective);
            }
        });
    }

    // ── what must be STOPPED ────────────────────────────────────────────────────────────────────────────

    /** The bh-g03 shape, as a running test: returns the pack id and a stop handle. */
    private record Flood(String ns, String tag, LivingEntity victim) {
    }

    /**
     * bh-g03, with the REAL {@code on_hit} template and the real compiler: a creature's "when something near it
     * is hurt, call a baby" ability, whose babies wear the creature's own tag — the worst case of the hypothesis,
     * where every baby carries the trigger too. Each time the victim is hurt, every creature wearing the tag calls
     * one more, so the crowd doubles per hit. (The babies are armour stands: a creature the spawn budget does not
     * count, as bh-g03's crowd got past it.)
     */
    private static Flood flood(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        long t = helper.getLevel().getGameTime();
        String ns = "gt_flood_" + t;
        String tag = "gt_mum_" + t;
        String src = EffectCatalogue.load().render("on_hit", Map.of("TAG", tag,
                "COMMAND", "execute at @s run summon minecraft:armor_stand ~ ~ ~ {Tags:[\"" + tag + "\"],Invisible:1b}",
                "RADIUS", "8", "TARGET", "mobs"));
        CompileResult r = DatapackForge.compile(ns, "gt bh-g03 flood", src);
        if (!r.ok()) {
            throw new IllegalStateException("the on_hit flood did not compile: " + r.failureSummary());
        }
        try {
            DatapackForge.activate(server, ns);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        var src2 = server.createCommandSourceStack().withPosition(net.minecraft.world.phys.Vec3.atBottomCenterOf(at))
                .withSuppressedOutput();
        server.getCommands().performPrefixedCommand(src2,
                "summon minecraft:armor_stand ~ ~ ~ {Tags:[\"" + tag + "\"],Invisible:1b}");
        server.getCommands().performPrefixedCommand(src2,
                "summon minecraft:zombie ~1 ~ ~ {NoAI:1b,PersistenceRequired:1b,Tags:[\"" + tag + "_victim\"],"
                        + "attributes:[{id:\"minecraft:max_health\",base:1024.0}],Health:1024f}");
        LivingEntity victim = null;
        for (Entity e : helper.getLevel().getAllEntities()) {
            if (e != null && e.getTags().contains(tag + "_victim") && e instanceof LivingEntity le) {
                victim = le;
            }
        }
        if (victim == null) {
            throw new IllegalStateException("no victim");
        }
        return new Flood(ns, tag, victim);
    }

    /** Hurt the victim every 20 ticks (its hurt time ends in 10), healing it so it never dies. */
    private static void hurt(GameTestHelper helper, Flood f, AtomicBoolean stop) {
        AtomicInteger n = new AtomicInteger();
        helper.onEachTick(() -> {
            if (stop.get() || f.victim().isRemoved() || n.incrementAndGet() % 20 != 0) {
                return;
            }
            f.victim().setHealth(f.victim().getMaxHealth());
            f.victim().invulnerableTime = 0;
            f.victim().hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 1.0f);
        });
    }

    /**
     * <b>bh-g03: a creature whose hit-ability calls copies of itself is paused before the server freezes, the copies
     * are cleared, and the server keeps ticking</b> — and another pack, beside it, keeps running throughout.
     */
    @GameTest(template = "empty_test", batch = "quarantine_flood", timeoutTicks = 200_000)
    public static void bhG03OnHitCopiesAreQuarantinedAndTheServerKeepsTicking(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.fail(Component.literal("no CBScript compiler: this test would prove nothing"));
            return;
        }
        MinecraftServer server = helper.getLevel().getServer();
        Flood f = flood(helper);
        try {
            DatapackForge.activate(server, "periodic_trex");
        } catch (IOException e) {
            helper.fail(Component.literal("could not install the bystander pack: " + e));
            return;
        }
        PackCostMeter.resetForTest();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong pausedAt = new AtomicLong();
        AtomicInteger peak = new AtomicInteger();
        hurt(helper, f, stop);
        long deadline = System.currentTimeMillis() + WALL;
        helper.onEachTick(() -> peak.accumulateAndGet(tagged(helper.getLevel(), f.tag()), Math::max));
        helper.succeedWhen(() -> {
            if (System.currentTimeMillis() > deadline) {
                stop.set(true);
                killTagged(helper.getLevel(), f.tag());
                cleanUp(server, f.ns());
                helper.fail(Component.literal("the flood was not paused in " + WALL / 1000 + " s (peak " + peak.get()
                        + " copies, slowest tick " + (int) PackCostMeter.maxTickMsForTest() + " ms)"));
            }
            helper.assertTrue(DatapackQuarantine.isQuarantined(f.ns()), Component.literal("flooding: " + peak.get()));
            stop.set(true);
            if (pausedAt.get() == 0) {
                pausedAt.set(helper.getLevel().getGameTime());
            }
            helper.assertTrue(helper.getLevel().getGameTime() - pausedAt.get() >= 40,
                    Component.literal("letting the server settle"));
            helper.assertTrue(PackCostMeter.maxTickMsForTest() < 60_000,
                    Component.literal("a tick reached the watchdog's 60 s before the pause"));
            helper.assertTrue(PackCostMeter.lastTickMsForTest() < QuarantineRule.SLOW_TICK_MS,
                    Component.literal("the server is still slow after the pause: " + (int) PackCostMeter.lastTickMsForTest() + " ms"));
            helper.assertTrue(tagged(helper.getLevel(), f.tag()) <= 2,
                    Component.literal("the copies were not cleared: " + tagged(helper.getLevel(), f.tag())));
            helper.assertFalse(DatapackReloader.isEnabled(server, f.ns()), Component.literal("the pack is still on"));
            helper.assertTrue(DatapackReloader.isEnabled(server, "periodic_trex") && ticks(server, "periodic_trex"),
                    Component.literal("the bystander pack stopped running"));
            helper.assertTrue(peak.get() > 8, Component.literal("the flood never grew (" + peak.get() + "): nothing was proved"));
            killTagged(helper.getLevel(), f.tag());
            f.victim().discard();
            cleanUp(server, f.ns());
            DatapackReloader.disableAndReload(server, "periodic_trex");
        });
    }

    /**
     * <b>The red control for bh-g03</b>: the same flood with the pausing OFF. The meter must judge it a breach
     * and name the pack — so the green test above is the quarantine working — and nothing pauses it. The test
     * stops the hurting the moment the meter says so, and cleans up itself.
     */
    @GameTest(template = "empty_test", batch = "quarantine_flood_off", timeoutTicks = 200_000)
    public static void withTheQuarantineOffTheSameFloodIsJudgedButNotPaused(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.fail(Component.literal("no CBScript compiler: this test would prove nothing"));
            return;
        }
        MinecraftServer server = helper.getLevel().getServer();
        PackCostMeter.quarantineForTest(false);
        Flood f;
        try {
            f = flood(helper);
        } catch (RuntimeException e) {
            PackCostMeter.quarantineForTest(true);
            throw e;
        }
        PackCostMeter.resetForTest();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean judged = new AtomicBoolean();
        hurt(helper, f, stop);
        long deadline = System.currentTimeMillis() + WALL;
        helper.onEachTick(() -> {
            var v = PackCostMeter.lastVerdict();
            if (v.breach() && f.ns().equals(v.pack())) {
                judged.set(true);
                stop.set(true);
            }
        });
        helper.succeedWhen(() -> {
            if (System.currentTimeMillis() > deadline) {
                stop.set(true);
                killTagged(helper.getLevel(), f.tag());
                PackCostMeter.quarantineForTest(true);
                cleanUp(server, f.ns());
                helper.fail(Component.literal("the meter never judged the flood in " + WALL / 1000 + " s"));
            }
            helper.assertTrue(judged.get(), Component.literal("flooding"));
            helper.assertFalse(DatapackQuarantine.isQuarantined(f.ns()),
                    Component.literal("with the pausing off, the pack was still paused"));
            helper.assertTrue(DatapackReloader.isEnabled(server, f.ns()), Component.literal("something else switched it off"));
            killTagged(helper.getLevel(), f.tag());
            f.victim().discard();
            PackCostMeter.quarantineForTest(true);
            cleanUp(server, f.ns());
        });
    }

    /**
     * <b>A runaway per-tick loop</b> (a heavy fill every tick, ~half a second each) is paused by the window, from
     * its own function time — on an open world and on an island world (the quarantine reads no world rules; the
     * island arm runs with its bases and rules in force). Then it STAYS off across a restart, and a wish that
     * re-makes the same pack cannot switch it back on.
     */
    @GameTest(template = "empty_test", batch = "quarantine_loop", timeoutTicks = 200_000)
    public static void aRunawayPerTickLoopIsPausedOnBothWorldsAndStaysPausedAcrossARestart(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        long t = helper.getLevel().getGameTime();
        String open = "gt_loop_w_" + t;
        String closed = "gt_loop_i_" + t;
        BlockPos o = helper.absolutePos(new BlockPos(0, 60, 0));
        AtomicInteger arm = new AtomicInteger();
        AtomicBoolean installed = new AtomicBoolean();
        long deadline = System.currentTimeMillis() + 2 * WALL;
        ServerPlayer him = MockPlayerNetwork.inLevel(helper, "mfam-quarantine");
        helper.succeedWhen(() -> {
            if (System.currentTimeMillis() > deadline) {
                com.moddingfromamod.world.WorldModuleHost.forTest(null);
                com.moddingfromamod.world.WorldRulesService.forTest(null);
                cleanUp(server, open);
                cleanUp(server, closed);
                helper.fail(Component.literal("a per-tick loop was not paused (arm " + arm.get() + ")"));
            }
            String ns = arm.get() == 0 ? open : closed;
            if (!installed.get()) {
                installed.set(true);
                if (arm.get() == 1) {
                    com.moddingfromamod.world.WorldModuleHost.forTest(
                            baseAround(him));
                    com.moddingfromamod.world.WorldRulesService.forTest(
                            closedRules());
                }
                StringBuilder heavy = new StringBuilder();
                for (int i = 0; i < 8; i++) {      // ~half a second a tick: under the hard stop, over the window
                    heavy.append("fill ").append(o.getX()).append(' ').append(o.getY()).append(' ').append(o.getZ())
                            .append(' ').append(o.getX() + 31).append(' ').append(o.getY() + 31).append(' ')
                            .append(o.getZ() + 31).append(' ').append(i % 2 == 0 ? "stone" : "air").append('\n');
                }
                try {
                    install(server, ns, Map.of("tick", heavy.toString()), true);
                    DatapackQuarantine.rememberOwner(ns, him.getUUID());
                } catch (IOException e) {
                    helper.fail(Component.literal("install: " + e));
                }
                PackCostMeter.resetForTest();
            }
            helper.assertTrue(DatapackQuarantine.isQuarantined(ns), Component.literal("looping (arm " + arm.get() + ")"));
            helper.assertTrue(DatapackQuarantine.paused().stream().anyMatch(e -> e.pack().equals(ns) && e.source().equals("meter")),
                    Component.literal("the loop was paused, but not by the window: " + DatapackQuarantine.paused()));
            helper.assertFalse(DatapackReloader.isEnabled(server, ns), Component.literal("paused but still on"));
            helper.assertTrue(MockPlayerNetwork.chat(him).stream().anyMatch(l -> l.contains("Moddy paused the")),
                    Component.literal("the child who wished it was not told: " + MockPlayerNetwork.chat(him)));
            if (arm.get() == 0) {
                // A RESTART: memory gone, the store re-read from disk.
                DatapackReloader.forgetSeedForTest();
                DatapackQuarantine.forgetForTest();
                helper.assertTrue(DatapackQuarantine.isQuarantined(ns), Component.literal("the pause was not saved"));
                helper.assertFalse(DatapackReloader.selectionWith(server, List.of()).contains(DatapackReloader.packId(ns)),
                        Component.literal("after a restart the next activation switched the paused pack back on"));
                boolean refusedReinstall = false;
                try {
                    DatapackReloader.installAndReload(server, ns, DatapackStore.ownZipFor(ns));
                } catch (IOException expected) {
                    refusedReinstall = expected.getMessage().contains("paused");
                }
                helper.assertTrue(refusedReinstall, Component.literal("a re-made pack of the same name switched back on"));
                arm.set(1);
                installed.set(false);
                helper.assertTrue(false, Component.literal("now the island arm"));
            }
            com.moddingfromamod.world.WorldModuleHost.forTest(null);
            com.moddingfromamod.world.WorldRulesService.forTest(null);
            for (int i = 0; i < 32; i++) {
                helper.getLevel().setBlock(o.offset(i, 0, 0), Blocks.AIR.defaultBlockState(), 2);
            }
            cleanUp(server, open);
            cleanUp(server, closed);
            MockPlayerNetwork.remove(him);
        });
    }

    /**
     * <b>A function that calls itself forever</b> (with the chain length every compiled wish pack sets, a billion)
     * is stopped INSIDE its tick by the hard stop, and two stops pause it — a sustained window could never see
     * this, because it is one tick and the watchdog ends the server at 60 s.
     */
    @GameTest(template = "empty_test", batch = "quarantine_recursion", timeoutTicks = 200_000)
    public static void aRecursiveFunctionIsStoppedInsideItsTickAndPaused(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String ns = "gt_recurse_" + helper.getLevel().getGameTime();
        var rule = server.getGameRules().getRule(GameRules.RULE_MAX_COMMAND_CHAIN_LENGTH);
        int before = rule.get();
        rule.set(1_000_000_000, server);
        try {
            install(server, ns, Map.of("tick", "function " + ns + ":loop\n",
                    "loop", "scoreboard players add #n gt_recurse 1\nfunction " + ns + ":loop\n"), true);
        } catch (IOException e) {
            rule.set(before, server);
            helper.fail(Component.literal("install: " + e));
            return;
        }
        server.getScoreboard().addObjective("gt_recurse", net.minecraft.world.scores.criteria.ObjectiveCriteria.DUMMY,
                Component.literal("gt"), net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER, true, null);
        PackCostMeter.resetForTest();
        long deadline = System.currentTimeMillis() + WALL;
        helper.succeedWhen(() -> {
            if (System.currentTimeMillis() > deadline) {
                rule.set(before, server);
                cleanUp(server, ns);
                helper.fail(Component.literal("the recursion was not paused"));
            }
            helper.assertTrue(DatapackQuarantine.isQuarantined(ns), Component.literal("recursing"));
            helper.assertTrue(DatapackQuarantine.paused().stream().anyMatch(e -> e.pack().equals(ns) && e.source().equals("hard-stop")),
                    Component.literal("the recursion was paused, but not by the hard stop: " + DatapackQuarantine.paused()));
            helper.assertTrue(PackCostMeter.maxTickMsForTest() < QuarantineRule.HARD_STOP_MS + 5_000,
                    Component.literal("a tick ran " + (int) PackCostMeter.maxTickMsForTest() + " ms: the hard stop did not cut it"));
            rule.set(before, server);
            cleanUp(server, ns);
            var objective = server.getScoreboard().getObjective("gt_recurse");
            if (objective != null) {
                server.getScoreboard().removeObjective(objective);
            }
        });
    }

    // ── after a crash, and the grown-up's door ───────────────────────────────────────────────────────────

    /**
     * <b>After a watchdog crash, the server boots with the suspected pack paused and every other pack loaded</b>:
     * first the meter's own suspect, then — with none — the pack installed just before. A crash already handled is
     * not handled twice, and a crash report that is not the watchdog's pauses nothing.
     */
    @GameTest(template = "empty_test", batch = "quarantine_crash", timeoutTicks = 400)
    public static void afterACrashTheSuspectBootsPausedAndEveryOtherPackLoads(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        long t = helper.getLevel().getGameTime();
        String suspect = "gt_crash_suspect_" + t;
        String recent = "gt_crash_recent_" + t;
        String innocent = "gt_crash_innocent_" + t;
        try {
            Path crashes = Files.createTempDirectory("mfam_crash");
            for (String ns : List.of(suspect, recent, innocent)) {
                install(server, ns, Map.of("tick", "scoreboard players add #t gt_noop 0\n"), true);
            }
            long now = System.currentTimeMillis();
            Path dp = DatapackReloader.worldDatapackDir(server);
            DatapackQuarantine.forgetForTest();     // a fresh boot's view: no breadcrumb from another batch
            Files.setLastModifiedTime(dp.resolve(innocent + ".zip"), FileTime.fromMillis(now - 3 * 3_600_000L));
            Files.setLastModifiedTime(dp.resolve(recent + ".zip"), FileTime.fromMillis(now - 3 * 3_600_000L));
            Files.setLastModifiedTime(dp.resolve(suspect + ".zip"), FileTime.fromMillis(now - 3 * 3_600_000L));
            // 1. a crash the meter saw coming: its suspect, alone
            DatapackQuarantine.noteSuspect(suspect, now - 30_000);
            Path c1 = crashes.resolve("crash-gt1-server.txt");
            Files.writeString(c1, "---- Minecraft Crash Report ----\nDescription: Watching Server\n"
                    + "java.lang.Error: ServerHangWatchdog detected that a single server tick took 60.00 seconds\n");
            Files.setLastModifiedTime(c1, FileTime.fromMillis(now));
            List<String> first = DatapackQuarantine.afterCrash(server, crashes, dp);
            helper.assertTrue(first.equals(List.of(suspect)), Component.literal("paused after crash 1: " + first));
            // handled once only
            helper.assertTrue(DatapackQuarantine.afterCrash(server, crashes, dp).isEmpty(),
                    Component.literal("the same crash was handled twice"));
            // 2. a crash with no fresh suspect: the pack installed just before it
            Files.setLastModifiedTime(dp.resolve(recent + ".zip"), FileTime.fromMillis(now + 3_600_000L - 60_000));
            Path c2 = crashes.resolve("crash-gt2-server.txt");
            Files.writeString(c2, "Description: Watching Server\nA single server tick took 61.20 seconds\n");
            Files.setLastModifiedTime(c2, FileTime.fromMillis(now + 3_600_000L));   // an hour later: the suspect is stale
            List<String> second = DatapackQuarantine.afterCrash(server, crashes, dp);
            helper.assertTrue(second.equals(List.of(recent)), Component.literal("paused after crash 2: " + second));
            // 3. not the watchdog: nothing
            Path c3 = crashes.resolve("crash-gt3-server.txt");
            Files.writeString(c3, "Description: Exception in server tick loop\njava.lang.NullPointerException\n");
            helper.assertTrue(DatapackQuarantine.afterCrash(server, crashes, dp).isEmpty(),
                    Component.literal("a crash that was not a freeze paused a pack"));
            helper.assertTrue(DatapackQuarantine.isQuarantined(suspect), Component.literal("the suspect is not paused"));
            helper.assertFalse(DatapackQuarantine.isQuarantined(innocent), Component.literal("the innocent pack was paused"));
            helper.assertTrue(DatapackReloader.isEnabled(server, innocent) || DatapackReloader.reloadInProgress(),
                    Component.literal("the innocent pack did not load"));
        } catch (IOException e) {
            helper.fail(Component.literal("io: " + e));
            return;
        } finally {
            for (String ns : List.of(suspect, recent, innocent)) {
                cleanUp(server, ns);
            }
        }
        helper.succeed();
    }

    // The /moddyop quarantine door is QuarantineCommandGameTest: /moddyop is the private operator root.

}
