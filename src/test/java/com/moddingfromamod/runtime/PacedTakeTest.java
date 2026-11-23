package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** a slow client skips to the moment that is due rather than drifting behind the world. */
class PacedTakeTest {

    @Test
    void theDueMomentIsWallClockOverPace() {
        assertEquals(0, PacedTake.due(0, 125, 48));
        assertEquals(0, PacedTake.due(124, 125, 48));
        assertEquals(1, PacedTake.due(125, 125, 48));
        assertEquals(16, PacedTake.due(2_000, 125, 48));
    }

    @Test
    void aClientThreeTimesTooSlowLandsOnTheRealMomentNotThePoseCount() {
        // measured: 48 frames in 20 s, ~420 ms a frame. Frame 10 is captured ~4.2 s in, which is
        // moment 33 of 48 — not pose 10, which is where the camera used to be (the wave 2.9 s ahead).
        assertEquals(33, PacedTake.due(4_200, 125, 48));
    }

    @Test
    void itNeverRunsPastTheLastPoseAndAnUnpacedTakeHasNoClock() {
        assertEquals(47, PacedTake.due(60_000, 125, 48));
        assertEquals(0, PacedTake.due(5_000, 0, 48));
        assertEquals(0, PacedTake.due(5_000, 125, 0));
    }
}
