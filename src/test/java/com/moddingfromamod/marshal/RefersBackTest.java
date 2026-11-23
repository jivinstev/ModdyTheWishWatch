package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule that decides whether affinity can bind at all.
 *
 * <p>Every case below is a rung from the refinement ladders or a wish from the corpus, because the failure this fixes was not hypothetical: on the first ladder run every
 * routing decision came back {@code anyone}, so arm A and arm B were the same experiment.
 */
class RefersBackTest {

    // ================= the refinements affinity exists for =================

    @Test
    void theRefinementsAChildActuallyMakesAllReferBack() {
        for (String wish : new String[] {
                "give him white gloves too",
                "make him a bit taller",
                "make him much stronger so he can win a fight",
                "call him SUPER SPEEDY",
                "he is too slow, speed him up a bit",
                "give him blue shoes",
                "make it bigger",
                "make her fly",
                "do that again",
                "give them more hearts",
                "make his fire even bigger"}) {
            assertTrue(RefersBack.yes(wish), "must refer back: " + wish);
        }
    }

    // ================= creations, which must NOT =================

    /**
     * "me" and "my" are deliberately not backward-looking. Every ladder opens with a wish that
     * mentions the child, and treating those as refinements would attach every new thing to the
     * previous one — which is the false positive that matters, because it is the one that happens
     * on every single ladder.
     */
    @Test
    void aCreationThatMentionsTheChildIsNotARefinement() {
        for (String wish : new String[] {
                "make a blue hedgehog mob standing next to me",
                "make a mad scientist mob standing next to me",
                "make me a castle",
                "build me a big machine that grinds trees",
                "I want a dragon"}) {
            assertFalse(RefersBack.yes(wish), "must NOT refer back: " + wish);
        }
    }

    // ================= whole words, or the rule discriminates nothing =================

    /**
     * "it" is a substring of an enormous number of ordinary words. Without whole-word matching
     * essentially every wish refers back and the rule is a constant — the same class of bug the
     * meat filter's whole-word rule exists for, where {@code cod} matched {@code cocoa_beans}.
     */
    @Test
    void aSubstringIsNotAPronoun() {
        for (String wish : new String[] {
                "make a white castle",
                "build a bit of a wall",
                "make a knight with a sword",
                "a hospital with a big waiting room",
                "make a rabbit"}) {
            assertFalse(RefersBack.yes(wish), "substring match leaked: " + wish);
        }
    }

    // ================= referring back without a pronoun =================

    @Test
    void theWordsAChildUsesInsteadOfAPronounCountToo() {
        assertTrue(RefersBack.yes("give him wings too"));
        assertTrue(RefersBack.yes("make it red again"));
        assertTrue(RefersBack.yes("more hearts please"));
        assertTrue(RefersBack.yes("do the same but blue"));
    }

    @Test
    void nothingAtAllRefersToNothing() {
        assertFalse(RefersBack.yes(null));
        assertFalse(RefersBack.yes(""));
        assertFalse(RefersBack.yes("   "));
    }

    /**
     * Precision over recall, stated as a test so the trade-off is a decision rather than an
     * accident. A missed refinement routes as a creation — today's behaviour, costing nothing. A
     * false positive sends a genuinely new thing to the last artifact's owner, which is a wrong
     * preference with a fallback behind it.
     */
    @Test
    void everyLadderFixtureOpensWithACreationAndContinuesWithRefinements() {
        // Mirrors corpus/context-ladders.json. If a fixture rung stops referring back, affinity
        // stops binding on it and that ladder silently measures nothing.
        // Rung 1's classification is deliberately NOT asserted, and the reason is worth writing
        // down rather than papering over with assertTrue(x || !x), which is what stood here first
        // and is not a test. The real opening rungs DO contain "he" ("...he is blue and runs super
        // fast"), so this rule calls them refinements — a description read as a reference. It does
        // not matter: in a fresh world nothing has been finished yet, so the resolver answers ""
        // either way and both arms let rung 1 through. The rule only has to be right from rung 2.
        for (String r : new String[] {
                "give him white gloves too", "make him a bit taller",
                "give him black boots", "make him really tough, he should be hard to beat",
                "give him blue shoes", "make him faster", "give him more hearts"}) {
            assertTrue(RefersBack.yes(r), "every refining rung must bind: " + r);
        }
    }

    @Test
    @DisplayName("\"this castle\" points at something already there")
    void thisIsDeictic() {
        // Measured on the buildings matrix: this exact rung routed `anyone` in all six
        // cells, so one rung of every cell was the same experiment in both arms.
        assertTrue(RefersBack.yes("but there's no doorway at all now in this castle!!!"));
        assertTrue(RefersBack.yes("make this one bigger"));
    }

    @Test
    @DisplayName("\"that\" and \"there\" are NOT added, and here is why")
    void theNearMissesStayOut() {
        // "that" is a relative pronoun far more often than a deictic, and this is rung 1 of the
        // floating_castle fixture — a brand new wish. Adding it would bind the CREATION rung, which
        // is worse than leaving a refinement unbound: it would claim an experiment ran on a wish
        // that had nothing to refer to.
        assertFalse(RefersBack.yes("build a tower that reaches the clouds"));
        assertFalse(RefersBack.yes("put a castle over there"));
    }
}
