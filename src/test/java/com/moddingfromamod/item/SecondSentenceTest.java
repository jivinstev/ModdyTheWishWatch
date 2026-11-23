package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gate A item 3 — <i>"a refusal that asks, when nothing recent resolves, because inventing
 * what A was is worse than asking which one he means."</i>
 *
 * <p>Half of these are the cases it must NOT fire on. A check that fires on the normal case is one
 * the agent learns to skip, and the real finding is skipped with it (P6) — and here "the normal
 * case" includes his very first item, which is exactly the moment a wrong refusal would be most
 * confusing.
 */
class SecondSentenceTest {

    private static SecondSentence.Verdict v(boolean refers, boolean start, boolean scratch, int n) {
        return SecondSentence.of(refers, start, scratch, n);
    }

    @Test
    @DisplayName("the wish it exists for: refers back, inherits nothing, and he owns things")
    void theSecondSentenceIsRefused() {
        assertEquals(SecondSentence.Verdict.ASK, v(true, false, false, 3));
    }

    @Test
    @DisplayName("inheriting something is the whole point — never refused")
    void aStartFromCallIsFine() {
        assertEquals(SecondSentence.Verdict.FINE, v(true, true, false, 3));
    }

    @Test
    @DisplayName("a wish that points at nothing is an ordinary new item")
    void aFirstSentenceIsFine() {
        assertEquals(SecondSentence.Verdict.FINE, v(false, false, false, 3));
    }

    /**
     * The case that reads like the opposite of the rule and is the most important to get right.
     * "Nothing recent resolves" sounds like the moment to ask — but with no items at all there is
     * nothing to ask ABOUT, and this is his FIRST item. Refusing here would be a question with no
     * answers in it.
     */
    @Test
    @DisplayName("an empty store is left alone, even for a wish that starts with 'and'")
    void hisFirstItemIsNeverRefused() {
        assertEquals(SecondSentence.Verdict.FINE, v(true, false, false, 0));
        assertEquals(SecondSentence.Verdict.FINE, v(true, false, false, -1));
    }

    @Test
    @DisplayName("the agent can say it checked — and that is a claim, not a bypass")
    void fromScratchIsTheWayOut() {
        assertEquals(SecondSentence.Verdict.FINE, v(true, false, true, 3));
    }

    @Test
    @DisplayName("the refusal names what he might have meant, not just the problem")
    void theRefusalIsActionable() {
        String r = SecondSentence.refusal("and one that shoves the noob in lava",
                List.of("real_pearl", "zap_wand"));
        assertTrue(r.contains("real_pearl") && r.contains("zap_wand"),
                "an agent cannot pick from a message that lists nothing: " + r);
        assertTrue(r.contains("and one that shoves the noob in lava"),
                "it quotes his own words back, so the agent can check the reading: " + r);
    }

    @Test
    @DisplayName("all three routes are named, and they are one word apart in the call")
    void bothOutcomesAreSpelledOut() {
        String r = SecondSentence.refusal("and one but blue", List.of("a"));
        assertTrue(r.contains("as_new:true"), "ANOTHER one — he ends up with both: " + r);
        assertTrue(r.contains("start_from:\"<id>\" alone"), "a CHANGE — he ends up with one: " + r);
        assertTrue(r.contains("from_scratch:true"), "genuinely unrelated: " + r);
        assertTrue(r.contains("He ends up with both") && r.contains("He ends up with one"),
                "the difference is stated in terms of what the CHILD is left holding, which is "
                        + "#311's own gate — items in his inventory afterwards: " + r);
    }

    /**
     * P8 — check what the remedy leaves you with. Following the escape must not read as the
     * cheapest way past the guard, because that is precisely the defect found in
     * {@code refuseFinish}: a message that advertised its own bypass, taken six seconds later.
     */
    @Test
    @DisplayName("the escape is worded as a claim to be audited, not as a retry")
    void theEscapeIsNotABypass() {
        String r = SecondSentence.refusal("and one", List.of("a"));
        assertTrue(r.contains("Only if you have actually checked"),
                "the escape has to carry its condition: " + r);
        assertFalse(r.contains("call again and it will go through"),
                "no advertised bypass: " + r);
    }

    // ─── the grammar, which is NOT RefersBack's ───────────────────────────────────────────

    /**
     * <b>The four sentences lists, one per wish class.</b> Borrowing {@code RefersBack} for
     * these was my first cut and it never fired once: its vocabulary is {@code again, too, also,
     * instead, that one, the one, same, still, more, this} — every one a word for CHANGING the
     * thing you point at, which is an earlier issue's question and the opposite of this one.
     */
    @Test
    @DisplayName("the sibling sentences from all four wish classes are recognised")
    void theSiblingGrammarIsRecognised() {
        for (String wish : List.of(
                "and one that shoves the noob in LAVA",
                "make another one but blue",
                "build another tower like that on the other side",
                "a second track that goes the other way",
                "can i have one more but red",
                "and another that freezes them")) {
            assertTrue(SecondSentence.looksLikeASibling(wish), "should read as a sibling: " + wish);
        }
    }

    /**
     * <b>The negatives matter more than the positives.</b> A refusal that fires on an ordinary new
     * wish is one the agent learns to route around, and it takes the true findings with it (P6).
     * Every line here is a wish a child really might type that must sail straight through.
     */
    @Test
    @DisplayName("an ordinary new wish is never read as a sibling")
    void ordinaryWishesAreLeftAlone() {
        for (String wish : List.of(
                "make me a wand that shoots fire",
                "give me a dragon that breathes ice",
                "build a castle with a moat and a drawbridge",
                "i want a sword",
                "make it hit harder",
                "a pearl that puts the noob in lava",
                "make the tower taller")) {
            assertFalse(SecondSentence.looksLikeASibling(wish), "must NOT be a sibling: " + wish);
        }
    }

    @Test
    @DisplayName("nothing to read is not a sibling")
    void emptyIsNotASibling() {
        assertFalse(SecondSentence.looksLikeASibling(null));
        assertFalse(SecondSentence.looksLikeASibling("  "));
    }

    /**
     * The word-boundary rule, which is why the match normalises punctuation first. Without it
     * "another" is found inside "anotherworldly" and every wish containing it refers back — the
     * same class of bug {@code RefersBack.containsWord} exists for ("it" matching "with", "a bit",
     * "white") and {@code MeatFilter}'s whole-word rule ("cod" matching "cocoa_beans").
     */
    @Test
    @DisplayName("a phrase inside a longer word does not count")
    void wholeWordsOnly() {
        assertFalse(SecondSentence.looksLikeASibling("make me an anotherworldly gate"));
        assertTrue(SecondSentence.looksLikeASibling("make me another, but bigger!"),
                "punctuation must not hide a real match");
    }
}
