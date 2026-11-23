package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Aliases for the things he already owns, recovered from what he already said.
 *
 * <p>Artifacts made before aliases existed need a one-off backfill, and the material is already on
 * disk: every manifest carries the child's original {@code intent} and its {@code refinements[]} —
 * the re-wish history, in his own words. Spikor's four contain <i>"SPIKOR king of the kroaks"</i> and
 * <i>"SPIKOR KING of the KROAKS"</i>, which is exactly the alias list rung 0 wants. Free.
 *
 * <p><b>What it deliberately does not do.</b> It never asks a model what a thing is called. That is
 * the recorded trap — <i>never let a model's own knowledge gate access to knowledge it lacks</i> —
 * and it fails precisely on the unfamiliar names ("the wobbly one", Steve's own inventions) that the
 * backfill exists for. This reads structure instead: the words he used next to the name.
 *
 * <p>Pure, so the extraction is table-driven and testable against the real manifests.
 */
public final class AliasBackfill {

    /**
     * Words that are never a name on their own. Deliberately short: over-filtering loses "kroak",
     * under-filtering costs nothing, because an alias that never matches anything is inert while a
     * missing one sends a resolvable wish up the ladder.
     */
    private static final Set<String> NOISE = Set.of(
            "a", "an", "and", "the", "of", "to", "for", "with", "his", "her", "its", "my", "your",
            "make", "makes", "made", "making", "create", "creates", "build", "builds", "summon",
            "give", "gives", "add", "adds", "put", "can", "you", "please", "again", "back", "sure",
            "let", "lets", "we", "should", "also", "new", "here", "it", "that", "this", "him",
            "them", "they", "he", "she", "is", "are", "be", "have", "has", "from", "out", "up",
            "bigger", "smaller", "really", "very", "super", "so", "much", "more", "even", "just");

    /**
     * Words that turn what follows the name into a <b>place or a circumstance</b>, not another name.
     *
     * <p>This set is a bug fix, and the bug is the reason the offline replay was worth running.
     * "I'd like you to build a giant airplane <b>in the sky</b>" produced the alias
     * <i>"in the sky"</i> for the aeroplane — which then confidently resolved <i>"there's a cool
     * castle in the sky, can you add a lighthouse on top of it?"</i> to the aeroplane. A false
     * positive at rung 0 is worse than a miss: a miss costs a rung, and this costs the child watching
     * the wrong thing change while being told the right name.
     *
     * <p>An appositive is a noun phrase abutting the name ("spikor king of the kroaks"). Anything
     * introduced by one of these is describing where it is, and is never what he calls it.
     */
    private static final Set<String> NOT_A_NAME_AFTER = Set.of(
            "in", "on", "at", "over", "under", "near", "by", "into", "onto", "above", "below",
            "beside", "behind", "inside", "outside", "around", "from", "through", "across",
            "against", "toward", "towards", "here", "there", "please", "thanks", "now", "then",
            "so", "because", "but", "or", "if", "when", "while", "who", "which");

    /** How many aliases one artifact may collect. Bounded so a chatty history can't dilute matching. */
    public static final int MAX_ALIASES = 8;

    private AliasBackfill() {
    }

    /**
     * Derive the names an artifact answers to from its own recorded history.
     *
     * @param id          the artifact id ("spikor")
     * @param intent      the child's original words
     * @param refinements every re-wish about it, in his words
     */
    public static List<String> from(String id, String intent, List<String> refinements) {
        Set<String> out = new LinkedHashSet<>();
        String normalisedId = KidTyping.normalise(id);
        List<String> sources = new ArrayList<>();
        if (intent != null) {
            sources.add(intent);
        }
        if (refinements != null) {
            sources.addAll(refinements);
        }

        for (String source : sources) {
            for (String phrase : shoutedPhrases(source)) {
                if (!phrase.isEmpty() && !phrase.equals(normalisedId)) {
                    out.add(phrase);
                }
            }
            for (String phrase : aroundTheName(source, normalisedId)) {
                if (!phrase.isEmpty() && !phrase.equals(normalisedId)) {
                    out.add(phrase);
                }
            }
        }

        List<String> list = new ArrayList<>(out);
        return list.size() > MAX_ALIASES ? list.subList(0, MAX_ALIASES) : list;
    }

    /**
     * Runs of SHOUTED words. A seven-year-old capitalises the thing he cares about — "bring me
     * SPIKOR!" — so shouting is a surprisingly reliable name signal in this corpus, and
     * it needs no model at all.
     */
    static List<String> shoutedPhrases(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        StringBuilder run = new StringBuilder();
        for (String raw : text.split("[^A-Za-z0-9]+")) {
            boolean shouted = raw.length() > 1 && raw.equals(raw.toUpperCase(Locale.ROOT))
                    && raw.chars().anyMatch(Character::isLetter);
            if (shouted && !NOISE.contains(raw.toLowerCase(Locale.ROOT))) {
                if (run.length() > 0) {
                    run.append(' ');
                }
                run.append(raw.toLowerCase(Locale.ROOT));
            } else {
                if (run.length() > 0) {
                    out.add(run.toString());
                    run.setLength(0);
                }
            }
        }
        if (run.length() > 0) {
            out.add(run.toString());
        }
        return out;
    }

    /**
     * The words immediately after the name — "spikor <b>king of the kroaks</b>" — as an appositive.
     *
     * <p>Kept whole rather than split into words: "kroaks" alone would match anything kroak-shaped,
     * and a phrase is both safer and closer to what he would actually say.
     */
    static List<String> aroundTheName(String text, String normalisedId) {
        List<String> out = new ArrayList<>();
        if (text == null || normalisedId.isEmpty()) {
            return out;
        }
        String norm = KidTyping.normalise(text);
        int at = norm.indexOf(normalisedId);
        if (at < 0) {
            return out;
        }
        String after = norm.substring(at + normalisedId.length()).trim();
        if (after.isEmpty()) {
            return out;
        }
        // "…airplane IN the sky" is a place, not a second name for the aeroplane. Bail on the very
        // first word rather than skipping it: skipping would just produce "the sky", which is worse.
        String firstWord = after.split(" ")[0];
        if (NOT_A_NAME_AFTER.contains(firstWord)) {
            return out;
        }
        List<String> words = new ArrayList<>();
        for (String w : after.split(" ")) {
            if (NOT_A_NAME_AFTER.contains(w)) {
                break;   // the name ended and a description began
            }
            if (w.isEmpty()) {
                continue;
            }
            if (NOISE.contains(w) && words.isEmpty()) {
                continue;   // skip leading filler ("the", "king" is not filler)
            }
            if (words.size() >= 4) {
                break;
            }
            words.add(w);
        }
        // Trim trailing filler, so "king of the kroaks and" does not become an alias with "and" on it.
        while (!words.isEmpty() && NOISE.contains(words.get(words.size() - 1))) {
            words.remove(words.size() - 1);
        }
        if (words.size() >= 2) {
            out.add(String.join(" ", words));
        }
        return out;
    }

    /**
     * An alias claimed by two artifacts is resolved <b>at write time</b>, not at read time.
     *
     * <p>A collision discovered when a child says the word is a coin flip in front of him; discovered
     * when the second artifact claims it, it is a design decision nobody is waiting on. So a new
     * artifact simply does not get an alias something else already holds.
     *
     * @return the subset of {@code wanted} that is genuinely free
     */
    public static List<String> withoutCollisions(List<String> wanted, AliasIndex existing) {
        List<String> free = new ArrayList<>();
        if (wanted == null) {
            return free;
        }
        for (String alias : wanted) {
            String n = KidTyping.normalise(alias);
            if (n.isEmpty()) {
                continue;
            }
            boolean taken = false;
            if (existing != null) {
                for (AliasIndex.Artifact a : existing.artifacts()) {
                    if (a.allNames().contains(n)) {
                        taken = true;
                        break;
                    }
                }
            }
            if (!taken && !free.contains(n)) {
                free.add(n);
            }
        }
        return free;
    }
}
