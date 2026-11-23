package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Gate A for "never put words in the child's mouth".
 *
 * <p>One text box does two jobs on the wish screen — typing a wish, and typing why you pressed 👎 —
 * and seeding it with Moddy's suggested improvement created a collision between them. This is a
 * small rule that is easy to break in a later refactor and impossible to notice in review, so it is
 * pinned here rather than left to memory.
 */
class WishBoxStateTest {

    private static final String SUGGESTION = "make Spikor's fire even bigger";

    @Test
    void aRejectionNeverCarriesModdysOwnSuggestion() {
        // THE bug: press 👎 on the wish Moddy just offered to improve, and its own words would be
        // submitted as the child's complaint about it.
        WishBoxState box = new WishBoxState();
        box.seeded();
        assertTrue(box.holdsModdysWords());
        assertEquals("", box.rejectionReason(SUGGESTION),
                "Moddy's suggestion must never be sent as the child's reason");
    }

    @Test
    void theChildsOwnWordsStillTravelWithARejection() {
        // The feature must survive the fix: complaining in his own words is how a wish gets undone
        // for the right reason.
        WishBoxState box = new WishBoxState();
        assertEquals("it was too scary", box.rejectionReason("it was too scary"));
    }

    @Test
    void typingOverASuggestionMakesItHisAgain() {
        WishBoxState box = new WishBoxState();
        box.seeded();
        box.edited();
        assertFalse(box.holdsModdysWords());
        assertEquals("I want it PURPLE", box.rejectionReason("I want it PURPLE"),
                "once he has typed, the box is his and a rejection should carry it");
    }

    @Test
    void clearingOrSendingLeavesNoOfferStanding() {
        WishBoxState cleared = new WishBoxState();
        cleared.seeded();
        cleared.cleared();
        assertFalse(cleared.holdsModdysWords(), "the ✕ ends the offer");

        WishBoxState sent = new WishBoxState();
        sent.seeded();
        sent.cleared();   // submit() marks it cleared once the wish is away
        assertFalse(sent.holdsModdysWords(), "sending it ends the offer too");
    }

    @Test
    void aFreshBoxHoldsNobodysWordsYet() {
        WishBoxState box = new WishBoxState();
        assertFalse(box.holdsModdysWords());
        assertEquals("", box.rejectionReason(null), "a null box must not throw");
        assertEquals("", box.rejectionReason(""));
    }

    @Test
    void aSecondSuggestionIsStillModdysWords() {
        // Two wishes in a row that both end could_be_better: the newer offer replaces the older one
        // and must be just as protected.
        WishBoxState box = new WishBoxState();
        box.seeded();
        box.edited();
        box.seeded();
        assertEquals("", box.rejectionReason("some newer suggestion"));
    }

    @Test
    void hisFirstKeystrokeReplacesModdysSuggestionRatherThanJoiningIt() {
        // The real one, 13 Aug: a suggestion about Spikor was sitting in the box, he wished for a
        // lighthouse, and what was sent was
        //   "make Spikor's fire thinner so I can see himbuild me a lighthouse..."
        // — one wish made of two sentences with no space, which then came back on his card as his
        // own words.
        var box = new WishBoxState();
        box.seeded();
        assertEquals("", box.beforeTyping("make Spikor's fire thinner so I can see him"),
                "typing over a suggestion must clear it, exactly as the ✕ button does");
    }

    @Test
    void onceItIsHisTextTypingMoreOfItKeepsWhatHeHasWritten() {
        // The obvious other half: this must not eat his own sentence on every keystroke.
        var box = new WishBoxState();
        box.seeded();
        box.edited();
        assertEquals("make my drag", box.beforeTyping("make my drag"));
    }

    @Test
    void anEmptyBoxIsSafeToTypeInto() {
        var box = new WishBoxState();
        assertEquals("", box.beforeTyping(null));
    }
}
