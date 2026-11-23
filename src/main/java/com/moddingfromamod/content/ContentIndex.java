package com.moddingfromamod.content;

import com.moddingfromamod.wishbuilder.Apprentice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * <b>One search over everything installed: items, blocks and creatures, vanilla and every mod</b> (
 * the Builder must find things the way the Magician's tools do, so the two cannot
 * drift). Pure — no Minecraft imports — so the same entries give the same answer in a unit test, in the
 * Magician's {@code find_content}, in the Builder's 🔍 Find and in the Apprentice. {@link ContentIndexes} builds the
 * live entries from the game's registries; {@code corpus/apprentice/content-index.json} is a snapshot of them for
 * the tests and for {@code tools/content_search.py}, the reference this is held equal to.
 *
 * <p>An entry answers to its registry id and to its English name. A query matches in tiers, best first:
 * <ol>
 *   <li>{@link Tier#NAME}: the query IS the thing's name or its id's words ("honey block", "zombie");</li>
 *   <li>{@link Tier#ALL_WORDS}: every query word is a word of the name or the id, or (find_content's old rule, kept)
 *       a piece of the full id — "wither storm" finds {@code witherstormmod:wither_storm};</li>
 *   <li>{@link Tier#SPELLING}: every word matches once a child's misspelling is put right, by the Apprentice's own
 *       rules ("honny blok" → honey block); a real English word is never respelt;</li>
 *   <li>{@link Tier#FIRST_WORD}: a query of several words whose first word stands alone in the id (find_content's
 *       weak hit, so a near miss still shows).</li>
 * </ol>
 * Within a tier, a mod's thing comes before vanilla's (find_content's one rule: the child means the real thing
 * a mod supplies), then the shorter name, then the id. Deterministic for the same entries and words.
 */
public final class ContentIndex {

    /** MOB is a living creature (what the Builder may summon); ENTITY is everything else that can be summoned. */
    public enum Kind { ITEM, BLOCK, MOB, ENTITY }

    public enum Tier { NAME, ALL_WORDS, SPELLING, FIRST_WORD }

    /** One installed thing: its id ({@code namespace:path}), what kind it is, and its English name. */
    public record Entry(String id, Kind kind, String name) {
    }

    /** A match: the entry, and how well. */
    public record Hit(Entry entry, Tier tier) {
    }

    static final Set<String> ARTICLES = Set.of("a", "an", "the", "of", "some");

    private final List<Entry> entries;
    private final List<Set<String>> words = new ArrayList<>();
    private final List<String> phrases = new ArrayList<>();
    private final List<String> idPhrases = new ArrayList<>();
    private final Set<String> vocab = new HashSet<>();
    private final Map<String, List<String>> sounds = new TreeMap<>();
    private final Set<String> english;
    /** phrase (a name's or an id's, stemmed) → the entries it names exactly: Tier NAME by a lookup. */
    private final Map<String, List<Integer>> named = new HashMap<>();
    /** a phrase's first word → (entry, the phrase's words): where {@link #respelt} looks. */
    private final Map<String, List<Phrase>> byFirst = new HashMap<>();

    private record Phrase(int entry, List<String> words) {
    }

    public ContentIndex(List<Entry> entries, Set<String> english) {
        this.entries = List.copyOf(entries);
        this.english = english;
        for (Entry e : this.entries) {
            Set<String> w = new LinkedHashSet<>(stems(e.name()));
            List<String> idw = stems(path(e.id()));
            w.addAll(idw);
            w.addAll(stems(namespace(e.id())));
            words.add(w);
            phrases.add(String.join(" ", stems(e.name())));
            idPhrases.add(String.join(" ", idw));
            int at = phrases.size() - 1;
            named.computeIfAbsent(phrases.get(at), x -> new ArrayList<>()).add(at);
            index(phrases.get(at), at);
            if (!idPhrases.get(at).equals(phrases.get(at))) {
                named.computeIfAbsent(idPhrases.get(at), x -> new ArrayList<>()).add(at);
                index(idPhrases.get(at), at);
            }
            vocab.addAll(w);
        }
        for (String v : new java.util.TreeSet<>(vocab)) {
            if (v.length() >= 5) {
                sounds.computeIfAbsent(Apprentice.sound(v, true), x -> new ArrayList<>()).add(v);
            }
        }
    }

    public List<Entry> entries() {
        return entries;
    }

    /** The best {@code limit} matches of {@code query} among {@code kinds} (empty set = every kind). */
    public List<Hit> search(String query, Set<Kind> kinds, int limit) {
        List<String> q = stems(query);
        if (q.isEmpty() || limit <= 0) {
            return List.of();
        }
        String phrase = String.join(" ", q);
        String[] raw = java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).trim().split("[^a-z0-9]+"))
                .filter(w -> !w.isEmpty() && !ARTICLES.contains(w)).toArray(String[]::new);
        List<Set<String>> fixed = new ArrayList<>();
        for (String w : q) {
            fixed.add(readings(w));
        }
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!kinds.isEmpty() && !kinds.contains(e.kind())) {
                continue;
            }
            Set<String> w = words.get(i);
            Tier t = null;
            if (phrase.equals(phrases.get(i)) || phrase.equals(idPhrases.get(i))) {
                t = Tier.NAME;
            } else if (allIn(q, w) || containsAll(e.id().toLowerCase(Locale.ROOT), raw)) {
                t = Tier.ALL_WORDS;
            } else if (allFixedIn(fixed, w)) {
                t = Tier.SPELLING;
            } else if (raw.length > 1 && !raw[0].isEmpty() && wholeWord(path(e.id()), raw[0])) {
                t = Tier.FIRST_WORD;
            }
            if (t != null) {
                hits.add(new Hit(e, t));
            }
        }
        hits.sort(Comparator.<Hit>comparingInt(h -> h.tier().ordinal())
                .thenComparingInt(h -> namespace(h.entry().id()).equals("minecraft") ? 1 : 0)
                .thenComparingInt(h -> h.entry().name().length())
                .thenComparing(h -> h.entry().id()));
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : List.copyOf(hits);
    }

    /**
     * The Tier NAME hits among the best {@code limit} of {@link #search}: the same answer (NAME ranks first, in the
     * same order), by a lookup instead of a walk over every entry. The Apprentice asks this for every span of a wish.
     */
    public List<Hit> named(String query, Set<Kind> kinds, int limit) {
        List<String> q = stems(query);
        if (q.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (int i : named.getOrDefault(String.join(" ", q), List.of())) {
            Entry e = entries.get(i);
            if (kinds.isEmpty() || kinds.contains(e.kind())) {
                hits.add(new Hit(e, Tier.NAME));
            }
        }
        hits.sort(Comparator.<Hit>comparingInt(h -> namespace(h.entry().id()).equals("minecraft") ? 1 : 0)
                .thenComparingInt(h -> h.entry().name().length())
                .thenComparing(h -> h.entry().id()));
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : List.copyOf(hits);
    }

    private void index(String phrase, int at) {
        List<String> w = List.of(phrase.split(" "));
        byFirst.computeIfAbsent(w.get(0), x -> new ArrayList<>()).add(new Phrase(at, w));
    }

    /**
     * the entries a misspelt query NAMES once each word is put right ({@link #readings}), word for word, and
     * only those it does not name as typed. Ranked as the other tiers. tools/content_search.py {@code respelt}.
     */
    public List<Hit> respelt(String query, Set<Kind> kinds, int limit) {
        List<String> q = stems(query);
        if (q.isEmpty() || limit <= 0) {
            return List.of();
        }
        String exact = String.join(" ", q);
        List<Set<String>> fixed = new ArrayList<>();
        for (String w : q) {
            fixed.add(readings(w));
        }
        Set<Integer> seen = new HashSet<>();
        List<Hit> hits = new ArrayList<>();
        for (String first : new java.util.TreeSet<>(fixed.get(0))) {
            for (Phrase p : byFirst.getOrDefault(first, List.of())) {
                if (seen.contains(p.entry()) || p.words().size() != q.size() || String.join(" ", p.words()).equals(exact)) {
                    continue;
                }
                boolean all = true;
                for (int i = 0; i < q.size() && all; i++) {
                    all = fixed.get(i).contains(p.words().get(i));
                }
                if (all) {
                    seen.add(p.entry());
                    Entry e = entries.get(p.entry());
                    if (kinds.isEmpty() || kinds.contains(e.kind())) {
                        hits.add(new Hit(e, Tier.SPELLING));
                    }
                }
            }
        }
        hits.sort(Comparator.<Hit>comparingInt(h -> namespace(h.entry().id()).equals("minecraft") ? 1 : 0)
                .thenComparingInt(h -> h.entry().name().length())
                .thenComparing(h -> h.entry().id()));
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : List.copyOf(hits);
    }

    /** Does the index know this (stemmed) word? A known word is never respelt. */
    public boolean knows(String word) {
        return vocab.contains(word);
    }

    /** What a typed word may be: itself, and, when the index lacks it and it is no English word, its near spellings. */
    Set<String> readings(String w) {
        Set<String> out = new LinkedHashSet<>();
        out.add(w);
        if (vocab.contains(w) || w.length() < 4 || english.contains(w)) {
            return out;
        }
        int limit = w.length() >= 8 ? 2 : 1;
        for (String v : vocab) {
            if (Math.abs(v.length() - w.length()) <= limit && v.charAt(0) == w.charAt(0)
                    && Apprentice.distance(w, v, limit) <= limit) {
                out.add(v);
            }
        }
        if (w.length() >= 5) {
            out.addAll(sounds.getOrDefault(Apprentice.sound(w, true), List.of()));
        }
        return out;
    }

    /** Lower case, letters and digits, little words dropped, plurals stemmed as the Apprentice stems them. */
    public static List<String> stems(String text) {
        List<String> out = new ArrayList<>();
        for (String t : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!t.isEmpty() && !ARTICLES.contains(t)) {
                out.add(Apprentice.stem(t));
            }
        }
        return out;
    }

    static String path(String id) {
        int c = id.indexOf(':');
        return c < 0 ? id : id.substring(c + 1);
    }

    static String namespace(String id) {
        int c = id.indexOf(':');
        return c < 0 ? "minecraft" : id.substring(0, c);
    }

    private static boolean allIn(List<String> q, Set<String> w) {
        for (String s : q) {
            if (!w.contains(s)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allFixedIn(List<Set<String>> fixed, Set<String> w) {
        for (Set<String> f : fixed) {
            boolean any = false;
            for (String s : f) {
                any |= w.contains(s);
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAll(String haystack, String[] raw) {
        boolean saw = false;
        for (String w : raw) {
            if (w.isEmpty() || ARTICLES.contains(w)) {
                continue;
            }
            saw = true;
            if (!haystack.contains(w)) {
                return false;
            }
        }
        return saw;
    }

    static boolean wholeWord(String path, String word) {
        int from = 0;
        while (true) {
            int at = path.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            int before = at - 1;
            int after = at + word.length();
            if ((before < 0 || !Character.isLetterOrDigit(path.charAt(before)))
                    && (after >= path.length() || !Character.isLetterOrDigit(path.charAt(after)))) {
                return true;
            }
            from = at + 1;
        }
    }
}
