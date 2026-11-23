package com.moddingfromamod.readymade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>The lexical half of the matcher</b>: names, aliases and tags first, then the
 * one-liner and the wishes an entry was made for, then a one-letter-off fuzzy step. Pure, so its
 * recall is a unit test and {@code ReadyMadeCli} gives the routing arm the very same ranking.
 *
 * <p>It ranks by the words the worker (or the plan) actually used. It never decides that nothing
 * fits — an empty answer only means no word matched — and the model half is what says {@code none}.
 */
public final class ReadyMadeSearch {

    private ReadyMadeSearch() {
    }

    /** One ranked answer. */
    public record Hit(ReadyMade entry, double score) {
    }

    // Weights per field. A name or alias is what the thing is CALLED, so a hit there is near-proof;
    // a purpose word is evidence; an example wish is weaker evidence again.
    static final double NAME = 3.0;
    static final double TAG = 2.0;
    static final double PURPOSE = 1.0;
    static final double EXAMPLE = 0.6;
    static final double FUZZY = 0.5;

    /** Words that say nothing about WHICH thing: they are in every wish. */
    static final Set<String> STOP = Set.of(
            "a", "an", "the", "my", "me", "i", "it", "its", "that", "this", "these", "those", "make",
            "made", "want", "wanna", "can", "could", "you", "with", "of", "to", "and", "or", "for",
            "is", "are", "be", "so", "when", "give", "please", "his", "her", "he", "she", "him",
            "them", "they", "like", "some", "one", "very", "really", "super", "in", "on", "at",
            "up", "out", "by", "from", "into", "do", "does", "will", "would", "has", "have", "who",
            "what", "which", "also", "just", "all", "any", "new", "thing", "something", "now",
            "then", "too", "but", "not", "no", "lot", "lots", "big", "little", "get", "gets", "put",
            "need", "needs", "use", "used", "using", "your", "our", "we", "us", "there", "here");

    /** The best {@code k} entries for this text, highest first; never an entry that scored zero. */
    public static List<Hit> rank(String text, List<ReadyMade> entries, int k) {
        List<String> q = words(text);
        List<Hit> hits = new ArrayList<>();
        if (q.isEmpty()) {
            return hits;
        }
        for (ReadyMade e : entries) {
            double s = score(q, e);
            if (s > 0) {
                hits.add(new Hit(e, s));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(h -> h.entry().id()));
        return hits.size() > k ? new ArrayList<>(hits.subList(0, k)) : hits;
    }

    /** Words an invented effect id carries that say nothing about which effect it means. */
    static final Set<String> GENERIC = Set.of("effect", "item", "invalid", "test", "custom", "special",
            "magic", "power", "ability", "xyz");

    /**
     * <b>The ready-made a made-up id most likely meant</b> — one id, two when they tie, or
     * none. Asked when a tool refuses an id it does not have ({@code spawn}, {@code anger},
     * {@code random_item}): the refusal already lists everything, and this names the line worth
     * reading first. Confident or silent, because the refusal also says "a thing that ALMOST does
     * what he asked is the answer he will notice": every meaningful word of the guess must match the
     * entry at tag level or better ({@code weather_clear} matching only "clear" names nothing), and a
     * three-way tie names nothing.
     */
    public static List<String> closest(String guess, List<ReadyMade> entries) {
        List<String> q = new ArrayList<>(words(guess == null ? "" : guess.replace('_', ' ')));
        q.removeIf(GENERIC::contains);
        if (q.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = rank(String.join(" ", q), entries, 3);
        if (hits.isEmpty() || hits.get(0).score() < TAG * new LinkedHashSet<>(q).size()) {
            return List.of();
        }
        double top = hits.get(0).score();
        List<String> out = new ArrayList<>();
        for (Hit h : hits) {
            if (h.score() == top) {
                out.add(h.entry().id());
            }
        }
        return out.size() <= 2 ? out : List.of();
    }

    static double score(List<String> query, ReadyMade e) {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(words(e.id()));
        names.addAll(words(e.name()));
        for (String a : e.aliases()) {
            names.addAll(words(a));
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String t : e.tags()) {
            tags.addAll(words(t));
        }
        Set<String> purpose = new LinkedHashSet<>(words(e.purpose()));
        Set<String> examples = new LinkedHashSet<>();
        for (String x : e.examples()) {
            examples.addAll(words(x));
        }
        double total = 0;
        for (String w : new LinkedHashSet<>(query)) {
            double best = 0;
            if (names.contains(w)) {
                best = NAME;
            } else if (tags.contains(w)) {
                best = TAG;
            } else if (purpose.contains(w)) {
                best = PURPOSE;
            } else if (examples.contains(w)) {
                best = EXAMPLE;
            } else if (w.length() >= 5 && (near(w, names) || near(w, tags))) {
                best = FUZZY;
            }
            total += best;
        }
        return total;
    }

    /** Lower-case word stems, stop words removed. "Breathes" and "breathing" both read "breath". */
    public static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.isEmpty() || STOP.contains(raw)) {
                continue;
            }
            String w = stem(raw);
            if (!w.isEmpty() && !STOP.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) {
            return trimDouble(w.substring(0, w.length() - 3));
        }
        if (w.length() > 4 && w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.length() > 4 && w.endsWith("es") && "sxz".indexOf(w.charAt(w.length() - 3)) >= 0) {
            return w.substring(0, w.length() - 2);
        }
        if (w.length() > 4 && w.endsWith("ed")) {
            return trimDouble(w.substring(0, w.length() - 2));
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) {
            String t = w.substring(0, w.length() - 1);
            // "breathes" -> "breathe" -> "breath": a trailing silent e is not part of the idea.
            return t.endsWith("e") && t.length() > 4 ? t.substring(0, t.length() - 1) : t;
        }
        if (w.length() > 4 && w.endsWith("e")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    private static String trimDouble(String w) {
        int n = w.length();
        if (n >= 3 && w.charAt(n - 1) == w.charAt(n - 2) && "lsz".indexOf(w.charAt(n - 1)) < 0) {
            return w.substring(0, n - 1);
        }
        return w;
    }

    private static boolean near(String w, Set<String> pool) {
        for (String p : pool) {
            if (p.length() >= 4 && Math.abs(p.length() - w.length()) <= 1 && edits(w, p) <= 1) {
                return true;
            }
        }
        return false;
    }

    /** Levenshtein distance, stopping early above 1 — all the fuzzy step ever asks. */
    static int edits(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > 1) {
                return 2;
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
