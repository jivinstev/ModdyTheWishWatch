package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.Spot;
import com.moddingfromamod.marshal.WishRun;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.testing.GateShards;

/**
 * Gate B for stage 2 — two wishes in flight, and neither treads on the other.
 *
 * <p>The unit tests ({@code PerWishContextTest}) prove the six former globals are per-run. What only
 * a running server settles is that the <b>blocks land in the right place</b>: the anchor is what
 * turned a per-player value into the "mid-air McTrain" bug once already, and concurrency makes the
 * same class of mistake reachable a second way — not by recomputing from a moving player, but by
 * reading the <i>other wish's</i> spot.
 *
 * <p>Each test binds its own worker ids and clears its own marshal state afterwards, because
 * GameTests in the same batch run interleaved on the one server thread.
 */
@GameTestHolder(GateShards.CI)
public class ConcurrentWishGameTest {

    /**
     * <b>A child of its own, per test.</b>
     *
     * <p>GameTests in the same batch run interleaved on the one server thread, so a UUID shared
     * across a file means one
     * test's in-flight wish is visible to another's {@code liveFor} — and the second fails against
     * its neighbour rather than against the code. That is exactly how three tests here went red at
     * once when a fourth was added: one threw, left two runs WORKING, and the slot counter and the
     * beam check both then measured somebody else's state.
     */
    private static UUID child(String testName) {
        return UUID.nameUUIDFromBytes(("gt-" + testName).getBytes());
    }

    private static BuildPlan plan(String id) {
        return BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"%s","name":"Concurrent test","shapes":[
              {"kind":"box","width":3,"height":3,"depth":3,"block":"minecraft:stone"}]}
            """.formatted(id)).getAsJsonObject());
    }

    private static WishRun run(UUID me, String worker, String text) {
        UUID id = UUID.randomUUID();
        Marshal.global().submit(id, me, text);
        return Marshal.global().assign(worker, id);
    }

    /**
     * <b>The headline.</b> Two wishes, two anchors, and each build lands at its own.
     *
     * <p>Both are asked for their spot through the SAME call the builder uses, with only the calling
     * worker different — which is the whole content of the change. A shared per-player anchor gives
     * both the same answer, and the second building appears on top of the first.
     */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void twoWishesAnchorIndependentlyAndBuildAtTheirOwnSpot(GameTestHelper helper) {
        UUID me = child("anchors");
        WishRun dragon = run(me, "gt-conc-1", "an ice dragon");
        WishRun castle = run(me, "gt-conc-2", "a floating castle");
        BlockPos here = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos dragonSpot = here.above(120);
        BlockPos castleSpot = here.above(160);
        String dim = helper.getLevel().dimension().location().toString();
        dragon.lockAnchor(new Spot(dim, dragonSpot.getX(), dragonSpot.getY(), dragonSpot.getZ()));
        castle.lockAnchor(new Spot(dim, castleSpot.getX(), castleSpot.getY(), castleSpot.getZ()));

        AgentContext.bind("gt-conc-1");
        BlockPos askedByDragon = WishProgress.currentWishAnchor();
        AgentContext.bind("gt-conc-2");
        BlockPos askedByCastle = WishProgress.currentWishAnchor();
        AgentContext.unbind();

        helper.assertTrue(dragonSpot.equals(askedByDragon),
                Component.literal("the dragon's worker got " + askedByDragon + ", wanted " + dragonSpot));
        helper.assertTrue(castleSpot.equals(askedByCastle),
                Component.literal("the castle's worker got " + askedByCastle + ", wanted " + castleSpot
                        + " — a shared anchor gives both wishes the same answer"));

        AgentContext.bind("gt-conc-1");
        try {
            Builder.submit(helper.getLevel(), askedByDragon, plan("gt_conc_dragon"));
        } finally {
            AgentContext.unbind();
        }
        AgentContext.bind("gt-conc-2");
        try {
            Builder.submit(helper.getLevel(), askedByCastle, plan("gt_conc_castle"));
        } finally {
            AgentContext.unbind();
        }

        helper.succeedWhen(() -> {
            helper.assertFalse(Builder.isBusy(), Component.literal("waiting for both builds"));
            helper.assertTrue(helper.getLevel().getBlockState(dragonSpot).is(Blocks.STONE),
                    Component.literal("the dragon's build is missing from its own spot"));
            helper.assertTrue(helper.getLevel().getBlockState(castleSpot).is(Blocks.STONE),
                    Component.literal("the castle's build is missing from its own spot"));
            Marshal.global().finish(dragon.wishId());
            Marshal.global().finish(castle.wishId());
        });
    }

    /** Two builds share the block budget and both finish — neither starves the other. */
    @GameTest(template = "empty_test", timeoutTicks = 900)
    public static void twoBuildsShareTheBudgetAndBothComplete(GameTestHelper helper) {
        UUID me = child("budget");
        WishRun a = run(me, "gt-budget-1", "one");
        WishRun b = run(me, "gt-budget-2", "two");
        BlockPos here = helper.absolutePos(new BlockPos(1, 1, 1));
        // High enough to clear the neighbouring plots, LOW enough to stay inside the world. A plot
        // sits at about y=-60, so above(400) lands at y=340 — past the build ceiling, where
        // setBlock quietly does nothing and the test reads as "the build never happened".
        BlockPos spotA = here.above(200);
        BlockPos spotB = here.above(240);

        AgentContext.bind("gt-budget-1");
        try {
            Builder.submit(helper.getLevel(), spotA, plan("gt_budget_a"));
        } finally {
            AgentContext.unbind();
        }
        AgentContext.bind("gt-budget-2");
        try {
            Builder.submit(helper.getLevel(), spotB, plan("gt_budget_b"));
        } finally {
            AgentContext.unbind();
        }

        helper.succeedWhen(() -> {
            helper.assertFalse(Builder.isBusy(), Component.literal("waiting for both builds"));
            helper.assertTrue(helper.getLevel().getBlockState(spotA).is(Blocks.STONE), Component.literal("A is missing"));
            helper.assertTrue(helper.getLevel().getBlockState(spotB).is(Blocks.STONE), Component.literal("B is missing"));
            Marshal.global().finish(a.wishId());
            Marshal.global().finish(b.wishId());
        });
    }

    /**
     * The camera is a lease, because he has one body. A second wish must be refused rather than
     * allowed to teleport him mid-shot and then release him while the first is still shooting.
     */
    @GameTest(template = "empty_test")
    public static void onlyOneWishMayBorrowHisEyesAtATime(GameTestHelper helper) {
        UUID me = child("camera");
        WishRun first = run(me, "gt-cam-1", "photograph the dragon");
        WishRun second = run(me, "gt-cam-2", "photograph the castle");
        helper.assertTrue(WishCapture.cameraHeldBy(me) == null, Component.literal("nobody has it yet"));

        // Driven through the ownership seam rather than a mock player: makeMockServerPlayerInLevel
        // puts a player in the player list, which fires our sync payloads at an embedded channel —
        // a known way to fail a GameTest for reasons unrelated to what it is testing.
        AgentContext.bind("gt-cam-1");
        boolean firstGotIt = WishCapture.claimCameraFor(me, first.wishId());
        AgentContext.bind("gt-cam-2");
        boolean secondGotIt = WishCapture.claimCameraFor(me, second.wishId());
        AgentContext.unbind();

        helper.assertTrue(firstGotIt, Component.literal("the first wish must get the camera"));
        helper.assertFalse(secondGotIt,
                Component.literal("the second must be told to shoot later, not allowed to steal it"));
        helper.assertTrue(first.wishId().equals(WishCapture.cameraHeldBy(me)),
                Component.literal("and the first wish still holds it"));

        // Not clear(): that releases every player's camera, including a neighbouring
        // test's, whose whole assertion is that a second claim gets REFUSED.
        WishCapture.forgetForTest(me);
        Marshal.global().finish(first.wishId());
        Marshal.global().finish(second.wishId());
        helper.succeed();
    }

    /**
     * The handover record reaches <b>disk</b>, and comes back off it.
     *
     * <p>This is the assertion the whole record depends on. Its value is not latency — introspection
     * is 11.1s per wish, 5.8% of in-wish latency, and only a third of that is about a prior artifact
     * at all. Its value is that the context stops living in a session, so a worker dying, a restart,
     * or the child coming back tomorrow does not lose what the last agent decided. A record that
     * only ever existed in memory would be a session's memory wearing a different hat, and would
     * pass every unit test in the suite.
     */
    @GameTest(template = "empty_test")
    public static void aHandoverRecordSurvivesBeingForgotten(GameTestHelper helper) {
        String id = "gt_handover_thing";
        HandoverStore.write(new com.moddingfromamod.marshal.HandoverRecord(
                id, "creature", "a test creature", java.util.List.of("the test one"),
                "built plain because this is a fixture",
                "tried making it enormous — it did not fit the plot",
                "0,0,0 in minecraft:overworld", 1, "it is only a fixture", 1L));

        // Exactly what a restart does to it: the in-memory mirror is ignored and only the file is
        // read. Deliberately NOT clear() — that empties the mirror for every test sharing this
        // batch, which is invisible at a handful and fatal at fifty (docs/GATE_PERFORMANCE.md).
        var back = HandoverStore.savedOnDisk(id);

        helper.assertTrue(back != null,
                Component.literal("the record did not come back off disk — with no world path it is a session in "
                        + "disguise, and every unit test would still pass"));
        helper.assertTrue(back.triedAndRejected().contains("did not fit the plot"),
                Component.literal("the dead end is the part a second turn of the crank most needs, and it must survive"));
        helper.assertFalse(back.isTrivial(), Component.literal("and it must still be worth reading"));
        helper.succeed();
    }

    /**
     * <b>A tool must learn its own wish ACROSS the hop to the server thread.</b>
     *
     * <p>AgentContext is a ThreadLocal bound on the MCP dispatch thread; every tool does its real
     * work inside {@code AgentBridge.onServer}, which hops to the server thread. The binding did not
     * cross that hop, so {@code currentRun()} fell through to its "single live run, else null"
     * fallback — always right at one slot, and NULL at three, which is exactly when it is asked.
     *
     * <p>The tools that ask then did nothing at all, silently: {@code plan_wish} set no plan, so the
     * wish showed no progress bar. That is how Steve found it — Zippy was being built with nothing on
     * screen while Dr Robotnik had a bar.
     *
     * <p><b>It has to make the crossing to mean anything.</b> The first version of this test bound a
     * worker and called {@code currentRun} on the spot, which exercises AgentContext — a class that
     * was never broken — and passed happily against the bug reintroduced. So the lookup runs on
     * another thread, through the real {@code AgentBridge.onServer}, which is the only arrangement
     * that can tell the two apart.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void aToolLearnsItsOwnWishAcrossTheHopToTheServerThread(GameTestHelper helper) {
        UUID kid = child("threadHop");
        Marshal marshal = Marshal.global();
        WishRun first = marshal.submit(UUID.randomUUID(), kid, "make me Dr Robotnik");
        WishRun second = marshal.submit(UUID.randomUUID(), kid, "make me Zippy");
        marshal.assign("hop-worker-1", first.wishId());
        marshal.assign("hop-worker-2", second.wishId());

        // A worker thread, exactly as the MCP dispatch is: bound, and NOT the server thread.
        // A sentinel, because set(null) and never-set are the same value — and "it returned
        // nothing" is the actual bug here, so reporting it as "still waiting" would send the next
        // reader looking at threading instead of at the answer.
        final Object nothing = new Object();
        java.util.concurrent.atomic.AtomicReference<Object> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> AgentContext.with("hop-worker-2", () -> {
            try {
                Object got = AgentBridge.onServer(() -> AgentContext.currentRun(marshal));
                seen.set(got == null ? nothing : got);
            } catch (RuntimeException e) {
                seen.set(e);
            }
            return null;
        }), "gt-hop-worker");
        worker.setDaemon(true);
        worker.start();

        // succeedWhen, because the submitted work only runs as the server ticks — and this test
        // method is itself on the server thread, so blocking here would deadlock the thing it is
        // waiting for.
        helper.succeedWhen(() -> {
            Object got = seen.get();
            helper.assertTrue(got != null, Component.literal("waiting for the worker thread"));
            helper.assertFalse(got instanceof RuntimeException,
                    Component.literal("the hop threw: " + got));
            helper.assertTrue(got != nothing,
                    Component.literal("the worker asked which wish it was granting from inside "
                            + "AgentBridge.onServer and was told NOTHING — its identity did not "
                            + "survive the hop to the server thread, so plan_wish sets no plan and "
                            + "the wish shows no progress bar"));
            helper.assertTrue(got == second,
                    Component.literal("a worker asking from inside AgentBridge.onServer got "
                            + (got == first ? "the OTHER wish" : "nothing")
                            + " — its identity did not survive the hop to the server thread, which "
                            + "is how a second wish ends up with no progress bar"));
            marshal.finish(first.wishId());
            marshal.finish(second.wishId());
        });
    }

    /**
     * <b>Three wishes, three bars — on a running server, with a real ServerPlayer, with Create loaded.</b>
     *
     * <p>The test that could not exist: asserting per-player behaviour needs a mock player in the
     * level, and Create broadcasts to every player each tick, so the server died with <i>"Payload
     * create:clientbound_chain_conveyor may not be sent to the client"</i>. See and
     * {@code docs/TESTING.md}. {@link MockPlayerNetwork#inLevel} attaches the payload setup BEFORE
     * the player is placed, which is the part that matters — placing a player is itself a broadcast.
     *
     * <p>It covers the wiring {@code BarBook}'s unit tests deliberately cannot: that
     * {@code WishProgress} really opens one {@code ServerBossEvent} per wish, that finishing ONE
     * leaves the others up, and that {@code forget} takes them all down — the last of which was
     * broken by handing a PLAYER id to a method taking a WISH id, which compiled because both are
     * UUID and matched nothing at runtime.
     */
    @GameTest(template = "empty_test", batch = "mockplayer", timeoutTicks = 200)
    public static void threeWishesGetThreeRealBarsOnARunningServer(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);   // on ENTRY —
        ServerPlayer player = MockPlayerNetwork.inLevel(helper);

        // The shim's own postcondition, asserted before anything depends on it. Without this the
        // test can only fail LATER, as somebody else's payload, several ticks away — which is the
        // wild goose chase is about.
        helper.assertTrue(MockPlayerNetwork.canBeSentAnything(player),
                Component.literal("the mock player still has no payload channels, so the next broadcast by any mod "
                        + "will kill the server"));

        UUID[] ids = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        String[] names = {"your ice dragon", "your castle", "your javelin"};
        for (int i = 0; i < ids.length; i++) {
            WishProgress.startThinking(player, ids[i], names[i]);
        }
        helper.assertTrue(WishProgress.barCount(player.getUUID()) == 3,
                Component.literal("three wishes in flight should be three bars, not "
                        + WishProgress.barCount(player.getUUID())));

        WishProgress.clearWishBar(ids[0]);
        helper.assertTrue(WishProgress.barCount(player.getUUID()) == 2,
                Component.literal("finishing one wish took down "
                        + (3 - WishProgress.barCount(player.getUUID())) + " bars"));

        // HELD ACROSS TICKS, and the cleanup lives in here rather than a finally — a finally runs
        // the instant this method returns, which would remove the player before a single tick had
        // passed and make the hold imaginary. Those ticks are what give Create's per-tick broadcast
        // a chance to reach the mock at all; without them this passes with or without the shim.
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(WishProgress.barCount(player.getUUID()) == 2,
                    Component.literal("the two remaining bars did not survive 60 ticks"));
            WishProgress.forget(player.getUUID());
            helper.assertTrue(WishProgress.barCount(player.getUUID()) == 0,
                    Component.literal("forget() left " + WishProgress.barCount(player.getUUID())
                            + " bar(s) behind — they leak on logout and over a finished wish"));
            WishProgress.clearAllFor(player.getUUID());
            MockPlayerNetwork.remove(player);
            helper.succeed();
        });
    }
}
