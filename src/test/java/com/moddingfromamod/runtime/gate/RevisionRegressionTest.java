package com.moddingfromamod.runtime.gate;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Has the blocking gate ever actually been seen to fire?</b>
 *
 * <p>Across 21 real creatures {@code UndeclaredPaletteLoss} fired <b>zero times</b>. That was read
 * as "criterion 4 satisfied, it does not false-positive" — which is true, and is <i>also</i> exactly
 * what an inert gate looks like. It detects a colour that was significant in one revision and gone
 * in the next, and every creature in that run was a first build, so there was nothing for it to
 * compare against. It could have been deleted entirely and the result would have been identical.
 *
 * <p>These use the proportions of a real sculpt (Spikor, as actually built: 43% green, 27% tan,
 * plus accents) so the thresholds are exercised against something the agent really produces rather
 * than numbers chosen to make the gate look good — the mistake that let a fixture-tuned gate catch
 * 0 of 14 real creatures on its first live run.
 */
class RevisionRegressionTest {

    private static final int GREEN = 0x2E8B22;
    private static final int TAN = 0xD2A15C;
    private static final int RED = 0xC0261E;

    private static CreatureSculpt.Part box(String name, int colour, int size) {
        return new CreatureSculpt.Part(name, null, new float[] {0, 0, 0},
                new float[] {0, 0, 0}, new float[] {size, size, size}, colour);
    }

    /** Roughly the real Spikor: green dominant, tan a real presence, a red accent. */
    private static List<CreatureSculpt.Part> spikor() {
        List<CreatureSculpt.Part> p = new ArrayList<>();
        p.add(box("shell", GREEN, 10));
        p.add(box("head", GREEN, 6));
        p.add(box("belly", TAN, 8));
        p.add(box("muzzle", TAN, 5));
        p.add(box("hair", RED, 5));
        return p;
    }

    /** The same creature with its tan repainted green — the drift, in one step. */
    private static List<CreatureSculpt.Part> spikorGoneGreen() {
        List<CreatureSculpt.Part> p = new ArrayList<>();
        p.add(box("shell", GREEN, 10));
        p.add(box("head", GREEN, 6));
        p.add(box("belly", GREEN, 8));
        p.add(box("muzzle", GREEN, 5));
        p.add(box("hair", RED, 5));
        return p;
    }

    @Test
    @DisplayName("THE GATE FIRES: a colour that mattered is gone, and nobody asked for that")
    void aLostColourIsBlocked() {
        var v = VisualChecks.inspect(new VisualCheck.Subject(
                spikorGoneGreen(), spikor(), "make him BIGGER and give him spikes"));
        assertTrue(v.blocked(),
                "tan went from a real presence to nothing and the wish never mentioned colour — "
                        + "this is the six-revision Spikor drift, and if it does not fire here the "
                        + "gate cannot fire on anything: " + v.warnings());
    }

    @Test
    @DisplayName("CRITERION 6: the same change is fine when the child asked for it")
    void anAskedForChangeIsNotARegression() {
        // Identical geometry, different wish. A gate that cannot tell these apart punishes the
        // child for getting what he requested.
        var v = VisualChecks.inspect(new VisualCheck.Subject(
                spikorGoneGreen(), spikor(), "make Spikor ALL GREEN please, no tan"));
        assertFalse(v.blocked(),
                "the child explicitly asked for the tan to go: " + v.blocking());
    }

    @Test
    @DisplayName("a first build can never be blocked — there is nothing to regress from")
    void firstBuildsAreNeverBlocked() {
        var v = VisualChecks.inspect(new VisualCheck.Subject(spikor(), null, "make SPIKOR"));
        assertFalse(v.blocked(), "no previous revision exists: " + v.blocking());
    }

    @Test
    @DisplayName("a revision that keeps its colours is not blocked")
    void anHonestRevisionPasses() {
        // Grown 20% larger, same palette. The commonest real revision, and it must stay silent or
        // the gate gets switched off.
        List<CreatureSculpt.Part> bigger = new ArrayList<>();
        bigger.add(box("shell", GREEN, 12));
        bigger.add(box("head", GREEN, 7));
        bigger.add(box("belly", TAN, 9));
        bigger.add(box("muzzle", TAN, 6));
        bigger.add(box("hair", RED, 6));
        var v = VisualChecks.inspect(new VisualCheck.Subject(bigger, spikor(), "make him bigger"));
        assertFalse(v.blocked(), "nothing was lost: " + v.blocking());
    }

    @Test
    @DisplayName("losing a mere accent is not worth blocking a child's wish over")
    void tinyAccentsDoNotBlock() {
        List<CreatureSculpt.Part> noHair = new ArrayList<>(spikor());
        noHair.removeIf(p -> p.name().equals("hair"));
        var v = VisualChecks.inspect(new VisualCheck.Subject(noHair, spikor(), "make him bigger"));
        assertFalse(v.blocked(),
                "a small accent disappearing is a warning at most: " + v.blocking());
    }
}
