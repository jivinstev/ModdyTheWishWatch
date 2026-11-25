package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** A world's limits change: which way a creature moved, and how hurt it stays. */
class RefitWordsTest {

    @Test
    void aRaiseKeepsHowHurtItWasAndALowerHoldsItToItsNewMost() {
        assertEquals(30.0, RefitWords.healthAfter(20, 40, 60), 1e-9, "half its hearts before, half after");
        assertEquals(60.0, RefitWords.healthAfter(40, 40, 60), 1e-9, "full stays full");
        assertEquals(20.0, RefitWords.healthAfter(20, 60, 40), 1e-9, "a lower keeps what it has when that fits");
        assertEquals(40.0, RefitWords.healthAfter(55, 60, 40), 1e-9, "a lower holds it to its new most");
        assertEquals(20.0, RefitWords.healthAfter(20, 40, 40), 1e-9, "no change, no change");
    }

    @Test
    void anyFallIsHeldDownNoneFallingAndOneRisingIsUnleashed() {
        double n = Double.NaN;
        assertEquals(-1, RefitWords.compare(new double[] {40, 6}, new double[] {60, 4}), "one fell: held down");
        assertEquals(1, RefitWords.compare(new double[] {40, 6, n}, new double[] {60, 6, n}), "one rose");
        assertEquals(0, RefitWords.compare(new double[] {40, n}, new double[] {40, n}), "nothing moved");
    }

    @Test
    void thePlayerIsToldOnceInChildWords() {
        assertEquals(LiveFit.HELD_DOWN, new LiveFit.Refit(3, 1, 0).line());
        assertEquals(LiveFit.UNLEASHED, new LiveFit.Refit(2, 0, 1).line());
        assertEquals("", new LiveFit.Refit(0, 0, 4).line());
    }
}
