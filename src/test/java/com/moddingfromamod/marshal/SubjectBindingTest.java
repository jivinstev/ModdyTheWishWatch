package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * calibrated on the sentences that actually failed, not on fixtures written to pass.
 *
 * <p>The five in {@link #THE_SENTENCES_THAT_FAILED} are quoted from the issue: every refinement
 * rung that did not bind across the buildings fixtures, at 11/16 against 16/16 for creatures, 8/8
 * for items, 12/12 for effects and 4/4 for datapacks. They are all one shape — the child names a
 * PART or a POSITION rather than the thing.
 */
class SubjectBindingTest {

    /** Verbatim. Not paraphrased: rewriting them is how a number stops describing anything. */
    private static final List<String> THE_SENTENCES_THAT_FAILED = List.of(
            "put a giant diamond spire on the very top",
            "put a flag on the very top",
            "put a big glass floor at the top so you can look down",
            "make the lighthouse light actually glow",
            "put a big flag on the very top");

    /** Sentences that are CREATIONS and must never bind, however recent the last build. */
    private static final List<String> CREATIONS = List.of(
            "make a castle that floats on the water",
            "put a castle over there",
            "build me a giant lighthouse");

    @Test
    @DisplayName("the five that failed now bind, and are marked RECENT rather than referred")
    void thePartNamingRefinementsBind() {
        for (String text : THE_SENTENCES_THAT_FAILED) {
            // None of them contains a referring word — that is the whole defect.
            assertFalse(RefersBack.yes(text),
                    "'" + text + "' now reads as a refer-back, so this fixture no longer "
                    + "reproduces #184 and the rest of this test proves nothing");
            var b = SubjectBinding.decide("", false, "sky_castle", 30_000L);
            assertTrue(b.bound(), "'" + text + "' still does not bind");
            assertEquals(SubjectBinding.How.RECENT, b.how(),
                    "it must be marked RECENT. Counting it as REFERRED is the inflation this "
                    + "class exists to prevent — it would report that the grammar rule improved");
            assertFalse(b.fromTheWords(),
                    "a recency binding is a convenience, not evidence that production routing "
                    + "works, and the honest denominator has to exclude it");
        }
    }

    @Test
    @DisplayName("a named artifact always wins, and is reported as NAMED")
    void namingItBeatsEverything() {
        var b = SubjectBinding.decide("spikor_castle", false, "sky_castle", 1_000L);
        assertEquals("spikor_castle", b.subjectId(),
                "he said which one; recency must never override his own words");
        assertEquals(SubjectBinding.How.NAMED, b.how());
        assertTrue(b.fromTheWords());
        // Guard 3 in the issue's list — "never when the wish names a DIFFERENT artifact" — is this
        // same rule, which is why it needs no separate branch.
        assertEquals("spikor_castle",
                SubjectBinding.decide("spikor_castle", true, "sky_castle", 1_000L).subjectId());
    }

    @Test
    @DisplayName("a referring word binds with no window at all")
    void aReferBackIsNotWindowLimited() {
        // Unchanged behaviour, deliberately: this is what already gave creatures 16/16, and
        // "make it taller" an hour later still means the thing he was talking about. Putting the
        // new window in front of it would REGRESS four classes to fix one.
        var old = SubjectBinding.decide("", true, "sky_castle", 10 * WishLadder.STICKY_MS);
        assertEquals("sky_castle", old.subjectId(),
                "the window was applied to a refer-back, which breaks the four classes that work");
        assertEquals(SubjectBinding.How.REFERRED, old.how());
        assertTrue(old.fromTheWords());
    }

    @Test
    @DisplayName("outside the window, a part-naming wish does not bind")
    void theWindowIsWhatMakesThisSafe() {
        // The only thing standing between this and binding every new wish that happens to follow a
        // finished one.: in production a mis-bind only chooses which worker takes the wish
        // (low harm), but in a MEASUREMENT it inflates the binding rate.
        assertFalse(SubjectBinding.decide("", false, "sky_castle",
                WishLadder.STICKY_MS + 1).bound(),
                "a wish minutes later bound to the last build");
        assertTrue(SubjectBinding.decide("", false, "sky_castle",
                WishLadder.STICKY_MS).bound(), "the boundary itself should still bind");
        assertEquals(WishLadder.STICKY_MS, 90_000L,
                "the window is WishLadder's existing stickiness, reused rather than a second "
                + "number that can drift from it — and #184's own example is 'ninety seconds ago'");
    }

    @Test
    @DisplayName("nothing behind it never binds — that is rung 1 of a ladder")
    void guardFourIsTheFirstWish() {
        // The issue's fourth guard: "it must not bind rung 1 of a ladder, which is a creation with
        // nothing behind it." In production that is simply "no last artifact".
        for (String recent : new String[]{null, "", "   "}) {
            assertFalse(SubjectBinding.decide("", false, recent, 0L).bound(),
                    "bound with nothing to bind to");
            assertFalse(SubjectBinding.decide("", true, recent, 0L).bound(),
                    "a referring word with nothing to refer to must not invent a subject");
            assertEquals(SubjectBinding.How.NONE,
                    SubjectBinding.decide("", true, recent, 0L).how());
        }
        // A missing age is refused by the window rather than allowed through it — the safe
        // direction, since the alternative is an unbounded window wearing a bug.
        assertFalse(SubjectBinding.decide("", false, "sky_castle", -1L).bound(),
                "an unmeasurable age was treated as recent");
    }

    @Test
    @DisplayName("a creation still routes nowhere, which is the thing widening the words would break")
    void aCreationMustNeverBind() {
        // Binding a CREATION rung is worse than leaving a refinement unbound: it claims an
        // experiment ran on a wish that had nothing to refer to. These are the exact sentences
        // gives as the reason "that" and "there" cannot be added to the word list.
        for (String text : CREATIONS) {
            assertFalse(RefersBack.yes(text),
                    "'" + text + "' is a CREATION and now reads as a refer-back — widening the "
                    + "word list is exactly what #184 rules out, and this is the sentence it "
                    + "names");
        }
        // ...and the honest limit, stated rather than hidden: with a recent build in hand, recency
        // CANNOT tell "put a flag on the very top" from "put a castle over there". Both name no
        // artifact and neither refers back. That is the mis-bind predicts, it is why the
        // binding is reported as RECENT, and it is why fromTheWords() exists.
        var misbound = SubjectBinding.decide("", false, "sky_castle", 1_000L);
        assertTrue(misbound.bound() && misbound.how() == SubjectBinding.How.RECENT,
                "the known false positive must be visible as RECENT rather than silently counted");
        assertFalse(misbound.fromTheWords(),
                "if a mis-bind ever counted as fromTheWords, the measurement would improve while "
                + "production routing got no better — the exact flattering error #184 warns about");
    }
}
