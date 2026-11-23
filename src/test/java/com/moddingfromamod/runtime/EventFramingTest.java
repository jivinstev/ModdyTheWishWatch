package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** an event is filmed on its whole area, from the child's side, looking down at a photographer's angle. */
class EventFramingTest {

    private static double flat(EventFraming.Shot s, double fx, double fz) {
        return Math.hypot(s.x() - fx, s.z() - fz);
    }

    @Test
    void theWholeAreaFitsTheFrameWithItsMargin() {
        for (int r : new int[] {4, 12, 24, 40}) {
            var s = EventFraming.frame(0, 64, 0, r, 0, 100);
            double dist = Math.hypot(flat(s, 0, 0), s.feetY() + EventFraming.EYE_HEIGHT - s.aimY());
            double halfWidth = (dist - r) * Math.tan(Math.toRadians(EventFraming.HALF_H_FOV_DEG));
            assertTrue(halfWidth >= r * EventFraming.MARGIN - 1e-6 || dist >= EventFraming.MAX_DISTANCE - 1e-6
                    || dist <= EventFraming.MIN_DISTANCE + 1e-6, "radius " + r + ": half-width " + halfWidth);
        }
    }

    @Test
    void itStandsOnTheChildsSideAndLooksAtTheCentre() {
        var s = EventFraming.frame(100, 64, 100, 10, 100, 160);       // he is south of it
        assertTrue(s.z() > 100, "the camera is on his (south) side: z " + s.z());
        assertEquals(180, s.yaw(), 1e-6, "facing north, back at the event");
        assertEquals(EventFraming.ELEVATION_DEG, s.pitch(), 0.5, "looking down at a photographer's angle");
        assertTrue(s.pitch() > 0, "positive pitch looks down");
        var east = EventFraming.frame(0, 64, 0, 10, 50, 0);
        assertTrue(east.x() > 0 && Math.abs(east.z()) < 1e-6, "east of it when he is east of it");
        assertEquals(90, east.yaw(), 1e-6);
    }

    @Test
    void noViewerOrAViewerOnTheSpotGetsAThreeQuarterView() {
        assertEquals(225, EventFraming.frame(0, 64, 0, 10, Double.NaN, Double.NaN).yaw(), 1e-6);
        assertEquals(225, EventFraming.frame(0, 64, 0, 10, 0.2, 0.3).yaw(), 1e-6);
    }

    @Test
    void itAimsALittleAboveTheGroundAndNeverTooCloseOrTooFar() {
        var s = EventFraming.frame(0, 64, 0, 20, 0, 100);
        assertEquals(64 + 20 * EventFraming.AIM_LIFT, s.aimY(), 1e-9, "things arrive from above");
        double d = Math.hypot(flat(s, 0, 0), s.feetY() + EventFraming.EYE_HEIGHT - s.aimY());
        assertTrue(d >= EventFraming.MIN_DISTANCE && d <= EventFraming.MAX_DISTANCE);
        var tiny = EventFraming.frame(0, 64, 0, 0, 0, 100);
        assertTrue(Math.hypot(flat(tiny, 0, 0), tiny.feetY() + 1.62 - tiny.aimY()) >= EventFraming.MIN_DISTANCE - 1e-6);
        var huge = EventFraming.frame(0, 64, 0, 500, 0, 100);
        assertTrue(Math.hypot(flat(huge, 0, 0), huge.feetY() + 1.62 - huge.aimY()) <= EventFraming.MAX_DISTANCE + 1e-6);
    }
}
