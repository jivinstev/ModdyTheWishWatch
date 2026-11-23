package com.moddingfromamod.wish.reveal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The feel of a served wish's fanfare, decided here rather than in play. */
class FanfareTimingTest {

    @Test
    void theWholeMomentIsAboutFourSeconds() {
        // The owner's budget: the slowest reveal (a creature's summon, climax at 44) after the full
        // rise lands its creature within five seconds of the tap, and never before the reveal.
        for (Reveals.Look look : Reveals.Look.values()) {
            int total = FanfareTiming.rise(false) + FanfareTiming.climax(look);
            assertTrue(total <= 5 * 20, look + " takes " + total + " ticks from tap to arrival");
        }
        assertTrue(FanfareTiming.rise(false) >= 3 * 20, "a full rise shorter than 3 s reads as a flicker");
    }

    @Test
    void aSecondTapIsQuick() {
        assertTrue(FanfareTiming.rise(true) <= 20, "the quick pillar is under a second");
        assertTrue(FanfareTiming.quick(true, -1), "another on its way: quick");
        assertTrue(FanfareTiming.quick(false, 10), "the last revealed half a second ago: quick");
        assertFalse(FanfareTiming.quick(false, FanfareTiming.QUICK_WINDOW), "long enough ago: full");
        assertFalse(FanfareTiming.quick(false, -1), "the first tap: full");
    }

    @Test
    void theHelixClimbsInThreeBeatsAndIsWholeAtTheReveal() {
        float last = -1;
        int changes = 0;
        for (int age = 0; age <= FanfareTiming.FULL_RISE; age++) {
            float f = FanfareTiming.fraction(age, false);
            assertTrue(f >= last, "the helix never shrinks");
            if (f != last) {
                changes++;
            }
            last = f;
        }
        assertEquals(1f, last, "whole by the reveal");
        assertEquals(4, changes, "0, a third, two thirds, whole");
        assertEquals(1f, FanfareTiming.fraction(FanfareTiming.QUICK_RISE, true));
    }

    @Test
    void theThingArrivesAtTheClimaxNotBefore() {
        // Each climax sits inside its reveal: after the start and no later than its flash.
        assertTrue(FanfareTiming.climax(Reveals.Look.CREATURE) >= 30 && FanfareTiming.climax(Reveals.Look.CREATURE) <= 50);
        assertTrue(FanfareTiming.climax(Reveals.Look.EVENT) <= 24);
        // The frame flashes at Frame.TICKS - 6 = 20; the picture appears in the flash, not after it.
        assertTrue(FanfareTiming.climax(Reveals.Look.PICTURE) >= 14 && FanfareTiming.climax(Reveals.Look.PICTURE) <= 20);
        assertEquals(0, FanfareTiming.climax(null), "a building goes up at once: the whirlwind is its moment");
    }

    @Test
    void everythingLandsInFrontOfHimAndABuildingFurther() {
        for (Reveals.Look look : Reveals.Look.values()) {
            assertTrue(FanfareTiming.forward(look) >= 3 && FanfareTiming.forward(look) <= 8, look.name());
        }
        assertEquals(12, FanfareTiming.forward(null), "where wishbuild has always placed a building");
    }
}
