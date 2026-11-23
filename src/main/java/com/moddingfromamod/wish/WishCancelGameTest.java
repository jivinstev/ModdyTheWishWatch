package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.marshal.WishSlots;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for stopping a wish: world state and wiring, never timing.
 *
 * <p>The pure policy is {@code CancelPolicyTest} and the dispatch refusal is
 * {@code McpCancelSeamTest}. What only a running server can settle is that the stop reaches the
 * <b>world</b> — that blocks stop appearing, that the beam and bar are really gone, and that an undo
 * actually takes a building back down.
 *
 * <p><b>No wall-clock assertions.</b> A GameTest server ticks as fast as it can, so "within a tick"
 * is asserted as <i>before the next tick's placement happens</i>, never as a duration.
 *
 * <p>Each test drives its own marshal state and clears it afterwards, because GameTests in different
 * the same batch run interleaved and anything asserting on a singleton's statics otherwise fails against its
 * neighbour rather than against the code.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishCancelGameTest {

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

    private static BuildPlan plan(String id, int side) {
        return BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"%s","name":"Cancel test","shapes":[
              {"kind":"box","width":%d,"height":%d,"depth":%d,"block":"minecraft:stone"}]}
            """.formatted(id, side, side, side)).getAsJsonObject());
    }

    /** A run this test owns, bound to a worker id nobody else uses. */
    private static WishRun ownRun(UUID me, String worker, String text) {
        UUID wishId = UUID.randomUUID();
        Marshal.global().submit(wishId, me, text);
        return Marshal.global().assign(worker, wishId);
    }

    private static void cleanUp(UUID me, WishRun run) {
        Builder.cancelJobsFor(run.wishId());
        Marshal.global().finish(run.wishId());
        WishProgress.forget(me);
    }

    /**
     * <b>The headline.</b> Stopping a wish stops the blocks.
     *
     * <p>A stop that leaves a castle still going up is not a stop — the child would watch a building
     * he just cancelled carry on appearing, which reads as the button not working. The fixture is
     * deliberately far bigger than one tick's budget so there is genuinely something left to stop.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void stoppingAWishStopsTheBlocksGoingUp(GameTestHelper helper) {
        UUID me = child("stop-blocks");
        WishRun run = ownRun(me, "gt-worker-blocks", "build me an enormous castle");
        // High above the plots: a 40-cube is far larger than this test's own structure and would
        // otherwise spill into whichever neighbour GameTest happened to lay out beside it.
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).above(150);

        AgentContext.bind("gt-worker-blocks");
        try {
            BuildPlan big = plan("gt_cancel_big", 40);
            helper.assertTrue(big.volume() > Builder.BLOCKS_PER_TICK,
                    Component.literal("the fixture must exceed one tick's budget or there is nothing to stop"));
            Builder.submit(helper.getLevel(), anchor, big);
        } finally {
            AgentContext.unbind();
        }

        helper.assertTrue(Builder.queuedJobsFor(run.wishId()) == 1,
                Component.literal("the job must be tagged with the wish that queued it, or a stop cannot find it"));

        Marshal.global().cancel(run.wishId(), CancelMode.STOP, "", null);
        int dropped = Builder.cancelJobsFor(run.wishId());

        helper.assertTrue(dropped >= 0, Component.literal("cancelling is never an error"));
        helper.assertTrue(Builder.queuedJobsFor(run.wishId()) == 0,
                Component.literal("the wish's blocks must stop; " + Builder.queuedJobsFor(run.wishId()) + " job(s) left"));
        cleanUp(me, run);
        helper.succeed();
    }

    /**
     * One wish's stop must not take another wish's building down with it — the failure that only
     * exists once more than one thing can be in flight.
     */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void stoppingOneWishLeavesAnotherWishesBuildingAlone(GameTestHelper helper) {
        UUID me = child("two-builds");
        WishRun mine = ownRun(me, "gt-worker-a", "a castle");
        WishRun theirs = ownRun(me, "gt-worker-b", "a tower");
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).above(190);

        AgentContext.bind("gt-worker-a");
        try {
            Builder.submit(helper.getLevel(), anchor, plan("gt_cancel_mine", 30));
        } finally {
            AgentContext.unbind();
        }
        AgentContext.bind("gt-worker-b");
        try {
            Builder.submit(helper.getLevel(), anchor.above(60), plan("gt_cancel_theirs", 30));
        } finally {
            AgentContext.unbind();
        }

        Marshal.global().cancel(mine.wishId(), CancelMode.STOP, "", null);
        Builder.cancelJobsFor(mine.wishId());

        helper.assertTrue(Builder.queuedJobsFor(mine.wishId()) == 0, Component.literal("the stopped one is gone"));
        helper.assertTrue(Builder.queuedJobsFor(theirs.wishId()) == 1,
                Component.literal("the other wish is still going up — nobody stopped it"));
        cleanUp(me, mine);
        cleanUp(me, theirs);
        helper.succeed();
    }

    /** The bar and the beam are the child's evidence that anything is happening. Both must go. */
    @GameTest(template = "empty_test")
    public static void stoppingTakesTheBarAndTheBeamDown(GameTestHelper helper) {
        UUID me = child("hud");
        WishRun run = ownRun(me, "gt-worker-hud", "a dragon");
        WishProgress.defaultAnchor(me, helper.absolutePos(new BlockPos(1, 1, 1)));
        helper.assertTrue(WishProgress.manifestAnchor(me) != null,
                Component.literal("the beam has to be up first, or this proves nothing"));

        Marshal.global().cancel(run.wishId(), CancelMode.STOP, "", null);
        WishCancel.clearHud(me, run.wishId(), Marshal.global());

        helper.assertTrue(WishProgress.manifestAnchor(me) == null,
                Component.literal("the sparkle beam must go out — it is a promise about a wish that no longer exists"));
        helper.assertFalse(WishProgress.hasBar(me), Component.literal("and the progress bar must come down"));
        cleanUp(me, run);
        helper.succeed();
    }

    /**
     * With something else of his still in flight, a stop must NOT wipe the HUD — he would watch a
     * wish he never touched apparently vanish.
     */
    @GameTest(template = "empty_test")
    public static void anotherWishStillGoingKeepsItsBeam(GameTestHelper helper) {
        UUID me = child("other-wish");
        WishRun stopped = ownRun(me, "gt-worker-x", "a dragon");
        WishRun other = ownRun(me, "gt-worker-y", "a castle");
        WishProgress.defaultAnchor(me, helper.absolutePos(new BlockPos(1, 1, 1)));

        Marshal.global().cancel(stopped.wishId(), CancelMode.STOP, "", null);
        WishCancel.clearHud(me, stopped.wishId(), Marshal.global());

        helper.assertTrue(WishProgress.manifestAnchor(me) != null,
                Component.literal("his other wish is still being made, so its beam must stay lit"));
        cleanUp(me, stopped);
        cleanUp(me, other);
        WishProgress.forget(me);
        helper.succeed();
    }

    /** An undo really takes the building back down, using the plan and anchor — no region snapshot. */
    // ITS OWN BATCH: with nothing bound, AgentContext.currentRun() credits a build to the ONE
    // working run — so while this run was the only one working, a neighbour's unbound
    // Builder.submit ('pers_tower', 'gt_odd') was recorded as made by it, and this undo took their
    // buildings down too (reversed=2, and two neighbours red). Measured 2026-09-27.
    @GameTest(template = "empty_test", timeoutTicks = 600, batch = "wishundo")
    public static void anUndoTakesTheBuildingBackDown(GameTestHelper helper) {
        UUID me = child("undo");
        WishRun run = ownRun(me, "gt-worker-undo", "build me a hut");
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).above(230);

        BuildPlan hut = plan("gt_cancel_hut", 4);
        // Both halves, exactly as build_structure does them: the PLAN goes in the registry and
        // Builder.submit remembers the anchor. An undo needs both — with only the anchor it
        // knows where the building is and not what it is made of, so it reverses nothing and
        // the [undo] button quietly lies. That is how this test first failed.
        com.moddingfromamod.builder.BuildRegistry.put(helper.getLevel().getServer(), hut);
        // RETRIED UNTIL ACCEPTED: Builder queues at most 8 jobs, and in a batch full of other
        // building tests the first submit can be refused ("Too many buildings are already going up")
        // — this test ignored the answer and then waited for a hut that was never coming.
        java.util.concurrent.atomic.AtomicBoolean accepted = new java.util.concurrent.atomic.AtomicBoolean();
        String[] submitSaid = {""};
        helper.onEachTick(() -> {
            if (accepted.get()) {
                return;
            }
            AgentContext.bind("gt-worker-undo");
            try {
                String said = Builder.submit(helper.getLevel(), anchor, hut);
                submitSaid[0] = said;
                accepted.set(said.startsWith("Building '"));
            } finally {
                AgentContext.unbind();
            }
        });
        run.usedTool("build_structure");

        helper.succeedWhen(() -> {
            helper.assertTrue(accepted.get(), Component.literal("waiting for the builder to take the hut"));
            helper.assertFalse(Builder.isBusy(), Component.literal("waiting for the hut to go up"));
            helper.assertTrue(helper.getLevel().getBlockState(anchor).is(Blocks.STONE),
                    Component.literal("the hut has to exist before it can be undone — at " + anchor + " (the world stops at y=319)"
                            + " the block is "
                            + helper.getLevel().getBlockState(anchor) + "; the builder said: " + submitSaid[0]));

            helper.assertTrue(
                    com.moddingfromamod.marshal.UndoContract.undoable(run.toolsUsed()),
                    Component.literal("a wish that only built is undoable, so [undo] may honestly be offered"));
            Marshal.global().cancel(run.wishId(), CancelMode.UNDO, "", null);
            var result = WishUndo.undo(helper.getLevel().getServer(), run);

            helper.assertTrue(result.reversed() == 1,
                    Component.literal("the building should have been taken back down, reversed=" + result.reversed()));
            helper.assertTrue(helper.getLevel().getBlockState(anchor).isAir(),
                    Component.literal("…and the blocks are actually gone from the world"));
            WishProgress.forget(me);
        });
    }

    /** Cancelling an EDIT must never remove the thing being edited. */
    @GameTest(template = "empty_test", timeoutTicks = 600)
    public static void stoppingAnEditLeavesTheThingItWasEditingStanding(GameTestHelper helper) {
        UUID me = child("edit");
        WishRun creation = ownRun(me, "gt-worker-c1", "build me a hut");
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1)).above(270);
        AgentContext.bind("gt-worker-c1");
        try {
            Builder.submit(helper.getLevel(), anchor, plan("gt_cancel_base", 4));
        } finally {
            AgentContext.unbind();
        }

        helper.succeedWhen(() -> {
            helper.assertFalse(Builder.isBusy(), Component.literal("waiting for the hut to go up"));
            helper.assertTrue(helper.getLevel().getBlockState(anchor).is(Blocks.STONE),
                    Component.literal("the base building has to exist first"));

            Marshal.global().finish(creation.wishId());
            // A second wish that EDITS it: it looked, it did not build.
            WishRun edit = ownRun(me, "gt-worker-c2", "make the hut nicer");
            edit.usedTool("look_around");
            Marshal.global().cancel(edit.wishId(), CancelMode.UNDO, "", null);
            var result = WishUndo.undo(helper.getLevel().getServer(), edit);

            helper.assertTrue(result.reversed() == 0, Component.literal("an edit made nothing, so it removes nothing"));
            helper.assertTrue(helper.getLevel().getBlockState(anchor).is(Blocks.STONE),
                    Component.literal("stopping 'make the hut nicer' must leave the hut exactly as it was"));
            WishProgress.forget(me);
        });
    }

    /** Every affordance the child clicks is a real command, or the links are decoration. */
    @GameTest(template = "empty_test")
    public static void everyStopAffordanceIsARegisteredCommand(GameTestHelper helper) {
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        var moddy = dispatcher.getRoot().getChild(ChildCommands.MODDY);
        helper.assertTrue(moddy != null, Component.literal("/moddy must exist"));
        for (String sub : new String[]{"stop", "undo", "redirect"}) {
            helper.assertTrue(moddy.getChild(sub) != null,
                    Component.literal("/moddy " + sub + " is not registered, so its chat link does nothing at all"));
        }
        helper.succeed();
    }

    /**
     * The A/B seam, at runtime. Every gate must stay green at one slot, and a concurrency bug must
     * be provable by flipping it — which is only true if the flag really reaches a headless run.
     */
    @GameTest(template = "empty_test")
    public static void theSlotFlagReachesAHeadlessRun(GameTestHelper helper) {
        int configured = WishSlots.slots();
        helper.assertTrue(configured >= 1 && configured <= WishSlots.MAX,
                Component.literal("slots out of range: " + configured));
        try {
            // Its OWN marshal, reading the REAL flag. The question is whether -Ptestprops reaches a
            // headless run, which is about WishSlots; using the global marshal as well made the
            // answer depend on whatever another test happened to leave in flight, and it did —
            // this went red because a neighbour threw and left two runs WORKING.
            UUID me = child("slot-flag");
            // ITS OWN SLOT DIAL as well as its own marshal. This used to hold the marshal
            // privately and then drive it by flipping WishSlots — a process-wide static — so for
            // the length of the serial arm below EVERY overlapping test was also at one slot. The
            // dial is a local now, and nothing outside this method can see it move.
            java.util.concurrent.atomic.AtomicInteger dial =
                    new java.util.concurrent.atomic.AtomicInteger(1);
            Marshal own = new Marshal(com.moddingfromamod.marshal.Clock.system(), dial::get);
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            own.submit(first, me, "one");
            own.submit(second, me, "two");

            dial.set(1);
            helper.assertTrue(own.claim("gt-slot-a") != null, Component.literal("the first one starts"));
            helper.assertTrue(own.claim("gt-slot-b") == null,
                    Component.literal("at wishslots=1 the second must NOT start — that is today's behaviour"));

            dial.set(3);
            helper.assertTrue(own.claim("gt-slot-b") != null,
                    Component.literal("and at wishslots=3 it does, or the A/B seam proves nothing"));
        } finally {
            // nothing to restore: the dial was never shared
        }
        helper.succeed();
    }
}
