package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import com.moddingfromamod.testing.gt.GameTestHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The cost of the five per-tick paths the 2026-10-31 audit flagged, under a realistic load</b>
 * — run on the code before the change and after it, and compared by the [perf] lines it logs. It
 * asserts nothing about time (a GameTest box is not a quiet machine); it asserts only that every
 * stage ran. Uses no API the old code lacks.
 *
 * <ul>
 *   <li><b>P1</b> DisplayRig.speciesOn — 60 designs, 100 carriers, 200 ticks.</li>
 *   <li><b>P2</b> RigPowers.stillBeingWished — 600 finished wishes in the marshal's history, 100
 *       creatures asking every tick for 200 ticks.</li>
 *   <li><b>P3</b> RigAbilities.tick — 30 shooters, 15 monsters in range, 200 ticks.</li>
 *   <li><b>P4</b> a design change (SpeciesRegistry.touch) with 36 sculpted creatures standing.</li>
 *   <li><b>P5</b> WatchSnapshot's refresh for a stock-client player with 60 things on his watch.</li>
 * </ul>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class TickScalingPerfGameTest {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-perf");
    static final int TICKS = 200;

    private static void report(String what, long nanos, long calls) {
        LOG.info("[perf] {}: {} ms total over {} calls ({} us/call)", what,
                String.format(java.util.Locale.ROOT, "%.1f", nanos / 1e6), calls,
                String.format(java.util.Locale.ROOT, "%.2f", calls == 0 ? 0 : nanos / 1e3 / calls));
    }

    @GameTest(template = "empty_test", batch = "tickscalingperf", timeoutTicks = 4000)
    public static void perTickPathsUnderLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        List<Entity> made = new ArrayList<>();

        // ── shared load: 60 designs, 100 carriers wearing them ──────────────────────────────────
        List<Species> designs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            designs.add(TickScalingGameTest.species("gt_perf_d" + i));
        }
        // In memory for this test only: a full Gate B can fill the registry's 500, and then every put is refused.
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, SpeciesRegistry.putForTest(designs));
        List<Pig> carriers = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Pig p = TickScalingGameTest.carrier(helper, 1 + (i % 10), "gt_perf_d" + (i % 60));
            p.teleportTo(p.getX(), p.getY(), p.getZ() + (i / 10) * 0.5);
            carriers.add(p);
            made.add(p);
        }
        // ── P2's load: the marshal's history ────────────────────────────────────────────────────
        for (int i = 0; i < 600; i++) {
            UUID w = UUID.nameUUIDFromBytes(("perf-hist-" + i + "-" + System.nanoTime()).getBytes());
            Marshal.global().submit(w, UUID.randomUUID(), "perf history " + i);
            Marshal.global().finish(w);
        }
        // ── P3's load: shooters and monsters ────────────────────────────────────────────────────
        Species shooter = Species.fromJson(com.google.gson.JsonParser.parseString(
                "{\"id\":\"gt_perf_shooter\",\"name\":\"Shooter\",\"health\":20,\"speed\":0.0,\"scale\":1.0,"
                        + "\"powers\":[\"FRIENDLY\"],\"abilities\":[\"ranged\"]}").getAsJsonObject());
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, SpeciesRegistry.putForTest(List.of(shooter)));
        List<Pig> shooters = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Pig p = helper.spawn(EntityTypes.PIG, new BlockPos(1 + (i % 6), 2, 6 + i / 6));
            p.addTag(DisplayRig.RIG_TAG);
            p.addTag(com.moddingfromamod.entity.WishMobTags.forSpecies(shooter.id()));
            shooters.add(p);
            made.add(p);
        }
        for (int i = 0; i < 15; i++) {
            Zombie z = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(1 + (i % 5), 2, 13 + i / 5));
            z.setNoAi(true);
            z.setPermanentlyInvulnerable(true);
            made.add(z);
        }

        long[] p1 = {0, 0}, p2 = {0, 0}, p3 = {0, 0}, p4 = {0, 0};
        long[] tickStart = {0};
        boolean[] armed = {false};
        List<LivingEntity> rigs = new ArrayList<>();
        int[] tick = {0};
        // ONE handler, one step per tick: scheduling from inside a scheduled callback crashes the
        // GameTest runner (fastutil iterator), so every phase is a range of ticks here.
        helper.onEachTick(() -> {
            int t = tick[0]++;
            if (t < TICKS) {
                long t0 = System.nanoTime();
                for (Pig c : carriers) {
                    if (DisplayRig.speciesOn(c) == null) {
                        throw new IllegalStateException("P1: a carrier had no species");
                    }
                }
                long t1 = System.nanoTime();
                for (int i = 0; i < carriers.size(); i++) {
                    RigPowers.stillBeingWished(carriers.get(i), designs.get(i % designs.size()));
                }
                long t2 = System.nanoTime();
                for (Pig sh : shooters) {
                    RigAbilities.tick(sh, shooter);
                }
                long t3 = System.nanoTime();
                p1[0] += t1 - t0;
                p1[1] += carriers.size();
                p2[0] += t2 - t1;
                p2[1] += carriers.size();
                p3[0] += t3 - t2;
                p3[1] += shooters.size();
            } else if (t == TICKS) {
                report("P1 speciesOn (60 designs, 100 carriers)", p1[0], p1[1]);
                report("P2 stillBeingWished (600 past wishes)", p2[0], p2[1]);
                report("P3 RigAbilities.tick (30 shooters, 15 monsters)", p3[0], p3[1]);
                // P4: sculpted creatures standing, then design changes
                String[] starters = {"trex", "frog", "pet_dragon", "turtle", "wolf", "cat"};
                for (int i = 0; i < 36; i++) {
                    BlockPos at = helper.absolutePos(new BlockPos(1 + (i % 6) * 2, 2, 20 + (i / 6) * 2));
                    LivingEntity e = CreatureSpawn.spawn(level, starters[i % starters.length],
                            at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, EntitySpawnReason.COMMAND);
                    if (e != null) {
                        rigs.add(e);
                        made.add(e);
                    }
                }
                NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre ev) -> tickStart[0] = System.nanoTime());
                NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post ev) -> {
                    if (armed[0]) {
                        p4[0] += System.nanoTime() - tickStart[0];
                        p4[1]++;
                        armed[0] = false;
                    }
                });
            } else if (t >= TICKS + 40 && t < TICKS + 90 && (t - TICKS) % 5 == 0) {
                SpeciesRegistry.touch();       // a design changed: the next tick pays for it
                armed[0] = true;
            } else if (t == TICKS + 100) {
                report("P4 design change, the tick after each bump (" + rigs.size() + " sculpted creatures)",
                        p4[0], p4[1]);
                // P5: the watch refresh for a stock-client player
                ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "perf_kid");
                long[] p5 = {0};
                MockPlayerNetwork.withoutChannels(kid, () -> {
                    var ev = new ServerTickEvent.Post(() -> true, server);
                    long s0 = System.nanoTime();
                    for (int i = 0; i < 200; i++) {
                        com.moddingfromamod.wish.WatchSnapshot.onServerTick(ev);
                    }
                    p5[0] = System.nanoTime() - s0;
                });
                report("P5 watch refresh, stock client (200 ticks = 20 refreshes)", p5[0], 20);
                MockPlayerNetwork.remove(kid);
                made.forEach(Entity::discard);
                helper.succeed();
            }
        });
    }
}
