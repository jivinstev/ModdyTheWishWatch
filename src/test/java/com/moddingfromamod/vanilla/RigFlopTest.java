package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Vanilla's death curve — the SHAPE of the fall, which is what a hand-rolled ease gets wrong. */
class RigFlopTest {

    @Test
    @DisplayName("upright before death, flat by the end")
    void itStartsUprightAndEndsFlat() {
        assertEquals(0f, RigFlop.degrees(0, 0f), 1e-4, "a live creature is upright");
        assertEquals(RigFlop.FLIP_DEGREES, RigFlop.degrees(RigFlop.DEATH_TICKS, 0f), 1e-4);
    }

    @Test
    @DisplayName("it never overshoots, and never goes backwards")
    void itIsMonotonicAndClamped() {
        float last = -1;
        for (int t = 0; t <= RigFlop.DEATH_TICKS + 10; t++) {
            float d = RigFlop.degrees(t, 0f);
            assertTrue(d >= last, "the body must not rise again at deathTime " + t);
            assertTrue(d <= RigFlop.FLIP_DEGREES + 1e-4,
                    "the clamp must hold at deathTime " + t + " (got " + d + ")");
            last = d;
        }
    }

    @Test
    @DisplayName("FAST at the start and SLOW at the end — a body giving way, not a door closing")
    void theCurveIsSqrtNotLinear() {
        // Halfway through the FALL (not through the animation), a sqrt curve is well past halfway
        // over. A linear ease would be at exactly 50%, and reads as a puppet being lowered.
        int flat = RigFlop.flatAt();
        float half = RigFlop.degrees(flat / 2, 0f) / RigFlop.FLIP_DEGREES;
        assertTrue(half > 0.62f,
                "at half the fall the body should already be past 62% over, was "
                        + Math.round(half * 100) + "%");
        // And the first tick alone is a visible lurch, not a creep.
        assertTrue(RigFlop.degrees(1, 0f) < 1f,
                "the very first tick is still essentially upright (vanilla's -1 offset)");
        assertTrue(RigFlop.degrees(2, 0f) > 8f,
                "by the second tick it is already committed, was " + RigFlop.degrees(2, 0f));
    }

    @Test
    @DisplayName("it is FLAT well before the body is removed — the last third is stillness")
    void itIsFlatBeforeItDespawns() {
        int flat = RigFlop.flatAt();
        assertTrue(flat < RigFlop.DEATH_TICKS,
                "the 1.6 rate exists so the body comes to rest before it vanishes; flat at "
                        + flat + " of " + RigFlop.DEATH_TICKS);
        assertEquals(14, flat, "vanilla's numbers put it flat at deathTime 14");
    }

    // ------------------------------------------------------------------ the fall as a client DRAWS it

    /**
     * A client easing one display into each flop it is sent: from where it draws the body, linearly, over the ease
     * (Display's TransformationInterpolator). Writes happen on server ticks; {@code late} delays chosen ones by a tick.
     * Returns the drawn angle every 1/10 tick from the first death tick, for 30 ticks. An unchanged pose is not sent
     * (DisplayRig.apply), so it does not restart the ease.
     */
    private static float[] drawn(int phase, int interval, int ease, boolean aimAhead, java.util.Set<Integer> late) {
        float[] out = new float[300];
        float cur = 0f;
        float from = 0f;
        float target = 0f;
        float start = -1;
        java.util.TreeMap<Integer, Float> arrivals = new java.util.TreeMap<>();
        float sent = Float.NaN;
        for (int d = 1; d <= RigFlop.DEATH_TICKS; d++) {
            long gameTime = d + phase;
            if (d == 1 || RigAnimator.writesOn(gameTime, interval)) {
                float a = aimAhead ? RigFlop.aimed(d, ease) : RigFlop.degrees(d, 0f);
                if (a == sent) {
                    continue;
                }
                sent = a;
                int at = d + RigCadence.TRACKER_LAG + (late.contains(d) ? 1 : 0);
                arrivals.merge(at, a, (x, y) -> y);
            }
        }
        for (int i = 0; i < out.length; i++) {
            int tick = i / 10;
            float p = (i % 10) / 10f;
            if (i % 10 == 0 && arrivals.containsKey(tick)) {
                from = cur;
                target = arrivals.get(tick);
                start = tick;
            }
            if (start >= 0) {
                cur = from + (target - from) * Math.min(1f, (tick - start + p) / ease);
            }
            out[i] = cur;
        }
        return out;
    }

    /** Longest run (in ticks) the drawn body stood still before it was flat. */
    private static float longestStop(float[] a) {
        int run = 0;
        int worst = 0;
        for (int i = 1; i < a.length && a[i - 1] < RigFlop.FLIP_DEGREES - 0.5f; i++) {
            if (a[i] - a[i - 1] < 1e-4f && a[i] > 0) {
                worst = Math.max(worst, ++run);
            } else {
                run = 0;
            }
        }
        return worst / 10f;
    }

    @Test
    @DisplayName("#2676: the shipped flop keeps falling when a packet is a tick late, and the old one-tick ease did not")
    void aLatePacketDoesNotStopTheFall() {
        int ease = RigAnimator.ease(VanillaRigAnimator.INTERVAL);
        for (int phase = 0; phase < VanillaRigAnimator.INTERVAL; phase++) {
            for (int lateTick = 1; lateTick < 14; lateTick++) {
                float[] a = drawn(phase, VanillaRigAnimator.INTERVAL, ease, true, java.util.Set.of(lateTick));
                // one sample (a tenth of a tick) at a restart is the client's own anchor: it restarts from the
                // last frame it drew. A stop longer than that is a gap with no ease running.
                assertTrue(longestStop(a) <= 0.1f, "the fall stopped for " + longestStop(a) + " ticks (phase " + phase
                        + ", write at deathTime " + lateTick + " a tick late)");
            }
        }
        // the control, which must fire: written every tick with a one-tick ease (the death film's driver)
        float[] old = drawn(0, 1, 1, false, java.util.Set.of(5));
        assertTrue(longestStop(old) >= 0.9f, "the control did not stop, so this check is blind: " + longestStop(old));
    }

    @Test
    @DisplayName("#2676: the shipped flop is drawn close to vanilla's curve, not an ease behind it")
    void theDrawnFallFollowsVanilla() {
        int ease = RigAnimator.ease(VanillaRigAnimator.INTERVAL);
        for (int phase = 0; phase < VanillaRigAnimator.INTERVAL; phase++) {
            float[] a = drawn(phase, VanillaRigAnimator.INTERVAL, ease, true, java.util.Set.of());
            float worst = 0f;
            for (int i = 0; i < a.length; i++) {
                // vanilla's client draws deathTime d at its tick d + the tracker's tick, at partial p
                float v = RigFlop.degrees(i / 10 - RigCadence.TRACKER_LAG, (i % 10) / 10f);
                worst = Math.max(worst, Math.abs(a[i] - v));
            }
            // measured: 12.7-14.2 degrees on the three beat phases, against 28.4 for the old every-tick flop
            assertTrue(worst <= 20f, "the drawn fall strays " + worst + " degrees from vanilla's (phase " + phase + ")");
            assertEquals(RigFlop.FLIP_DEGREES, a[a.length - 1], 0.01f, "it ends flat");
            // not aimed ahead it lags a whole ease: the aim is what keeps it near vanilla
            float[] behind = drawn(phase, VanillaRigAnimator.INTERVAL, ease, false, java.util.Set.of());
            float worstBehind = 0f;
            for (int i = 0; i < behind.length; i++) {
                worstBehind = Math.max(worstBehind,
                        Math.abs(behind[i] - RigFlop.degrees(i / 10 - RigCadence.TRACKER_LAG, (i % 10) / 10f)));
            }
            assertTrue(worst < worstBehind, "aiming ahead must draw it nearer vanilla: " + worst + " vs " + worstBehind);
        }
    }
}
