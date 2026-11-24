package com.moddingfromamod.builder;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.BuildStructureTool;
import com.moddingfromamod.agent.tools.DefineSpeciesTool;
import com.moddingfromamod.agent.tools.InspectBuildTool;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.SpikeFixture;
import com.moddingfromamod.wish.LearnedTemplates;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishExecutor;
import com.moddingfromamod.wish.WishSource;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Build spots, where they RUN </b>: a boss on his throne through the tools the agent
 * calls and the command a replay runs. The rule is unit-tested in {@code BuildSpotsTest} and
 * {@code SpotLinkTest}; this drives the real {@code build_structure}, {@code inspect_build},
 * {@code define_creature}, {@code wishmob spawn … at} and {@code add_template} paths — and an earlier issue's
 * option C, where a REPLAYED seat follows his aim rather than the castle its recipe names.
 *
 * <p>Each test is in its own batch: they bind a wish run, and {@code AgentContext} falls back to
 * "the single wish in flight", which another test's wish would make ambiguous.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class BuildSpotsGameTest {

    /** A hollow stone hall with a gold throne at its back and a doorway in the south wall. */
    static String hallJson(String id) {
        return "{\"id\":\"" + id + "\",\"name\":\"Throne Hall\",\"note\":\"gate\",\"shapes\":["
                + "{\"kind\":\"box\",\"name\":\"hall\",\"dx\":0,\"dy\":0,\"dz\":0,\"width\":11,\"height\":7,"
                + "\"depth\":11,\"block\":\"minecraft:stone_bricks\",\"hollow\":true},"
                + "{\"kind\":\"box\",\"name\":\"seat\",\"dx\":5,\"dy\":1,\"dz\":2,\"width\":1,\"height\":1,"
                + "\"depth\":1,\"block\":\"minecraft:gold_block\"},"
                + "{\"kind\":\"box\",\"name\":\"door\",\"dx\":5,\"dy\":1,\"dz\":10,\"width\":1,\"height\":1,"
                + "\"depth\":1,\"block\":\"minecraft:oak_door\"}],"
                // dy 1 is the SEAT itself — the commonest mistake; build_structure lifts it to 2.
                + "\"spots\":[{\"name\":\"throne\",\"dx\":5,\"dy\":1,\"dz\":2,\"facing\":\"south\"}]}";
    }

    // ===================== the tool path: build_structure, inspect_build, define_creature at =====

    @GameTest(template = "empty_test", batch = "spots_tool", timeoutTicks = 600)
    public static void aBossTakesHisThroneOnceTheHallIsUp(GameTestHelper helper) {
        String hall = "gt_spots_hall", boss = "gt_spots_boss";
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        Wish wish = bind(level, mock, "a boss on a throne in a hall", "gt-spots-tool");
        AtomicReference<String> built = new AtomicReference<>();
        AtomicReference<String> inspected = new AtomicReference<>();
        AtomicReference<ToolResult> seated = new AtomicReference<>();
        offThread(() -> AgentContext.with("gt-spots-tool", () -> {
            built.set(new BuildStructureTool().call(args(hallJson(hall))).text());
            inspected.set(new InspectBuildTool().call(args("{\"id\":\"" + hall + "\"}")).text());
            seated.set(new DefineSpeciesTool().call(args("{\"id\":\"" + boss + "\",\"name\":\"Boss\","
                    + "\"spawn\":true,\"powers\":[\"friendly\"],\"at\":\"" + hall + ":throne\"}")));
            return null;
        }));
        helper.succeedWhen(() -> {
            helper.assertTrue(seated.get() != null, Component.literal("waiting for define_creature"));
            {   // NOT try/finally: a poll that throws must not tidy away what the next poll looks for
                helper.assertTrue(built.get().contains("SPOTS") && built.get().contains("throne at 5,2,2"),
                        Component.literal("build_structure must say where its spots are (and lift the throne spot onto "
                                + "the seat): " + built.get()));
                helper.assertTrue(inspected.get().contains("throne at 5,2,2") && inspected.get().contains("front"),
                        Component.literal("inspect_build must list the spots, the default front among them: " + inspected.get()));
                helper.assertFalse(seated.get().isError(), Component.literal("define_creature failed: " + seated.get().text()));
                helper.assertTrue(seated.get().text().contains("on " + hall + ":throne"),
                        Component.literal("define_creature must say it put him on the throne: " + seated.get().text()));
                Long anchor = BuildRegistry.anchorOf(hall);
                helper.assertTrue(anchor != null, Component.literal("the hall has no anchor"));
                BlockPos throne = BlockPos.of(anchor).offset(5, 2, 2);
                List<LivingEntity> on = carriers(level, boss);
                helper.assertTrue(on.size() == 1, Component.literal("one boss should stand there, found " + on.size()));
                LivingEntity c = on.get(0);
                helper.assertTrue(Math.abs(c.getX() - (throne.getX() + 0.5)) <= 1.0
                                && Math.abs(c.getY() - throne.getY()) <= 1.0
                                && Math.abs(c.getZ() - (throne.getZ() + 0.5)) <= 1.0,
                        Component.literal("the boss is at " + c.blockPosition().toShortString() + ", not on the throne at "
                                + throne.toShortString()));
                helper.assertFalse(c.isInWall(), Component.literal("the boss is inside a block"));
                WishRun run = Marshal.global().run(wish.id());
                helper.assertTrue(run != null && (hall + ":throne").equals(run.seats().get(boss)),
                        Component.literal("the wish must remember where he sat: " + (run == null ? "no run" : run.seats())));
            }
            {   // every assertion held: tidy up
                Marshal.global().finish(wish.id());
                tidy(level, boss);
                MockPlayerNetwork.remove(mock);
            }
        });
    }

    // ===================== no spot named, in a wish that built → front, never the anchor ========

    @GameTest(template = "empty_test", batch = "spots_front", timeoutTicks = 600)
    public static void aWishThatBuildsAndNamesNoSpotStandsItAtTheFront(GameTestHelper helper) {
        String hall = "gt_spots_front_hall", rex = "gt_spots_rex";
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        Wish wish = bind(level, mock, "a garage with a dinosaur", "gt-spots-front");
        AtomicReference<ToolResult> seated = new AtomicReference<>();
        AtomicReference<LivingEntity> seen = new AtomicReference<>();
        AtomicReference<BlockPos> firstAt = new AtomicReference<>();
        offThread(() -> AgentContext.with("gt-spots-front", () -> {
            new BuildStructureTool().call(args(hallJson(hall)));
            seated.set(new DefineSpeciesTool().call(args("{\"id\":\"" + rex + "\",\"name\":\"Rex\","
                    + "\"spawn\":true,\"powers\":[\"friendly\"]}")));
            return null;
        }));
        helper.succeedWhen(() -> {
            helper.assertTrue(seated.get() != null, Component.literal("waiting for define_creature"));
            {   // NOT try/finally: a poll that throws must not tidy away what the next poll looks for
                helper.assertFalse(seated.get().isError(), Component.literal("define_creature failed: " + seated.get().text()));
                Long anchor = BuildRegistry.anchorOf(hall);
                helper.assertTrue(anchor != null, Component.literal("the hall has no anchor"));
                BlockPos a = BlockPos.of(anchor);
                WishRun r0 = Marshal.global().run(wish.id());
                if (seen.get() == null && r0 != null) {
                    for (var cs : r0.creaturesSpawned()) {
                        if (cs.carrier() != null && level.getEntity(cs.carrier()) instanceof LivingEntity l) {
                            seen.set(l);
                        }
                    }
                }
                List<LivingEntity> on = carriers(level, rex);
                if (firstAt.get() == null && on.size() == 1) {
                    firstAt.set(on.get(0).blockPosition());   // where it was PUT, before it strolls
                }
                LivingEntity was = seen.get();
                helper.assertTrue(on.size() == 1, Component.literal("one creature should stand there, found " + on.size()
                        + " (tagged anywhere: " + tagged(level, rex) + "; the carrier "
                        + (was == null ? "never seen" : was.getType().toShortString() + " at "
                                + was.blockPosition().toShortString() + " removal=" + was.getRemovalReason()
                                + " health=" + was.getHealth() + " fire=" + was.getRemainingFireTicks())
                        + "); define_creature said: " + seated.get().text()));
                LivingEntity c = on.get(0);
                // The door is at 5,1,10: the front is two blocks outside it, at 5,1,12.
                BlockPos front = a.offset(5, 1, 12);
                // The FRONT is a column, and the creature stands on whatever is the ground there.
                // That column is outside this test's own cleared box, and the GameTest world is
                // reused between runs, so another test's leftover blocks can sit on it — the creature
                // then correctly stands on top of them (measured: 6 blocks up, 4 runs in 19). What
                // this test is about is WHICH spot (the front, never the anchor), so it checks the
                // column exactly and only that it is not below the front's ground.
                BlockPos put = firstAt.get();
                helper.assertTrue(Math.abs(put.getX() - front.getX()) + Math.abs(put.getZ() - front.getZ()) <= 1
                                && put.getY() >= front.getY() - 1,
                        Component.literal("a wish that built and named no spot must stand it at the FRONT ("
                                + front.toShortString() + "), it was put at " + firstAt.get().toShortString()
                                + " (the anchor is " + a.toShortString() + "): " + seated.get().text()));
                helper.assertFalse(c.isInWall(), Component.literal("it is inside a block"));
                WishRun run = Marshal.global().run(wish.id());
                helper.assertTrue(run != null && (hall + ":front").equals(run.seats().get(rex)),
                        Component.literal("the front seat is a seat too: " + (run == null ? "no run" : run.seats())));
            }
            {   // every assertion held: tidy up
                Marshal.global().finish(wish.id());
                tidy(level, rex);
                MockPlayerNetwork.remove(mock);
            }
        });
    }

    // ===================== the replay path: wishmob spawn … at waits for its building ============

    @GameTest(template = "empty_test", batch = "spots_cmd", timeoutTicks = 600)
    public static void wishmobSpawnAtWaitsForTheBuildingThenSeatsHim(GameTestHelper helper) {
        String hall = "gt_spots_cmd_hall", boss = "gt_spots_cmd_boss";
        ServerLevel level = helper.getLevel();
        plantSculpted(helper, boss);
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        BuildPlan plan = BuildPlan.fromJson(args(hallJson(hall)));
        plan = plan.withSpots(BuildSpots.checked(plan, plan.spots(), new ArrayList<>()));
        BuildRegistry.put(level.getServer(), plan);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, 40, 0);
        Builder.submit(level, anchor, plan);
        helper.assertTrue(Builder.isBuilding(hall), Component.literal("the hall should still be going up"));
        WishExecutor.Result r = WishExecutor.runOne(level.getServer(), level, mock.position(), mock,
                "wishmob spawn " + boss + " at " + hall + ":throne");
        String said = String.join(" ", r.output());
        helper.assertTrue(r.success(), Component.literal("wishmob spawn … at failed: " + said));
        helper.assertTrue(said.contains("will take its place"), Component.literal("it must say it is waiting: " + said));
        helper.assertTrue(carriers(level, boss).isEmpty(), Component.literal("#1355: he was seated BEFORE the hall was "
                + "built — on air where the throne will be"));
        BlockPos throne = anchor.offset(5, 2, 2);
        BuildPlan built = plan;
        helper.succeedWhen(() -> {
            List<LivingEntity> on = carriers(level, boss);
            helper.assertTrue(on.size() == 1, Component.literal("waiting for the seat (" + on.size() + " standing)"));
            LivingEntity c = on.get(0);
            {   // NOT try/finally: a poll that throws must not tidy away what the next poll looks for
                helper.assertTrue(c.blockPosition().distManhattan(throne) <= 1,
                        Component.literal("he is at " + c.blockPosition().toShortString() + ", not on the throne at "
                                + throne.toShortString()));
                helper.assertFalse(c.isInWall(), Component.literal("he is inside a block"));
            }
            {   // every assertion held: tidy up
                tidy(level, boss);
                Builder.clearBuild(level, anchor, built);
                MockPlayerNetwork.remove(mock);
            }
        });
    }

    // ===================== option C: a REPLAY follows his aim ===============================

    @GameTest(template = "empty_test", batch = "spots_replay", timeoutTicks = 1200)
    public static void aReplayedSeatFollowsHisAimNotTheRecipesCastle(GameTestHelper helper) {
        // The recipe names the FIRST hall; he is looking at a NEWER one, then at the sky far away.
        String first = "gt_spots_replay_hall", newer = "gt_spots_replay_hall_2", boss = "gt_spots_replay_boss";
        ServerLevel level = helper.getLevel();
        plantSculpted(helper, boss);
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        BlockPos a = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, 50, 0);
        BlockPos b = a.offset(40, 0, 0);
        BuildPlan pa = BuildPlan.fromJson(args(hallJson(first)));
        pa = pa.withSpots(BuildSpots.checked(pa, pa.spots(), new ArrayList<>()));
        BuildPlan pb = BuildPlan.fromJson(args(hallJson(newer)));
        pb = pb.withSpots(BuildSpots.checked(pb, pb.spots(), new ArrayList<>()));
        BuildRegistry.put(level.getServer(), pa);
        BuildRegistry.put(level.getServer(), pb);
        Builder.submit(level, a, pa);
        Builder.submit(level, b, pb);
        BuildPlan builtA = pa, builtB = pb;
        String replay = "wishmob spawn " + boss + " at " + first + ":throne";
        AtomicReference<String> said = new AtomicReference<>("");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(!Builder.isBuilding(first) && !Builder.isBuilding(newer),
                        Component.literal("waiting for both halls")))
                .thenExecute(() -> {
                    // In front of the NEWER hall's south wall, looking straight at it (yaw 180 = north).
                    com.moddingfromamod.compat.Teleports.player(mock, level, b.getX() + 5.5, b.getY() + 2,
                            b.getZ() + 20.5, 180f, 0f);
                    WishExecutor.Result r = WishExecutor.runOne(level.getServer(), level, mock.position(), mock, replay);
                    said.set(String.join(" ", r.output()));
                    helper.assertTrue(r.success(), Component.literal("the replay failed: " + said.get()));
                })
                .thenWaitUntil(() -> {
                    List<LivingEntity> on = carriers(level, boss);
                    helper.assertTrue(on.size() == 1, Component.literal("waiting for the boss (" + on.size() + ")"));
                    BlockPos throne = b.offset(5, 2, 2);
                    helper.assertTrue(on.get(0).blockPosition().distManhattan(throne) <= 1,
                            Component.literal("#1376: a replay he aims at the NEWER hall must seat him on ITS throne at "
                                    + throne.toShortString() + ", not on the first hall's — he is at "
                                    + on.get(0).blockPosition().toShortString() + ": " + said.get()));
                })
                .thenExecute(() -> {
                    tidy(level, boss);
                    // Far away, looking at the sky: no hall in view, so he comes to him.
                    com.moddingfromamod.compat.Teleports.player(mock, level, b.getX() + 300.5, b.getY() + 2,
                            b.getZ() + 0.5, 0f, -80f);
                    BlockPos far = BlockPos.containing(b.getX() + 300.5, b.getY() + 2, b.getZ() + 0.5);
                    holdAround(level, far, true);
                    releaseWhenTheTestEnds(helper, level, far);
                })
                // A child who wishes far away has been standing there: the ground round him is loaded and ticking,
                // because a real player's presence holds it. A MOCK holds nothing (its connection is never ticked, so
                // the chunk map never moves it), so the far replay spawned into ground nobody had loaded and only
                // got a boss when the spawn path happened to load it in time: "no Boss is standing there", or a boss
                // counted far off (2026-11-11 → 11-18, 5 failures, every one in a full tier). The test holds the 3x3
                // round him, as he would, and waits for it to tick.
                .thenWaitUntil(() -> helper.assertTrue(heldAndTicking(level,
                                BlockPos.containing(b.getX() + 300.5, b.getY() + 2, b.getZ() + 0.5)),
                        Component.literal("waiting for the ground round him, 300 blocks off, to tick")))
                .thenExecute(() -> {
                    WishExecutor.Result r = WishExecutor.runOne(level.getServer(), level, mock.position(), mock, replay);
                    said.set(String.join(" ", r.output()));
                    helper.assertTrue(r.success(), Component.literal("the far replay failed: " + said.get()));
                })
                .thenWaitUntil(() -> {
                    List<LivingEntity> on = carriers(level, boss);
                    helper.assertTrue(on.size() == 1, Component.literal("waiting for the far boss (" + on.size() + ")"));
                    helper.assertTrue(on.get(0).distanceTo(mock) < 16,
                            Component.literal("#1376: far from every hall the replay must put him where HE is, not 300 blocks "
                                    + "off on a throne — he is " + (int) on.get(0).distanceTo(mock) + " away: "
                                    + said.get()));
                    helper.assertTrue(said.get().contains("right here with him"),
                            Component.literal("and say so plainly: " + said.get()));
                })
                .thenExecute(() -> {
                    tidy(level, boss);
                    Builder.clearBuild(level, a, builtA);
                    Builder.clearBuild(level, b, builtB);
                    BuildRegistry.markRemoved(level.getServer(), first);
                    BuildRegistry.markRemoved(level.getServer(), newer);
                    MockPlayerNetwork.remove(mock);
                })
                .thenSucceed();
    }

    /** Pass, fail or time-out: the forced chunks go back, so a red run leaves nothing loaded for the rest of the tier. */
    private static void releaseWhenTheTestEnds(GameTestHelper helper, ServerLevel level, BlockPos at) {
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            @Override
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {
            }

            @Override
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,
                    net.minecraft.gametest.framework.GameTestRunner runner) {
                holdAround(level, at, false);
            }

            @Override
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,
                    net.minecraft.gametest.framework.GameTestRunner runner) {
                holdAround(level, at, false);
            }

            @Override
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo before,
                    net.minecraft.gametest.framework.GameTestInfo after,
                    net.minecraft.gametest.framework.GameTestRunner runner) {
            }
        });
    }

    /**
     * Every one of the 3x3 is ticking entities, not only his own chunk: the creature stands a few blocks in front of
     * him, and measured, that was the NEXT chunk south, which had not yet caught up when his own was ticking.
     */
    private static boolean heldAndTicking(ServerLevel level, BlockPos at) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!level.isPositionEntityTicking(at.offset(dx * 16, 0, dz * 16))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Force (or release) the 3x3 chunks round a spot: what a real player standing there holds loaded and ticking. */
    private static void holdAround(ServerLevel level, BlockPos at, boolean hold) {
        net.minecraft.world.level.ChunkPos c = net.minecraft.world.level.ChunkPos.containing(at);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced(c.x() + dx, c.z() + dz, hold);
            }
        }
    }

    // ===================== wishspot: a REAL mob (or anything) on a spot ==========================

    @GameTest(template = "empty_test", batch = "spots_wishspot", timeoutTicks = 400)
    public static void wishspotRunsItsCommandOnTheSpotOnceTheBuildingIsUp(GameTestHelper helper) {
        String hall = "gt_spots_ws_hall";
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        BuildPlan plan = BuildPlan.fromJson(args(hallJson(hall)));
        plan = plan.withSpots(BuildSpots.checked(plan, plan.spots(), new ArrayList<>()));
        BuildRegistry.put(level.getServer(), plan);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).offset(0, 60, 0);
        Builder.submit(level, anchor, plan);
        WishExecutor.Result r = WishExecutor.runOne(level.getServer(), level, mock.position(), mock,
                "wishspot " + hall + ":throne run summon minecraft:armor_stand ~ ~ ~ {Tags:[\"gt_spot_stand\"]}");
        String said = String.join(" ", r.output());
        helper.assertTrue(r.success() && said.contains("the moment the building is finished"),
                Component.literal("wishspot must wait for the hall: " + said));
        BlockPos throne = anchor.offset(5, 2, 2);
        BuildPlan built = plan;
        helper.succeedWhen(() -> {
            Entity stand = null;
            for (Entity e : level.getEntities().getAll()) {
                if (e.entityTags().contains("gt_spot_stand")) {
                    stand = e;
                }
            }
            helper.assertTrue(stand != null, Component.literal("waiting for the armour stand on the throne"));
            helper.assertTrue(stand.blockPosition().equals(throne), Component.literal("it was summoned at "
                    + stand.blockPosition().toShortString() + ", not on the throne at " + throne.toShortString()));
            stand.discard();
            Builder.clearBuild(level, anchor, built);
            MockPlayerNetwork.remove(mock);
        });
    }

    // ===================== a FORKED building can be replayed (and so taught) ======================

    @GameTest(template = "empty_test", batch = "spots_fork", timeoutTicks = 200)
    public static void aForkedBuildingsIdReplays(GameTestHelper helper) {
        // Per-child ownership forks a curated building to `<id>~<short uuid>`. The first real grant edited
        // the starter castle, seated the boss on `…~380df991:throne`, and could not teach
        // `wishbuild lava_moat_castle~380df991`: brigadier's word() refuses `~`.
        String hall = "gt_spots_hall~1a2b3c4d";
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        BuildRegistry.put(level.getServer(), BuildPlan.fromJson(args(hallJson(hall))));
        WishExecutor.Result r = WishExecutor.runOne(level.getServer(), level, mock.position(), mock,
                "wishbuild " + hall);
        String said = String.join(" ", r.output());
        helper.assertTrue(r.success() && said.contains("Throne Hall"),
                Component.literal("`wishbuild " + hall + "` must replay a forked building: " + said));
        BuildPlan plan = BuildRegistry.get(hall);
        helper.succeedWhen(() -> {
            helper.assertFalse(Builder.isBuilding(hall), Component.literal("waiting for the hall to finish"));
            Long at = BuildRegistry.anchorOf(hall);
            if (at != null) {
                Builder.clearBuild(level, BlockPos.of(at), plan);   // take it back down
            }
            MockPlayerNetwork.remove(mock);
        });
    }

    // ===================== a FORKED creature's taught recipe replays =====================

    @GameTest(template = "empty_test", batch = "spots_fork_mob", timeoutTicks = 600)
    public static void aForkedCreaturesTaughtRecipeReplays(GameTestHelper helper) {
        // an earlier issue's fix let `wishbuild <fork>` parse and left `wishmob spawn <fork>` on brigadier's
        // word(), which refuses `~` — so a recipe holding his copy of a creature taught a button
        // that did nothing. The recipe here is the grant's shape with BOTH parts forked.
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        String who = mock.getUUID().toString();
        String hall = com.moddingfromamod.marshal.ArtifactOwner.forkKey("gt_spots_fhall", who);
        String boss = com.moddingfromamod.marshal.ArtifactOwner.forkKey("gt_spots_fboss", who);
        helper.assertTrue(hall.contains("~") && boss.contains("~"), Component.literal("not fork spellings: " + hall + " " + boss));
        plantSculpted(helper, boss);
        BuildPlan plan = BuildPlan.fromJson(args(hallJson(hall)));
        BuildRegistry.put(server, plan.withSpots(BuildSpots.checked(plan, plan.spots(), new ArrayList<>())));
        LearnedTemplates.load(server);
        Wish wish = bind(level, mock, "my boss on my throne", "gt-spots-fork-mob");
        List<String> cmds;
        try {
            String said = AgentContext.with("gt-spots-fork-mob", () -> LearnedTemplates.addAndSave(server,
                    args("{\"id\":\"gt_fork_mob_wish\",\"all\":[\"throne\",\"boss\"],\"commands\":["
                            + "\"wishbuild " + hall + "\",\"wishmob spawn " + boss + " at " + hall
                            + ":throne\"],\"say\":\"Here he is!\"}")));
            cmds = LearnedTemplates.commandsOf("gt_fork_mob_wish");
            helper.assertTrue(cmds.equals(List.of("wishbuild " + hall,
                            "wishmob spawn " + boss + " at " + hall + ":throne")),
                    Component.literal("the recipe must keep both forked ids as taught: " + cmds + " — " + said));
        } finally {
            LearnedTemplates.forget(server, "gt_fork_mob_wish");
            Marshal.global().finish(wish.id());
        }
        // REPLAY it, command by command, as the button does.
        for (String cmd : cmds) {
            WishExecutor.Result r = WishExecutor.runOne(server, level, mock.position(), mock, cmd);
            helper.assertTrue(r.success(), Component.literal("`" + cmd + "` must replay: " + String.join(" ", r.output())));
        }
        clearInside(level, hall);
        helper.succeedWhen(() -> {
            helper.assertFalse(Builder.isBuilding(hall), Component.literal("waiting for the hall to finish"));
            List<LivingEntity> on = carriers(level, boss);
            helper.assertTrue(on.size() == 1, Component.literal("waiting for his boss on the throne (" + on.size()
                    + " standing; tagged " + tagged(level, boss) + ")"));
            Long at = BuildRegistry.anchorOf(hall);
            {   // NOT try/finally: a poll that throws must not tidy away what the next poll looks for
                helper.assertTrue(at != null, Component.literal("the hall has no anchor"));
                BlockPos throne = BlockPos.of(at).offset(5, 2, 2);
                LivingEntity him = on.get(0);
                // THE SEAT: SpotSeat.remember restricts him to the spot it chose, before anything
                // can move him -- so this is the recipe's `at <fork>:throne` resolving, which is
                // what broke, whatever happens to his body afterwards.
                helper.assertTrue(him instanceof net.minecraft.world.entity.Mob m && m.hasHome()
                                && m.getHomePosition().distManhattan(throne) <= 1,
                        Component.literal("the replay did not seat him on the throne at " + throne.toShortString() + ": "
                                + whereabouts(level, him, throne)));
                // THE BODY: on the throne itself (the hall's inside was cleared above, so nothing
                // another test left behind can push him off it).
                helper.assertTrue(him.blockPosition().distManhattan(throne) <= 1,
                        Component.literal("he is at " + him.blockPosition().toShortString() + ", not on the throne at "
                                + throne.toShortString() + ": " + whereabouts(level, him, throne)));
            }
            {   // every assertion held: tidy up
                tidy(level, boss);
                Builder.clearBuild(level, BlockPos.of(at), BuildRegistry.get(hall));
                MockPlayerNetwork.remove(mock);
            }
        });
    }

    // ===================== the link: a template taught without `at` carries the seat ============

    @GameTest(template = "empty_test", batch = "spots_link", timeoutTicks = 200)
    public static void aTaughtRecipeCarriesWhereItsCreatureSat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        LearnedTemplates.load(server);
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        Wish wish = bind(level, mock, "a boss on a throne", "gt-spots-link");
        WishRun run = Marshal.global().run(wish.id());
        run.made(com.moddingfromamod.marshal.MadeKind.BUILD, "gt_link_castle");
        run.made(com.moddingfromamod.marshal.MadeKind.CREATURE, "gt_link_boss");
        run.seated("gt_link_boss", "gt_link_castle:throne");
        try {
            String said = AgentContext.with("gt-spots-link", () -> LearnedTemplates.addAndSave(server,
                    args("{\"id\":\"gt_link_wish\",\"all\":[\"throne\",\"boss\"],\"commands\":["
                            + "\"wishmob spawn gt_link_boss\",\"wishbuild gt_link_castle\"],"
                            + "\"say\":\"Here he is!\"}")));
            List<String> cmds = LearnedTemplates.commandsOf("gt_link_wish");
            helper.assertTrue(cmds.equals(List.of("wishbuild gt_link_castle",
                            "wishmob spawn gt_link_boss at gt_link_castle:throne")),
                    Component.literal("the recipe must carry the seat, castle first: " + cmds + " — " + said));
            helper.assertTrue(said.contains("seats them again"), Component.literal("add_template must say so: " + said));
            helper.succeed();
        } finally {
            LearnedTemplates.forget(server, "gt_link_wish");
            Marshal.global().finish(wish.id());
            MockPlayerNetwork.remove(mock);
        }
    }

    // ===================== helpers =====================

    public static Wish bind(ServerLevel level, ServerPlayer mock, String text, String worker) {
        UUID player = mock.getUUID();
        Wish wish = WishBook.get(level.getServer()).add(Wish.create(text, WishSource.TYPED,
                System.currentTimeMillis(), player, "Steve", "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), player, wish.text());
        Marshal.global().assign(worker, wish.id());
        return wish;
    }

    private static void plantSculpted(GameTestHelper helper, String id) {
        var parts = SpikeFixture.parts(SpikeFixture.SPIKOR);
        try {
            java.nio.file.Path spec = SpikeFixture.find(
                    SpikeFixture.CORPUS_DIR + "/" + SpikeFixture.SPIKOR + "/sculpt.json");
            helper.assertTrue(spec != null, Component.literal("the corpus sculpt is missing"));
            CreatureFiles.write(id, parts, java.nio.file.Files.readString(spec));
        } catch (Exception e) {
            helper.fail(Component.literal("could not write the sculpt: " + e));
        }
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, Species.fromJson(
                JsonParser.parseString("{\"id\":\"" + id + "\",\"name\":\"Boss\","
                        + "\"modelKey\":\"" + id + "\",\"health\":20,\"speed\":0.0,"
                        + "\"scale\":1.0,\"powers\":[\"friendly\"]}").getAsJsonObject()));
    }

    /**
     * <b>The hall's inside, emptied before it goes up.</b> A hollow box places its walls and leaves
     * its inside as it was, and the GameTest world is REUSED across batches and runs -- so another
     * test's leftovers inside the hall filled the boss's headroom, SafeSpot moved him off the
     * throne for having no room (correctly), and this test went red in 2 of 20 release runs
     * (2026-10-21, 2026-10-25; reproduced by putting one block over the throne). Called right after
     * the replay QUEUES the hall, before a block of it is placed: the seat and the door are left to
     * the build.
     */
    private static void clearInside(ServerLevel level, String hall) {
        Long at = BuildRegistry.anchorOf(hall);
        if (at == null) {
            return;
        }
        BlockPos a = BlockPos.of(at);
        for (BlockPos p : BlockPos.betweenClosed(a.offset(1, 1, 1), a.offset(9, 5, 9))) {
            level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        }
    }

    /** Everything that decides whether he fits on that throne, for a failure to say. */
    private static String whereabouts(ServerLevel level, LivingEntity him, BlockPos throne) {
        int filled = 0;
        for (BlockPos p : BlockPos.betweenClosed(throne.offset(-1, 0, -1), throne.offset(1, 3, 1))) {
            if (!level.getBlockState(p).isAir()) {
                filled++;
            }
        }
        var box = him.getBoundingBox();
        String home = him instanceof net.minecraft.world.entity.Mob m && m.hasHome()
                ? m.getHomePosition().toShortString() + " r" + (int) m.getHomeRadius() : "none";
        return String.format("carrier %s %.2fx%.2f, restricted to %s, glowing=%s, %d block(s) in the "
                        + "throne's 3x4x3 headroom", him.getType().toShortString(), box.getXsize(), box.getYsize(),
                home, him.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING), filled);
    }

    static List<LivingEntity> carriers(ServerLevel level, String id) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : level.getEntities().getAll()) {
            if (e instanceof LivingEntity l && e.isAlive()
                    && e.entityTags().contains(DisplayRig.RIG_TAG)
                    && e.entityTags().contains(WishMobTags.forSpecies(id))) {
                out.add(l);
            }
        }
        return out;
    }

    private static String tagged(ServerLevel level, String id) {
        List<String> out = new ArrayList<>();
        for (Entity e : level.getEntities().getAll()) {
            if (e.entityTags().contains(WishMobTags.forSpecies(id))) {
                out.add(e.getType().toShortString() + "@" + e.blockPosition().toShortString()
                        + (e.isAlive() ? "" : " dead") + (e.entityTags().contains(DisplayRig.RIG_TAG) ? " rig" : ""));
            }
        }
        return out.toString();
    }

    static void tidy(ServerLevel level, String id) {
        for (LivingEntity c : carriers(level, id)) {
            DisplayRig.reap(level, c.getUUID());
            c.discard();
        }
    }

    static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    public static void offThread(Runnable work) {
        Thread t = new Thread(work, "spots-gametest");
        t.setDaemon(true);
        t.start();
    }
}
