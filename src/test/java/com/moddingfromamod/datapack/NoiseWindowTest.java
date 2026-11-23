package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** a drone is turned down to one a second; a roar now and then never is. */
class NoiseWindowTest {

    @Test
    void aSoundOnEveryTickIsCappedToOneASecondAfterHalfTheWindow() {
        NoiseWindow w = new NoiseWindow();
        int allowed = 0;
        for (long t = 0; t < NoiseWindow.ON_AT - 1; t++) {
            assertTrue(w.emit(t), "not throttled yet at " + t);
        }
        assertFalse(w.throttled());
        for (long t = NoiseWindow.ON_AT - 1; t < NoiseWindow.ON_AT - 1 + 200; t++) {
            if (w.emit(t)) {
                allowed++;
            }
        }
        assertTrue(w.throttled());
        assertEquals(200 / NoiseWindow.ONE_EVERY, allowed, "one per " + NoiseWindow.ONE_EVERY + " ticks");
    }

    @Test
    void aRoarEveryFiveSecondsAndABurstOnLandingAreNeverTouched() {
        NoiseWindow w = new NoiseWindow();
        for (long t = 0; t < 20 * 600; t += 100) {
            assertTrue(w.emit(t));
        }
        for (long t = 0; t < 20 * 600; t += 7) {        // a busy 3-in-20 landing rhythm
            assertTrue(w.emit(20 * 600 + t));
        }
        assertFalse(w.throttled());
    }

    @Test
    void aPackThatCalmsDownIsLetGo() {
        NoiseWindow w = new NoiseWindow();
        long t = 0;
        for (; t < 400; t++) {
            w.emit(t);
        }
        assertTrue(w.throttled());
        for (; t < 400 + 1200; t += 100) {               // now a sound every 5 s
            w.emit(t);
        }
        assertFalse(w.throttled(), "it kept being capped after it went quiet");
    }
}
