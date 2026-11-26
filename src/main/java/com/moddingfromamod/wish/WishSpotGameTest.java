package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.BuildStructureTool;
import com.moddingfromamod.agent.tools.ConjureAtTool;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.PlayerKind;
import com.moddingfromamod.marshal.Spot;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.reveal.Fx;
import com.moddingfromamod.wish.reveal.FxVeil;
import com.moddingfromamod.wish.reveal.Reveals;
import com.moddingfromamod.wish.reveal.WishOrbs;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Where a wish lands, how its pillar moves, and how it ends</b> — the parts a
 * headless server can check. How it LOOKS is Gate C's ({@code RevealFilmClientTest}).
 *
 * <ul>
 *   <li>{@code conjure_at(forward)} after he has walked away does not move the spot (it used to
 *   re-aim from the live player, 20–25 s into every wish);</li>
 *   <li>a corrected spot is reached by ONE glide, the pillar never torn down on the way;</li>
 *   <li>at the end, "it's here!" comes after the reveal, and the pillar does not come back;</li>
 *   <li>a camera holding its lease does not take the child's pillar down;</li>
 *   <li>a building aimed at a treetop is set down on the ground below, and its pillar
 *   glides there once, never torn down;</li>
 *   <li>an offset never throws the aim away: {@code offsetForward: 0} still builds where he
 *   aimed and says so, and {@code offsetY: 10} floats it ten above that spot.</li>
 * </ul>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WishSpotGameTest {

    private static final String BATCH = "wishspot";

    private static UUID wishFor(String test) {
        return UUID.nameUUIDFromBytes(("wishspot-" + test).getBytes());
    }

    /** A run the marshal knows, assigned to a worker, with the WishBook entry the tools look up. */
    private static String startWishing(ServerPlayer child, UUID wishId, String text) {
        Marshal m = Marshal.global();
        m.submit(wishId, child.getUUID(), text);
        String worker = "gt-worker-" + wishId;
        m.assign(worker, wishId);
        Wish wish = new Wish(wishId, text, WishSource.TYPED, WishStatus.RUNNING, System.currentTimeMillis(),
                child.getUUID(), child.getGameProfile().name(), "minecraft:overworld", 0, 64, 0, "", "", 0L);
        WishBook.get(((ServerLevel) child.level()).getServer()).add(wish);
        WishProgress.started(child, wish);
        return worker;
    }

    private static void cleanUp(UUID wishId, ServerPlayer... players) {
        try {
            WishProgress.clearWishBar(wishId);
            Marshal.global().finish(wishId);
            WishOrbs.drop(wishId);
        } catch (RuntimeException ignored) {
            // best effort
        }
        for (ServerPlayer p : players) {
            MockPlayerNetwork.remove(p);
        }
    }

    private static void pollUntil(GameTestHelper helper, long deadlineMs, int attemptsLeft,
                                  java.util.function.BooleanSupplier ready, Runnable check) {
        if (ready.getAsBoolean() || attemptsLeft <= 0 || System.currentTimeMillis() > deadlineMs) {
            check.run();
            return;
        }
        helper.runAfterDelay(2, () -> pollUntil(helper, deadlineMs, attemptsLeft - 1, ready, check));
    }

    /**
     * <b>: the spot is decided when he wishes.</b> He wishes, walks 30 blocks off and turns
     * round; the agent then calls {@code conjure_at(forward: 12)}, as all seven live calls did. The
     * spot must not move. Then {@code up: 20} lifts it — from where he STOOD when he wished.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 3000)
    public static void conjureForwardAfterHeMovedDoesNotMoveTheSpot(GameTestHelper helper) {
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "spot-child");
        UUID wishId = wishFor("conjure");
        String worker = startWishing(child, wishId, "a little house");
        BlockPos stand = helper.absolutePos(new BlockPos(1, 2, 1));
        child.snapTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0f, 10f);
        WishRun run = Marshal.global().run(wishId);
        BlockPos locked = WishAimer.lockAtWishTime(child, run, "gt");
        Spot before = run.anchor();
        WishRun.Aim from = run.aimedFrom();
        helper.assertTrue(before != null && from != null, Component.literal("the wish-time lock recorded nothing"));
        // He walks away and turns round.
        child.snapTo(stand.getX() + 30.5, stand.getY() + 5, stand.getZ() - 20.5, 180f, 0f);

        AtomicReference<ToolResult> forward = new AtomicReference<>();
        AtomicReference<ToolResult> up = new AtomicReference<>();
        AtomicReference<Spot> afterForward = new AtomicReference<>();
        Thread agent = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                JsonObject a = new JsonObject();
                a.addProperty("message", "watch!");
                a.addProperty("forward", 12);
                forward.set(new ConjureAtTool().call(a));
                afterForward.set(run.anchor());
                JsonObject b = new JsonObject();
                b.addProperty("up", 20);
                up.set(new ConjureAtTool().call(b));
            } finally {
                AgentContext.unbind();
            }
        }, "gt-wishspot-conjure");
        agent.setDaemon(true);
        agent.start();
        pollUntil(helper, System.currentTimeMillis() + 20_000L, 1200, () -> up.get() != null, () -> {
            try {
                helper.assertTrue(forward.get() != null && !forward.get().isError(),
                        Component.literal("conjure_at(forward) failed: " + (forward.get() == null ? "never ran" : forward.get().text())));
                helper.assertTrue(before.equals(afterForward.get()),
                        Component.literal("conjure_at(forward: 12) MOVED the spot after he walked off: " + before + " → "
                                + afterForward.get() + " (it must stay where he looked when he wished, "
                                + locked.toShortString() + ")"));
                Spot lifted = run.anchor();
                helper.assertTrue(up.get() != null && !up.get().isError(), Component.literal("conjure_at(up) failed"));
                helper.assertTrue(lifted.x() == before.x() && lifted.z() == before.z(),
                        Component.literal("`up` moved the spot sideways: " + before + " → " + lifted));
                helper.assertTrue(lifted.y() == (int) Math.floor(from.y()) + 20,
                        Component.literal("`up: 20` should be 20 above where he STOOD when he wished (" + from.y()
                                + "), not where he is now; got y=" + lifted.y()));
                helper.assertTrue(run.raised(), Component.literal("an `up` lift was not recorded, so a slide could undo it"));
                helper.succeed();
            } finally {
                cleanUp(wishId, child);
            }
        });
    }

    /**
     * <b>: a correction GLIDES.</b> The spot moves once; the pillar's displays must never all
     * be gone on the way (it used to be taken down and rebuilt at the new spot), and it must arrive.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aCorrectedSpotIsReachedByOneGlide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID wishId = wishFor("glide");
        WishRun run = Marshal.global().submit(wishId, UUID.randomUUID(), "a glide");
        try {
            BlockPos a = helper.absolutePos(new BlockPos(1, 2, 1));
            BlockPos b = a.offset(14, 0, 6);
            String dim = level.dimension().location().toString();
            run.defaultAnchor(new Spot(dim, a.getX(), a.getY(), a.getZ()));
            long now = 1000;
            for (int i = 0; i < 4; i++) {
                WishOrbs.tick(level.getServer(), List.of(run), now += 2);
            }
            int shownAtA = Fx.count(level, Fx.wishTag(wishId));
            helper.assertTrue(shownAtA > 0, Component.literal("no pillar at the first spot"));
            run.lockAnchor(new Spot(dim, b.getX(), b.getY(), b.getZ()));
            for (int i = 0; i < 40; i++) {
                WishOrbs.tick(level.getServer(), List.of(run), now += 2);
                helper.assertTrue(WishOrbs.shown(wishId),
                        Component.literal("the pillar was taken down mid-move at step " + i + " — a correction must glide"));
            }
            helper.assertTrue(WishOrbs.glides(wishId) == 1, Component.literal("expected exactly one glide, saw " + WishOrbs.glides(wishId)));
            double[] foot = WishOrbs.footOf(wishId);
            helper.assertTrue(foot != null && Math.abs(foot[0] - (b.getX() + 0.5)) < 0.01
                            && Math.abs(foot[2] - (b.getZ() + 0.5)) < 0.01,
                    Component.literal("the pillar did not arrive at the corrected spot: " + java.util.Arrays.toString(foot)));
            helper.succeed();
        } finally {
            Marshal.global().finish(wishId);
            WishOrbs.drop(wishId);
        }
    }

    /**
     * <b>: no false finish.</b> The announcement waits for the reveal; the pillar is gone when
     * it is said, and it does not come back while the wish is still live.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void itIsHereIsSaidAfterTheRevealAndThePillarStaysDown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID wishId = wishFor("finale");
        WishRun run = Marshal.global().submit(wishId, UUID.randomUUID(), "a finale");
        try {
            BlockPos a = helper.absolutePos(new BlockPos(2, 2, 2));
            run.defaultAnchor(new Spot(level.dimension().location().toString(), a.getX(), a.getY(), a.getZ()));
            long now = 5000;
            WishOrbs.tick(level.getServer(), List.of(run), now += 2);
            helper.assertTrue(WishOrbs.shown(wishId), Component.literal("no pillar before the end"));
            int burstsBefore = Reveals.played(Reveals.Look.BURST);
            int[] saidAtTick = {-1};
            int[] revealsWhenSaid = {-1};
            int[] displaysWhenSaid = {-1};
            Reveals.finale(run, () -> {
                saidAtTick[0] = 1;
                revealsWhenSaid[0] = Reveals.played(Reveals.Look.BURST) - burstsBefore;
                displaysWhenSaid[0] = Fx.count(level, Fx.wishTag(wishId));
            });
            helper.assertTrue(saidAtTick[0] == -1,
                    Component.literal("\"it's here!\" was said BEFORE the reveal played (the false finish)"));
            int t = 0;
            for (; t < 200 && saidAtTick[0] == -1; t++) {
                Reveals.tick(level.getServer());
                WishOrbs.tick(level.getServer(), List.of(run), now += 1);
            }
            helper.assertTrue(saidAtTick[0] == 1, Component.literal("the announcement never came"));
            helper.assertTrue(revealsWhenSaid[0] >= 1, Component.literal("it was said with no reveal having played"));
            helper.assertTrue(displaysWhenSaid[0] == 0,
                    Component.literal("it was said while the reveal was still on screen (" + displaysWhenSaid[0] + " displays)"));
            for (int i = 0; i < 20; i++) {
                WishOrbs.tick(level.getServer(), List.of(run), now += 2);
                helper.assertTrue(!WishOrbs.shown(wishId),
                        Component.literal("the pillar came BACK after the reveal while the wish was still live"));
            }
            helper.succeed();
        } finally {
            Marshal.global().finish(wishId);
            WishOrbs.drop(wishId);
        }
    }

    /**
     * <b>: a camera shooting does not take the child's pillar down.</b> The camera holds its
     * lease (what made {@code anyShooting()} true for every pillar in the world); the pillar stays,
     * and the camera's connection is told to forget it instead.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aCameraShootingLeavesTheChildsPillarUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer camera = MockPlayerNetwork.inLevel(helper, "spot-camera");
        PlayerKind.registerServiceClient(camera.getUUID());
        UUID wishId = wishFor("camera");
        WishRun run = Marshal.global().submit(wishId, UUID.randomUUID(), "a camera");
        try {
            BlockPos a = helper.absolutePos(new BlockPos(2, 2, 2));
            camera.snapTo(a.getX() + 6.5, a.getY(), a.getZ() + 0.5, 90f, 0f);
            run.defaultAnchor(new Spot(level.dimension().location().toString(), a.getX(), a.getY(), a.getZ()));
            long sentBefore = FxVeil.sent();
            helper.assertTrue(WishCapture.claimCameraFor(camera.getUUID(), wishId), Component.literal("could not lease the camera"));
            helper.assertTrue(WishCapture.anyShooting(), Component.literal("the lease did not register as a shot"));
            long now = 9000;
            for (int i = 0; i < 6; i++) {
                WishOrbs.tick(level.getServer(), List.of(run), now += 2);
                FxVeil.sweep(level.getServer());
                helper.assertTrue(WishOrbs.shown(wishId),
                        Component.literal("the camera's shot took the CHILD's pillar down (it must only be hidden from the camera)"));
            }
            helper.assertTrue(FxVeil.sent() > sentBefore,
                    Component.literal("nothing was withheld from the camera's connection, so the pillar would be in its photograph"));
            helper.succeed();
        } finally {
            WishCapture.releaseCameraLease(camera.getUUID(), wishId);
            PlayerKind.forgetServiceClient(camera.getUUID());
            Marshal.global().finish(wishId);
            WishOrbs.drop(wishId);
            MockPlayerNetwork.remove(camera);
        }
    }

    /**
     * <b>: a building aimed at a treetop is SET DOWN on the ground below, and the wish's spot
     * GLIDES there</b> — through the same seam {@code conjure_at} and
     * {@code SafePlacement} use, one glide, the pillar never torn down on the way, and he is told
     * where it went. The live orb ticker draws the pillar (the run is live), so every poll checks it
     * is still up.
     */
    // ITS OWN BATCH: the other tests here tick WishOrbs by hand with only their own run, and a tick
    // that does not list a wish takes its pillar down — so beside them this pillar was swept away
    // and redrawn, which reads as exactly the teardown this test exists to catch.
    /** The space above the 9×9×9 test structure that the treetop case uses, emptied: x and z -1..9, y +9..+20. */
    static void clearAbove(ServerLevel level, BlockPos o) {
        for (int x = -1; x <= 9; x++) {
            for (int z = -1; z <= 9; z++) {
                for (int y = 9; y <= 20; y++) {
                    level.setBlock(o.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    @GameTest(template = "empty_test", batch = BATCH + "_setdown", timeoutTicks = 4000)
    public static void aBuildAimedAtATreetopIsSetDownAndItsPillarGlidesOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "spot-tree");
        UUID wishId = wishFor("treetop");
        String worker = startWishing(child, wishId, "a little house up in that tree");
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        // ITS OWN SPACE (#129). The test structure is 9×9×9, so the framework clears y+0..8 and nothing above. This
        // case reads and builds above it (the canopy's top at y+9, the house at y+10..14), where an earlier batch's
        // build can still stand: once one did, the footing read it as ground under the house and filled instead of
        // setting it down. Everything this case touches, from y+9 up, is cleared first, so it reads only its own world.
        clearAbove(level, o);
        MockPlayerNetwork.afterTest(helper, () -> clearAbove(level, o));
        // Ground at y+1, and a tree on it: a trunk to y+9 under a 7×7 canopy at y+8..9.
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                level.setBlockAndUpdate(o.offset(x, 1, z), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        for (int x = 1; x <= 7; x++) {
            for (int z = 1; z <= 7; z++) {
                for (int y = 8; y <= 9; y++) {
                    level.setBlockAndUpdate(o.offset(x, y, z), net.minecraft.world.level.block.Blocks.OAK_LEAVES
                            .defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
                }
            }
        }
        for (int y = 2; y <= 9; y++) {
            level.setBlockAndUpdate(o.offset(4, y, 4), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
        }
        child.snapTo(o.getX() + 8.5, o.getY() + 2, o.getZ() + 8.5, 135f, 30f);
        WishRun run = Marshal.global().run(wishId);
        BlockPos aimed = o.offset(2, 10, 2);       // the canopy's top, where the watch pointed
        BlockPos ground = o.offset(2, 2, 2);       // on the grass below it
        run.defaultAnchor(new Spot(level.dimension().location().toString(), aimed.getX(), aimed.getY(), aimed.getZ()));
        String id = "gt_treetop_house";

        AtomicReference<ToolResult> built = new AtomicReference<>();
        boolean[] down = {false};
        boolean[] arrived = {false};
        boolean[] early = {false};
        int[] glidesOnArrival = {-1};
        Thread agent = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                JsonObject a = com.google.gson.JsonParser.parseString("{\"id\":\"" + id + "\",\"name\":\"Tree House\","
                        + "\"note\":\"gt\",\"as_new\":true,\"shapes\":["
                        + "{\"kind\":\"box\",\"dx\":0,\"dy\":0,\"dz\":0,\"width\":5,\"height\":1,\"depth\":5,"
                        + "\"block\":\"minecraft:oak_planks\",\"name\":\"floor\"},"
                        + "{\"kind\":\"box\",\"dx\":0,\"dy\":1,\"dz\":0,\"width\":5,\"height\":4,\"depth\":5,"
                        + "\"block\":\"minecraft:spruce_planks\",\"hollow\":true,\"name\":\"walls\"}]}").getAsJsonObject();
                built.set(new BuildStructureTool().call(a));
            } finally {
                AgentContext.unbind();
            }
        }, "gt-wishspot-treetop");
        // Let the pillar stand at the treetop first, so what follows is a CORRECTION of a pillar
        // he has already seen — the glide that was asked for, not a pillar first drawn below.
        // The live orb ticker draws it; wait for it rather than guessing how many ticks that takes.
        pollUntil(helper, System.currentTimeMillis() + 10_000L, 200, () -> WishOrbs.shown(wishId), () -> {
            Spot first = run.anchor();
            boolean atTreetop = first != null && first.y() == aimed.getY();
            if (!atTreetop || !WishOrbs.shown(wishId) || WishOrbs.glides(wishId) != 0) {
                // Never leave the run live: an abandoned run is "the single live run" every other
                // test's tools would then fall back to.
                cleanUp(wishId, child);
                helper.assertTrue(false, Component.literal(!atTreetop ? "the wish's spot is not the treetop it was given: " + first
                        : WishOrbs.shown(wishId) ? "the pillar moved before anything was built"
                        : "no pillar at the treetop before the build"));
            }
            agent.setDaemon(true);
            agent.start();
            // pollUntil asks `ready` on EVERY poll, so it doubles as the watch on the pillar.
            pollUntil(helper, System.currentTimeMillis() + 30_000L, 1400, () -> {
                double[] foot = WishOrbs.footOf(wishId);
                boolean there = foot != null && Math.abs(foot[1] - ground.getY()) < 0.6;
                if (built.get() != null && !arrived[0]) {
                    if (there) {
                        arrived[0] = true;
                        glidesOnArrival[0] = WishOrbs.glides(wishId);
                    } else if (!WishOrbs.shown(wishId)) {
                        down[0] = true;     // gone before it got there: torn down, not glided
                    }
                    if (!there && level.getBlockState(ground).is(net.minecraft.world.level.block.Blocks.OAK_PLANKS)) {
                        early[0] = true;    // the building rose before its pillar arrived
                    }
                }
                return arrived[0] || down[0];
            }, () -> {
                try {
                    ToolResult r = built.get();
                    helper.assertTrue(r != null && !r.isError(),
                            Component.literal("build_structure failed: " + (r == null ? "never returned" : r.text())));
                    helper.assertTrue(r.text().contains("set it down on the ground below"),
                            Component.literal("the agent was not told it was set down: " + r.text()));
                    Spot now = run.anchor();
                    helper.assertTrue(now.x() == ground.getX() && now.y() == ground.getY() && now.z() == ground.getZ(),
                            Component.literal("the wish's spot did not move to the ground below the tree: " + now + " (want "
                                    + ground.toShortString() + ")"));
                    Long at = BuildRegistry.anchorOf(id);
                    helper.assertTrue(at != null && at == ground.asLong(),
                            Component.literal("the building is remembered at " + (at == null ? "nowhere" : BlockPos.of(at).toShortString())
                                    + ", not where it was set down"));
                    helper.assertTrue(!down[0], Component.literal("the pillar was taken down on the way — a set-down must GLIDE"));
                    helper.assertTrue(arrived[0], Component.literal("the pillar never arrived at the ground: "
                            + java.util.Arrays.toString(WishOrbs.footOf(wishId))));
                    helper.assertTrue(!early[0], Component.literal("the building went up before its pillar had glided there"));
                    helper.assertTrue(glidesOnArrival[0] == 1, Component.literal("expected exactly one glide, saw " + glidesOnArrival[0]));
                    helper.assertTrue(Builder.isBuilding(id) || level.getBlockState(ground).is(
                                    net.minecraft.world.level.block.Blocks.OAK_PLANKS),
                            Component.literal("the building is neither going up nor up"));
                    helper.assertTrue(MockPlayerNetwork.chat(child).stream().anyMatch(l -> l.contains("treetops")),
                            Component.literal("he was not told where it went: " + MockPlayerNetwork.chat(child)));
                    Builder.drainNow(level);
                    helper.assertTrue(level.getBlockState(ground).is(net.minecraft.world.level.block.Blocks.OAK_PLANKS),
                            Component.literal("no floor on the ground at " + ground.toShortString()));
                    helper.assertTrue(!level.getBlockState(aimed).is(net.minecraft.world.level.block.Blocks.OAK_PLANKS),
                            Component.literal("the house was built in the canopy as well as below it"));
                    helper.succeed();
                } finally {
                    var plan = BuildRegistry.get(id);
                    if (plan != null) {
                        Builder.clearBuild(level, ground, plan);
                    }
                    cleanUp(wishId, child);
                }
            });
        });
    }

    /**
     * <b>: an offset never throws the aim away.</b> On a held-out grant the agent passed
     * {@code offsetForward: 0}, {@code chooseAnchor} let that win over the locked spot, and the
     * building went up ON the child, 11 blocks from where he aimed. Here he aims at a spot, walks
     * off, and the agent sends {@code offsetForward: 0}: it must be built where he aimed, the reply
     * must say the offset was ignored, and nothing must stand where he is. Then {@code offsetY: 10}
     * floats a second building ten above the same spot, with nothing filled under it (an earlier issue's
     * "floated on purpose").
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 1200)
    public static void anOffsetBuildsAtTheAimedSpotAndOffsetYFloatsFromIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "spot-offset");
        UUID wishId = wishFor("offsets");
        String worker = startWishing(child, wishId, "a little house over there");
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        for (int x = 0; x <= 9; x++) {
            for (int z = 0; z <= 9; z++) {
                level.setBlockAndUpdate(o.offset(x, 1, z), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        BlockPos aimed = o.offset(5, 2, 5);           // on the grass, where the watch pointed
        BlockPos stand = o.offset(1, 2, 1);           // where he stands when the agent builds
        child.snapTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0f, 10f);
        WishRun run = Marshal.global().run(wishId);
        run.defaultAnchor(new Spot(level.dimension().location().toString(), aimed.getX(), aimed.getY(), aimed.getZ()));
        // Unique per run: the registry outlives a run, and a known id is an EDIT, rebuilt where it stood.
        String ground = "gt_offset_ground_" + level.getGameTime();
        String sky = "gt_offset_sky_" + level.getGameTime();
        String hut = ",\"note\":\"gt\",\"as_new\":true,\"shapes\":["
                + "{\"kind\":\"box\",\"dx\":0,\"dy\":0,\"dz\":0,\"width\":3,\"height\":1,\"depth\":3,"
                + "\"block\":\"minecraft:oak_planks\",\"name\":\"floor\"}]}";

        AtomicReference<ToolResult> first = new AtomicReference<>();
        AtomicReference<ToolResult> second = new AtomicReference<>();
        Thread agent = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                first.set(new BuildStructureTool().call(com.google.gson.JsonParser.parseString(
                        "{\"id\":\"" + ground + "\",\"name\":\"Hut\",\"offsetForward\":0" + hut).getAsJsonObject()));
                second.set(new BuildStructureTool().call(com.google.gson.JsonParser.parseString(
                        "{\"id\":\"" + sky + "\",\"name\":\"Sky Hut\",\"offsetY\":10" + hut).getAsJsonObject()));
            } finally {
                AgentContext.unbind();
            }
        }, "gt-wishspot-offsets");
        agent.setDaemon(true);
        agent.start();
        pollUntil(helper, System.currentTimeMillis() + 30_000L, 600, () -> second.get() != null, () -> {
            try {
                ToolResult a = first.get();
                ToolResult b = second.get();
                helper.assertTrue(a != null && !a.isError(), Component.literal("offsetForward build failed: " + (a == null ? "never returned" : a.text())));
                helper.assertTrue(b != null && !b.isError(), Component.literal("offsetY build failed: " + (b == null ? "never returned" : b.text())));
                Long at = BuildRegistry.anchorOf(ground);
                helper.assertTrue(at != null && at == aimed.asLong(), Component.literal("offsetForward 0 built it at "
                        + (at == null ? "nowhere" : BlockPos.of(at).toShortString()) + ", not at the aimed spot "
                        + aimed.toShortString()));
                helper.assertTrue(a.text().contains("offsetForward only applies when there is no aimed spot"),
                        Component.literal("the agent was not told its offsetForward was ignored: " + a.text()));
                Long up = BuildRegistry.anchorOf(sky);
                helper.assertTrue(up != null && up == aimed.above(10).asLong(), Component.literal("offsetY 10 built it at "
                        + (up == null ? "nowhere" : BlockPos.of(up).toShortString()) + ", not ten above the aimed spot"));
                helper.assertTrue(!b.text().contains("offsetForward only applies"),
                        Component.literal("an offsetY-only call was told about an offsetForward it never sent: " + b.text()));
                Builder.drainNow(level);
                helper.assertTrue(level.getBlockState(aimed).is(net.minecraft.world.level.block.Blocks.OAK_PLANKS),
                        Component.literal("no floor at the aimed spot " + aimed.toShortString()));
                helper.assertTrue(level.getBlockState(stand).isAir(), Component.literal("something was built where he stands"));
                helper.assertTrue(level.getBlockState(aimed.above(10)).is(net.minecraft.world.level.block.Blocks.OAK_PLANKS),
                        Component.literal("no floor ten above the aimed spot"));
                helper.assertTrue(level.getBlockState(aimed.above(9)).isAir(),
                        Component.literal("something was filled under a build floated on purpose with offsetY"));
                helper.succeed();
            } finally {
                for (String id : List.of(ground, sky)) {
                    var plan = BuildRegistry.get(id);
                    Long at = BuildRegistry.anchorOf(id);
                    if (plan != null && at != null) {
                        Builder.clearBuild(level, BlockPos.of(at), plan);
                    }
                }
                cleanUp(wishId, child);
            }
        });
    }
}
