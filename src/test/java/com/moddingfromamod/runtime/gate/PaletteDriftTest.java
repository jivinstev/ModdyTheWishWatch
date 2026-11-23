package com.moddingfromamod.runtime.gate;

import java.util.List;

import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole-body check that survives grading the edit on the edit.
 *
 * <p>Dropping the whole-body check entirely was the obvious move once {@link DeclaredPalette} was
 * shown to be wrong for a diff — and it would have reinstated the failure that motivated it: six
 * consecutive Spikor revisions drifting in colour with nothing ever saying so. Every individual
 * edit was locally fine. The creature was not.
 */
class PaletteDriftTest {

    private static final int ORANGE = 0xE07020;
    private static final int GREEN = 0x2E8B22;
    private static final int WHITE = 0xF2F2F2;

    private static CreatureSculpt.Part box(String name, int colour, int size) {
        return new CreatureSculpt.Part(name, null, new float[] {0, 0, 0},
                new float[] {0, 0, 0}, new float[] {size, size, size}, colour);
    }

    /** An orange creature with a bit of green — the Snorble, roughly. */
    private static List<CreatureSculpt.Part> orangeThing() {
        return List.of(box("body", ORANGE, 10), box("stripe", GREEN, 4));
    }

    @Test
    @DisplayName("THE case it exists for: an edit repaints the creature and says so")
    void theSpikorDrift() {
        var after = List.of(box("body", WHITE, 10), box("stripe", GREEN, 4));
        String said = PaletteDrift.between(orangeThing(), after, "give him tiny black wings");
        assertTrue(said.contains("orange"), said);
        assertTrue(said.contains("HE DID NOT ASK FOR A COLOUR CHANGE"), said);
    }

    @Test
    @DisplayName("adding wings to an intact body is not drift")
    void aFaithfulEditIsSilent() {
        var after = List.of(box("body", ORANGE, 10), box("stripe", GREEN, 4),
                box("wingL", 0x101010, 2), box("wingR", 0x101010, 2));
        assertTrue(PaletteDrift.between(orangeThing(), after, "give him tiny black wings").isEmpty(),
                "a check that fires on every edit is one the agent learns to skim");
    }

    /**
     * A COLOUR WISH silences the check entirely, and per-colour matching is not enough to do it.
     *
     * <p>"make him white instead" names white, so white is excused — but the orange being replaced
     * is not, and the check fires on the very change he asked for. The headline is "he did not ask
     * for a colour change", so the rule has to be exactly that.
     */
    /**
     * A colour wish softens the HEADLINE; it does not silence the report.
     *
     * <p>The first attempt was a blanket "if the wish names any colour, say nothing" — and it
     * silenced the exact wish this exists for, because <i>"give him tiny black wings"</i> names a
     * colour. The comparison always runs; only the wording changes.
     */
    @Test
    @DisplayName("a colour wish softens the headline but still reports the change")
    void aColourWishSoftensRatherThanSilences() {
        var after = List.of(box("body", WHITE, 10), box("stripe", GREEN, 4));
        String said = PaletteDrift.between(orangeThing(), after, "make him white instead");
        assertFalse(said.isEmpty(), "losing the orange is still worth saying");
        assertFalse(said.contains("HE DID NOT ASK"),
                "he DID ask — claiming otherwise is the tool arguing with the customer: " + said);
        assertTrue(said.contains("also changed"), said);

        // …while a wish about anything else keeps the full flag.
        assertTrue(PaletteDrift.between(orangeThing(), after, "make him taller")
                .contains("HE DID NOT ASK"));
    }

    @Test
    @DisplayName("a creation has nothing to drift from")
    void creationIsExempt() {
        assertTrue(PaletteDrift.between(List.of(), orangeThing(), "make me a Snorble").isEmpty());
        assertTrue(PaletteDrift.between(null, orangeThing(), "make me a Snorble").isEmpty());
    }

    /**
     * It reports and asks. It never instructs.
     *
     * <p>Three mechanisms have now been caught telling the agent to do the wrong thing in an
     * authoritative voice — a phantom reference verdict, a MISSED palette on a two-part diff, and a
     * lesson pointing at a post-hoc mirror. Every one of them was obeyed, and one of them repainted
     * a child's creature. This one may not join them.
     */
    @Test
    @DisplayName("it informs, it does not command")
    void itNeverIssuesAnInstruction() {
        var after = List.of(box("body", WHITE, 10), box("stripe", GREEN, 4));
        String said = PaletteDrift.between(orangeThing(), after, "give him wings");
        for (String order : new String[] {"re-sculpt", "MISSED", "Fix the", "you must"}) {
            assertFalse(said.contains(order),
                    "must not command ('" + order + "'): " + said);
        }
        assertTrue(said.contains("If that was not deliberate"), "it asks: " + said);
    }

    @Test
    @DisplayName("small drift is not worth a child's attention")
    void smallDriftIsIgnored() {
        // Repainting one small part is how a creature stays alive across edits.
        var after = List.of(box("body", ORANGE, 10), box("stripe", WHITE, 4));
        String said = PaletteDrift.between(orangeThing(), after, "give him wings");
        assertTrue(said.isEmpty() || !said.contains("orange"),
                "the body did not move; only a small part did: " + said);
    }

    @Test
    @DisplayName("bucket names are matched by the words a child types")
    void bucketsMatchPlainWords() {
        assertTrue(PaletteDrift.mentioned("make him orange", "tan/orange"));
        assertTrue(PaletteDrift.mentioned("a purple tail", "purple/pink"));
        assertFalse(PaletteDrift.mentioned("make him taller", "tan/orange"));
    }

    /**
     * Naming a colour does not make a wish a repaint — what it does to the creature does.
     *
     * <p>Both earlier cuts of this rule were wrong: a blanket "names a colour" silenced the very
     * wish that destroyed a Snorble, and per-colour matching alone flagged a requested repaint as
     * drift. Black arrives as a 4% pair of wings; white arrives as 71% of the creature.
     */
    @Test
    @DisplayName("a repaint is a colour he named that then took the creature over")
    void repaintIsAboutShareNotVocabulary() {
        var before = java.util.Map.of("tan/orange", 0.71, "green", 0.29);
        var wingsAfter = java.util.Map.of("tan/orange", 0.68, "green", 0.28, "black", 0.04);
        assertFalse(PaletteDrift.repaintRequested(before, wingsAfter, "give him tiny black wings"),
                "black is a 4% pair of wings, not a repaint");

        var whiteAfter = java.util.Map.of("white", 0.71, "green", 0.29);
        assertTrue(PaletteDrift.repaintRequested(before, whiteAfter, "make him white instead"),
                "white took the creature over: that is a repaint");
        assertTrue(PaletteDrift.repaintRequested(before, whiteAfter, "change his colour"),
                "…and saying it outright counts too");
    }
}
