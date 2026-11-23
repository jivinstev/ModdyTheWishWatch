package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The relationships between the capture timings</b> — which is the part no single constant can
 * be wrong about on its own, and the part that decides whether anything waiting on them is safe.
 *
 * <p>Each of these numbers was individually defensible while living in its own file, and the
 * question none of those files could answer was <i>how long can a child actually be frozen?</i> The
 * answer is a sum, not a constant, and a wait sized against any one term of it fires its fallback
 * on a perfectly healthy run.
 */
class CaptureTimingsTest {

    @Test
    @DisplayName("the idle exit fires before the hard cap, or it is dead code")
    void idleExitIsInsideTheHardCap() {
        assertTrue(CaptureTimings.IDLE_EXIT_MS < CaptureTimings.MAX_SESSION_MS,
                "if a session can only ever end at the cap, every burst holds the child for the "
                        + "full " + CaptureTimings.MAX_SESSION_MS + " ms and the idle exit — the "
                        + "thing that makes a quick re-shoot feel like one freeze rather than "
                        + "several — never runs at all");
    }

    @Test
    @DisplayName("the settle wait outlasts the longest possible hold, plus the ticker's lateness")
    void theSettleWaitCannotExpireOnAHealthySession() {
        long worstCase = CaptureTimings.MAX_SESSION_MS + CaptureTimings.tickCheckMs();
        assertTrue(CaptureTimings.settleWaitMs() > worstCase,
                "a wait of " + CaptureTimings.settleWaitMs() + " ms against a worst case of "
                        + worstCase + " ms would report a FAULT on an ordinary slow tick — and the "
                        + "fallback it triggers tells the agent not to trust the answer, so a "
                        + "too-short wait is not a smaller version of this bug, it is a different "
                        + "and louder one");
        assertTrue(CaptureTimings.settleWaitMs() - worstCase >= CaptureTimings.SETTLE_BUFFER_MS,
                "and the head-room must actually be the buffer, not an accident of the arithmetic");
    }

    @Test
    @DisplayName("the settle wait is DERIVED, so it cannot drift from what it waits for")
    void theWaitIsDerivedRatherThanTyped() {
        // Not tautology-checking the formula: pinning that every term is IN it. A wait that
        // silently stopped depending on the cap would still pass every other test here.
        assertEquals(CaptureTimings.MAX_SESSION_MS + CaptureTimings.tickCheckMs()
                        + CaptureTimings.SETTLE_BUFFER_MS,
                CaptureTimings.settleWaitMs(),
                "every term of the worst case must be in the wait, or a change to one of them "
                        + "leaves a fourth independent number behind");
    }

    @Test
    @DisplayName("the two caps do not cross, and the child's is the shorter")
    void theChildIsNeverHeldAsLongAsACamera() {
        assertTrue(CaptureTimings.MAX_SESSION_MS < CaptureTimings.MAX_CAMERA_HOLD_MS,
                "a child's cap is a promise TO THE CHILD and being cut off mid-burst is the right "
                        + "outcome; a camera's is only an anti-wedge backstop, and cutting IT off "
                        + "mid-burst hands the camera to a second wish halfway through the first "
                        + "one's orbit. Inverting them would trade a correct interruption for an "
                        + "incorrect one");
    }

    @Test
    @DisplayName("a wish in the queue is not dropped before the holder is forced to let go")
    void theQueueOutlastsTheHold() {
        assertTrue(CaptureTimings.MAX_WAIT_MS >= CaptureTimings.MAX_CAMERA_HOLD_MS,
                "a waiter that expires first is dropped for a camera it would have been handed "
                        + "moments later, and queueDepth then reports congestion that is not real");
        // ⚠ RECORDED RATHER THAN CHANGED: they are currently EQUAL, so a wish that queues at the
        // same instant a camera lease begins can be dropped in the very tick the holder is evicted.
        // WishCapture's own javadoc justifies MAX_WAIT_MS as "far longer than MAX_SESSION_MS",
        // which is true of the CHILD's cap (10 s) while the thing it actually races is the CAMERA's
        // (60 s). Left alone deliberately -- widening it is a behaviour change nobody has measured,
        // and the invariant above is what stops it silently inverting in the meantime.
        assertTrue(CaptureTimings.MAX_WAIT_MS >= CaptureTimings.MAX_SESSION_MS);
    }

    @Test
    @DisplayName("the ticker's lateness allows for a server that is running slowly")
    void theGranularityIsWallClockAndNotTicks() {
        assertTrue(CaptureTimings.SLOW_SERVER_FACTOR > 1,
                "the caps are compared in milliseconds but only CHECKED on the tick, so a server "
                        + "ticking at half speed checks half as often — assuming nominal ticks is "
                        + "assuming the failure case away");
        assertEquals((long) CaptureTimings.TICK_INTERVAL * CaptureTimings.MS_PER_TICK
                        * CaptureTimings.SLOW_SERVER_FACTOR,
                CaptureTimings.tickCheckMs());
    }
}
