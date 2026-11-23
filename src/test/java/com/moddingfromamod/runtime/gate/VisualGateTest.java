package com.moddingfromamod.runtime.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rubric enforces itself, and every BLOCK ships with the test proving it does not punish good
 * work (criterion 4).
 */
class VisualGateTest {

    private static CreatureSculpt.Part box(String n, float x0, float y0, float z0,
                                           float x1, float y1, float z1, int c) {
        return new CreatureSculpt.Part(n, null, new float[]{0, 0, 0},
                new float[]{x0, y0, z0}, new float[]{x1, y1, z1}, c);
    }

    private static List<CreatureSculpt.Part> spikor(int skin) {
        return List.of(box("head", -5, 18, -4, 5, 26, 4, skin),
                box("shell", -8, 8, 1, 8, 20, 7, 0x2E7D32),
                box("body", -6, 8, -4, 6, 20, 2, skin),
                box("armL", -9, 10, -3, -6, 19, 3, skin),
                box("legL", -5, 0, -3, -1, 9, 3, skin),
                box("hair", -4, 26, -2, 4, 29, 2, 0xD62828));
    }

    private static final int TAN = 0xF0B040;
    private static final int GREEN = 0x3FA34D;

    @Test
    @DisplayName("a check cannot declare BLOCK unless it meets every criterion")
    void theRubricIsEnforcedAtConstruction() {
        var e = assertThrows(IllegalArgumentException.class, () ->
                new VisualGate("wishful", VisualGate.Severity.BLOCK, "because I say so",
                        false, true, true, false, true, false));
        assertTrue(e.getMessage().contains("NOT OBJECTIVE"), e.getMessage());
        assertTrue(e.getMessage().contains("NO PROVEN NON-TRIGGER"), e.getMessage());
        assertTrue(e.getMessage().contains("NOT INTENT-AWARE"), e.getMessage());
    }

    @Test
    @DisplayName("every shipped check's declared severity is legal")
    void everyShippedCheckObeysTheRubric() {
        for (VisualCheck c : VisualChecks.all()) {
            VisualGate g = c.gate();
            assertFalse(g.rationale().isBlank(), g.name() + " must say why it exists");
        }
    }

    /**
     * WARN, not BLOCK — and the demotion is the point. This shipped as a block on the strength of
     * "Chucko's face was buried in his hair". Measured against the real sculpts that is false: his
     * head is 72% visible from the front, MORE than the accepted version's 56%. The 20% figure came
     * from my own metric reading the back of the head. With no validated real case, criterion 4
     * fails and the rubric forbids blocking.
     */
    @Test
    @DisplayName("WARN: a fully enclosed part is still worth saying, but may not block")
    void buriedFaceOnlyWarns() {
        var v = VisualChecks.inspect(new VisualCheck.Subject(List.of(
                box("face", -2, 20, -2, 2, 24, 2, 0xFFFFFF),
                box("hair", -5, 18, -5, 5, 27, 5, 0xCC0000)), null, ""));
        assertFalse(v.blocked(),
                "no validated real case exists, so this must not refuse a wish: " + v.blocking());
        assertTrue(v.warnings().toString().contains("face"), v.warnings().toString());
    }

    @Test
    @DisplayName("NON-TRIGGER: a face FRAMED by hair is fine (criterion 4)")
    void framedFaceDoesNotBlock() {
        var v = VisualChecks.inspect(new VisualCheck.Subject(List.of(
                box("face", -3, 20, -5, 3, 26, -3, 0xFFFFFF),
                box("hairL", -6, 20, -3, -3, 27, 3, 0xCC0000),
                box("hairR", 3, 20, -3, 6, 27, 3, 0xCC0000),
                box("hairTop", -6, 26, -3, 6, 29, 3, 0xCC0000)), null, ""));
        assertFalse(v.blocked(), "a visible face must NOT block: " + v.blocking());
    }

    @Test
    @DisplayName("BLOCK: a revision drains the tan while claiming to add fire breath")
    void undeclaredPaletteLossBlocks() {
        var v = VisualChecks.inspect(new VisualCheck.Subject(
                spikor(GREEN), spikor(TAN), "add a fire breath attack every 15 seconds"));
        assertTrue(v.blocked(),
                "the Spikor regression: revisions about fire breath drained his colour and nothing "
                        + "objected. " + v);
        assertTrue(v.blocking().toString().contains("tan/orange"), v.blocking().toString());
    }

    @Test
    @DisplayName("NON-TRIGGER: the SAME change is fine when the child asked for it (criterion 6)")
    void aRequestedRecolourIsObeyedWithoutComplaint() {
        var v = VisualChecks.inspect(new VisualCheck.Subject(
                spikor(GREEN), spikor(TAN), "recolour him all green like the child asked"));
        assertFalse(v.blocked(),
                "\"make Spikor all green\" is obedience, not damage: " + v.blocking());
    }

    @Test
    @DisplayName("NON-TRIGGER: a first sculpt has nothing to regress from")
    void aFirstSculptIsNotARegression() {
        var v = VisualChecks.inspect(new VisualCheck.Subject(spikor(GREEN), null, "make spikor"));
        assertTrue(v.blocking().stream().noneMatch(s -> s.contains("palette loss")),
                "a first build cannot have lost anything: " + v.blocking());
    }

    @Test
    @DisplayName("BLOCK: a zero-thickness part")
    void flatPartBlocks() {
        var v = VisualChecks.inspect(new VisualCheck.Subject(List.of(
                box("body", -4, 0, -4, 4, 12, 4, GREEN),
                box("wing", 4, 6, 0, 4, 10, 4, 0xFFFFFF)), null, ""));
        assertTrue(v.blocked() && v.blocking().toString().contains("wing"), v.blocking().toString());
    }

    /**
     * Criterion 6 for the monochrome check. An invented creature asked for as "a squishy PURPLE
     * four-eyed slug" came out 99% purple. Warning about that was the gate's only false positive on the
     * one creature the child accepted first time.
     */
    @Test
    @DisplayName("NON-TRIGGER: monochrome is silent when the child asked for that colour")
    void monochromeRespectsTheChildsOwnWords() {
        var purpleSlug = List.of(box("body", -5, 0, -5, 5, 10, 5, 0x9B59B6),
                box("eye3", -1, 8, -6, 1, 10, -5, 0xFFFFFF));
        var v = VisualChecks.inspect(new VisualCheck.Subject(purpleSlug, null,
                "invent a new creature called Glorp, a squishy purple four-eyed slug"));
        assertTrue(v.warnings().stream().noneMatch(w -> w.contains("monochrome")),
                "a purple slug being purple is obedience: " + v.warnings());
    }

    @Test
    @DisplayName("WARN not BLOCK: an all-green creature is suspicious, not forbidden")
    void monochromeOnlyWarns() {
        var v = VisualChecks.inspect(new VisualCheck.Subject(spikor(GREEN), null, ""));
        assertFalse(v.blocked(), "a slime is legitimately one colour: " + v.blocking());
        assertTrue(v.warnings().toString().contains("monochrome"), v.warnings().toString());
    }

    @Test
    @DisplayName("the Spikor v1 shape trips nothing")
    void theGoodSpikorIsClean() {
        var v = VisualChecks.inspect(new VisualCheck.Subject(spikor(TAN), null, ""));
        assertEquals(List.of(), v.blocking(), "v1 was the best Spikor and must pass cleanly");
        assertEquals(List.of(), v.warnings(), "…without noise either: " + v.warnings());
    }
}
