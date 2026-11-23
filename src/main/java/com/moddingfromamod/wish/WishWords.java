package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Word-level matching for the offline wish engine. Pure — no Minecraft — so every rule here is
 * unit-tested without a game.
 *
 * <p><b>Why this exists.</b> The engine used to ask {@code hay.contains(term)}, which is a
 * substring test on the raw sentence. One incidental letter-sequence inside a seven-word wish was
 * treated as certainty, and the result was a watch that confidently granted the wrong thing:
 *
 * <pre>
 *   "build me a k<b>night</b>"                 → "make it night and spooky"
 *   "break<b>fast</b> at a big table"          → "make me super fast"
 *   "a <b>cat</b>hedral with stained glass"    → cats
 *   "Spikor's airship <b>fly</b>ing in the sky" → "make me fly"
 * </pre>
 *
 * Nine out of ten realistic phrases misfired against the shipped templates alone. To a child that
 * is worse than doing nothing: he asked for an airship and the watch cheerfully made him fly, so
 * the mod looks like it isn't listening.
 *
 * <p><b>Two rules replace it,</b> and they are deliberately biased towards <i>escalating</i>. A
 * wish that goes to the agent costs a few seconds; a wish granted wrongly costs his trust, and
 * (because the agent caches its answers as templates) can be served wrongly forever after.
 *
 * <ol>
 *   <li><b>Whole words, not substrings.</b> {@code fly} no longer matches "flying" or "butterfly".
 *       Plurals are tolerated in both directions, so {@code chicken} still matches "chickens".</li>
 *   <li><b>Nothing important left unexplained.</b> If the wish contains meaningful words the
 *       template knows nothing about — "spikor", "airship", "lighthouse" — then the wish is about
 *       something else, however well one keyword lines up.</li>
 * </ol>
 *
 * <p>Rule 2 is what separates "i want <b>treasure</b>" from "i want a <b>treasure</b> map to a
 * secret island". Both contain the word; only the first is actually the treasure wish.
 */
public final class WishWords {

    /**
     * Words that carry no intent, so they never count as "unexplained".
     *
     * <p>Generous on purpose. A word wrongly listed here weakens rule 2 slightly; a word wrongly
     * <i>missing</i> sends a perfectly good wish to the agent, which is the safe direction — it
     * still gets granted, just a few seconds later.
     */
    private static final Set<String> FILLER = Set.copyOf(List.of(
            // articles, pronouns, prepositions, conjunctions
            "a", "an", "the", "i", "im", "me", "my", "mine", "we", "us", "our", "you", "your",
            "it", "its", "that", "this", "these", "those", "there", "here", "and", "or", "but",
            "to", "for", "of", "on", "in", "at", "with", "from", "into", "onto", "up", "down",
            "so", "if", "then", "than", "as", "is", "are", "am", "be", "been", "was", "were",
            // asking
            "want", "wants", "wanted", "wish", "wishing", "wished", "like", "need", "needs",
            "please", "pls", "plz", "can", "could", "would", "will", "shall", "may", "let",
            "lets", "gimme", "give", "gives", "get", "gets", "got", "have", "has", "had",
            "do", "does", "did", "go", "going", "hey", "ok", "okay", "now", "again",
            // making
            "make", "makes", "making", "made", "build", "builds", "building", "built",
            "create", "creates", "creating", "created", "summon", "summons", "spawn", "spawns",
            "put", "place", "add", "adds", "show", "see", "look",
            // vague intensifiers a child sprinkles everywhere
            "really", "very", "super", "so", "such", "just", "some", "any", "all", "lots",
            "lot", "more", "most", "much", "many", "cool", "awesome", "amazing", "epic",
            "nice", "good", "great", "best", "thanks", "thank"));

    private WishWords() {
    }

    /** Split a wish into lowercase word tokens. Punctuation and apostrophes are separators. */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }

    /**
     * Fold a word to a comparable stem.
     *
     * <p>Only a trailing plural {@code s} is stripped, and only on words long enough that it is
     * likely a plural rather than part of the word. Deliberately <b>not</b> a real stemmer:
     * stripping {@code -ing} would put "flying" back onto "fly" and re-open the exact bug this
     * class exists to close. Both sides of a comparison are folded, so a stem that is technically
     * wrong ("boxes" → "boxe") still compares consistently — it just fails to match "box", and a
     * missed match escalates, which is safe.
     */
    public static String stem(String word) {
        if (word == null) {
            return "";
        }
        String w = word.toLowerCase(Locale.ROOT);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    /**
     * Does this wish contain the term as a whole word?
     *
     * <p>A term with spaces ("lava beast") must appear as consecutive words.
     */
    public static boolean hasWord(List<String> tokens, String term) {
        List<String> want = tokenize(term);
        if (want.isEmpty() || tokens.isEmpty()) {
            return false;
        }
        if (want.size() == 1) {
            String w = stem(want.get(0));
            for (String t : tokens) {
                if (stem(t).equals(w)) {
                    return true;
                }
            }
            return false;
        }
        for (int i = 0; i + want.size() <= tokens.size(); i++) {
            boolean all = true;
            for (int j = 0; j < want.size(); j++) {
                if (!stem(tokens.get(i + j)).equals(stem(want.get(j)))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /** Fold a template's vocabulary (its terms and canonical phrase) into comparable stems. */
    public static Set<String> vocabulary(List<String>... termLists) {
        Set<String> out = new LinkedHashSet<>();
        for (List<String> terms : termLists) {
            if (terms == null) {
                continue;
            }
            for (String term : terms) {
                for (String w : tokenize(term)) {
                    out.add(stem(w));
                }
            }
        }
        return out;
    }

    /**
     * The meaningful words in this wish that the vocabulary cannot account for.
     *
     * <p>Empty means the template plausibly explains the whole wish. A non-empty list is the signal
     * that the child asked for something else — "spikor", "airship", "lighthouse" — and the wish
     * belongs to the agent, not the cache.
     */
    public static List<String> unexplained(List<String> tokens, Set<String> vocabulary) {
        List<String> out = new ArrayList<>();
        for (String t : tokens) {
            if (t.length() < 3 || FILLER.contains(t)) {
                continue;   // filler carries no intent
            }
            if (t.chars().allMatch(Character::isDigit)) {
                continue;   // "30 chickens" — the number is handled by `countable`
            }
            String s = stem(t);
            if (!vocabulary.contains(s) && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }
}
