package com.moddingfromamod.film;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CameraPathTest {

    @Test
    void theFirstStepSnapsAndLaterOnesGlideWithoutOvershoot() {
        CameraPath.Ease e = new CameraPath.Ease(0.1);
        assertEquals(5.0, e.step(5, 0, 0)[0], 1e-9);
        double prev = 5;
        for (int i = 0; i < 200; i++) {
            double x = e.step(10, 0, 0)[0];
            assertTrue(x >= prev && x <= 10, "it moves toward the target and never past it");
            prev = x;
        }
        assertEquals(10.0, prev, 1e-6);
    }

    @Test
    void aTargetThatShakesReachesTheCameraAsMuchLessShake() {
        // A creature's middle bobbing ±0.3 every tick: the raw camera shook by 0.6 a tick.
        CameraPath.Ease e = new CameraPath.Ease(0.06);
        e.step(0, 0, 0);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < 400; i++) {
            double y = e.step(0, i % 2 == 0 ? 0.3 : -0.3, 0)[1];
            if (i > 200) {
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
        }
        assertTrue(hi - lo < 0.05, "the shake left is " + (hi - lo));
    }

    @Test
    void yawAndPitchFollowMinecraftsConvention() {
        assertEquals(0f, CameraPath.yawPitch(0, 0, 1)[0], 1e-4);     // south
        assertEquals(90f, CameraPath.yawPitch(-1, 0, 0)[0], 1e-4);   // west
        assertEquals(-90f, CameraPath.yawPitch(1, 0, 0)[0], 1e-4);   // east
        assertEquals(180f, Math.abs(CameraPath.yawPitch(0, 0, -1)[0]), 1e-4);   // north
        assertEquals(45f, CameraPath.yawPitch(0, -1, 1)[1], 1e-4);   // looking down is positive
    }

    @Test
    void anOrbitSweepsSmoothlyFromOneSideToTheOtherAndHolds() {
        double[] start = CameraPath.orbit(0, 0, 10, 0, 20, 0, 100);
        double[] mid = CameraPath.orbit(0, 0, 10, 0, 20, 50, 100);
        double[] end = CameraPath.orbit(0, 0, 10, 0, 20, 100, 100);
        double[] after = CameraPath.orbit(0, 0, 10, 0, 20, 300, 100);
        assertEquals(-10.0, Math.toDegrees(Math.atan2(start[1], start[0])), 1e-6);
        assertEquals(0.0, Math.toDegrees(Math.atan2(mid[1], mid[0])), 1e-6);
        assertEquals(10.0, Math.toDegrees(Math.atan2(end[1], end[0])), 1e-6);
        assertEquals(end[0], after[0], 1e-9);
        assertEquals(10.0, Math.hypot(mid[0], mid[1]), 1e-9);   // it keeps its distance
        // starts at rest: the first tick moves far less than an even pace would
        double[] first = CameraPath.orbit(0, 0, 10, 0, 20, 1, 100);
        double moved = Math.toDegrees(Math.atan2(first[1], first[0])) + 10;
        assertTrue(moved < 0.2 * 0.2, "first-tick move " + moved);
    }
}
