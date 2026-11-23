package com.moddingfromamod.seam;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** the stall rule a Gate B watchdog acts on. Pure: the clock is handed in. */
class GameTestStallTest {

    private static final long S = 1_000_000_000L;

    @Test
    void ticksComingIsNoStall() {
        assertNull(GameTestStall.verdict(100 * S, 99 * S, "a:test", 99 * S, 900 * S));
        assertNull(GameTestStall.verdict(1000 * S, 100 * S, null, 0, 900 * S), "exactly at the limit is not past it");
    }

    @Test
    void aStallInsideATestsTickNamesThatTest() {
        String v = GameTestStall.verdict(2000 * S, 1000 * S, "moddingfromamod:islandreclaimgametest.x", 1000 * S, 900 * S);
        assertNotNull(v);
        assertTrue(v.contains("islandreclaimgametest.x"), v);
        assertTrue(v.contains("1000 s"), v);
        assertTrue(v.contains("no timeoutTicks can fire"), v);
    }

    @Test
    void aStallOutsideAnyTestSaysSo() {
        String v = GameTestStall.verdict(2000 * S, 1000 * S, null, 0, 900 * S);
        assertNotNull(v);
        assertTrue(v.contains("outside any test's tick"), v);
    }
}
