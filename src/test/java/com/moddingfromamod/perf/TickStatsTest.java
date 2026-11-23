package com.moddingfromamod.perf;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TickStatsTest {

    @Test
    void aHitchEverySecondShowsAsTicksOverBudget() {
        TickStats s = new TickStats(100);
        for (int i = 0; i < 100; i++) {
            assertEquals(i < 99, s.add(i % 20 == 0 ? 120.0 : 4.0));
        }
        assertEquals(5, s.over());
        String line = s.summary();
        assertTrue(line.contains("max 120.0") && line.contains("5 over 50 ms"), line);
    }

    @Test
    void aSmoothMinuteHasNoneOver() {
        TickStats s = new TickStats(10);
        for (int i = 0; i < 10; i++) {
            s.add(3.0);
        }
        assertEquals(0, s.over());
        assertTrue(s.summary().startsWith("mean 3.0 ms, p95 3.0, max 3.0"), s.summary());
        s.reset();
        assertEquals(0, s.count());
        assertFalse(s.summary().contains("mean"));
    }
}
