package com.moddingfromamod.seam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WallClockTimeoutTest {

    @Test
    @DisplayName("KeptIsNotAProp's release smoke: 1202 of 1200 ticks after five seconds is NOT a timeout yet")
    void aFastBatchDoesNotEatTheBudget() {
        assertFalse(WallClockTimeout.timedOut(1202, 1200, 5_000L));
        assertTrue(WallClockTimeout.timedOut(1202, 1200, 60_000L), "a minute at 20 ticks a second is its length");
    }

    @Test
    @DisplayName("a slow batch still times out on its ticks, as strictly as before")
    void aSlowBatchIsUnchanged() {
        // 18 ticks a second: 1201 ticks is ~67 s, past the 60 s floor -- the tick budget decides.
        assertTrue(WallClockTimeout.timedOut(1201, 1200, 66_700L));
        assertFalse(WallClockTimeout.timedOut(1200, 1200, 600_000L), "never before its ticks, however long it ran");
    }

    @Test
    @DisplayName("a huge tick budget is capped at one minute of wall clock, never hours")
    void theFloorIsCapped() {
        assertEquals(60_000L, WallClockTimeout.floorMs(1_000_000));
        assertEquals(60_000L, WallClockTimeout.floorMs(18_000), "the Bedrock batch's own 90 s pacing is not doubled");
        assertTrue(WallClockTimeout.timedOut(1_000_001, 1_000_000, 60_000L));
        assertEquals(2_000L, WallClockTimeout.floorMs(40));
        assertEquals(0L, WallClockTimeout.floorMs(-5));
    }
}
