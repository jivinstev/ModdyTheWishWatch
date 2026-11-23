package com.moddingfromamod.reference;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceVerdictTest {

    private static final int GREEN = 0x2E8B22;
    private static final int TAN = 0xD2A15C;
    private static final int RED = 0xC0261E;

    private static CreatureSculpt.Part box(int colour, int size) {
        return new CreatureSculpt.Part("p" + colour + size, null, new float[] {0, 0, 0},
                new float[] {0, 0, 0}, new float[] {size, size, size}, colour);
    }

    /** A reference at a given confidence — the axis the Snorble failure turned on. */
    private static ReferenceSpec refAt(ReferenceSpec.Confidence conf, Object... pairs) {
        List<ReferenceSpec.ColourShare> cs = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            cs.add(new ReferenceSpec.ColourShare((String) pairs[i], (Integer) pairs[i + 1]));
        }
        return new ReferenceSpec(cs, List.of(), ReferenceSpec.Size.LARGE, conf, List.of());
    }

    // ══════════ the Snorble failure: a phantom reference for an invented creature ══════════

    /**
     * <b>The measured failure.</b> Steve invented a Snorble and asked for "bright orange with green
     * stripes and one big purple eye". look_up_character answered — for a thing that does not
     * exist — at confidence LOW with white 58 / purple 18 / black 14 / cyan 10. This class threw
     * the confidence away and reported "I checked a real picture of Snorble and I got this one
     * wrong (not enough white, not enough purple, too much orange-brown, too much green)".
     *
     * <p>Two of three cross-worker runs then repainted the creature to white 55 / purple 17 /
     * black 18 / cyan 10 — within a few points of the phantom. They were obeying, not forgetting.
     */
    @Test
    @DisplayName("a LOW-confidence reference never contradicts the child")
    void lowConfidenceIsNotAPhotograph() {
        var v = ReferenceVerdict.of(List.of(box(TAN, 8), box(GREEN, 4)),
                refAt(ReferenceSpec.Confidence.LOW, "white", 58, "purple/pink", 18), "Snorble");
        assertEquals(ReferenceVerdict.Grade.UNKNOWN, v.grade(),
                "an invented creature has no real picture, so there is nothing to be wrong about");
        assertFalse(v.worthMentioning());
        assertTrue(v.followUpWish().isEmpty(),
                "and it must not seed a 'fix Snorble' wish that would destroy him");
        assertFalse(v.childFriendly().contains("real picture"),
                "claiming a real picture of an invented creature is the whole bug");
    }

    @Test
    @DisplayName("a MEDIUM-confidence reference raises a doubt, never a verdict")
    void mediumWondersAloud() {
        var v = ReferenceVerdict.of(List.of(box(GREEN, 8)),
                refAt(ReferenceSpec.Confidence.MEDIUM, "white", 60, "purple/pink", 30), "Chip");
        // Measured over 139 lookups: MEDIUM is usually the RIGHT character under a misspelt name
        // ("Tralalelo Tralalala" vs the HIGH "Tralalero Tralala"), so it must not be discarded —
        // but it must not command either.
        assertTrue(v.childFriendly().contains("might be thinking of a different character"),
                "got: " + v.childFriendly());
        assertTrue(v.followUpWish().isEmpty(),
                "a doubt the agent can weigh is useful; an instruction it will obey is not");
    }

    @Test
    @DisplayName("a colour the child asked for is never reported as a defect")
    void theChildOutranksTheReference() {
        // He said "bright orange with green stripes". Even at HIGH confidence, the reference does
        // not get to call that wrong.
        var v = ReferenceVerdict.of(List.of(box(TAN, 8), box(GREEN, 4)),
                refAt(ReferenceSpec.Confidence.HIGH, "white", 58, "purple/pink", 18), "Snorble",
                "make a Snorble mob, he is bright orange with green stripes and one purple eye");
        assertFalse(String.join(",", v.misses()).contains("orange"),
                "he asked for orange: " + v.misses());
        assertFalse(String.join(",", v.misses()).contains("green"),
                "he asked for green stripes: " + v.misses());
    }

    @Test
    @DisplayName("…but a colour he never mentioned is still reported")
    void theRuleIsNotABlanketAmnesty() {
        var v = ReferenceVerdict.of(List.of(box(TAN, 8), box(GREEN, 4)),
                refAt(ReferenceSpec.Confidence.HIGH, "white", 58), "Snorble",
                "make him a bit taller");
        assertTrue(String.join(",", v.misses()).contains("white"),
                "nothing in that wish mentions white: " + v.misses());
    }

    @Test
    @DisplayName("bucket names are matched by the words a child actually types")
    void bucketsAreMatchedByPlainWords() {
        // He writes "orange", never "tan/orange"; "purple", never "purple/pink".
        assertTrue(ReferenceVerdict.childNamed("he is bright orange", "tan/orange"));
        assertTrue(ReferenceVerdict.childNamed("one big purple eye", "purple/pink"));
        assertTrue(ReferenceVerdict.childNamed("a pink bow", "purple/pink"));
        assertFalse(ReferenceVerdict.childNamed("make him taller", "tan/orange"));
        assertFalse(ReferenceVerdict.childNamed("", "green"));
    }

    /** HIGH is Spikor and Zippy — measured, 95 of 139 lookups — and the check earns its place. */
    @Test
    @DisplayName("HIGH confidence still catches the six-revision Spikor drift")
    void highConfidenceIsUnchanged() {
        var v = ReferenceVerdict.of(List.of(box(GREEN, 10), box(TAN, 3)),
                refAt(ReferenceSpec.Confidence.HIGH, "green", 38, "tan/orange", 34), "Spikor");
        assertTrue(v.worthMentioning(), "an all-green Spikor must still be caught");
        assertTrue(v.childFriendly().contains("looked up what Spikor really looks like"));
    }

    @Test
    @DisplayName("#360: it never claims a picture, because no picture is ever fetched")
    void itDoesNotClaimAGroundingItDoesNotHave() {
        // ReferenceResearcher launches with --allowed-tools WebSearch,WebFetch. Two TEXT tools.
        // No image is fetched and the cached artifact carries wiki URLs and zero images, so every
        // grade below is reached from prose and the model's own recall.
        //
        // This ran to the CHILD, on all three grades — the sentence opened 24 of 24 sculpt_creature
        // results in the action log — and to an agent that has no way to disbelieve it. It is the
        // Snorble failure one layer out: gating LOW confidence stopped a phantom SPEAKING, and left
        // the wording asserting a picture that does not exist even when the colours are right.
        for (var v : List.of(
                ReferenceVerdict.of(List.of(box(GREEN, 10), box(TAN, 3)),
                        refAt(ReferenceSpec.Confidence.HIGH, "green", 38, "tan/orange", 34), "Spikor"),
                ReferenceVerdict.of(List.of(box(GREEN, 13)),
                        refAt(ReferenceSpec.Confidence.HIGH, "green", 38, "tan/orange", 34), "Spikor"))) {
            assertFalse(v.childFriendly().contains("real picture"),
                    "#360: the researcher has no image access, so this sentence is false — and it "
                    + "is written to be read aloud to a child. Say what it actually did. If a "
                    + "vision pass lands (#361) this becomes true and should be changed back "
                    + "deliberately, not by a wording tidy-up.");
        }
    }

    private static ReferenceSpec ref(Object... pairs) {
        List<ReferenceSpec.ColourShare> cs = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            cs.add(new ReferenceSpec.ColourShare((String) pairs[i], (Integer) pairs[i + 1]));
        }
        return new ReferenceSpec(cs, List.of(), ReferenceSpec.Size.LARGE,
                ReferenceSpec.Confidence.HIGH, List.of());
    }

    @Test
    @DisplayName("a faithful build is ABOUT RIGHT and the child is told nothing")
    void faithful() {
        // Roughly the real Spikor: sourced reference says green 38 / tan 34.
        var v = ReferenceVerdict.of(List.of(box(GREEN, 8), box(TAN, 8)),
                ref("green", 45, "tan/orange", 45), "Spikor");
        assertEquals(ReferenceVerdict.Grade.ABOUT_RIGHT, v.grade(), v.childFriendly());
        assertFalse(v.worthMentioning(), "do not nag a child about a creature that is fine");
        assertTrue(v.followUpWish().isEmpty());
    }

    @Test
    @DisplayName("THE case this exists for: all-green Spikor is caught as wrong")
    void theSixRevisionDrift() {
        // 77% green / 7% tan was a real shipped Spikor, and nothing in the system noticed.
        var v = ReferenceVerdict.of(List.of(box(GREEN, 10), box(TAN, 4)),
                ref("green", 38, "tan/orange", 34), "Spikor");
        assertTrue(v.grade() != ReferenceVerdict.Grade.ABOUT_RIGHT, v.childFriendly());
        assertTrue(v.misses().stream().anyMatch(m -> m.contains("orange-brown")),
                "should say the tan is missing: " + v.misses());
        assertFalse(v.followUpWish().isBlank(), "should propose the next wish");
    }

    @Test
    @DisplayName("a completely wrong creature says so, and offers to try again")
    void completelyWrong() {
        var v = ReferenceVerdict.of(List.of(box(RED, 10)),
                ref("green", 50, "tan/orange", 40), "Spikor");
        assertEquals(ReferenceVerdict.Grade.COMPLETELY_WRONG, v.grade(), v.childFriendly());
        assertTrue(v.childFriendly().contains("try again"), v.childFriendly());
    }

    @Test
    @DisplayName("no reference means no verdict — never a guess dressed as a fact")
    void noReference() {
        var v = ReferenceVerdict.of(List.of(box(GREEN, 8)), ReferenceSpec.none(), "Zizzle");
        assertEquals(ReferenceVerdict.Grade.UNKNOWN, v.grade());
        assertFalse(v.worthMentioning());
        assertTrue(v.childFriendly().contains("best guess"), v.childFriendly());
    }

    @Test
    @DisplayName("what a child is told contains no jargon and no numbers")
    void childFacingLanguage() {
        var v = ReferenceVerdict.of(List.of(box(GREEN, 10), box(TAN, 2)),
                ref("green", 38, "tan/orange", 34), "Spikor");
        String s = v.childFriendly();
        assertFalse(s.contains("tan/orange"), "bucket names are for reports: " + s);
        assertFalse(s.matches(".*\\d.*"), "no numbers to a seven-year-old: " + s);
        assertTrue(s.contains("Spikor"), s);
    }
}
