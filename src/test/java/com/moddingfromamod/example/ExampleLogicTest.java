package com.moddingfromamod.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * <b>Gate A — pure JUnit.</b> Runs with {@code ./gradlew test}: no Minecraft boot, milliseconds.
 *
 * <p>Because {@link ExampleLogic} imports nothing from Minecraft, we can hammer its edge cases
 * directly. Aim your tests at <i>properties</i> that must always hold (bounds, monotonicity,
 * determinism, conservation) rather than one hand-picked value — property-style assertions catch
 * far more real bugs than a single example does.
 */
class ExampleLogicTest {

    @Test
    void weakerMobsComeInBiggerPacks() {
        // Same budget, weaker mob -> strictly more of them (the whole point of the rule).
        int strong = ExampleLogic.packSize(50, 100, 99);
        int weak = ExampleLogic.packSize(5, 100, 99);
        assertTrue(weak > strong, "weak=" + weak + " should exceed strong=" + strong);
    }

    @Test
    void alwaysAtLeastOneAndNeverOverTheCap() {
        // A pack of zero would silently spawn nothing; over the cap would lag the server.
        for (double strength = 0.1; strength < 500; strength *= 1.7) {
            for (double budget = 0; budget < 5000; budget = budget * 2 + 1) {
                int n = ExampleLogic.packSize(strength, budget, 12);
                assertTrue(n >= 1 && n <= 12,
                        "out of bounds: n=" + n + " (strength=" + strength + ", budget=" + budget + ")");
            }
        }
    }

    @Test
    void handlesNonsenseInputSafely() {
        // Bad data shouldn't throw or return absurd values — it should degrade to something sane.
        assertEquals(1, ExampleLogic.packSize(0, 0, 5), "zero strength + zero budget -> 1");
        assertEquals(1, ExampleLogic.packSize(-3, -3, 5), "negative input -> 1");
        assertEquals(1, ExampleLogic.packSize(10, 100, 0), "a cap below 1 is clamped to 1");
    }

    @Test
    void isDeterministic() {
        // Same inputs must always give the same answer (no hidden randomness/state).
        assertEquals(ExampleLogic.packSize(7, 90, 20), ExampleLogic.packSize(7, 90, 20));
    }
}
