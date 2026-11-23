package com.moddingfromamod.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Sunrise and sunset are EDGES of the day clock, counted once however the clock got there. */
class RuleClockTest {

    @Test
    void sunriseIsCrossedOnceAMorning() {
        assertTrue(RuleClock.crossed(23_990, 24_010, RuleClock.SUNRISE));
        assertFalse(RuleClock.crossed(24_000, 24_001, RuleClock.SUNRISE),
                "the tick after sunrise is not another sunrise");
        assertFalse(RuleClock.crossed(100, 200, RuleClock.SUNRISE));
    }

    @Test
    void sunsetLandsExactlyOnItsMark() {
        assertTrue(RuleClock.crossed(11_999, 12_000, RuleClock.SUNSET));
        assertTrue(RuleClock.crossed(36_000 - 1, 36_000, RuleClock.SUNSET), "on day two as well");
        assertFalse(RuleClock.crossed(12_000, 12_500, RuleClock.SUNSET));
    }

    @Test
    void aJumpOverTheMarkCountsAndAJumpBackDoesNot() {
        assertTrue(RuleClock.crossed(5_000, 30_000, RuleClock.SUNSET), "time add past it");
        assertTrue(RuleClock.crossed(1_000, 100_000, RuleClock.SUNRISE), "a whole day or more");
        assertFalse(RuleClock.crossed(13_000, 1_000, RuleClock.SUNSET),
                "time set to earlier is the child's own command, not a sunset");
        assertFalse(RuleClock.crossed(-1, 12_000, RuleClock.SUNSET), "not primed yet");
    }

    @Test
    void everyNSecondsIsDueOncePerPeriod() {
        int hits = 0;
        for (long t = 1; t <= 200; t++) {
            if (RuleClock.due(t, 5)) {
                hits++;
            }
        }
        assertTrue(hits == 2, "5 s over 200 ticks is twice, got " + hits);
    }
}
