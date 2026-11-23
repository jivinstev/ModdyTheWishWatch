package com.moddingfromamod.marshal;

import java.util.Locale;
import java.util.Set;

/**
 * <b>Is this wish about something that already exists?</b> — the question affinity routing cannot
 * work without, answered without a model.
 *
 * <h2>Why this exists at all</h2>
 * {@link WishAffinity} routes on <i>which artifact a wish is about</i>, and the only identity
 * available before a worker starts was rung 0: does the wish NAME something we hold notes on. On the
 * first real ladder run every routing decision came back {@code artifactId=""} and
 * {@code routing=anyone} — so <b>arm A and arm B were identical and the experiment measured
 * nothing.</b>
 *
 * <p>The reason is not a bug in rung 0, it is rung 0 working as documented: it resolves 21% of
 * referring wishes, and the 79% it misses are exactly these —
 *
 * <pre>
 *   "give him white gloves too"        "make him a bit taller"
 *   "he is too slow, speed him up"     "call him SUPER ZIPPY"
 * </pre>
 *
 * A child refining a thing does not re-name it. He says <i>him</i>. So the wishes affinity is FOR
 * are precisely the wishes rung 0 cannot see, which is a neat way to build a control that can never
 * bind.
 *
 * <h2>The rule, and why it is this one</h2>
 * A third-person pronoun or demonstrative with nothing new attached is the cheapest
 * high-precision signal there is: it needs no model, no world, and no session memory — and
 * <b>{@code WishResolver}'s stickiness map, which would have been the obvious answer, is never
 * written to by any production code path.</b>
 *
 * <p>Deliberately biased toward <b>precision over recall</b>. A missed refinement routes as a
 * creation, which is today's behaviour and costs nothing; a false positive sends a genuinely new
 * thing to the last artifact's owner. Even that is only a preference with a fallback, and affinity
 * defaults to OFF, so nothing a child sees depends on this being right.
 *
 * <p>Pure — no game types — so the whole rule is a unit test.
 */
public final class RefersBack {

    /**
     * Words that point at a thing already in the conversation.
     *
     * <p><b>"me" and "my" are deliberately absent.</b> "make a Zippy mob standing next to me" is a
     * creation that mentions the child, and treating it as a refinement would attach every new
     * thing to the previous one.
     */
    private static final Set<String> BACKWARD = Set.of(
            "him", "her", "it", "them", "his", "hers", "its", "their", "theirs", "he", "she",
            "they");

    /**
     * Phrases that refer back without a pronoun.
     *
     * <p>"again", "too" and "also" are the ones a seven-year-old actually reaches for — "make it
     * red again", "give him wings too" — and each is meaningless without a previous thing.
     */
    private static final Set<String> BACKWARD_PHRASES = Set.of(
            "again", "too", "also", "instead", "that one", "the one", "same", "still", "more",
            // "this castle", "this one", "this thing" — pointing at something already in front of
            // him. Measured on the buildings matrix: "but there's no doorway at all now in
            // THIS CASTLE!!!" routed `anyone` in all six cells, so a whole rung of every cell was
            // the same experiment in both arms.
            //
            // "this" ONLY. Not "that", which is a relative pronoun far more often than a deictic —
            // "make a castle THAT floats on the water" is a brand new wish and is rung 1 of that
            // very fixture. Not "there", which is mostly positional: "put a castle over there".
            "this");

    private RefersBack() {
    }

    /** True when this wish is talking about something that already exists. */
    public static boolean yes(String wishText) {
        if (wishText == null || wishText.isBlank()) {
            return false;
        }
        String text = wishText.toLowerCase(Locale.ROOT);
        for (String phrase : BACKWARD_PHRASES) {
            if (phrase.contains(" ") ? text.contains(phrase) : containsWord(text, phrase)) {
                return true;
            }
        }
        for (String word : BACKWARD) {
            if (containsWord(text, word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whole words only.
     *
     * <p>Without this "it" matches "with", "a bit", "white" and "waiting", so essentially every
     * wish refers back and the rule stops discriminating anything. The same class of bug the
     * MeatFilter's whole-word rule exists for — {@code cod} matching {@code cocoa_beans}.
     */
    private static boolean containsWord(String haystack, String word) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            boolean leftOk = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            int end = at + word.length();
            boolean rightOk = end >= haystack.length()
                    || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }
}
