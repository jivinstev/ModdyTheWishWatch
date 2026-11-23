package com.moddingfromamod.film;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The modded client's play clock for the smooth film camera: smooth, locked to the server, never backwards. */
class FilmClockTest {

    /** Frames at ~58 fps over {@code ticks} client ticks, with the game time the client sees each tick. */
    private static double[] frames(FilmClock c, long gameTime0, int ticks, java.util.function.LongUnaryOperator gameTimeAt) {
        int perTick = 3;
        double[] out = new double[ticks * perTick];
        int n = 0;
        for (int t = 0; t < ticks; t++) {
            c.tick(gameTimeAt.applyAsLong(gameTime0 + t));
            for (int f = 0; f < perTick; f++) {
                out[n++] = c.at(f / (double) perTick + 0.1);
            }
        }
        return out;
    }

    @Test
    void onASteadyClockEveryFrameAdvancesTheSameAmount() {
        FilmClock c = new FilmClock(1000);
        double[] at = frames(c, 1000, 100, g -> g);
        for (int i = 1; i < at.length; i++) {
            assertEquals(1 / 3.0, at[i] - at[i - 1], 1e-9, "frame " + i);
        }
        assertEquals(0.1, at[0], 1e-9, "play tick 0 plus the frame's partial tick");
        assertEquals(0, c.snaps());
    }

    @Test
    void aOneTickWobbleInTheServersTimeIsAbsorbedWithoutAStepOrAReverse() {
        FilmClock c = new FilmClock(0);
        // the server's time packet lands a tick late every second, then is put right a tick later
        double[] at = frames(c, 0, 200, g -> g % 20 == 5 ? g - 1 : g);
        for (int i = 1; i < at.length; i++) {
            double d = at[i] - at[i - 1];
            assertTrue(d >= 0, "never backwards, frame " + i + ": " + d);
            assertTrue(Math.abs(d - 1 / 3.0) < 0.06, "no visible step, frame " + i + ": " + d);
        }
        assertEquals(0, c.snaps());
    }

    @Test
    void itFollowsASlowerServerWithinAFewTicks() {
        FilmClock c = new FilmClock(0);
        // the server runs at 19 ticks a second for 10 s: the game time falls behind the client's ticks
        long[] g = {0};
        int[] n = {0};
        frames(c, 0, 200, x -> {
            if (n[0]++ % 20 != 19) {
                g[0]++;
            }
            return g[0];
        });
        // at(0) is held at the last frame's moment (never backwards), so allow that frame's partial tick
        double err = c.at(0) - g[0];
        assertTrue(Math.abs(err) < 2.5, "locked to the server's play ticks: " + err);
        assertEquals(0, c.snaps(), "a 5% slow server is followed, not snapped to");
    }

    @Test
    void aBigDisagreementSnaps() {
        FilmClock c = new FilmClock(0);
        assertFalse(c.started());
        c.tick(10);
        assertTrue(c.started());
        assertEquals(10, c.at(0), 1e-9);
        c.tick(210);   // a freeze, a sleep: 199 ticks off
        assertEquals(210, c.at(0), 1e-9);
        assertEquals(1, c.snaps());
        c.tick(5);   // and backwards too, when the server says so (a re-play under the same start)
        assertEquals(5, c.at(0), 1e-9);
    }

    @Test
    void aServerStallItCatchesUpOnIsRiddenOutWithoutASnapOrAStep() {
        // T7-c: a 0.69 s tick (14 ticks with no game time), then the server runs ticks back to back to catch up
        FilmClock c = new FilmClock(0);
        long[] g = {0};
        int[] n = {0};
        double[] at = frames(c, 0, 120, x -> {
            int k = n[0]++;
            if (k < 40) {
                g[0]++;
            } else if (k < 54) {
                // stalled: no game time
            } else if (k < 68) {
                g[0] += 2;
            } else {
                g[0]++;
            }
            return g[0];
        });
        for (int i = 1; i < at.length; i++) {
            double d = at[i] - at[i - 1];
            assertTrue(d >= 0, "never backwards, frame " + i + ": " + d);
            assertTrue(Math.abs(d - 1 / 3.0) < 0.065, "no visible step (the same bound as a wobble), frame " + i + ": " + d);
        }
        assertEquals(0, c.snaps(), "a stall is not a snap");
    }

    @Test
    void beforePlayTickZeroItIsZero() {
        FilmClock c = new FilmClock(100);
        assertEquals(0, c.at(0.5), 0);
        c.tick(98);
        assertEquals(0, c.at(0.5), 0);
    }
}
