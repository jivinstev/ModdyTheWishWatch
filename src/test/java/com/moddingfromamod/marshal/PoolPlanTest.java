package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule that decides what the pool costs.
 *
 * <p>Each of these is a bill, not a preference: a parked worker polls {@code await_wish} on a timer
 * forever, so "one more worker than needed" is a standing charge, and "no free worker" is a child
 * waiting.
 */
class PoolPlanTest {

    /** The healthy case: every running worker is believed live. */
    private static int spawns(int running, int busy) {
        return PoolPlan.spawnsNeeded(running, busy, running, 3);
    }

    /** A world opens with nothing running: start exactly one, not the whole pool. */
    @Test
    void aWorldOpensWithOneWorker() {
        assertEquals(1, spawns(0, 0), "one worker covers the common single-wish case");
    }

    /** One free worker is the steady state: nothing more is owed. */
    @Test
    void oneFreeWorkerIsEnough() {
        assertEquals(0, spawns(1, 0), "a lone idle worker is the spare");
        assertEquals(0, spawns(3, 2), "two busy of three still leaves a spare");
    }

    /** The moment the last free worker takes a wish, the next one starts. */
    @Test
    void takingTheLastSpareStartsTheNextWorker() {
        assertEquals(1, spawns(1, 1), "worker 1 is busy; start worker 2 so a second wish is heard");
        assertEquals(1, spawns(2, 2), "and again when that one is taken");
    }

    /**
     * ⚠ The bug this input choice exists to prevent, seen in a real game.
     *
     * <p>A healthy worker leaves the parked set every time {@code await_wish} returns and rejoins it
     * a turn later. Keyed on "parked", the supervisor samples that gap, believes it has no spare and
     * starts a worker it does not need — observed at 05:30 in a pool whose only worker was fine.
     * Keyed on "busy", a worker between polls is still free, and there is no window to sample.
     */
    @Test
    void aWorkerBetweenPollsIsStillFree() {
        assertEquals(0, spawns(1, 0),
                "not parked this instant, but holding no wish — it is a spare, do not double up");
    }

    /** A booting worker is not busy either, so it already counts — no grace period needed. */
    @Test
    void aBootingWorkerAlreadyCountsAsTheSpareItWillBe() {
        assertEquals(0, spawns(1, 0), "launched a second ago, not yet parked, still the spare");
    }

    /** The cap is absolute — this is what stops a busy session growing without bound. */
    @Test
    void theCapIsNeverExceeded() {
        assertEquals(0, spawns(3, 3), "at the cap a wish waits rather than starting a 4th worker");
        assertEquals(0, PoolPlan.spawnsNeeded(5, 5, 5, 3), "and over it, nothing is started");
        assertEquals(0, PoolPlan.spawnsNeeded(0, 0, 0, 0), "a cap of zero starts nothing at all");
    }

    /**
     * A worker that booted and never listened is not a spare.
     *
     * <p>The pool used to keep three parked workers, so some subset listened and a child was heard
     * anyway. Keeping ONE spare removes that redundancy: a deaf spare means nobody is listening.
     * Measured while building this — a JIT-spawned worker produced two text-only turns and stopped.
     */
    @Test
    void aWorkerThatBootedButNeverListenedIsNotASpare() {
        assertEquals(0, PoolPlan.deafCount(1, 0, 1), "running, listening — fine");
        assertEquals(0, PoolPlan.deafCount(1, 1, 0), "running, working — fine");
        assertEquals(1, PoolPlan.deafCount(1, 0, 0), "running, neither — nobody can hear a wish");
        assertEquals(2, PoolPlan.deafCount(3, 1, 0), "one working, two deaf");
        assertEquals(0, PoolPlan.deafCount(-1, -1, -1), "nonsense cannot invent a deaf worker");
    }

    /**
     * Discounting a deaf worker makes the pool replace it — but it still occupies a slot.
     *
     * <p>That bound is what stops a persistently deaf pool turning into an unbounded stream of
     * launches: three boots, then it stops and the log says why.
     */
    @Test
    void aDeafWorkerIsReplacedButStillCountsAgainstTheCap() {
        assertEquals(1, PoolPlan.spawnsNeeded(0, 0, 1, 3),
                "one running worker is deaf, so it is not a spare — start a real one");
        assertEquals(0, PoolPlan.spawnsNeeded(0, 0, 3, 3),
                "all three deaf, but they still fill the cap — stop, and let the log say why");
    }

    /** Never negative, and never more than one at a time, whatever it is handed. */
    @Test
    void nonsenseInputsCannotProduceNegativeOrRunawayWork() {
        assertEquals(0, PoolPlan.spawnsNeeded(-1, -1, -1, -1));
        assertEquals(1, PoolPlan.spawnsNeeded(0, 0, 0, 3), "one at a time, even from a cold pool");
        assertEquals(1, PoolPlan.spawnsNeeded(0, 9, 0, 3),
                "a nonsense busy count still starts just one, never a burst");
    }

    // ── THE ARM-B DEADLOCK — why a stranger cannot be waited for before the first wish ─────
    //
    // Gate D's arm B needs two workers to exist so a refinement can go to somebody other than the
    // owner, and WishLadderGateD.waitPool used to block rung 1 until a second worker parked. It
    // could not: the pool boots ONE worker, and this class only asks for another once the first is
    // busy — which happens when a wish is submitted, which is the thing the wait was blocking.
    //
    // Every arm-B cell voided after 90 seconds. It reads exactly like (a worker booted and
    // went deaf) and is not that: measured on two real cells, wish-granter-1 was the only worker
    // ever STARTED, with zero supervisor top-ups and zero deaf warnings.
    //
    // These two cases are the deadlock stated as arithmetic, so the wait cannot be moved back.

    @Test
    @DisplayName("a fresh pool with one parked worker asks for nobody — so waiting for a second is futile")
    void anIdlePoolWillNotGrowOnItsOwn() {
        assertEquals(0, PoolPlan.spawnsNeeded(1, 0, 1, 3),
                "one live idle worker satisfies MIN_SPARE, so no amount of waiting produces a "
                + "second one. A gate that blocks its first wish until two workers exist is "
                + "waiting for something only that wish can cause.");
    }

    @Test
    @DisplayName("the first wish is what creates the second worker")
    void takingAWishIsWhatGrowsThePool() {
        assertEquals(1, PoolPlan.spawnsNeeded(1, 1, 1, 3),
                "with the only worker busy there is no spare, so the pool starts one — this is the "
                + "edge the stranger arm depends on, and it is reachable only AFTER a submission");
    }

    @Test
    @DisplayName("and after that first wish the pool really can hold two")
    void thePoolReachesTwoOnceAWishHasBeenTaken() {
        // running=2 (the original plus the supervisor's), both idle once the wish finishes.
        assertEquals(0, PoolPlan.spawnsNeeded(2, 0, 2, 3), "two idle workers need no third");
        assertTrue(2 >= 2, "and two is what WishAffinity.DIFFERENT needs to be satisfiable");
    }

    // ── desiredWorkers: the LEVEL a split-topology runner converges on (HO-6) ────────────────

    @Test
    void reapingIsTheSameNumberAndNotASecondChannel() {
        // `desired < running` IS the stop instruction. If reaping travelled on its own channel
        // there would be a path where a runner is told to start and never told to stop, and three
        // parked workers bill ~$6.40/h doing nothing.
        assertEquals(0, PoolPlan.desiredWorkers(3, 3, 0, 3, true, false),
                "a reap must be expressible as a desired level, not a separate message");
    }

    @Test
    void itIsALevelSoReadingItTwiceConvergesRatherThanDoubling() {
        // The whole reason it is not "spawn one more": a runner that reads a delta twice, or reads
        // a stale copy, launches twice. Same inputs must give the same absolute answer.
        int once = PoolPlan.desiredWorkers(0, 0, 0, 3, false, true);
        int twice = PoolPlan.desiredWorkers(0, 0, 0, 3, false, true);
        assertEquals(once, twice);
        assertEquals(1, once, "an empty pool with somebody here wants exactly one worker");
    }

    @Test
    void aCapOfZeroBeatsTheBootFloor() {
        // shouldBoot raises the floor to 1; a cap of 0 is how an operator turns the pool OFF.
        // Clamping in the wrong order would make that switch do nothing.
        assertEquals(0, PoolPlan.desiredWorkers(0, 0, 0, 0, false, true));
    }

    @Test
    void aBusyPoolWithNoSpareWantsOneMoreAndNeverMoreThanTheCap() {
        assertEquals(2, PoolPlan.desiredWorkers(1, 1, 1, 3, false, false),
                "one worker, busy, no spare — want one more");
        assertEquals(3, PoolPlan.desiredWorkers(3, 3, 3, 3, false, false),
                "at the cap it stays at the cap rather than asking for a fourth");
    }

    @Test
    void aSteadyPoolAsksForNoChange() {
        // The common case, and the one that must be boring: nothing to do is `desired == running`.
        assertEquals(2, PoolPlan.desiredWorkers(2, 2, 1, 3, false, false));
    }

    // ── Shrinking back to one spare ────────────────────────────────────────────────────

    private static final long G = PoolPlan.EXTRA_IDLE_STOP_MS;
    /** Long after every boot, so boot grace never decides a case unless a test says so. */
    private static final long NOW = 3_600_000L;

    /** Parked, free, stoppable, booted long ago, idle since {@code idleSince}. */
    private static PoolPlan.Worker parked(String name, long idleSince) {
        return new PoolPlan.Worker(name, 0L, idleSince, true, false, true);
    }

    private static String shrink(PoolPlan.Worker... ws) {
        return PoolPlan.shrinkCandidate(java.util.List.of(ws), NOW, G);
    }

    @Test
    void theGraceIsTwoMinutes() {
        assertEquals(120_000L, PoolPlan.EXTRA_IDLE_STOP_MS);
        assertTrue(PoolPlan.EXTRA_IDLE_STOP_MS > 110_000L,
                "longer than one await_wish cycle, so a worker that just finished a wish gets one "
                        + "whole poll to catch the child's follow-up");
    }

    @Test
    void anExtraParkedPastTheGraceIsStopped() {
        assertEquals("wish-granter-2", shrink(parked("wish-granter-1", NOW - 10),
                parked("wish-granter-2", NOW - G)));
    }

    @Test
    void theLastFreeWorkerIsNeverStoppedHoweverLongItIdles() {
        assertNull(shrink(parked("wish-granter-1", 0)), "the one spare stays, whatever the clock says");
        // ...and a busy worker beside it changes nothing: busy is not free.
        assertNull(shrink(parked("wish-granter-1", 0),
                new PoolPlan.Worker("wish-granter-2", 0L, 0L, false, true, true)));
    }

    @Test
    void aWorkerHoldingAWishIsNeverStopped() {
        // Even one the census somehow reads as parked: busy wins.
        assertNull(shrink(parked("wish-granter-1", 0),
                new PoolPlan.Worker("wish-granter-2", 0L, 0L, true, true, true)));
    }

    @Test
    void aWorkerNotParkedThisTickIsNeverStopped() {
        // Between two await_wish calls it may have just TAKEN a wish the marshal has not recorded
        // yet (the safety grade sits between the dequeue and the assign). Wait for the next tick.
        assertEquals("wish-granter-2", shrink(parked("wish-granter-1", 0), parked("wish-granter-2", 0),
                new PoolPlan.Worker("wish-granter-3", 0L, 0L, false, false, true)),
                "the top slot is not parked this tick, so a parked extra goes instead");
        assertNull(shrink(parked("wish-granter-1", 0),
                new PoolPlan.Worker("wish-granter-3", 0L, 0L, false, false, true)),
                "only one is LISTENING, so it is the spare and the unparked one is not a candidate");
    }

    @Test
    void theSpareIsCountedFromParkedWorkersNotFromRunningMinusBusy() {
        // A deaf or booting worker is "free" to spawnsNeeded but listens to nobody; it must never be
        // what lets the last listener go.
        assertNull(shrink(parked("wish-granter-1", 0),
                new PoolPlan.Worker("wish-granter-2", 0L, 0L, false, false, true)));
    }

    @Test
    void insideTheGraceNothingIsStopped() {
        assertNull(shrink(parked("wish-granter-1", NOW), parked("wish-granter-2", NOW - G + 1)));
        assertEquals("wish-granter-2", shrink(parked("wish-granter-1", NOW),
                parked("wish-granter-2", NOW - G)), "exactly at the grace it goes");
    }

    @Test
    void aBootingWorkerIsNeverStopped() {
        long boot = NOW - PoolPlan.BOOT_GRACE_MS + 1;
        assertNull(PoolPlan.shrinkCandidate(java.util.List.of(parked("wish-granter-1", NOW),
                new PoolPlan.Worker("wish-granter-2", boot, boot, true, false, true)), NOW, 1L),
                "inside BOOT_GRACE even a 1 ms grace stops nothing");
    }

    @Test
    void anUnstoppableWorkerIsNotChosenButStillCountsAsTheSpare() {
        // No CLI session id: `claude stop` cannot name it, so retiring it from the queue would leave
        // it listening and never hearing.
        assertEquals("wish-granter-1", shrink(parked("wish-granter-1", 0),
                new PoolPlan.Worker("wish-granter-2", 0L, 0L, true, false, false)));
        assertNull(shrink(new PoolPlan.Worker("wish-granter-1", 0L, 0L, true, false, false),
                new PoolPlan.Worker("wish-granter-2", 0L, 0L, true, false, false)));
    }

    @Test
    void atMostOnePerCallAndTheLongestIdleGoesFirst() {
        assertEquals("wish-granter-2", shrink(parked("wish-granter-1", NOW - G),
                parked("wish-granter-2", NOW - 9 * G), parked("wish-granter-3", NOW - 5 * G),
                parked("wish-granter-4", NOW - 2 * G)));
    }

    @Test
    void aTieDrainsFromTheTopSlotNumericallyNotAlphabetically() {
        assertEquals("wish-granter-3", shrink(parked("wish-granter-1", 0), parked("wish-granter-3", 0),
                parked("wish-granter-2", 0)));
        assertEquals("wish-granter-10", shrink(parked("wish-granter-9", 0),
                parked("wish-granter-10", 0)), "-10 is above -9");
    }

    @Test
    void aBurstDrainsToExactlyOneSpareOneTickAtATime() {
        java.util.List<PoolPlan.Worker> pool = new java.util.ArrayList<>(java.util.List.of(
                parked("wish-granter-1", 0), parked("wish-granter-2", 0),
                parked("wish-granter-3", 0), parked("wish-granter-4", 0)));
        int stopped = 0;
        String name;
        while ((name = PoolPlan.shrinkCandidate(pool, NOW, G)) != null) {
            String n = name;
            pool.removeIf(w -> w.name().equals(n));
            stopped++;
            // THE SPAWN RULE MUST NOT ANSWER THE STOP: after it there is still a free listener.
            assertEquals(0, PoolPlan.spawnsNeeded(pool.size(), 0, pool.size(), 10),
                    "stopping " + n + " left no spare, so the next tick would relaunch it");
        }
        assertEquals(3, stopped);
        assertEquals(java.util.List.of("wish-granter-1"), pool.stream().map(PoolPlan.Worker::name).toList());
    }

    @Test
    void anIdleClockOlderThanTheBootIsReadFromTheBoot() {
        // A slot reused for a relaunch: its idle clock starts at its OWN boot.
        assertNull(PoolPlan.shrinkCandidate(java.util.List.of(parked("wish-granter-1", NOW),
                new PoolPlan.Worker("wish-granter-2", NOW - G + 1, 0L, true, false, true)), NOW, G));
    }

    @Test
    void nonsenseInputsStopNothing() {
        assertNull(PoolPlan.shrinkCandidate(null, NOW, G));
        assertNull(PoolPlan.shrinkCandidate(java.util.List.of(), NOW, G));
        assertNull(PoolPlan.shrinkCandidate(java.util.List.of(parked("a-1", 0), parked("a-2", 0)), NOW, 0L),
                "a zero grace is not 'stop everything at once'");
    }
}
