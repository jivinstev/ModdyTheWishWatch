package com.moddingfromamod.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What "make it bigger" is allowed to mean. */
class CreatureGrowthTest {

    /** A shark built 32 units tall — the real case. At scale 1.375 it stood 2.75 blocks. */
    private static final float SHARK = 32f;

    private static double blocks(float units, double scale) {
        return Framing.heightInBlocks(units, scale);
    }

    @Test
    @DisplayName("THE BUG: a shark cannot go from 2.75 blocks to 12 in one step")
    void theRecordedRunaway() {
        // Observed in a live two-wish run: "make it bigger and cooler" took tralalero from 2.75 to
        // 12.00 blocks, with the part count unchanged at 37 -- so nothing was added, the scale was
        // simply multiplied by four.
        var d = CreatureGrowth.decide(SHARK, 6.0, 1.375);
        assertTrue(d.capped());
        assertTrue(blocks(SHARK, d.scale()) < 5.0,
                "12 blocks is a building; got " + blocks(SHARK, d.scale()));
        assertEquals(1.375 * CreatureGrowth.MAX_STEP, d.scale(), 0.01);
    }

    @Test
    @DisplayName("but bigger still means BIGGER — a step is not a refusal")
    void aStepIsStillAStep() {
        var d = CreatureGrowth.decide(SHARK, 6.0, 1.375);
        assertTrue(d.scale() > 1.375, "capping must never shrink the creature");
        // ...and asking twice gets there, so "enormous" is one more sentence, not impossible.
        var again = CreatureGrowth.decide(SHARK, 6.0, d.scale());
        assertTrue(again.scale() > d.scale());
    }

    @Test
    @DisplayName("a modest 'a bit bigger' is left completely alone")
    void aReasonableGrowthIsUntouched() {
        var d = CreatureGrowth.decide(SHARK, 1.8, 1.375);
        assertFalse(d.capped(), "1.3x is exactly what a child means by bigger: " + d.because());
        assertEquals(1.8, d.scale(), 0.001);
        assertEquals("", d.because());
    }

    @Test
    @DisplayName("the absolute ceiling holds however many polite steps it took")
    void stepsCannotStackPastTheCeiling() {
        // Three 1.6x steps from 2.75 blocks reach 11.3 -- each one individually allowed. Without
        // the absolute ceiling the step cap only makes the runaway take three wishes instead of one.
        double scale = 1.375;
        for (int i = 0; i < 6; i++) {
            scale = CreatureGrowth.decide(SHARK, scale * 1.6, scale).scale();
        }
        assertTrue(blocks(SHARK, scale) <= CreatureGrowth.MAX_HEIGHT_BLOCKS + 0.01,
                "ended up " + blocks(SHARK, scale) + " blocks tall");
    }

    @Test
    @DisplayName("a first build is NOT step-capped — a boss may be born big")
    void aFirstBuildHasNothingToStepFrom() {
        // previousScale 0 means "no previous". Applying the step cap here would mean a creature
        // meant to be large has to be wished for twice, which is worse than the bug.
        var d = CreatureGrowth.decide(SHARK, 3.5, 0);
        assertEquals(3.5, d.scale(), 0.001);
        assertFalse(d.capped());
        assertTrue(blocks(SHARK, d.scale()) > 6.0, "a GINORMOUS boss is still ginormous");
    }

    @Test
    @DisplayName("but even a first build cannot be a building")
    void theCeilingAppliesToFirstBuildsToo() {
        var d = CreatureGrowth.decide(SHARK, 8.0, 0);
        assertTrue(d.capped());
        assertEquals(CreatureGrowth.MAX_HEIGHT_BLOCKS, blocks(SHARK, d.scale()), 0.05);
    }

    @Test
    @DisplayName("the ceiling is measured in BLOCKS, not in the scale number")
    void aTallModelAtScaleOneIsStillTooTall() {
        // The trap this exists to avoid: scale 1.0 sounds harmless, but a creature built 200 units
        // tall is 12.5 blocks at scale 1.0. Capping the multiplier instead of the height would
        // wave this straight through.
        var d = CreatureGrowth.decide(200f, 1.0, 0);
        assertTrue(d.capped(), "200 units at scale 1.0 is 12.5 blocks");
        assertTrue(blocks(200f, d.scale()) <= CreatureGrowth.MAX_HEIGHT_BLOCKS + 0.01);
    }

    @Test
    @DisplayName("a creature is never shrunk into a speck")
    void theFloorHolds() {
        var d = CreatureGrowth.decide(SHARK, 0.05, 0);
        assertTrue(blocks(SHARK, d.scale()) >= CreatureGrowth.MIN_HEIGHT_BLOCKS - 0.01);
        assertTrue(d.capped());
    }

    @Test
    @DisplayName("the agent is TOLD, and told to add detail rather than only stretch")
    void theReasonIsUsable() {
        // A silently clamped size reads to the agent as its instruction having no effect, and the
        // usual response to that is a bigger number.
        String why = CreatureGrowth.decide(SHARK, 6.0, 1.375).because();
        assertFalse(why.isBlank());
        assertTrue(why.toLowerCase().contains("add"), why);
    }

    @Test
    @DisplayName("nonsense input cannot produce a nonsense creature")
    void degenerateInput() {
        assertTrue(CreatureGrowth.decide(0f, 1.0, 0).scale() > 0, "a creature with no geometry");
        assertTrue(CreatureGrowth.decide(SHARK, 0, 0).scale() > 0, "scale zero");
        assertTrue(CreatureGrowth.decide(SHARK, -5, 0).scale() > 0, "negative scale");
    }
}
