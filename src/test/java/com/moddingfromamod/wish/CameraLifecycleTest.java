package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * an earlier issue's rule, and every edge case that changed it from the one-line version.
 *
 * <p>Each of these is a way a demand-driven camera goes wrong, and none of them announces itself:
 * a camera that never starts, one that never stops, one that restarts every time somebody relogs,
 * and one that spawns a Minecraft every few seconds forever.
 */
class CameraLifecycleTest {

    private static CameraLifecycle.State s(int children, int wishes, boolean up, boolean starting,
                                           long idleSince, long startedAt, int failures) {
        return new CameraLifecycle.State(children, wishes, up, starting, idleSince, startedAt,
                failures);
    }

    // ── starting ────────────────────────────────────────────────────────────────────────────────

    @Test
    void aChildArrivingStartsTheCamera() {
        assertEquals(CameraLifecycle.Action.START,
                CameraLifecycle.decide(s(1, 0, false, false, 0, 0, 0), 1000));
    }

    @Test
    void anEmptyServerStartsNothing() {
        assertEquals(CameraLifecycle.Action.NONE,
                CameraLifecycle.decide(s(0, 0, false, false, 0, 0, 0), 1000));
    }

    @Test
    void aSecondChildDoesNotStartASecondCamera() {
        assertEquals(CameraLifecycle.Action.NONE,
                CameraLifecycle.decide(s(2, 0, true, false, 0, 0, 0), 1000));
    }

    @Test
    void aStartInFlightIsNotStartedAgain() {
        // Without this the supervisor spawns a Minecraft every tick for the 13 s it takes to join.
        assertEquals(CameraLifecycle.Action.NONE,
                CameraLifecycle.decide(s(1, 0, false, true, 0, 900, 0), 1000));
    }

    @Test
    void aStartThatOverranIsGivenUpOnRatherThanWaitedForForever() {
        assertTrue(CameraLifecycle.startTimedOut(
                s(1, 0, false, true, 0, 0, 0), CameraLifecycle.START_TIMEOUT_MS + 1));
        assertEquals(CameraLifecycle.Action.STOP, CameraLifecycle.decide(
                s(1, 0, false, true, 0, 0, 0), CameraLifecycle.START_TIMEOUT_MS + 1));
    }

    /** Measured on a test server, 2026-10-03: a camera VM is ready in 53–57 s and joins in 68–95 s — not in 90 s. */
    @Test
    void aCameraOnItsOwnVmIsGivenLongerToJoin() {
        try {
            CameraLifecycle.vmBodies(true);
            long overLocal = CameraLifecycle.START_TIMEOUT_MS + 1;
            assertEquals(CameraLifecycle.Action.NONE, CameraLifecycle.decide(s(1, 0, false, true, 0, 0, 0), overLocal),
                    "a VM body still booting at 90 s is not a failure");
            assertEquals(CameraLifecycle.Action.STOP, CameraLifecycle.decide(
                    s(1, 0, false, true, 0, 0, 0), CameraLifecycle.VM_START_TIMEOUT_MS + 1));
        } finally {
            CameraLifecycle.vmBodies(false);
        }
    }

    // ── stopping ────────────────────────────────────────────────────────────────────────────────

    @Test
    void theLastChildLeavingDoesNotStopItImmediately() {
        // THE RELOG CASE. Stopping the instant he disconnects costs a full ~13 s join when he comes
        // straight back, and the wish he reconnects to would run with no camera at all.
        assertEquals(CameraLifecycle.Action.NONE,
                CameraLifecycle.decide(s(0, 0, true, false, 1000, 0, 0), 1000 + 5_000));
    }

    @Test
    void itStopsOnceTheServerHasBeenEmptyForTheLingerPeriod() {
        assertEquals(CameraLifecycle.Action.STOP, CameraLifecycle.decide(
                s(0, 0, true, false, 1000, 0, 0), 1000 + CameraLifecycle.LINGER_MS));
    }

    @Test
    void aChildComingBackInsideTheLingerKeepsTheSameCamera() {
        assertEquals(CameraLifecycle.Action.NONE, CameraLifecycle.decide(
                s(1, 0, true, false, 0, 0, 0), 1000 + CameraLifecycle.LINGER_MS));
    }

    // ── draining ────────────────────────────────────────────────────────────────────────────────

    @Test
    void aWishStillRunningKeepsTheCameraAfterEverybodyHasLeft() {
        // The half of the proposed rule that is exactly right: a wish outlives its wisher, and
        // killing the camera mid-wish loses the photographs the wish is about to hand back.
        assertEquals(CameraLifecycle.Action.NONE, CameraLifecycle.decide(
                s(0, 1, true, false, 1000, 0, 0), 1000 + CameraLifecycle.LINGER_MS + 1));
    }

    @Test
    void aWedgedWishDoesNotHoldTheCameraForever() {
        // "Wait for the last wish" with no cap is a camera that never exits, which is the leak
        // is about -- so the drain is bounded and the caller reports that it was.
        assertEquals(CameraLifecycle.Action.STOP, CameraLifecycle.decide(
                s(0, 1, true, false, 1000, 0, 0), 1000 + CameraLifecycle.DRAIN_CAP_MS));
    }

    // ── failure ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void repeatedFailureGivesUpLoudlyRatherThanRespawningForever() {
        assertEquals(CameraLifecycle.Action.GIVE_UP, CameraLifecycle.decide(
                s(1, 0, false, false, 0, 0, CameraLifecycle.MAX_CONSECUTIVE_FAILURES), 1000));
    }

    @Test
    void oneFailureIsStillWorthRetrying() {
        assertEquals(CameraLifecycle.Action.START,
                CameraLifecycle.decide(s(1, 0, false, false, 0, 0, 1), 1000));
    }

    @Test
    void aCameraThatDIEDWhileChildrenAreOnlineIsRestarted() {
        // Viewpoint.acquire falls back to the child when there is no camera, which is correct
        // degradation AND indistinguishable from success -- an earlier issue's own complaint.
        assertEquals(CameraLifecycle.Action.START,
                CameraLifecycle.decide(s(1, 1, false, false, 0, 0, 0), 1000));
    }

    // ── the coupling to ────────────────────────────────────────────────────────────────────

    @Test
    void cameraIsServingIsFalseWhileItIsSTARTING() {
        // 🔴 THE ONE THAT MATTERS FOR THE PACK DEFERRAL. During the ~13 s join the agent
        // photographs THROUGH THE CHILD, so withholding his pack in that window makes the
        // self-critique loop look at a creature made of paper -- an earlier issue's bug, induced on purpose.
        assertFalse(CameraLifecycle.cameraIsServing(s(1, 0, false, true, 0, 0, 0)));
        assertFalse(CameraLifecycle.cameraIsServing(s(1, 0, false, false, 0, 0, 0)));
        assertTrue(CameraLifecycle.cameraIsServing(s(1, 0, true, false, 0, 0, 0)));
    }

    @Test
    void havingGivenUpDoesNotBYITSELFDecideWhetherOneIsServing() {
        // ⚠ THE NAME MATTERS HERE, because the obvious one ("serving is false once we have given
        // up") would be TRUE OF A BUG: it holds whether or not the rule consults the failure
        // count, since after a give-up no camera is connected anyway. The rule deliberately does
        // NOT consult it -- 'serving' is a question about who is CONNECTED, and a camera started
        // by something else (a harness, a service manager) serves perfectly well on a server that
        // long ago gave up trying to start its own. So both halves are asserted at the same
        // failure count, and only cameraUp moves.
        assertFalse(CameraLifecycle.cameraIsServing(
                s(1, 0, false, false, 0, 0, CameraLifecycle.MAX_CONSECUTIVE_FAILURES)),
                "nothing is connected, so nothing is serving");
        assertTrue(CameraLifecycle.cameraIsServing(
                s(1, 0, true, false, 0, 0, CameraLifecycle.MAX_CONSECUTIVE_FAILURES)),
                "a camera IS connected -- who started it, and how many times we failed to start "
                        + "our own, are not the question. Withholding the child's pack while a "
                        + "working camera is in the world would cost him the creature for nothing");
    }

    // ── the numbers are ordered, which is what makes the drain meaningful ───────────────────────

    @Test
    void theDrainCapIsLongerThanTheLinger() {
        assertTrue(CameraLifecycle.DRAIN_CAP_MS > CameraLifecycle.LINGER_MS,
                "a drain shorter than the linger would never be reached, so the wish-draining "
                        + "half of the rule would be dead code");
    }

    @Test
    void theLingerIsLongerThanAStart() {
        assertTrue(CameraLifecycle.LINGER_MS > 13_000,
                "a linger shorter than a join means a relog always costs a full restart, which is "
                        + "the thing the linger exists to avoid");
    }
}
