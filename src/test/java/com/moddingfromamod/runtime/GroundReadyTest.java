package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GroundReadyTest {

    @Test
    void theBenchVolcanosReachIsThirtyTwoSections() {
        // reach [-22,109,-18 → 27,121,31] from the bench server's log: sections x -2..1, z -2..1, and
        // y 109..121 crosses 96..111 and 112..127 — two rows
        var s = GroundReady.sections(-22, 109, -18, 27, 121, 31, -64, 320);
        assertEquals(4 * 2 * 4, s.size());
        for (int[] c : s) {
            assertEquals(8, Math.floorMod(c[0], 16), "a centre, not a corner");
        }
    }

    @Test
    void negativeCoordinatesLandInTheirOwnSection() {
        var s = GroundReady.sections(-1, 0, -1, -1, 0, -1, -64, 320);
        assertEquals(1, s.size());
        assertEquals(-8, s.get(0)[0]);
        assertEquals(8, s.get(0)[1]);
        assertEquals(-8, s.get(0)[2]);
    }

    @Test
    void theWorldsFloorAndCeilingClampIt() {
        assertTrue(GroundReady.sections(0, 400, 0, 10, 500, 10, -64, 320).isEmpty());
        var s = GroundReady.sections(0, -200, 0, 0, -50, 0, -64, 320);
        assertEquals(1, s.size(), "-64..-50 is one section");
    }

    @Test
    void aHugeBoxIsSampledNotWalkedWhole() {
        var s = GroundReady.sections(-2000, -64, -2000, 2000, 319, 2000, -64, 320);
        assertTrue(s.size() <= GroundReady.MAX_SECTIONS * 8, "sampled: " + s.size());
        assertFalse(s.isEmpty());
    }

    @Test
    void outsideTheRenderDistanceIsNotWaitedFor() {
        int[] near = {8, 64, 8};
        int[] far = {16 * 20 + 8, 64, 8};
        assertTrue(GroundReady.inRange(near, -43, 52, 8));
        assertFalse(GroundReady.inRange(far, -43, 52, 8));
    }
}
