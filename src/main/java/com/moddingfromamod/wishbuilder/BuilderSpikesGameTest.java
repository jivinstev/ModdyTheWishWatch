package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * <b>A creature with Crystal spikes outlives its own eruptions</b>. The {@code erupt_blocks} fill is
 * "replace air", and the space a creature stands in is air, so every creature given the move was encased by its own
 * spikes and "suffocated in a wall" within a minute — one, three or thirty of them, measured on a dedicated server
 * ({@code BuilderMaxProbe}). Its GameTest watched the spikes APPEAR, which happens before the suffocation adds up.
 *
 * <p>This makes one cat and a herd of three with the move through the REAL Builder make ({@link BuilderMake}: the
 * lowering, sculpt_creature, apply_effect and the compiled template), and keeps them under watch for two minutes of game
 * time (2400 ticks — suffocation is counted in ticks, not seconds) while they erupt: every one alive at the end, and
 * every herd erupting at least three times. The ring is the template's: a living thing's space is never filled. The two
 * herds stand side by side ON PURPOSE: the move's damage said "anything but a player", and the herd of three's spikes
 * killed the lone cat beside it ("killed by magic") — now it names monsters only. (A creature's eruption marker is
 * tagged {@code mfam_erl_wishmob_<id>}: apply_effect's TAG is the carrier's {@code wishmob_<id>}.)
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderSpikesGameTest {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** Two minutes of game time. */
    static final int WATCH_TICKS = 2400;
    /** Each herd must erupt at least this often in the watch (it erupts every 8 s: 15 times in two minutes). */
    static final int MIN_ERUPTIONS = 3;
    /** The herd counts as whole once it has stood complete this many ticks in a row (past the rig's carrier swap). */
    static final int STEADY = 40;
    /** One missing this many ticks in a row is a death, not a swap. */
    static final int GONE = 20;

    private BuilderSpikesGameTest() {
    }

    @GameTest(template = "empty_test", batch = "builderSpikes", timeoutTicks = 30000)
    public static void aCreatureWithCrystalSpikesOutlivesItsOwnEruptions(GameTestHelper helper) {
        if (!com.moddingfromamod.datapack.CbScriptCompiler.canBuildReadyMades()) {
            helper.succeed();
            return;
        }
        var p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtspikes");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        int[] herds = {1, 3};
        String[] ids = new String[herds.length];
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean[] made = {false};
        Thread t = new Thread(() -> {
            long rise = BuilderMake.riseMillis;
            BuilderMake.riseMillis = 0;
            try {
                for (int i = 0; i < herds.length; i++) {
                    String json = "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":1,"
                            + "\"name\":\"Spiky " + herds[i] + "\",\"count\":" + herds[i] + ",\"powers\":[\"ability:spikes\"]}";
                    BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(json).getAsJsonObject());
                    BuilderMake.Result r = BuilderAbilityGameTest.onServer(server, () -> BuilderMake.run(server,
                            BuilderMake.Who.of(p), spec, null, BuilderAbilityGameTest.protocol(), false, false))
                            .get(120, TimeUnit.SECONDS).get(120, TimeUnit.SECONDS);
                    if (r.outcome() != BuilderMake.Outcome.MADE) {
                        wrong.add("herd of " + herds[i] + " not made: " + r.outcome() + " " + r.detail());
                    }
                    ids[i] = r.madeId();
                }
            } catch (Throwable e) {
                wrong.add("threw: " + e);
            } finally {
                BuilderMake.riseMillis = rise;
                made[0] = true;
            }
        }, "builder-spikes-gt");
        t.setDaemon(true);
        t.start();

        long[] start = {-1};
        int[] most = new int[herds.length];
        int[] whole = new int[herds.length];
        int[] short_ = new int[herds.length];
        int[] peak = new int[herds.length];
        List<Set<java.util.UUID>> eruptions = List.of(new HashSet<>(), new HashSet<>());
        helper.onEachTick(() -> {
            if (!made[0]) {
                return;
            }
            if (!wrong.isEmpty()) {
                throw com.moddingfromamod.testing.gt.GameTestCompat.assertion(String.join("; ", wrong));
            }
            if (start[0] < 0) {
                start[0] = helper.getTick();
            }
            AABB near = new AABB(at).inflate(48);
            for (int i = 0; i < herds.length; i++) {
                String id = ids[i];
                if (id == null) {
                    continue;
                }
                int alive = 0;
                for (Entity e : level.getEntities((Entity) null, near, x -> true)) {
                    var sp = com.moddingfromamod.vanilla.DisplayRig.speciesOn(e);
                    if (sp != null && id.equals(sp.id()) && e instanceof LivingEntity l && l.isAlive()) {
                        alive++;
                    }
                    if (e.entityTags().contains("mfam_erl_wishmob_" + id)) {
                        eruptions.get(i).add(e.getUUID());
                    }
                }
                // Latched once the whole herd has STOOD (STEADY ticks in a row): any one missing for GONE ticks in a row
                // is a death (they are persistent and never despawn). Not a single tick, so a carrier rebuilt in place
                // never reads as a death. ⚠ The gate run that prompted this ("died at tick 6 ... after 0 eruption(s)",
                // 2026-11-11) was NOT a carrier swap: it was, the end of the wish reaping the herd's first member as
                // scaffolding a few ticks after the make returned — which then read "never stood whole: 0" here
                // (WishShimmer.joinsHerd, ShimmerHerdGameTest).
                peak[i] = Math.max(peak[i], alive);
                whole[i] = alive >= herds[i] ? whole[i] + 1 : 0;
                if (whole[i] >= STEADY) {
                    most[i] = herds[i];
                }
                short_[i] = most[i] == herds[i] && alive < herds[i] ? short_[i] + 1 : 0;
                if (short_[i] >= GONE) {
                    wrong.add("a creature of the herd of " + herds[i] + " died at tick " + (helper.getTick() - start[0])
                            + " of " + WATCH_TICKS + " (" + alive + " left) after " + eruptions.get(i).size() + " eruption(s)");
                }
            }
            if (!wrong.isEmpty()) {
                throw com.moddingfromamod.testing.gt.GameTestCompat.assertion(String.join("; ", wrong));
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(made[0] && start[0] >= 0, Component.literal("still making"));
            helper.assertTrue(helper.getTick() - start[0] >= WATCH_TICKS,
                    Component.literal("watching: " + (helper.getTick() - start[0]) + " of " + WATCH_TICKS + " ticks"));
            for (int i = 0; i < herds.length; i++) {
                helper.assertTrue(most[i] == herds[i], Component.literal("the herd of " + herds[i] + " never stood whole: " + most[i]
                        + " (at most " + peak[i] + " alive at once)"));
                helper.assertTrue(eruptions.get(i).size() >= MIN_ERUPTIONS, Component.literal("the herd of " + herds[i]
                        + " erupted " + eruptions.get(i).size() + " time(s) in two minutes, not " + MIN_ERUPTIONS + "+: the move never fired"));
            }
            LOGGER.info("builder spikes GT: herds {} alive after {} ticks; eruptions {} / {}", java.util.Arrays.toString(most),
                    WATCH_TICKS, eruptions.get(0).size(), eruptions.get(1).size());
        });
    }
}
