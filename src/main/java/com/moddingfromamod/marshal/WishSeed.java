package com.moddingfromamod.marshal;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * The words to put in his mouth so the match cannot go wrong.
 *
 * <p><b>Why this is better than a cleverer matcher.</b> Two failures kept happening in play, and
 * they are opposite ends of the same guess: the router deciding he meant to CHANGE something when he
 * was asking for something new, and the router failing to see that <i>"make my crushing plant"</i>
 * was a dead-obvious hit on the recipe that built it — which sent a cached, instant answer into a
 * long agent loop instead.
 *
 * <p>Rather than improve the guessing, remove the need for it. A learned recipe already records the
 * terms that are <b>guaranteed</b> to match it — the {@code all} array, which is exactly what
 * {@code LearnedTemplates.asPresets} joins to build a preset's text. So when the card seeds his wish
 * box, it seeds those terms. He completes a sentence that was already going to resolve correctly,
 * and nothing has to be inferred from how a seven-year-old happened to phrase it.
 *
 * <p>Pure, so the choice of phrase is unit-tested rather than eyeballed in a running game.
 */
public final class WishSeed {

    private WishSeed() {
    }

    /**
     * The recipe this thing came from, or empty.
     *
     * <p>A template matches an artifact when <b>every one of its guaranteed terms</b> is something
     * the artifact answers to. That direction matters: {@code all=[spikor, kroak]} identifies
     * Spikor, while merely sharing one word would make every recipe mentioning "spikor" a candidate
     * for every Spikor-ish thing he owns.
     *
     * <p>One artifact really does have several recipes — his Spikor has {@code spikor_fire_breath},
     * {@code spikor_boss}, {@code spikor_king} — so the MOST SPECIFIC wins (the one that had to
     * satisfy the most terms), and ties go to whichever was learned most recently, since that is the
     * version of the thing he last cared about.
     *
     * @param answersTo every name and alias the artifact responds to
     * @param templates candidate recipes, newest LAST
     */
    public static Template bestMatch(List<String> answersTo, List<Template> templates) {
        if (answersTo == null || templates == null) {
            return null;
        }
        Set<String> words = new LinkedHashSet<>();
        for (String name : answersTo) {
            for (String w : KidTyping.normalise(name).split(" ")) {
                if (!w.isBlank()) {
                    words.add(w);
                }
            }
        }
        Template best = null;
        for (Template t : templates) {
            if (t == null || t.terms().isEmpty()) {
                continue;   // no guaranteed terms means no guarantee; skip it
            }
            boolean all = t.terms().stream()
                    .allMatch(term -> words.contains(term.toLowerCase(Locale.ROOT).strip()));
            if (all && (best == null || t.terms().size() >= best.terms().size())) {
                best = t;   // >= so a later, equally specific recipe wins on recency
            }
        }
        return best;
    }

    /**
     * The sentence to drop in his box, with the cursor after it.
     *
     * <p><b>"I want my X to " and not "make another X".</b> The first is an unfinished sentence,
     * which is an invitation — a seven-year-old reads it and types what he wants it to DO. The
     * second is a complete instruction, which invites him to press enter and get a duplicate he did
     * not ask for; making another one is its own button.
     *
     * <p>The trailing space is load-bearing: without it his first word runs into the name.
     */
    public static String sentence(String phrase) {
        String p = phrase == null ? "" : phrase.strip();
        return p.isEmpty() ? "I want my " : "I want my " + p + " to ";
    }

    /**
     * Is this one of our own seeded sentences, still unfinished?
     *
     * <p>Sending {@code "I want my crushing plant to "} as a wish is sending nothing — he opened the
     * box and pressed enter. Worth naming because the empty-ish case is not blank, so a plain
     * is-it-empty check waves it through and an agent is woken to grant a sentence with no verb.
     */
    public static boolean isUnfinished(String text) {
        String t = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        return t.startsWith("i want my") && t.endsWith(" to");
    }

    /** One learned recipe: its id, and the terms guaranteed to match it. */
    public record Template(String id, List<String> terms) {

        public Template {
            terms = terms == null ? List.of() : List.copyOf(terms);
        }
    }
}
