package com.moddingfromamod.wishbuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>The Apprentice wish box's matcher</b>: typed words in, up to five ranked Builder makes out. No model,
 * no clock, no randomness: the same words give the same makes in the same order, on the server, in well under a
 * millisecond. It never invents anything: every make is a set of picks from {@link BuilderTree}, the tree of what
 * this world's Builder offers, so a make the menus could not do by hand cannot be offered.
 *
 * <p>How it reads a wish is docs/APPRENTICE.md §3, and it is the SAME algorithm as the reference in
 * {@code tools/apprentice_match.py}, statement for statement and in integer arithmetic. The back-test runs through
 * this class ({@code ApprenticeCorpusTest} writes its answers for the whole corpus) and
 * {@code tools/apprentice-backtest.py --sync} fails when the two disagree on any wish, so a change goes into both
 * or into neither. Pure: no Minecraft type.
 */
public final class Apprentice {

    public enum Reach {
        /** Every meaningful word was used. */
        IN,
        /** The thing was found, some words were not: the tiles are the closest the Builder has. */
        PART,
        /** Nothing the words name is in the tree. No tiles. */
        OUT;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One filled-in make: a kind, the way of making it, and the picked entries of each slot it names. */
    public record Pick(String kind, String form, Map<String, List<String>> picks) {
        public Pick {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            picks.forEach((s, v) -> copy.put(s, List.copyOf(v)));
            picks = java.util.Collections.unmodifiableMap(copy);
        }

        public String first(String slot) {
            List<String> v = picks.get(slot);
            return v == null || v.isEmpty() ? "" : v.get(0);
        }
    }

    /**
     * {@code layer}: for each offer, the picks ({@code "<slot>=<entry id>"}) the trained layer supplied, empty where
     * every pick came from a listed word. The page tags a tile by it.
     */
    public record Answer(List<Pick> offers, Reach reach, List<String> unmatched, List<List<String>> layer) {
        public Answer(List<Pick> offers, Reach reach, List<String> unmatched) {
            this(offers, reach, unmatched, java.util.Collections.nCopies(offers.size(), List.of()));
        }

        /** Did the layer supply a pick of this offer? */
        public boolean guessed(int offer) {
            return offer < layer.size() && !layer.get(offer).isEmpty();
        }
    }

    /** A keyword answer with what the hybrid needs from inside it. */
    private record Keywords(List<Candidate> offers, List<String> toks, List<Integer> unread, List<String> unmatched,
                            Reach reach) {
        Answer answer() {
            List<Pick> picks = new ArrayList<>();
            offers.forEach(o -> picks.add(o.pick()));
            return new Answer(picks, reach, unmatched);
        }
    }

    static final Set<String> ARTICLES = Set.of("a", "an", "the");

    /** Words that carry no pick: never spell-corrected, never counted as unmatched. */
    static final Set<String> STOP = Set.copyOf(java.util.Arrays.asList(("i me my mine we us our you your u it its is are was be am im that this these "
            + "those there here which who what and or but so if then than as of to for in on at by with w from into onto "
            + "up down out off over under can could would will shall should may might must do does did done has have "
            + "had having get gets got make makes made making give gives gave want wants wanna need needs let lets "
            + "please pls plz like likes some any all every each one very really super so too just also only even more "
            + "most much many lot lots not no never ever always when while where why how am thing things something "
            + "anything stuff kind sort cool awesome amazing epic best nice good great now today new called named name "
            + "his her him he she they them their when whenever "
            + "t don doesn didn won wouldn couldn isn aren wasn weren shouldn haven hasn without nothing none").split(" ")));   // the last line:

    static final Map<String, Integer> SLOT_WEIGHT = Map.ofEntries(Map.entry("from", 30), Map.entry("shape", 30),
            Map.entry("look", 30), Map.entry("motion", 30), Map.entry("when", 24), Map.entry("does", 24), Map.entry("power", 24),
            Map.entry("snack_powers", 24), Map.entry("material", 30), Map.entry("trigger", 24),
            Map.entry(BuilderTurnInto.TO_SLOT, 24));   //: what things turn INTO is what the change is for   // a snack's power is what the machine is FOR: it outranks the stock machine
    static final int OTHER_WEIGHT = 12;
    static final int KIND_WORD = 15;
    /** Exact, an edit away, the same sound: index 3, 2, 1. */
    static final int[] QUALITY = {0, 6, 7, 10};
    /** With no word naming the kind, some kinds are the likelier reading of a bare noun ("a castle" is a building). */
    static final Map<String, Integer> KIND_PRIOR = Map.of("creature", 0, "ride", -2, "building", 0, "item", 0, "machine", -3,
            "event", -4, "rule", -4, "magic_block", -6, "picture", -25, "supplies", 0);
    /** supplies before item, so a bare "sticks" (a supply and the wand's word) is the plain vanilla sticks. */
    static final List<String> KIND_ORDER = List.of("creature", "ride", "supplies", "item", "machine", "magic_block", "building",
            "picture", "event", "rule");
    static final int MOST_OFFERS = 5;

    private record Ref(boolean kindWord, String kind, String form, String slot, String entry, int order,
                       Map<String, List<String>> also) {
    }

    private record Term(String[] stems, Ref ref) {
    }

    private record Reading(String stem, int quality) {
    }

    private record Found(int a, int b, int q, Ref ref, String[] stems) {
    }

    private record Hit(String entry, int order, Map<String, List<String>> also, int q) {
    }

    private static final class Group {
        final int a;
        final int b;
        final String slot;
        int q;
        /** ranked after every other reading of its words (a power word beside a change said in full). */
        int yields;
        final List<Hit> entries = new ArrayList<>();

        Group(int a, int b, String slot, int q) {
            this.a = a;
            this.b = b;
            this.slot = slot;
            this.q = q;
        }
    }

    private record Alt(String slot, String entry, int weight, int n) {
    }

    private record Candidate(Pick pick, int score, int order, Set<Integer> used, List<int[]> spans, List<int[]> anchors,
                             Set<Integer> lost) {
    }

    private final BuilderTree tree;
    private final Set<String> english;
    private final Map<String, List<Term>> terms = new HashMap<>();
    private final Set<String> vocab = new HashSet<>();
    /**
     * what a misspelt word may be put right INTO — the vocabulary but the words only an item's look says. 204
     * looks brought short everyday words ("heli", "toy", "game", "pan", "mic", "icon"), and "hel" stopped being heal,
     * "tiy" tiny: a look word is read when it is typed, never guessed from a slip.
     */
    private final Set<String> targets = new HashSet<>();
    private final Set<String> lookOnly = new HashSet<>();
    private final Set<String> notLook = new HashSet<>();
    private final Map<String, List<String>> sounds = new HashMap<>();
    private final Map<String, String> leads = new HashMap<>();
    /** The filler and joining words a slipped word may be put back as: none the tree gives a meaning to. */
    private final List<String> slips;
    private final Map<String, List<Reading>> readings = new ConcurrentHashMap<>();

    public Apprentice(BuilderTree tree, Set<String> english) {
        this.tree = tree;
        this.english = english;
        for (BuilderTree.Kind k : tree.kinds()) {
            for (String w : k.words()) {
                add(BuilderTree.Term.parse(w).phrase(), new Ref(true, k.id(), null, null, null, 0, Map.of()));
            }
            for (BuilderTree.Form f : k.forms()) {
                for (int si = 0; si < f.slots().size(); si++) {
                    BuilderTree.Slot s = f.slots().get(si);
                    for (int ei = 0; ei < s.entries().size(); ei++) {
                        BuilderTree.Entry e = s.entries().get(ei);
                        Set<String> seen = new HashSet<>();
                        List<String[]> own = new ArrayList<>();
                        for (String w : e.words()) {
                            BuilderTree.Term t = BuilderTree.Term.parse(w);
                            if (seen.add(t.phrase())) {
                                Map<String, List<String>> also = new LinkedHashMap<>();
                                t.also().forEach((slot, entry) -> also.put(slot, List.of(entry)));
                                add(t.phrase(), new Ref(false, k.id(), f.id(), s.id(), e.id(), si * 1000 + ei, also));
                            }
                        }
                        if (!s.steps() && !BuilderLooks.SLOT.equals(s.id())) {
                            // The label itself is always a way to say the entry. Not a look's: "Penguin" on the
                            // penguin toy would bring back the bare animal name its words leave to the creature.
                            String label = BuilderTree.normal(e.label());
                            if (!label.isEmpty() && seen.add(label)) {
                                add(label, new Ref(false, k.id(), f.id(), s.id(), e.id(), si * 1000 + ei, Map.of()));
                            }
                        }
                    }
                }
            }
        }
        targets.addAll(vocab);
        lookOnly.removeAll(notLook);
        targets.removeAll(lookOnly);
        for (String v : vocab) {
            leads.put(v, lead(v));
        }
        for (String v : new TreeSet<>(targets)) {
            if (v.length() >= 5) {
                for (String key : new java.util.LinkedHashSet<>(List.of(sound(v, false), sound(v, true)))) {
                    sounds.computeIfAbsent(key, x -> new ArrayList<>()).add(v);
                }
            }
        }
        TreeSet<String> sl = new TreeSet<>();
        for (Set<String> group : List.of(CONNECT, ARTICLES, STOP)) {
            for (String f : group) {
                if (f.length() >= 3 && (group != STOP || !vocab.contains(f))) {
                    sl.add(f);
                }
            }
        }
        slips = List.copyOf(sl);
    }

    private void add(String phrase, Ref ref) {
        List<String> toks = new ArrayList<>();
        for (String t : phrase.split(" ")) {
            if (!t.isEmpty() && !ARTICLES.contains(t)) {
                toks.add(stem(t));
            }
        }
        if (toks.isEmpty()) {
            return;
        }
        vocab.addAll(toks);
        (BuilderLooks.SLOT.equals(ref.slot()) ? lookOnly : notLook).addAll(toks);
        terms.computeIfAbsent(toks.get(0), x -> new ArrayList<>()).add(new Term(toks.toArray(new String[0]), ref));
    }

    private static volatile Set<String> englishCache;

    /**
     * Real English words ({@code apprentice/english-words.txt}, SCOWL's small list). A typed word that is one is
     * never respelt: "front" is not a misspelt "frost", and "pond" is not a "pony".
     */
    public static Set<String> english() {
        Set<String> c = englishCache;
        if (c != null) {
            return c;
        }
        Set<String> out = new HashSet<>(60000);
        try (InputStream in = Apprentice.class.getClassLoader().getResourceAsStream("apprentice/english-words.txt")) {
            if (in == null) {
                throw new IllegalStateException("apprentice/english-words.txt is not in the jar");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                if (!line.isBlank()) {
                    out.add(line.strip());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the English word list", e);
        }
        englishCache = c = Set.copyOf(out);
        return c;
    }

    // ------------------------------------------------------------------ words

    public static String stem(String w) {
        w = English.singular(w);   //: the irregular plurals ("geese", "potatoes", "cookies") first, then the endings
        int n = w.length();
        if (n > 4 && w.endsWith("ies")) {
            return w.substring(0, n - 3) + "y";
        }
        if (n > 4 && (w.endsWith("sses") || w.endsWith("xes") || w.endsWith("zes"))
                || n > 5 && (w.endsWith("ches") || w.endsWith("shes"))) {
            return w.substring(0, n - 2);
        }
        if (n > 3 && w.endsWith("s") && !w.endsWith("ss")) {
            return w.substring(0, n - 1);
        }
        return w;
    }

    static List<String> wordsOf(String text) {
        return wordsOf(text, false);
    }

    /** The words, "a/an/the" dropped; with {@code articles} kept, for the spelling to join ("brea the" is "breathe"). */
    static List<String> wordsOf(String text, boolean articles) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String low = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (int i = 0; i <= low.length(); i++) {
            char c = i < low.length() ? low.charAt(i) : ' ';
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                cur.append(c);
            } else if (cur.length() > 0) {
                String w = cur.toString();
                cur.setLength(0);
                if (articles || !ARTICLES.contains(w)) {
                    out.add(w);
                }
            }
        }
        return out;
    }

    /**
     * The first sound written one way ("kastle", "jiant", "sircle", "fone", "rite"): a leading ph/wh/kn/wr/ps is its
     * sound, a leading c is k or (before e, i, y) s, and q, z, j are k, s, g. "ch" and "sh" are left alone. Used to
     * compare a typed word with the tree's words, never to rewrite what was typed.
     */
    static String lead(String w) {
        for (String[] ab : new String[][] {{"ph", "f"}, {"wh", "w"}, {"kn", "n"}, {"wr", "r"}, {"ps", "s"}}) {
            if (w.startsWith(ab[0])) {
                w = ab[1] + w.substring(2);
                break;
            }
        }
        if (w.isEmpty() || w.startsWith("ch") || w.startsWith("sh")) {
            return w;
        }
        char c = w.charAt(0);
        if (c == 'c') {
            c = w.length() > 1 && "eiy".indexOf(w.charAt(1)) >= 0 ? 's' : 'k';
        } else if (c == 'q') {
            c = 'k';
        } else if (c == 'z') {
            c = 's';
        } else if (c == 'j') {
            c = 'g';
        }
        return c + w.substring(1);
    }

    /** The consonant skeleton a child spells by ear: "dinasor" and "dinosaur" are both "dnsr". */
    static String sound(String word) {
        return sound(word, true);
    }

    /** With {@code firstSound}, the first letter is its sound ({@link #lead}): "kastle" and "castle" are both "kstl". */
    public static String sound(String word, boolean firstSound) {
        String w = firstSound ? lead(word) : word;
        w = w.replace("ph", "f").replace("ck", "k").replace("qu", "kw").replace("x", "ks").replace("gh", "");
        if (w.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder().append(w.charAt(0));
        for (int i = 1; i < w.length(); i++) {
            char c = w.charAt(i);
            c = switch (c) {
                case 'c', 'q' -> 'k';
                case 'z' -> 's';
                case 'j' -> 'g';
                case 'v' -> 'f';
                default -> c;
            };
            if ("aeiouyhw".indexOf(c) >= 0) {
                continue;
            }
            if (out.charAt(out.length() - 1) != c) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** A word's consonants in order, doubles written once, then sorted: "wniggs" and "wings" are both "gnsw". */
    static String skeleton(String w) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if ("aeiouy".indexOf(c) < 0 && (out.length() == 0 || out.charAt(out.length() - 1) != c)) {
                out.append(c);
            }
        }
        char[] cs = out.toString().toCharArray();
        java.util.Arrays.sort(cs);
        return new String(cs);
    }

    /** The same consonants, or {@code a} missing one of {@code b}'s ("sotu" for "snout", "peunin" for "penguin"). */
    static boolean oneConsonantOff(String a, String b) {
        String sa = skeleton(a), sb = skeleton(b);
        if (sa.equals(sb)) {
            return true;
        }
        if (sa.length() + 1 != sb.length()) {
            return false;
        }
        for (int i = 0; i < sb.length(); i++) {
            if ((sb.substring(0, i) + sb.substring(i + 1)).equals(sa)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code typed} is {@code word} with one keyboard slip that keeps its first letter in place or swaps it with the
     * second: a swapped pair ("taht", "hte"), a doubled letter ("thatt") or a letter left out after the first ("wth").
     * Not a letter changed or added, which is how a real word's typo ("hon" for "horn", "ared") would become filler.
     */
    static boolean smallSlip(String typed, String word) {
        if (typed.equals(word)) {
            return false;
        }
        int n = word.length();
        if (typed.length() == n) {
            List<Integer> d = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (typed.charAt(i) != word.charAt(i)) {
                    d.add(i);
                }
            }
            return d.size() == 2 && d.get(1) == d.get(0) + 1 && typed.charAt(d.get(0)) == word.charAt(d.get(1))
                    && typed.charAt(d.get(1)) == word.charAt(d.get(0));
        }
        if (typed.length() == n + 1) {
            for (int i = 1; i < typed.length(); i++) {
                if ((typed.substring(0, i) + typed.substring(i + 1)).equals(word) && typed.charAt(i) == typed.charAt(i - 1)) {
                    return true;
                }
            }
            return false;
        }
        if (typed.length() == n - 1) {
            for (int i = 1; i < n; i++) {
                if ((word.substring(0, i) + word.substring(i + 1)).equals(typed)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The slips a three-letter word really takes: two letters swapped ("cta"), one vowel for another ("cet"), or a
     * letter of a four-letter word left out ("rde" for "ride", which is why "rde" is not "red": two readings, none
     * taken).
     */
    /** Is {@code a} {@code b} with letters left out ('gren' in 'green')? */
    static boolean isSubsequence(String a, String b) {
        int i = 0;
        for (int j = 0; j < b.length() && i < a.length(); j++) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
            }
        }
        return i == a.length();
    }

    static boolean slip3(String typed, String v) {
        if (typed.length() != 3) {
            return false;
        }
        if (v.length() == 4) {
            for (int i = 1; i < 4; i++) {
                if ((v.substring(0, i) + v.substring(i + 1)).equals(typed)) {
                    return true;
                }
            }
            return false;
        }
        char[] a = typed.toCharArray(), b = v.toCharArray();
        java.util.Arrays.sort(a);
        java.util.Arrays.sort(b);
        if (java.util.Arrays.equals(a, b)) {
            return true;
        }
        int diff = -1, n = 0;
        for (int i = 0; i < 3; i++) {
            if (typed.charAt(i) != v.charAt(i)) {
                diff = i;
                n++;
            }
        }
        return n == 1 && "aeiou".indexOf(typed.charAt(diff)) >= 0 && "aeiou".indexOf(v.charAt(diff)) >= 0;
    }

    /** Edit distance with swaps (Damerau), or {@code limit + 1} once it is past the limit. */
    public static int distance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) {
            return limit + 1;
        }
        int[] prev2 = null;
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            int best = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (prev2 != null && i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    cur[j] = Math.min(cur[j], prev2[j - 2] + 1);
                }
                best = Math.min(best, cur[j]);
            }
            if (best > limit) {
                return limit + 1;
            }
            prev2 = prev;
            prev = cur;
        }
        return prev[b.length()];
    }

    // ------------------------------------------------------------------ any number
    //
    // A count or amount used to be read only when it was one of the slider's own steps ("two", "16", "a stack"); any
    // other number was dropped while the page said it read every word ("7 bees" made one bee). Now any number counts
    // the thing it stands in front of (a word the pick keeps, within three words, no joining word between: "12 cows",
    // "3 diamond swords", never "a sword that does 10 damage"), and is FIT to the slider as the dialog would (the
    // nearest step at or below it, at most what this thing is given at once). A number it had to move is a note
    // ("Closest it has: 5 (for 7)"), so the page is never sure on it. tools/apprentice_match.py `with_number`.

    static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(Map.entry("one", 1), Map.entry("two", 2),
            Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11),
            Map.entry("twelve", 12), Map.entry("thirteen", 13), Map.entry("fourteen", 14), Map.entry("fifteen", 15),
            Map.entry("sixteen", 16), Map.entry("seventeen", 17), Map.entry("eighteen", 18), Map.entry("nineteen", 19),
            Map.entry("twenty", 20), Map.entry("thirty", 30), Map.entry("forty", 40), Map.entry("fifty", 50),
            Map.entry("sixty", 60), Map.entry("seventy", 70), Map.entry("eighty", 80), Map.entry("ninety", 90),
            Map.entry("hundred", 100), Map.entry("dozen", 12));
    static final Set<String> TENS = Set.of("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety");
    static final List<String> COUNT_SLOTS = List.of("count", "amount");

    /** A run-together number ("thirtyone", "fortyeight") as its two words; any other word as itself. */
    static List<String> unnumber(String t) {
        for (String tens : TENS) {
            String rest = t.startsWith(tens) ? t.substring(tens.length()) : null;
            if (rest != null && NUMBER_WORDS.containsKey(rest) && NUMBER_WORDS.get(rest) < 10) {
                return List.of(tens, rest);
            }
        }
        return List.of(t);
    }

    /** A number, in digits or words: never a meaningful word of its own (it counts something). */
    static boolean isNum(String t) {
        return digits(t) || NUMBER_WORDS.containsKey(t);
    }

    /** [start, end, value] for every number in the words ("twenty four" is one number). */
    static List<int[]> numbers(List<String> toks) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);
            if (digits(t)) {
                out.add(new int[] {i, i + 1, Integer.parseInt(t.length() > 9 ? "999999999" : t)});
            } else if (TENS.contains(t) && i + 1 < toks.size() && NUMBER_WORDS.containsKey(toks.get(i + 1))
                    && NUMBER_WORDS.get(toks.get(i + 1)) < 10) {
                out.add(new int[] {i, i + 2, NUMBER_WORDS.get(t) + NUMBER_WORDS.get(toks.get(i + 1))});
                i++;
            } else if (NUMBER_WORDS.containsKey(t)) {
                out.add(new int[] {i, i + 1, NUMBER_WORDS.get(t)});
            }
        }
        return out;
    }

    /** The token indexes a pick keeps: its kind word and its picks' words, or the words that named a found thing. */
    private Set<Integer> keptBy(Keywords got, Pick pick) {
        if (isFound(pick)) {
            FoundThing f = found(got, pick.first("from"));
            return f == null ? Set.of() : f.kept();
        }
        Set<Integer> kept = new HashSet<>();
        for (Found m : find(got.toks())) {
            Ref r = m.ref();
            boolean mine = r.kindWord() ? r.kind().equals(pick.kind())
                    : r.kind().equals(pick.kind()) && r.form().equals(pick.form())
                    && pick.picks().getOrDefault(r.slot(), List.of()).contains(r.entry());
            if (mine) {
                for (int i = m.a(); i < m.b(); i++) {
                    kept.add(i);
                }
            }
        }
        return kept;
    }

    private record Counted(String slot, int a, int b, int n) {
    }

    /** "like 1000", "about 20": a number said after the thing still counts it. */
    static final Set<String> ABOUT = Set.of("like", "about", "around", "maybe", "least", "roughly", "nearly");
    /** a plural with no number is a handful, not one; "a", "one", "my" before the noun is one. */
    static final Map<String, Integer> HANDFUL = Map.of("count", 3, "amount", 16);
    static final Set<String> ONE_WORDS = Set.of("a", "an", "one", "my", "this", "that", "single", "another");
    static final Set<String> MANY_WORDS = Set.of("lots", "lot", "loads", "tons", "heaps", "pile", "zillion");
    static final Set<String> MOST_WORDS = Set.of("army", "herd", "swarm", "flock", "horde", "crowd");

    /** The words of how many with no number ("lots", "an army") that no other phrase reads: "lots of legs" is legs. */
    private Set<String> vague(List<String> toks) {
        Set<String> out = new HashSet<>();
        List<Found> found = find(toks);
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);
            if (!MANY_WORDS.contains(t) && !MOST_WORDS.contains(t)) {
                continue;
            }
            final int at = i;
            boolean other = found.stream().anyMatch(m -> m.a() <= at && at < m.b() && !m.ref().kindWord()
                    && !COUNT_SLOTS.contains(m.ref().slot()) && java.util.stream.IntStream.range(m.a(), m.b())
                    .anyMatch(j -> !STOP.contains(toks.get(j)) && !MANY_WORDS.contains(toks.get(j)) && !MOST_WORDS.contains(toks.get(j))));
            if (!other) {
                out.add(t);
            }
        }
        return out;
    }

    /** The slot that says how many of this pick, or null. */
    private String countSlot(Pick pick) {
        BuilderTree.Kind k = tree.kind(pick.kind());
        BuilderTree.Form form = k == null ? null : k.form(pick.form());
        for (BuilderTree.Slot s : form == null ? List.<BuilderTree.Slot>of() : form.slots()) {
            if (COUNT_SLOTS.contains(s.id()) && s.steps()) {
                return s.id();
            }
        }
        return null;
    }

    /** Did he name this pick's thing in the plural ("cookies", "wolves"), with no "a"/"one" before it? */
    private boolean pluralNamed(Keywords got, Pick pick) {
        String fid = pick.first("from");
        BuilderTree.Form form = tree.kind(pick.kind()).form(pick.form());
        BuilderTree.Slot from = form == null ? null : form.slot("from");
        BuilderTree.Entry e = from == null || fid.isEmpty() ? null : from.entry(fid);
        if (e == null) {
            return false;
        }
        Set<String> own = new HashSet<>(wordsOf(e.label()));
        e.words().stream().filter(w -> !w.contains(">")).forEach(w -> own.addAll(wordsOf(w)));   // not "pegasus>parts=…"
        List<String> toks = got.toks();
        for (int i : new java.util.TreeSet<>(keptBy(got, pick))) {
            String t = toks.get(i);
            String s = stem(t);
            if (!s.equals(t) && own.contains(s) && (i == 0 || !ONE_WORDS.contains(toks.get(i - 1)))) {
                return true;
            }
        }
        return false;
    }

    /** The number that counts this pick, or null. */
    private Counted counted(Keywords got, Pick pick) {
        BuilderTree.Kind k = tree.kind(pick.kind());
        BuilderTree.Form form = k == null ? null : k.form(pick.form());
        String slot = null;
        for (BuilderTree.Slot s : form == null ? List.<BuilderTree.Slot>of() : form.slots()) {
            if (COUNT_SLOTS.contains(s.id()) && s.steps()) {
                slot = s.id();
                break;
            }
        }
        if (slot == null) {
            return null;
        }
        List<String> toks = got.toks();
        Set<Integer> kept = keptBy(got, pick);
        for (int[] num : numbers(toks)) {
            for (int j = num[1]; j < Math.min(num[1] + 3, toks.size()); j++) {
                if (CONNECT.contains(toks.get(j))) {
                    break;
                }
                if (kept.contains(j) && (j < num[0] || j >= num[1])) {
                    return new Counted(slot, num[0], num[1], num[2]);
                }
            }
        }
        for (int[] num : numbers(toks)) {   //: "lots of tnt like 1000"
            int a = num[0];
            if (a > 0 && ABOUT.contains(toks.get(a - 1)) && kept.stream().anyMatch(t -> t < a)) {
                return new Counted(slot, num[0], num[1], num[2]);
            }
        }
        return null;
    }

    /** The slider value the dialog would set for {@code n}: the nearest step at or below it, at most this thing's most. */
    private String fit(Pick pick, String slot, int n) {
        BuilderTree.Form form = tree.kind(pick.kind()).form(pick.form());
        List<Integer> steps = new ArrayList<>();
        for (BuilderTree.Entry e : form.slot(slot).entries()) {
            if (!e.id().isEmpty() && digits(e.id())) {
                steps.add(Integer.parseInt(e.id()));
            }
        }
        if (slot.equals("amount")) {
            BuilderTree.Slot from = form.slot("from");
            BuilderTree.Entry e = from == null || pick.first("from").isEmpty() ? null : from.entry(pick.first("from"));
            int most = Integer.parseInt(e == null ? "1" : e.facts().getOrDefault("most", "1"));
            steps.removeIf(v -> v > most);
        } else if (slot.equals("count")) {   //: the world's spawn rules for this make's creatures (bees: 25)
            int most = countMost(pick);
            boolean over = !steps.isEmpty() && most < Collections.max(steps);
            steps.removeIf(v -> v > most);
            if (over && !steps.contains(most)) {
                steps.add(most);
            }
        }
        if (steps.isEmpty()) {
            return null;
        }
        steps.sort(Comparator.naturalOrder());
        Integer best = null;
        for (int v : steps) {
            if (v <= n) {
                best = v;
            }
        }
        return String.valueOf(best == null ? steps.get(0) : best);
    }

    /** the most of this supply one make gives (its own stack, the world's limit), or -1. */
    static int amountMost(BuilderTree t, Pick pick) {
        BuilderTree.Kind k = t.kind(pick.kind());
        BuilderTree.Form form = k == null ? null : k.form(pick.form());
        BuilderTree.Slot from = form == null ? null : form.slot("from");
        BuilderTree.Entry e = from == null || pick.first("from").isEmpty() ? null : from.entry(pick.first("from"));
        return e == null ? -1 : Integer.parseInt(e.facts().getOrDefault("most", "-1"));
    }

    /** the most creatures this make may bring (BuilderCounts, on this world's spawn rules). */
    static int countMost(Pick pick) {
        BuilderSpec.Kind k = pick.kind().equals("creature") ? BuilderSpec.Kind.CREATURE : BuilderSpec.Kind.ITEM;
        return BuilderCounts.most(BuilderAllowlist.REVIEWED, k, pick.first("from"));
    }

    /** Each tile with the number that counts it, fitted; a tile that already names how many is left alone. */
    private Answer withNumber(Keywords got, Answer page) {
        List<Pick> offers = new ArrayList<>();
        for (Pick pick : page.offers()) {
            Counted c = counted(got, pick);
            String slot = countSlot(pick);
            String v = null;
            String into = null;
            if (c != null && pick.picks().getOrDefault(c.slot(), List.of()).isEmpty()) {
                v = fit(pick, c.slot(), c.n());
                into = c.slot();
            } else if ("count".equals(slot) && !pick.first(slot).isEmpty() && digits(pick.first(slot))) {
                // a count read from his words ("an army") is never past this world's most for this make
                v = fit(pick, slot, Integer.parseInt(pick.first(slot)));
                into = slot;
            } else if (slot != null && c == null && pick.first(slot).isEmpty() && !vague(got.toks()).isEmpty()) {
                // "lots of cookies" is a stack; "lots of bees" ten; "an army" as many as may be
                Set<String> said = vague(got.toks());
                v = fit(pick, slot, slot.equals("amount") || said.stream().anyMatch(MOST_WORDS::contains) ? 1000 : 10);
                into = slot;
            } else if (slot != null && c == null && pick.first(slot).isEmpty() && pluralNamed(got, pick)) {
                v = fit(pick, slot, HANDFUL.get(slot));   //: "cookies" is a handful
                into = slot;
            }
            if (v != null && !v.equals(pick.first(into))) {
                Map<String, List<String>> picks = new LinkedHashMap<>(pick.picks());
                picks.put(into, List.of(v));
                pick = new Pick(pick.kind(), pick.form(), picks);
            }
            offers.add(pick);
        }
        // The colour rule and the ride/wear rule may drop, add or reorder tiles: each tile keeps what the layer supplied.
        List<Placed> placed = new ArrayList<>();
        for (int i = 0; i < offers.size(); i++) {
            placed.add(new Placed(offers.get(i), i));
        }
        placed = forHim(got.toks(), withColours(got.toks(), placed));
        List<Pick> picks = new ArrayList<>();
        List<List<String>> layer = new ArrayList<>();
        for (Placed pl : placed) {
            picks.add(pl.pick());
            layer.add(page.layer().get(pl.from()));
        }
        return new Answer(picks, page.reach(), page.unmatched(), layer);
    }

    // (R1): what he means to do with it. tools/apprentice_match.py _for_him / _unsure.
    static final Set<String> RIDE_WORDS = Set.of("ride", "rides", "riding", "drive", "drives", "driving");
    static final Set<String> WEAR_WORDS = Set.of("wear", "ware", "wears", "wearing", "worn");
    static final Set<String> RIDDEN_LOOKS = Set.of("skateboard", "hoverboard", "scooter", "jetpack", "bike", "bicycle",
            "race_car", "car", "airplane", "helicopter", "boat", "rocket", "spaceship", "train", "sled", "surfboard",
            "toy_car", "ufo");
    static final Set<String> WEARABLE = Set.of("stock:helmet", "stock:chestplate", "stock:leggings", "stock:boots");
    static final Set<String> JUST_FOR_FUN = Set.of("", "itempower:show");

    /** "a picture of me as a pirate", "me as a knight": he is the subject, never a Person body. */
    static boolean meSubject(List<String> toks) {
        for (int i = 0; i < toks.size(); i++) {
            if (toks.get(i).equals("me") && (i > 0 && toks.get(i - 1).equals("of") || i + 1 < toks.size() && toks.get(i + 1).equals("as"))) {
                return true;
            }
        }
        return false;
    }

    /** what colours a wish SAYS for one kind of thing: the solids named, in order, and the theme or null. */
    record Said(List<String> named, String theme) {
    }

    private final Map<String, Said> saidCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * the colours named for this KIND of thing, from the phrases the matcher READ (so a misspelt colour counts as
     * the matcher counts it). A colour word inside a longer or equal phrase of another of this kind's slots is that
     * slot's ("white wool" is what it turns into, "on white" a background). A longer colour name wins over one inside
     * it ("light blue"). The theme: the longest scheme phrase that is not only colours ("red and orange") and not a
     * mood ("fluffy"). tools/apprentice_match.py {@code colours_said}.
     */
    Said colourSaid(List<String> toks, String kind) {
        return saidCache.computeIfAbsent(String.join(" ", toks) + "\u0000" + kind, k -> {
            Set<String> solids = new HashSet<>();
            Set<String> colourStems = new HashSet<>(Colours.JOINS);
            for (Schemes.Scheme sc : Colours.solids()) {
                solids.add(sc.id());
                for (String w : sc.words()) {
                    for (String x : w.replace("~", "").strip().split(" ")) {
                        colourStems.add(stem(x));
                    }
                }
            }
            Colours.JOINS.forEach(x -> colourStems.add(stem(x)));
            Set<String> moods = new HashSet<>();
            Colours.MOODS.forEach(m -> moods.add(stem(m)));
            record Sch(int a, int b, String entry, List<String> stems) {
            }
            Set<Sch> sch = new LinkedHashSet<>();
            List<int[]> other = new ArrayList<>();
            for (Found f : find(toks)) {
                if (f.ref().kindWord() || !kind.equals(f.ref().kind())) {
                    continue;
                }
                if ("scheme".equals(f.ref().slot())) {
                    sch.add(new Sch(f.a(), f.b(), f.ref().entry(), List.of(f.stems())));
                } else {
                    other.add(new int[] {f.a(), f.b()});
                }
            }
            List<Sch> kept = new ArrayList<>();
            for (Sch h : sch) {
                boolean taken = false;
                for (int[] o : other) {
                    taken |= o[0] <= h.a() && h.b() <= o[1] && o[1] - o[0] >= h.b() - h.a();
                }
                if (!taken) {
                    kept.add(h);
                }
            }
            List<Sch> solid = new ArrayList<>(kept.stream().filter(h -> solids.contains(h.entry())).toList());
            solid.sort(java.util.Comparator.comparingInt(Sch::a).thenComparing(h -> -h.b()).thenComparing(Sch::entry));
            List<String> named = new ArrayList<>();
            int end = -1;
            for (Sch h : solid) {
                if (h.a() < end) {
                    continue;
                }
                named.add(h.entry());
                end = h.b();
            }
            String theme = null;
            int bestLen = -1;
            int bestAt = Integer.MAX_VALUE;
            String bestId = null;
            for (Sch h : kept) {
                if (solids.contains(h.entry()) || colourStems.containsAll(h.stems())
                        || h.stems().size() == 1 && moods.contains(h.stems().get(0))) {
                    continue;
                }
                int len = String.join(" ", h.stems()).length();
                if (len > bestLen || len == bestLen && (h.a() < bestAt || h.a() == bestAt && h.entry().compareTo(bestId) < 0)) {
                    theme = h.entry();
                    bestLen = len;
                    bestAt = h.a();
                    bestId = h.entry();
                }
            }
            return new Said(List.copyOf(named), theme);
        });
    }

    /** a building with one colour word and no "all": the three-tone lead it takes, or null. */
    String buildingLead(String kind, List<String> toks) {
        if (!"building".equals(kind) || Colours.allOver(toks)) {
            return null;
        }
        List<String> named = colourSaid(toks, kind).named();
        return new LinkedHashSet<>(named).size() == 1 ? Schemes.LEAD.get(named.get(0)) : null;
    }

    /**
     * two or more colours are the closest three-tone scheme; one colour on a BUILDING is the three-tone scheme it
     * leads, with the solid as the next tile (unless he said all / just / only / solid). tools/apprentice_match.py
     * {@code with_colours}.
     */
    /** A tile, and which of the page's tiles it came from (so it keeps what the layer supplied for it). */
    record Placed(Pick pick, int from) {
    }

    List<Placed> withColours(List<String> toks, List<Placed> offers) {
        List<Placed> out = new ArrayList<>();
        for (Placed pl : offers) {
            Pick o = pl.pick();
            if (o.first("scheme").isEmpty()) {
                out.add(pl);
                continue;
            }
            Said said = colourSaid(toks, o.kind());
            List<String> closest = new LinkedHashSet<>(said.named()).size() >= 2 ? Colours.closestAll(said.named()) : List.of();
            String now = colourScheme(toks, o.kind(), o.first("scheme"));
            if (!now.equals(o.first("scheme")) || !closest.isEmpty() || said.theme() != null) {
                out.add(now.equals(o.first("scheme")) ? pl : new Placed(withScheme(o, now), pl.from()));
                continue;
            }
            String lead = buildingLead(o.kind(), toks);
            if (lead != null && said.named().get(0).equals(o.first("scheme"))) {
                out.add(new Placed(withScheme(o, lead), pl.from()));
                out.add(pl);   // the solid, one press away
            } else {
                out.add(pl);
            }
        }
        List<Pick> seen = new ArrayList<>();
        List<Placed> kept = new ArrayList<>();
        for (Placed pl : out) {
            if (!seen.contains(pl.pick())) {
                seen.add(pl.pick());
                kept.add(pl);
            }
        }
        return kept.size() > MOST_OFFERS ? kept.subList(0, MOST_OFFERS) : kept;
    }

    /**
     * one tile's scheme under the colour rule: a theme he named replaces a solid; one colour named (no
     * theme) is that colour on anything but a building, even where a mood word chose a scheme ("a fluffy white bunny");
     * two or more colours are the closest three-tone scheme (when two tie, the one already chosen). Run on the keyword
     * answer, so the trained layer compares the same colours it will be shown, and again on the page.
     * tools/apprentice_match.py {@code colour_scheme}.
     */
    String colourScheme(List<String> toks, String kind, String scheme) {
        if (scheme.isEmpty()) {
            return scheme;
        }
        Said said = colourSaid(toks, kind);
        if (said.theme() != null && Schemes.solid(scheme)) {
            return said.theme();
        }
        if (said.theme() == null && new LinkedHashSet<>(said.named()).size() == 1 && !"building".equals(kind)
                && !Schemes.solid(scheme)) {
            return said.named().get(0);
        }
        List<String> closest = new LinkedHashSet<>(said.named()).size() >= 2 ? Colours.closestAll(said.named()) : List.of();
        return closest.isEmpty() || closest.contains(scheme) ? scheme : closest.get(0);
    }

    private static Pick withScheme(Pick o, String scheme) {
        Map<String, List<String>> picks = new LinkedHashMap<>(o.picks());
        picks.put("scheme", List.of(scheme));
        return new Pick(o.kind(), o.form(), picks);
    }

    /** No Person body for a wish about HIM; a wearable look first when he says he will wear it. */
    static List<Placed> forHim(List<String> toks, List<Placed> offers) {
        List<Placed> out = new ArrayList<>(offers);
        if (meSubject(toks)) {
            out.removeIf(o -> o.pick().first("from").equals("bodies/person"));
        }
        if (toks.stream().anyMatch(WEAR_WORDS::contains)) {
            Placed worn = out.stream().filter(o -> o.pick().kind().equals("item")
                    && WEARABLE.contains(o.pick().first("shape"))).findFirst().orElse(null);
            if (worn != null) {
                out.remove(worn);
                out.add(0, worn);
            }
        }
        return out;
    }

    /** A page is never sure on a thing he means to ride or wear that the tile cannot be ridden or worn. */
    boolean unsure(List<String> toks, Pick pick) {
        boolean item = pick.kind().equals("item");
        if (item && toks.stream().anyMatch(RIDE_WORDS::contains)) {
            return true;
        }
        String look = pick.first("itemlook");
        look = look.substring(look.indexOf(':') + 1);
        if (item && RIDDEN_LOOKS.contains(look) && JUST_FOR_FUN.contains(pick.first("power"))) {
            return true;
        }
        if (buildingLead(pick.kind(), toks) != null) {
            return true;   //: one colour on a building could be all over or a lead: both tiles, never sure
        }
        return toks.stream().anyMatch(WEAR_WORDS::contains) && !(item && WEARABLE.contains(pick.first("shape")));
    }

    /** [[the number as typed, what the tile makes it]] when the tile does not make the number he typed. */
    private List<List<String>> numberNote(Keywords got, Pick pick) {
        Counted c = counted(got, pick);
        if (c == null) {
            return List.of();
        }
        String have = pick.first(c.slot());
        if (have.isEmpty()) {
            if (c.slot().equals("amount")) {
                BuilderTree.Slot from = tree.kind(pick.kind()).form(pick.form()).slot("from");
                BuilderTree.Entry e = from == null || pick.first("from").isEmpty() ? null : from.entry(pick.first("from"));
                have = e == null ? "1" : e.facts().getOrDefault("usual", "1");
            } else {
                have = "1";
            }
        }
        return Integer.parseInt(have) == c.n() ? List.of()
                : List.of(List.of(String.join(" ", got.toks().subList(c.a(), c.b())), have));
    }

    private static boolean digits(String w) {
        for (int i = 0; i < w.length(); i++) {
            if (w.charAt(i) < '0' || w.charAt(i) > '9') {
                return false;
            }
        }
        return !w.isEmpty();
    }

    /** What a typed word may be. Exact first; a misspelling only when it is neither filler nor a real word. */
    private List<Reading> readings(String word) {
        List<Reading> cached = readings.get(word);
        if (cached != null) {
            return cached;
        }
        List<Reading> out = read(word);
        if (readings.size() < 4096) {
            readings.put(word, out);
        }
        return out;
    }

    private List<Reading> read(String word) {
        String s = stem(word);
        if (vocab.contains(s)) {
            return List.of(new Reading(s, 3));
        }
        if (vocab.contains(word)) {
            return List.of(new Reading(word, 3));
        }
        if (STOP.contains(word) || word.length() < 3 || digits(word) || english.contains(word) || english.contains(s)) {
            return List.of();
        }
        // Compared by first SOUND too, so "kastle" and "castle" start alike. A three-letter word ("cta") is respelt
        // only into a word of three or four letters, by a slip it really takes (slip3), and only when exactly one fits.
        String ls = lead(s);
        int limit = word.length() < 8 ? 1 : 2;
        int best = limit + 1;
        List<String> found = new ArrayList<>();
        for (String v : targets) {
            if (v.length() < 3 || v.length() == 3 && !(s.length() == 3 || s.length() == 4 && dropsADouble(s, v))
                    || s.length() == 3 && v.length() > 4) {
                continue;
            }
            int d = near(s, ls, v, limit, false);
            if (d < best) {
                best = d;
                found.clear();
                found.add(v);
            } else if (d == best && d <= limit) {
                found.add(v);
            }
        }
        if (s.length() == 3) {
            found.removeIf(v -> !slip3(s, v) && !slip3(ls, leads.get(v)));
        }
        if (found.size() > 1 && s.length() > 3) {
            // two words equally near, and the typo is one of them with a letter left out ('gren' is green, not
            // grey; 'goey' gooey): that one. apprentice_match.Index._readings, the same rule.
            List<String> dropped = found.stream().filter(v -> isSubsequence(s, v)).toList();
            if (!dropped.isEmpty() && dropped.size() < found.size()) {
                found = new ArrayList<>(dropped);
            }
        }
        if (!found.isEmpty() && best <= limit && (s.length() > 3 || found.size() == 1)) {
            found.sort(Comparator.naturalOrder());
            List<Reading> out = new ArrayList<>();
            found.forEach(v -> out.add(new Reading(v, 2)));
            return out;
        }
        if (word.length() >= 5) {
            TreeSet<String> same = new TreeSet<>();
            for (String key : List.of(sound(s, false), sound(s, true))) {
                if (key.length() >= 3 && sounds.containsKey(key)) {
                    same.addAll(sounds.get(key));
                }
            }
            if (!same.isEmpty()) {
                List<Reading> out = new ArrayList<>();
                same.forEach(v -> out.add(new Reading(v, 1)));
                return out;
            }
        }
        if (word.length() >= 6 && word.length() < 8) {
            // Two slips in a word of six or seven letters ("wniggs", "peunin"; at five, "latte" became "little"): only
            // into a word of five letters or more with the same consonants or one left out (never a different
            // consonant, which is how "piglin" became "piglet" and "swimmy" "slimy"), and only when exactly one fits.
            String only = null;
            int count = 0;
            for (String v : targets) {
                if (v.length() >= 5 && near(s, ls, v, 2, true) <= 2) {
                    count++;
                    only = v;
                }
            }
            if (count == 1) {
                return List.of(new Reading(only, 1));
            }
        }
        return List.of();
    }

    /** A four-letter stem that is a three-letter word with one letter doubled ("catt", "caat"). */
    private static boolean dropsADouble(String s, String v) {
        for (int i = 1; i < 4; i++) {
            if (s.charAt(i) == s.charAt(i - 1) && (s.substring(0, i) + s.substring(i + 1)).equals(v)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Edit distance from a typed stem to a tree word, starting alike either as written ("cihcken", "chicken"), by
     * first sound ("kastle", "castle") or with ch and sh heard alike ("shicken"); {@code limit + 1} when they start
     * apart. With {@code skeleton}, the pairing used must also agree on its consonants ({@link #oneConsonantOff}).
     */
    private int near(String s, String ls, String v, int limit, boolean skeleton) {
        String lv = leads.get(v);
        int best = limit + 1;
        List<String[]> pairs = new ArrayList<>(List.of(new String[] {s, v}, new String[] {ls, lv}));
        if (s.length() >= 2 && v.length() >= 2) {
            String a = s.substring(0, 2), b = v.substring(0, 2);
            if ((a.equals("sh") || a.equals("ch")) && (b.equals("sh") || b.equals("ch")) && !a.equals(b)) {
                pairs.add(new String[] {b + s.substring(2), v});
            }
        }
        for (String[] p : pairs) {
            if (!p[0].isEmpty() && !p[1].isEmpty() && p[0].charAt(0) == p[1].charAt(0) && (!skeleton || oneConsonantOff(p[0], p[1]))) {
                best = Math.min(best, distance(p[0], p[1], limit));
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ typed words, put back together
    //
    // Before any word is looked up, the slips that change WHERE the words are, not how one is spelt, are undone:
    // letters held down ("caaat"), two words run together ("soupgiant"), one word broken in two ("crys tals"), and a
    // joining word mistyped ("taht"), which would otherwise read as an unknown word and hide a clause boundary.
    // tools/apprentice_match.py (Index.normalize) is the reference; the typo suite (docs/APPRENTICE.md §4⅞) holds both.

    /** A word that is something as typed: the tree's, a filler word, a real English word or a number. */
    private boolean known(String w) {
        String s = stem(w);
        return vocab.contains(w) || vocab.contains(s) || STOP.contains(w) || CONNECT.contains(w) || ARTICLES.contains(w)
                || english.contains(w) || english.contains(s) || digits(w);
    }

    private boolean ours(String w) {
        return vocab.contains(w) || vocab.contains(stem(w));
    }

    /** The tree's word, and not a filler word some phrase happens to hold ("all" of "all blocks"): "snowb all" is a snowball. */
    private boolean meant(String w) {
        return ours(w) && !filler(w);
    }

    private static boolean filler(String w) {
        return STOP.contains(w) || CONNECT.contains(w) || ARTICLES.contains(w);
    }

    private boolean lost(String w) {
        return !known(w) && readings(w).isEmpty();
    }

    /** "caaat" -> "cat", "greeeen" -> "green", "sooo" -> "so": a run of three or more is two of the letter or one. */
    private String unstretch(String w) {
        if (!w.matches(".*(.)\\1\\1.*")) {
            return w;
        }
        String two = w.replaceAll("(.)\\1{2,}", "$1$1"), one = w.replaceAll("(.)\\1{2,}", "$1");
        for (String c : List.of(two, one)) {
            if (known(c)) {
                return c;
            }
        }
        for (String c : List.of(two, one)) {
            if (!readings(c).isEmpty()) {
                return c;
            }
        }
        return one;
    }

    /**
     * "soupgiant" -> "soup giant", "dragonthat" -> "dragon that": a word nothing reads, cut where each half is the
     * tree's word or a filler word (never merely English: "cathes" is not "cat hes"; a two-letter filler only in
     * front, "itexplodes", since at the back it is a word ending as often: "swimmy" is not "swim my"), not both
     * filler. The cut with more of the tree's words wins, then the longer of them, then the earlier cut.
     */
    private List<String> unmerge(String w) {
        if (w.length() < 5 || !lost(w)) {
            return List.of(w);
        }
        List<String> best = null;
        int[] bestKey = null;
        for (int i = 2; i < w.length() - 1; i++) {
            String a = w.substring(0, i), b = w.substring(i);
            if (!(known(a) && known(b))) {
                continue;
            }
            boolean fa = filler(a), fb = filler(b) && b.length() >= 3;
            boolean oa = ours(a) && !filler(a), ob = ours(b) && !filler(b);
            if (!((oa || fa) && (ob || fb)) || fa && fb) {
                continue;
            }
            int[] key = {-((oa ? 1 : 0) + (ob ? 1 : 0)), -Math.max(oa ? a.length() : 0, ob ? b.length() : 0), i};
            if (bestKey == null || java.util.Arrays.compare(key, bestKey) < 0) {
                bestKey = key;
                best = List.of(a, b);
            }
        }
        return best == null ? List.of(w) : best;
    }

    /**
     * "crys tals" -> "crystals", "lemon ade" -> "lemonade", "drag on" -> "dragon": two words that are one of the
     * tree's words together, when one half is nothing as typed or neither half is the tree's; or two that nothing
     * reads and that the spelling reads together.
     */
    private boolean joins(String a, String b) {
        String j = a + b;
        if (ARTICLES.contains(a) || ARTICLES.contains(b)) {
            // "brea the" -> "breathe": an article ends a word he split only after a half that is no word as typed.
            return ARTICLES.contains(b) && !ARTICLES.contains(a) && ours(j) && !known(a);
        }
        if (ours(j) && (lost(a) || lost(b) || !meant(a) && !meant(b) && !(filler(a) && filler(b)))) {
            return true;
        }
        return lost(a) && lost(b) && !readings(j).isEmpty();
    }

    /**
     * "taht" -> "that", "adn" -> "and", "hte" -> "the": a word nothing reads (or reads only as filler words), one
     * slip from a joining or filler word, is that word (a joining word first, so the clause boundary it marks is not
     * lost). Only a filler word the tree gives no meaning to: "lol" is not "lot" (a count), "duper" not "super".
     */
    private String unslip(String w) {
        if (w.length() < 3 || known(w)) {
            return w;
        }
        for (Reading r : readings(w)) {
            if (!filler(r.stem())) {
                return w;
            }
        }
        String joining = null, any = null;
        for (String f : slips) {
            if (smallSlip(w, f)) {
                if (any == null) {
                    any = f;
                }
                if (joining == null && CONNECT.contains(f)) {
                    joining = f;
                }
            }
        }
        return joining != null ? joining : any != null ? any : w;
    }

    List<String> normalize(List<String> toks) {
        List<String> out = new ArrayList<>();
        for (String t : toks) {
            for (String p : unnumber(unstretch(t))) {   // "fortyeight" is forty eight, never "fourteen"
                out.addAll(unmerge(p));
            }
        }
        List<String> joined = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            if (i + 1 < out.size() && joins(out.get(i), out.get(i + 1))) {
                joined.add(out.get(i) + out.get(i + 1));
                i++;
            } else {
                joined.add(out.get(i));
            }
        }
        List<String> done = new ArrayList<>();
        for (String t : joined) {
            String u = unslip(t);
            if (!ARTICLES.contains(u)) {
                done.add(u);
            }
        }
        return done;
    }

    private List<Found> find(List<String> toks) {
        List<List<Reading>> reads = new ArrayList<>();
        for (String t : toks) {
            reads.add(readings(t));
        }
        List<Found> out = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) {
            for (Reading r0 : reads.get(i)) {
                for (Term term : terms.getOrDefault(r0.stem(), List.of())) {
                    String[] stems = term.stems();
                    if (i + stems.length > toks.size()) {
                        continue;
                    }
                    int q = r0.quality();
                    for (int j = 1; j < stems.length && q > 0; j++) {
                        int qj = 0;
                        for (Reading r : reads.get(i + j)) {
                            if (r.stem().equals(stems[j])) {
                                qj = Math.max(qj, r.quality());
                            }
                        }
                        q = qj == 0 ? 0 : Math.min(q, qj);
                    }
                    if (q > 0) {
                        out.add(new Found(i, i + stems.length, q, term.ref(), stems));
                    }
                }
            }
        }
        return oneWordOnePick(out, reads);
    }

    /**
     * step 6: a misspelt word that could be two different words of ONE kind of thing, in two different slots,
     * takes neither ({@code apprentice_match._one_word_one_pick}, the reference this must equal). 'taol' is one slip
     * from a creature's 'tail' part and from its size 'tall'; read as both it offered the tail AND a size he never asked
     * for, as sure. Per kind, because 'beek' may be a creature's beak or a machine's beef and the rest of the wish says
     * which kind; across slots only, because inside one slot the rest of the wish already chooses. An exact word, a
     * word that is itself two picks, and a phrase of several words are untouched.
     */
    private List<Found> oneWordOnePick(List<Found> hits, List<List<Reading>> reads) {
        java.util.Set<Integer> torn = new java.util.HashSet<>();
        for (int i = 0; i < reads.size(); i++) {
            List<String> words = new ArrayList<>();
            for (Reading r : reads.get(i)) {
                if (r.quality() == 2) {
                    words.add(r.stem());
                }
            }
            if (words.size() < 2) {
                continue;
            }
            Map<String, Map<String, java.util.Set<String>>> byKind = new LinkedHashMap<>();
            for (String w : words) {
                for (Term term : terms.getOrDefault(w, List.of())) {
                    if (term.stems().length == 1 && !term.ref().kindWord()) {
                        byKind.computeIfAbsent(term.ref().kind(), k -> new LinkedHashMap<>())
                                .computeIfAbsent(w, k -> new java.util.HashSet<>()).add(term.ref().slot());
                    }
                }
            }
            for (Map<String, java.util.Set<String>> perWord : byKind.values()) {
                if (perWord.size() < 2) {
                    continue;
                }
                java.util.Set<String> common = null;
                for (java.util.Set<String> slots : perWord.values()) {
                    if (common == null) {
                        common = new java.util.HashSet<>(slots);
                    } else {
                        common.retainAll(slots);
                    }
                }
                if (common.isEmpty()) {
                    torn.add(i);
                    break;
                }
            }
        }
        if (torn.isEmpty()) {
            return hits;
        }
        List<Found> kept = new ArrayList<>();
        for (Found h : hits) {
            if (!(torn.contains(h.a()) && h.q() == 2 && h.b() == h.a() + 1)) {
                kept.add(h);
            }
        }
        return kept;
    }

    // ------------------------------------------------------------------ the match

    public Answer match(String text) {
        Keywords got = keywords(text);
        return withNumber(got, withFound(got, got.answer()).answer());
    }

    static final Set<String> NEGATIONS = Set.of("never", "not", "no", "without", "nothing", "none");
    static final Set<String> NT_STEMS = Set.of("don", "doesn", "didn", "won", "wouldn", "can", "couldn", "isn", "aren", "wasn",
            "weren", "shouldn", "haven", "hasn");
    /**
     * the bodies made to be ridden whose creature twin is never a tile. The griffin is left out on purpose: a
     * child may want one as a pet.
     */
    static final Set<String> RIDDEN_ONLY = Set.of("bodies/boat", "bodies/car", "bodies/flying_saucer", "bodies/helicopter",
            "bodies/hot_air_balloon", "bodies/motorbike", "bodies/plane", "bodies/rocket", "bodies/submarine", "bodies/tank",
            "bodies/truck");

    /**
     * the token positions a negation covers: after never/not/no/without or a "-n't" word ("doesn t"), up to the
     * next joining word or the end. Their words are not read; they become what a tile "can't do".
     */
    static Set<Integer> negated(List<String> toks) {
        return negated(toks, Map.of());
    }

    /**
     * …where {@code spoken} maps a negation's position to the end of the Builder's own phrase that SAYS it ("not too
     * strong"): that negation ends with the phrase, so "not too strong five at a time" still reads the five (step
     * 6; {@code apprentice_match.negated}, the reference this must equal).
     */
    static Set<Integer> negated(List<String> toks, Map<Integer, Integer> spoken) {
        Set<Integer> out = new HashSet<>();
        boolean on = false;
        Integer stop = null;
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);
            if (stop != null && i >= stop) {
                on = false;
                stop = null;
            }
            if (CONNECT.contains(t)) {
                on = false;
                stop = null;
                continue;
            }
            if (on) {
                out.add(i);
            }
            if (NEGATIONS.contains(t) || (t.equals("t") && i > 0 && NT_STEMS.contains(toks.get(i - 1)))) {
                on = true;
                stop = spoken.get(i);
            }
        }
        return out;
    }

    /** The positions of the negation words themselves ("never", "not", and both pieces of "doesn t"). */
    static Set<Integer> triggers(List<String> toks) {
        Set<Integer> out = new HashSet<>();
        for (int i = 0; i < toks.size(); i++) {
            if (NEGATIONS.contains(toks.get(i))) {
                out.add(i);
            } else if (toks.get(i).equals("t") && i > 0 && NT_STEMS.contains(toks.get(i - 1))) {
                out.add(i - 1);
                out.add(i);
            }
        }
        return out;
    }

    private Keywords keywords(String text) {
        List<String> toks = normalize(wordsOf(text, true));
        List<Found> found = find(toks);
        // nothing is read from a negated stretch ("a horse that NEVER runs away" is not a shy horse).
        // A phrase that SAYS the negation is read as written: "never leaves" and "not too strong" are the Builder's own words.
        Set<Integer> trig = triggers(toks);
        Map<Integer, Integer> spoken = new HashMap<>();
        for (int t : trig) {
            for (Found m : found) {
                if (m.a() <= t && t < m.b()) {
                    spoken.merge(t, m.b(), Math::max);
                }
            }
        }
        Set<Integer> neg = negated(toks, spoken);
        if (!neg.isEmpty()) {
            found.removeIf(m -> {
                boolean inNeg = false;
                boolean saysIt = false;
                for (int i = m.a(); i < m.b(); i++) {
                    inNeg |= neg.contains(i);
                    saysIt |= trig.contains(i);
                }
                return inNeg && !saysIt;
            });
        }
        Map<String, Set<Integer>> kindHits = new LinkedHashMap<>();
        for (Found m : found) {
            if (m.ref().kindWord()) {
                Set<Integer> s = kindHits.computeIfAbsent(m.ref().kind(), x -> new TreeSet<>());
                for (int i = m.a(); i < m.b(); i++) {
                    s.add(i);
                }
            }
        }
        List<Candidate> cands = new ArrayList<>();
        for (BuilderTree.Kind k : tree.kinds()) {
            for (BuilderTree.Form f : k.forms()) {
                List<Found> own = new ArrayList<>();
                for (Found m : found) {
                    if (!m.ref().kindWord() && m.ref().kind().equals(k.id()) && m.ref().form().equals(f.id())) {
                        own.add(m);
                    }
                }
                cands.addAll(fill(k, f, own, kindHits, toks));
            }
        }
        // A creature he can ride IS the ride form: keep the one tile.
        Set<String> rides = new HashSet<>();
        for (Candidate c : cands) {
            if (c.pick().kind().equals("ride")) {
                rides.add(c.pick().first("from"));
            }
        }
        cands.removeIf(c -> c.pick().kind().equals("creature")
                && c.pick().picks().getOrDefault("powers", List.of()).contains("power:rideable")
                && rides.contains(c.pick().first("from")));
        // a body made to be ridden (a car, a boat, a motorbike) is never offered as a creature he cannot ride.
        cands.removeIf(c -> {
            if (!c.pick().kind().equals("creature") || !RIDDEN_ONLY.contains(c.pick().first("from"))) {
                return false;
            }
            BuilderTree.Slot from = tree.kind("creature").form(c.pick().form()).slot("from");
            BuilderTree.Entry e = from == null ? null : from.entry(c.pick().first("from"));
            return e != null && "1".equals(e.facts().get("ridden"));
        });
        cands.sort(Comparator.<Candidate>comparingInt(c -> -c.score())
                .thenComparingInt(c -> KIND_ORDER.indexOf(c.pick().kind())).thenComparingInt(Candidate::order));
        List<Candidate> offers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Candidate c : cands) {
            String key = key(filled(tree, c.pick()));   // two picks that differ only by a default are one make
            if (seen.contains(key) || c.score() * 2 < cands.get(0).score()) {
                continue;   // far behind the best reading: not a choice worth a tile
            }
            boolean inside = false;
            for (int[] a : c.anchors()) {
                for (int[] t : cands.get(0).spans()) {
                    // A pick the form supplied for a word (a zero-width anchor) reads no word: nobody's fragment.
                    if (a[1] > a[0] && a[0] >= t[0] && a[1] <= t[1] && a[1] - a[0] < t[1] - t[0]) {
                        inside = true;   // it reads one word of a phrase the best reading read whole
                    }
                }
            }
            if (inside && cands.get(0).used().containsAll(c.used())) {
                continue;   // ...and no word the best reading did not
            }
            seen.add(key);
            offers.add(c);
            if (offers.size() == MOST_OFFERS) {
                break;
            }
        }
        // the colour rule on the keyword answer, so the trained layer compares the colours the page will show.
        for (int i = 0; i < offers.size(); i++) {
            Candidate c = offers.get(i);
            String now = colourScheme(toks, c.pick().kind(), c.pick().first("scheme"));
            if (!now.equals(c.pick().first("scheme"))) {
                offers.set(i, new Candidate(withScheme(c.pick(), now), c.score(), c.order(), c.used(), c.spans(),
                        c.anchors(), c.lost()));
            }
        }
        // A colour he named is on the top tile, and the same make as it comes is always among the tiles after it:
        // a colour word is only ever the nearest scheme, so the plain one stays one press away.
        if (!offers.isEmpty() && !offers.get(0).pick().first("scheme").isEmpty()) {
            Pick top = offers.get(0).pick();
            Map<String, List<String>> plain = new LinkedHashMap<>(top.picks());
            plain.remove("scheme");
            String plainKey = key(filled(tree, new Pick(top.kind(), top.form(), plain)));
            boolean there = false;
            for (Candidate o : offers) {
                there |= key(filled(tree, o.pick())).equals(plainKey);
            }
            if (!there) {
                for (Candidate c : cands) {
                    if (key(filled(tree, c.pick())).equals(plainKey)) {
                        if (offers.size() == MOST_OFFERS) {
                            offers.remove(offers.size() - 1);
                        }
                        offers.add(c);
                        break;
                    }
                }
            }
        }
        List<Integer> content = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) {
            if (!STOP.contains(toks.get(i)) && !isNum(toks.get(i))) {
                content.add(i);
            }
        }
        List<String> unmatched = new ArrayList<>();
        if (offers.isEmpty()) {
            content.forEach(i -> unmatched.add(toks.get(i)));
            return new Keywords(List.of(), toks, List.of(), unmatched, Reach.OUT);
        }
        Set<Integer> used = new HashSet<>(offers.get(0).used());
        kindHits.values().forEach(used::addAll);
        used.removeAll(offers.get(0).lost());
        List<Integer> unread = new ArrayList<>();
        for (int i : content) {
            if (!used.contains(i)) {
                unread.add(i);
                unmatched.add(toks.get(i));
            }
        }
        return new Keywords(offers, toks, unread, unmatched, unmatched.isEmpty() ? Reach.IN : Reach.PART);
    }

    // ------------------------------------------------------------------ how sure the top tile is

    /** The collaborate mode's bands (docs/APPRENTICE.md §8): SURE shows the page, the rest go to the Magician. */
    public enum Band { SURE, CLOSE, NOTHING }

    /**
     * How sure this page is, and its stratum ({@code auto}, {@code sure}, {@code close_hi}, {@code close_lo},
     * {@code nothing}). tools/apprentice_match.py {@code confidence} is the reference; the back-test's --sync holds
     * them equal.
     */
    public record Confidence(Band band, String stratum) {
    }

    public Confidence confidence(String text, Answer answer) {
        Keywords kw = keywords(text);
        int content = 0;
        for (String t : kw.toks()) {
            if (!STOP.contains(t) && !isNum(t)) {
                content++;
            }
        }
        // A found thing is first exactly when it tops the page (with_found in the reference).
        boolean first = !answer.offers().isEmpty() && isFound(answer.offers().get(0));
        int read = content - (first ? answer.unmatched().size() : kw.unread().size());
        if (answer.offers().isEmpty() || (!first && kw.offers().isEmpty()) || read * 2 < content) {
            return new Confidence(Band.NOTHING, "nothing");
        }
        boolean sure = answer.reach() == Reach.IN
                && notes(text, first ? answer.offers().get(0) : kw.offers().get(0).pick()).empty()
                && numberNote(kw, answer.offers().get(0)).isEmpty()   //: a number it had to move is not sure
                && !unsure(kw.toks(), filled(tree, answer.offers().get(0)));   //: to ride or wear, it must be
        if (sure) {
            Pick top = answer.offers().get(0);
            int family = 0;
            for (Pick o : answer.offers().subList(1, answer.offers().size())) {
                if (o.kind().equals(top.kind()) && o.form().equals(top.form())
                        && java.util.Objects.equals(o.picks().get("from"), top.picks().get("from"))) {
                    family++;
                }
            }
            return new Confidence(Band.SURE, family == 0 && content >= 2 ? "auto" : "sure");
        }
        return new Confidence(Band.CLOSE, read * 4 >= content * 3 ? "close_hi" : "close_lo");
    }

    // ------------------------------------------------------------------ what a tile leaves out, named

    /**
     * What one tile does with his words (a tile may grant what it can if it says,
     * deterministically, what it cannot do). {@code cant}: the meaningful words, as read after typo repair,
     * that this tile does not keep. {@code near}: each word it used only as a STAND-IN for one of its picks
     * ({@code apprentice/standins.json}), with that pick's label. tools/apprentice_match.py {@code notes} is the
     * reference; the back-test's --sync holds the two equal.
     */
    public record Notes(List<String> cant, List<List<String>> near) {
        public boolean empty() {
            return cant.isEmpty() && near.isEmpty();
        }
    }

    public Notes notes(String text, Pick pick) {
        List<String> toks = normalize(wordsOf(text, true));
        if (isFound(pick)) {   //: an installed thing keeps the words that named it
            FoundThing f = found(keywords(text), pick.first("from"));
            Set<Integer> kept = f == null ? Set.of() : f.kept();
            List<String> cant = new ArrayList<>();
            for (int i = 0; i < toks.size(); i++) {
                if (!STOP.contains(toks.get(i)) && !isNum(toks.get(i)) && !kept.contains(i)) {
                    cant.add(toks.get(i));
                }
            }
            List<List<String>> near = new ArrayList<>();
            if (f != null && f.said() != null) {
                near.add(f.said());
            }
            near.addAll(numberNote(keywords(text), pick));
            return new Notes(cant, near);
        }
        return notesOn(keywords(text), pick);
    }

    private Notes notesOn(Keywords got, Pick pick) {
        List<String> toks = got.toks();
        Map<String, Set<String>> exact = exactWords();
        Set<Integer> kept = new HashSet<>();
        List<Found> mine = new ArrayList<>();
        for (Found m : find(toks)) {
            Ref r = m.ref();
            if (r.kindWord()) {
                if (r.kind().equals(pick.kind())) {
                    for (int i = m.a(); i < m.b(); i++) {
                        kept.add(i);
                    }
                }
            } else if (r.kind().equals(pick.kind()) && r.form().equals(pick.form())
                    && pick.picks().getOrDefault(r.slot(), List.of()).contains(r.entry())) {
                for (int i = m.a(); i < m.b(); i++) {
                    kept.add(i);
                }
                if (exact.containsKey(r.slot() + "|" + r.entry())) {
                    mine.add(m);
                }
            }
        }
        List<String> cant = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) {
            if (!STOP.contains(toks.get(i)) && !isNum(toks.get(i)) && !kept.contains(i)) {
                cant.add(toks.get(i));
            }
        }
        TreeSet<String> near = new TreeSet<>();
        for (List<String> n : numberNote(got, pick)) {   //
            near.add(n.get(0) + "\u0000" + n.get(1));
        }
        for (Found m : mine) {
            boolean inside = false;
            for (Found o : mine) {
                if (o.ref().slot().equals(m.ref().slot()) && o.ref().entry().equals(m.ref().entry())
                        && o.a() <= m.a() && m.b() <= o.b() && (o.b() - o.a()) > (m.b() - m.a())) {
                    inside = true;   // "car" inside "race car" is not a second, exact reading
                }
            }
            if (!inside && !exact.get(m.ref().slot() + "|" + m.ref().entry()).contains(String.join(" ", m.stems()))) {
                BuilderTree.Entry e = tree.kind(pick.kind()).form(pick.form()).slot(m.ref().slot()).entry(m.ref().entry());
                near.add(String.join(" ", toks.subList(m.a(), m.b())) + "\u0000" + e.label());
            }
        }
        List<List<String>> pairs = new ArrayList<>();
        for (String n : near) {
            String[] two = n.split("\u0000", 2);
            pairs.add(List.of(two[0], two[1]));
        }
        return new Notes(cant, pairs);
    }

    private volatile Map<String, Set<String>> exactWords;

    /**
     * {slot|entry: stemmed phrases that name the pick itself}. A word {@code apprentice/standins.json} has not
     * classified is NOT in it: a pick added or reworded is never sure until tools/apprentice-standins.py has read it.
     * The label always is.
     */
    private Map<String, Set<String>> exactWords() {
        Map<String, Set<String>> have = exactWords;
        if (have != null) {
            return have;
        }
        com.google.gson.JsonObject picks = new com.google.gson.JsonObject();
        try (InputStream in = Apprentice.class.getClassLoader().getResourceAsStream("apprentice/standins.json")) {
            if (in != null) {
                picks = com.google.gson.JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("picks");
            }
        } catch (IOException e) {
            throw new IllegalStateException("apprentice/standins.json could not be read", e);
        }
        Map<String, Set<String>> out = new HashMap<>();
        for (BuilderTree.Kind k : tree.kinds()) {
            for (BuilderTree.Form f : k.forms()) {
                for (BuilderTree.Slot s : f.slots()) {
                    if (s.steps()) {
                        continue;
                    }
                    for (BuilderTree.Entry e : s.entries()) {
                        String key = s.id() + "|" + e.id();
                        Set<String> ok = new java.util.LinkedHashSet<>();
                        com.google.gson.JsonObject row = picks.has(key) ? picks.getAsJsonObject(key) : null;
                        if (row != null) {
                            Set<String> nearWords = new HashSet<>();
                            row.getAsJsonArray("near").forEach(x -> nearWords.add(x.getAsString()));
                            row.getAsJsonArray("words").forEach(x -> {
                                if (!nearWords.contains(x.getAsString())) {
                                    ok.add(x.getAsString());
                                }
                            });
                        }
                        ok.add(BuilderTree.normal(e.label()));
                        Set<String> stemmed = out.computeIfAbsent(key, x -> new HashSet<>());
                        for (String w : ok) {
                            List<String> st = new ArrayList<>();
                            for (String t : w.split(" ")) {
                                if (!t.isEmpty() && !ARTICLES.contains(t)) {
                                    st.add(stem(t));
                                }
                            }
                            stemmed.add(String.join(" ", st));
                        }
                    }
                }
            }
        }
        exactWords = out;
        return out;
    }

    // ------------------------------------------------------------------ any installed thing ()
    //
    // Besides the Builder's own picks, his words may NAME a real installed creature or item ("a bee", "golden
    // apples", "a cake"). content/Found (the same search as the Magician's find_content) finds it, and it becomes a
    // make: a real creature (creature/animal, "mob:<id>") or a Supplies of the item (supplies/ready, "item:<id>").
    // Only an exact name counts (Tier NAME); size and count words are never read as things ("giant" is a size, not
    // minecraft:giant). A found thing goes FIRST only when it keeps more of his words than the keyword top tile keeps
    // exactly, or the same words; otherwise it is a later tile. tools/apprentice_match.py `found` / `with_found`.

    /**
     * {@code said}: null for an exact name, else [the words as typed, the thing's name], a misspelt name put right
     *, which {@link #notes} names as a stand-in, so a page that rests on it is never sure.
     */
    private record FoundThing(Pick pick, Set<Integer> kept, List<String> said) {
    }

    private record Paged(Answer answer, boolean first) {
    }

    private volatile Set<String> stepWords;

    /** Every one-word phrase a size, count or amount slot lists, stemmed: never read as a thing. */
    private Set<String> stepWords() {
        Set<String> have = stepWords;
        if (have != null) {
            return have;
        }
        Set<String> out = new HashSet<>();
        for (BuilderTree.Kind k : tree.kinds()) {
            for (BuilderTree.Form f : k.forms()) {
                for (BuilderTree.Slot s : f.slots()) {
                    if (s.steps()) {
                        for (BuilderTree.Entry e : s.entries()) {
                            for (String w : e.words()) {
                                String phrase = BuilderTree.Term.parse(w).phrase();
                                if (!phrase.contains(" ")) {   // "cat 5" is no reason never to read "cat" as a cat
                                    out.add(stem(phrase));
                                }
                            }
                        }
                    }
                }
            }
        }
        stepWords = out;
        return out;
    }

    /** {value, token index} of a count word for this slot ("two", "64"), or null. */
    private String[] foundCount(List<String> toks, String kind, String form, String slot) {
        BuilderTree.Kind k = tree.kind(kind);
        BuilderTree.Form f = k == null ? null : k.form(form);
        BuilderTree.Slot s = f == null ? null : f.slot(slot);
        if (s == null) {
            return null;
        }
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);
            for (BuilderTree.Entry e : s.entries()) {
                boolean said = t.equals(e.id());
                for (String w : e.words()) {
                    said |= !w.contains(" ") && stem(w).equals(t);
                }
                if (said) {
                    return new String[] {e.id(), String.valueOf(i)};
                }
            }
        }
        return null;
    }

    /** The installed thing his words name, with the token indexes it keeps; null if none. {@code want}: only that id. */
    private FoundThing found(Keywords got, String want) {
        String key = String.join(" ", got.toks()) + "|" + want;
        java.util.Optional<FoundThing> have = foundCache.get(key);
        if (have == null) {
            have = java.util.Optional.ofNullable(findThing(got, want));
            if (foundCache.size() > 4096) {
                foundCache.clear();
            }
            foundCache.put(key, have);
        }
        return have.orElse(null);
    }

    private final Map<String, java.util.Optional<FoundThing>> foundCache = new ConcurrentHashMap<>();

    private FoundThing findThing(Keywords got, String want) {
        List<String> toks = got.toks();
        Set<String> steps = stepWords();
        com.moddingfromamod.content.ContentIndex ix = com.moddingfromamod.content.Found.index();
        Set<com.moddingfromamod.content.ContentIndex.Kind> kinds = Set.of(com.moddingfromamod.content.ContentIndex.Kind.MOB,
                com.moddingfromamod.content.ContentIndex.Kind.ITEM);
        // The words the Builder's keywords read EXACTLY as typed; any other word (unread, or read only through a typo
        // repair or a stand-in) may be put right as an installed thing's name.
        Set<Integer> exactRead = new HashSet<>();
        for (Found m : find(toks)) {
            List<String> typed = new ArrayList<>();
            for (int j = m.a(); j < m.b(); j++) {
                typed.add(stem(toks.get(j)));
            }
            if (String.join(" ", m.stems()).equals(String.join(" ", typed))) {
                for (int j = m.a(); j < m.b(); j++) {
                    exactRead.add(j);
                }
            }
        }
        for (boolean respell : new boolean[] {false, true}) {   // every exact name first, anywhere; then a misspelt one
        for (int length = 4; length >= 1; length--) {
            for (int i = 0; i + length <= toks.size(); i++) {
                List<String> words = toks.subList(i, i + length);
                boolean skip = false;
                for (String w : words) {
                    skip |= STOP.contains(w) || digits(w) || steps.contains(w) || CONNECT.contains(w);
                }
                if (skip) {
                    continue;
                }
                if (respell) {
                    // Only words the keywords did not read exactly are put right, and at least one must be: "a carrot"
                    // is never a parrot. A word the index knows is never respelt (readings keeps it as typed).
                    boolean allExact = true;
                    boolean knownOnly = true;
                    for (int j = i; j < i + length; j++) {
                        allExact &= exactRead.contains(j);
                        knownOnly &= !exactRead.contains(j) || ix.knows(stem(toks.get(j)));
                    }
                    if (allExact || !knownOnly) {
                        continue;
                    }
                }
                List<com.moddingfromamod.content.ContentIndex.Hit> hits = new ArrayList<>();
                for (var h : respell ? ix.respelt(String.join(" ", words), kinds, 6)
                        : ix.named(String.join(" ", words), kinds, 6)) {   // named = search's top 6, Tier NAME
                    String id = (h.entry().kind() == com.moddingfromamod.content.ContentIndex.Kind.MOB ? "mob:" : "item:")
                            + h.entry().id();
                    boolean here = h.entry().kind() == com.moddingfromamod.content.ContentIndex.Kind.MOB
                            ? hasFrom("creature", "animal") : hasFrom("supplies", "ready");   // this world's tree has it
                    if (here && !h.entry().id().startsWith(com.moddingfromamod.content.Found.OWN)
                            // never an item the Builder's own Find refuses ("Uncraftable Potion")
                            && !(h.entry().kind() == com.moddingfromamod.content.ContentIndex.Kind.ITEM
                                 && BuilderFind.refused(h.entry().id()))
                            && (want == null || want.equals(id))) {
                        hits.add(h);
                    }
                }
                if (hits.isEmpty()) {
                    continue;
                }
                var h = hits.stream().filter(x -> x.entry().kind() == com.moddingfromamod.content.ContentIndex.Kind.MOB)
                        .findFirst().orElse(hits.get(0));
                if (listed(h.entry())) {
                    continue;   // the menu's own pick ("a chicken", "some sticks") is the keywords' to read
                }
                Set<Integer> kept = new HashSet<>();
                for (int j = i; j < i + length; j++) {
                    kept.add(j);
                }
                boolean mob = h.entry().kind() == com.moddingfromamod.content.ContentIndex.Kind.MOB;
                String kind = mob ? "creature" : "supplies";
                String form = mob ? "animal" : "ready";
                String countSlot = mob ? "count" : "amount";
                Map<String, List<String>> picks = new LinkedHashMap<>();
                picks.put("from", List.of((mob ? "mob:" : "item:") + h.entry().id()));
                String[] n = foundCount(toks, kind, form, countSlot);
                if (n != null && !n[0].isEmpty()) {
                    picks.put(countSlot, List.of(n[0]));
                    kept.add(Integer.parseInt(n[1]));
                }
                // "a giant skeleton" is a skeleton at Giant; "wolves that are my friends" are friendly
                for (String slot : mob ? List.of("mobsize", BuilderActs.SLOT) : List.<String>of()) {
                    Found size = foundSize(toks, slot);
                    if (size != null) {
                        picks.put(slot, List.of(size.ref().entry()));
                        for (int j = size.a(); j < size.b(); j++) {
                            kept.add(j);
                        }
                    }
                }
                return new FoundThing(new Pick(kind, form, picks), kept,
                        respell ? List.of(String.join(" ", words), h.entry().name()) : null);
            }
        }
        }
        return null;
    }

    /** A real creature's named size in the words, or null: the first by position, then the longest, then the entry id. */
    private Found foundSize(List<String> toks, String slot) {
        Found best = null;
        for (Found m : find(toks)) {
            Ref r = m.ref();
            if (r.kindWord() || !r.kind().equals("creature") || !"animal".equals(r.form()) || !slot.equals(r.slot())) {
                continue;
            }
            if (best == null || m.a() < best.a() || m.a() == best.a() && (m.b() - m.a() > best.b() - best.a()
                    || m.b() - m.a() == best.b() - best.a() && m.ref().entry().compareTo(best.ref().entry()) < 0)) {
                best = m;
            }
        }
        return best;
    }

    /** Is this installed thing already one of the menu's own picks ("mob:minecraft:cow", "supply:stick")? */
    private boolean listed(com.moddingfromamod.content.ContentIndex.Entry e) {
        boolean mob = e.kind() == com.moddingfromamod.content.ContentIndex.Kind.MOB;
        BuilderTree.Slot s = tree.kind(mob ? "creature" : "supplies").form(mob ? "animal" : "ready").slot("from");
        String id = mob ? "mob:" + e.id()
                : e.id().startsWith("minecraft:") ? "supply:" + e.id().substring("minecraft:".length()) : null;
        return id != null && s.entries().stream().anyMatch(x -> x.id().equals(id));
    }

    private boolean hasFrom(String kind, String form) {
        BuilderTree.Kind k = tree.kind(kind);
        return k != null && k.form(form) != null && k.form(form).slot("from") != null;
    }

    /** Is this pick's From an installed thing found by name, not one of the menu's own picks? */
    public boolean isFound(Pick pick) {
        if (!(pick.kind().equals("creature") && pick.form().equals("animal"))
                && !(pick.kind().equals("supplies") && pick.form().equals("ready"))) {
            return false;
        }
        BuilderTree.Kind k = tree.kind(pick.kind());
        BuilderTree.Form f = k == null ? null : k.form(pick.form());
        BuilderTree.Slot s = f == null ? null : f.slot("from");
        return s != null && s.isFound(pick.first("from"));
    }

    /** The page with the found thing in it: first when it keeps more words than the top tile keeps exactly. */
    private Paged withFound(Keywords got, Answer page) {
        FoundThing f = found(got, null);
        if (f == null) {
            return new Paged(page, false);
        }
        List<String> toks = got.toks();
        List<Integer> content = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) {
            if (!STOP.contains(toks.get(i)) && !isNum(toks.get(i))) {
                content.add(i);
            }
        }
        Set<Integer> mine = new HashSet<>();
        for (int i : content) {
            if (f.kept().contains(i)) {
                mine.add(i);
            }
        }
        int m = mine.size();
        int k = 0;
        boolean same = false;
        if (!got.offers().isEmpty()) {
            Notes n = notesOn(got, got.offers().get(0).pick());
            Set<String> lost = new HashSet<>(n.cant());
            for (List<String> pair : n.near()) {
                lost.addAll(List.of(pair.get(0).split(" ")));
            }
            Set<Integer> treeKept = new HashSet<>();
            for (int i : content) {
                if (!lost.contains(toks.get(i))) {
                    treeKept.add(i);
                }
            }
            k = treeKept.size();
            // A tie where both read exactly the same words: the real thing is the literal reading ("64 golden apples"
            // is golden apples, not a rule that a cow drops one).
            same = m == k && mine.equals(treeKept);
            // ...unless the top tile IS the Builder's own one of that name ("a wolf": the Builder's Wolf, then the real one).
            Pick top = got.offers().get(0).pick();
            BuilderTree.Kind tk = tree.kind(top.kind());
            BuilderTree.Slot from = tk == null || tk.form(top.form()) == null ? null : tk.form(top.form()).slot("from");
            BuilderTree.Entry own = from == null || top.first("from").isEmpty() ? null : from.entry(top.first("from"));
            String fid = f.pick().first("from");
            String real = com.moddingfromamod.content.Found.name(fid.startsWith("mob:")
                    ? com.moddingfromamod.content.ContentIndex.Kind.MOB : com.moddingfromamod.content.ContentIndex.Kind.ITEM,
                    fid.substring(fid.indexOf(':') + 1));
            if (same && own != null && real != null && own.label().toLowerCase(Locale.ROOT).equals(real.toLowerCase(Locale.ROOT))) {
                same = false;
            }
        }
        String key = key(filled(tree, f.pick()));
        List<Pick> offers = new ArrayList<>();
        List<List<String>> layer = new ArrayList<>();
        for (int i = 0; i < page.offers().size(); i++) {
            if (!key(filled(tree, page.offers().get(i))).equals(key)) {
                offers.add(page.offers().get(i));
                layer.add(i < page.layer().size() ? page.layer().get(i) : List.of());
            }
        }
        if (page.offers().isEmpty() || m > k || same) {
            List<String> left = new ArrayList<>();
            for (int i : content) {
                if (!f.kept().contains(i)) {
                    left.add(toks.get(i));
                }
            }
            List<Pick> out = new ArrayList<>(List.of(f.pick()));
            out.addAll(offers.subList(0, Math.min(MOST_OFFERS - 1, offers.size())));
            List<List<String>> outLayer = new ArrayList<>(List.of(List.of()));
            outLayer.addAll(layer.subList(0, Math.min(MOST_OFFERS - 1, layer.size())));
            return new Paged(new Answer(out, left.isEmpty() ? Reach.IN : Reach.PART, left, outLayer), true);
        }
        if (offers.size() < MOST_OFFERS && offers.size() == page.offers().size()) {
            List<Pick> out = new ArrayList<>(offers);
            out.add(f.pick());
            List<List<String>> outLayer = new ArrayList<>(layer);
            outLayer.add(List.of());
            return new Paged(new Answer(out, page.reach(), page.unmatched(), outLayer), false);
        }
        return new Paged(page, false);
    }

    // ------------------------------------------------------------------ the trained layer
    //
    // The keywords only know the words somebody listed. A clause they leave partly unread ("that spits flames") is
    // handed to a ClauseReader, which answers with the Builder picks it most likely means, or "none". The rule,
    // exactly as measured and as tools/apprentice_match.py `hybrid` writes it:
    //   * start from the keyword answer; the BASE is everything up to the end of what the wish starts from;
    //   * split the rest into clauses at a connecting word; a clause the keywords read whole is left alone;
    //   * for a clause with an unread word, the layer's best pick the make can still take is ADDED. Where the
    //     keywords had taken a different pick from that clause, the layer's pick replaces it and the keyword make
    //     is the next tile; where they agree, nothing changes;
    //   * the layer's second and third choices are further tiles; "none", or nothing over the threshold, adds nothing;
    //   * a page the layer added to is never IN: a guess is said to be one.

    static final Set<String> CONNECT = Set.of("with", "that", "and", "but", "which", "so", "plus", "also", "in", "make",
            "made", "from");
    /** How many of a reader's answers are looked at. */
    public static final int LAYER_READS = 6;

    /** Does this wish have a clause the layer would be asked about? Cheap: the caller decides whether to wait for a reader. */
    public boolean wantsReader(String text) {
        Keywords got = keywords(text);
        if (got.offers().isEmpty() || got.unread().isEmpty()) {
            return false;
        }
        int end = baseEnd(got);
        for (int i : got.unread()) {
            if (i >= end) {
                return true;
            }
        }
        return false;
    }

    private static int baseEnd(Keywords got) {
        int end = 0;
        for (int[] a : got.offers().get(0).anchors()) {
            end = Math.max(end, a[1]);
        }
        return end;
    }

    /** The keyword answer with the layer's picks added. {@code reader == null} is {@link #match(String)}. */
    public Answer match(String text, ClauseReader reader, double threshold) {
        Keywords got = keywords(text);
        return withNumber(got, withFound(got, hybrid(got, reader, threshold)).answer());
    }

    private Answer hybrid(Keywords got, ClauseReader reader, double threshold) {
        Answer plain = got.answer();
        if (reader == null || got.offers().isEmpty() || got.unread().isEmpty()) {
            return plain;
        }
        List<String> toks = got.toks();
        Set<Integer> unread = new HashSet<>(got.unread());
        int end = baseEnd(got);
        if (end >= toks.size()) {
            return plain;
        }
        List<List<Integer>> clauses = new ArrayList<>();
        clauses.add(new ArrayList<>());
        for (int i = end; i < toks.size(); i++) {
            if (CONNECT.contains(toks.get(i)) && !clauses.get(clauses.size() - 1).isEmpty()) {
                clauses.add(new ArrayList<>());
            }
            clauses.get(clauses.size() - 1).add(i);
        }
        Pick top = got.offers().get(0).pick();
        Pick make = top;
        List<String> mine = new ArrayList<>();
        List<Pick> altPicks = new ArrayList<>();
        List<List<String>> altBy = new ArrayList<>();
        boolean still = false;
        for (List<Integer> cl : clauses) {
            boolean hasContent = false;
            boolean partly = false;
            for (int i : cl) {
                if (!STOP.contains(toks.get(i)) && !CONNECT.contains(toks.get(i))) {
                    hasContent = true;
                    partly |= unread.contains(i);
                }
            }
            if (!hasContent || !partly) {
                continue;   // nothing to read, or the keywords read this clause whole
            }
            StringBuilder ctext = new StringBuilder();
            for (int i : cl) {
                ctext.append(ctext.length() == 0 ? "" : " ").append(toks.get(i));
            }
            StringBuilder rest = new StringBuilder();
            for (int i = 0; i < toks.size(); i++) {
                if (i < end || !cl.contains(i)) {
                    rest.append(rest.length() == 0 ? "" : " ").append(toks.get(i));
                }
            }
            Keywords without = keywords(rest.toString());
            Pick basis = make;
            Set<String> kept = Set.of();
            Pick withoutTop = without.offers().isEmpty() ? null : without.offers().get(0).pick();
            if (withoutTop != null && withoutTop.kind().equals(make.kind()) && withoutTop.form().equals(make.form())) {
                kept = added(withoutTop, top);
            }
            if (!kept.isEmpty()) {
                List<String[]> first = guesses(withoutTop, ctext.toString(), reader, threshold, null);
                if (!first.isEmpty() && !kept.contains(first.get(0)[0] + "=" + first.get(0)[1])) {
                    basis = withoutTop;   // the layer hears something else in this clause
                    altPicks.add(make);
                    altBy.add(List.copyOf(mine));
                    mine = new ArrayList<>();
                } else if (!first.isEmpty()) {
                    continue;   // they agree: the keyword answer stands
                }
            }
            List<Pick> made = new ArrayList<>();
            List<String[]> guesses = guesses(basis, ctext.toString(), reader, threshold, made);
            if (guesses.isEmpty()) {
                still = true;
                continue;
            }
            for (int g = 1; g < Math.min(3, guesses.size()); g++) {
                List<String> by = new ArrayList<>(mine);
                by.add(guesses.get(g)[0] + "=" + guesses.get(g)[1]);
                altPicks.add(made.get(g));
                altBy.add(by);
            }
            make = made.get(0);
            mine.add(guesses.get(0)[0] + "=" + guesses.get(0)[1]);
        }
        if (mine.isEmpty() && altPicks.isEmpty()) {
            return plain;
        }
        List<Pick> offers = new ArrayList<>(List.of(make));
        List<List<String>> layer = new ArrayList<>(List.of(List.copyOf(mine)));
        Set<String> seen = new HashSet<>(Set.of(key(filled(tree, make))));
        for (Candidate o : got.offers()) {
            altPicks.add(o.pick());
            altBy.add(List.of());
        }
        for (int i = 0; i < altPicks.size(); i++) {
            String key = key(filled(tree, altPicks.get(i)));
            if (offers.size() < MOST_OFFERS && seen.add(key)) {
                offers.add(altPicks.get(i));
                layer.add(List.copyOf(altBy.get(i)));
            }
        }
        List<String> left = new ArrayList<>();
        if (still) {
            left.addAll(got.unmatched());
        } else {
            for (int i : got.unread()) {
                if (i < end) {
                    left.add(toks.get(i));
                }
            }
        }
        boolean flagged = !mine.isEmpty() || still || !left.isEmpty();
        return new Answer(offers, flagged ? Reach.PART : Reach.IN, left, layer);
    }

    /** The make with one more pick, or null when the Builder would not allow it (no room, a clash, the body does it). */
    static Pick canAdd(BuilderTree tree, Pick pick, String slotId, String entryId) {
        BuilderTree.Form form = tree.kind(pick.kind()).form(pick.form());
        BuilderTree.Slot slot = form.slot(slotId);
        if (slot == null || slot.entry(entryId) == null) {
            return null;
        }
        List<String> have = pick.picks().getOrDefault(slotId, List.of());
        if (have.contains(entryId) || (!have.isEmpty() && slot.most() == 1) || have.size() >= slot.most()) {
            return null;
        }
        Map<String, List<String>> picks = new LinkedHashMap<>(pick.picks());
        List<String> more = new ArrayList<>(have);
        more.add(entryId);
        picks.put(slotId, more);
        Pick next = new Pick(pick.kind(), pick.form(), picks);
        if (!problems(tree, next).isEmpty()) {
            return null;
        }
        if (slotId.equals("powers") && !pick.first("from").isEmpty()
                && bodyPowers(form, pick.first("from")).contains(entryId.substring(entryId.indexOf(':') + 1))) {
            return null;   // the body already does it: the Builder asks for no pick
        }
        return key(filled(tree, next)).equals(key(filled(tree, pick))) ? null : next;
    }

    /** Up to three {slot, entry} the reader's answer gives for this make, best first; empty for "none". */
    private List<String[]> guesses(Pick make, String clause, ClauseReader reader, double threshold, List<Pick> made) {
        List<ClauseReader.Guess> read = reader.read(clause);
        List<String[]> out = new ArrayList<>();
        if (read.isEmpty() || read.get(0).key().equals(ClauseReader.NONE)) {
            return out;
        }
        for (int i = 0; i < Math.min(LAYER_READS, read.size()); i++) {
            ClauseReader.Guess g = read.get(i);
            if (g.key().equals(ClauseReader.NONE)) {
                continue;
            }
            if (g.prob() < (out.isEmpty() ? threshold : threshold / 3)) {
                break;
            }
            int eq = g.key().indexOf('=');
            Pick next = eq < 1 ? null : canAdd(tree, make, g.key().substring(0, eq), g.key().substring(eq + 1));
            if (next != null) {
                out.add(new String[] {g.key().substring(0, eq), g.key().substring(eq + 1)});
                if (made != null) {
                    made.add(next);
                }
            }
            if (out.size() == 3) {
                break;
            }
        }
        return out;
    }

    private Set<String> added(Pick a, Pick b) {
        Map<String, List<String>> fa = filled(tree, a).picks();
        Set<String> out = new HashSet<>();
        filled(tree, b).picks().forEach((s, v) -> v.forEach(x -> {
            if (!fa.getOrDefault(s, List.of()).contains(x)) {
                out.add(s + "=" + x);
            }
        }));
        return out;
    }

    /** The picks a form cannot be offered without: what its words must name. */
    static List<String> anchors(String kind, String form) {
        return anchorsOf(kind, form);
    }

    private static List<String> anchorsOf(String kind, String form) {
        return switch (kind + "/" + form) {
            case "item/made_up" -> List.of("shape", "power");
            case "event/made_up" -> List.of("motion");
            case "rule/rule" -> List.of("when", "does");
            case "machine/composed" -> List.of("look");
            // what it does and when, never the look: a look is a colour word ("gold", "icy"), and reading
            // "a chicken that's gold" as a chicken block stole colour schemes from every other kind.
            case "magic_block/made_up" -> List.of("power", "trigger");
            default -> List.of("from");
        };
    }

    /** The candidates one form gives: its best pick, then one per alternative of each ambiguous slot. */
    /** The word before a pick that says which slot it is for, when two slots list the same word. */
    private static final Map<String, Set<String>> CUES = Map.of("ingredient",
            Set.of("from", "with", "needs", "need", "using", "of", "takes", "on", "uses", "use"));

    private List<Candidate> fill(BuilderTree.Kind kind, BuilderTree.Form form, List<Found> found,
                                 Map<String, Set<Integer>> kindHits, List<String> toks) {
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < form.slots().size(); i++) {
            order.put(form.slots().get(i).id(), i);
        }
        // Group the matches over the same words for the same slot: a family word names several entries at once.
        Map<String, Group> groups = new LinkedHashMap<>();
        for (Found m : found) {
            Group g = groups.computeIfAbsent(m.a() + ":" + m.b() + ":" + m.ref().slot(),
                    x -> new Group(m.a(), m.b(), m.ref().slot(), m.q()));
            g.q = Math.max(g.q, m.q());
            if (g.entries.stream().noneMatch(e -> e.entry().equals(m.ref().entry()))) {
                g.entries.add(new Hit(m.ref().entry(), m.ref().order(), m.ref().also(), m.q()));
            }
        }
        foundMaterials(form, groups, toks);   // §4: "into honey blocks", "turns tuff"
        foundCreatures(form, groups, toks);   //: "make it rain slimes"
        List<Group> ranked = turnGroups(form, new ArrayList<>(groups.values()), toks);
        ranked.sort(Comparator.<Group>comparingInt(g -> g.yields).thenComparingInt(g -> -(g.b - g.a)).thenComparingInt(g -> -g.q)
                .thenComparingInt(g -> g.a).thenComparingInt(g -> order.get(g.slot)));
        Comparator<Hit> byQuality = Comparator.<Hit>comparingInt(e -> -e.q()).thenComparingInt(Hit::order);
        List<String> anchorSlots = anchorsOf(kind.id(), form.id());
        // A word two pick-one slots of this form both list ("carrot" is a snack's look and its ingredient) goes to the
        // slot another word has NOT already named; with both free, the word before it decides ("from carrots").
        List<Group> single = new ArrayList<>();
        for (Group g : ranked) {
            if (!form.slot(g.slot).steps() && form.slot(g.slot).most() == 1) {
                single.add(g);
            }
        }
        Set<String> sure = new HashSet<>();
        for (Group g : single) {
            boolean rival = false;
            for (Group o : single) {
                rival |= o != g && !o.slot.equals(g.slot) && o.a == g.a && o.b == g.b;
            }
            if (!rival) {
                sure.add(g.slot);
            }
        }
        Set<Integer> taken = new HashSet<>();
        List<Group> kept = new ArrayList<>();
        List<Group> steps = new ArrayList<>();
        for (Group g : ranked) {
            if (form.slot(g.slot).steps()) {
                steps.add(g);
                continue;
            }
            boolean free = true;
            for (int i = g.a; i < g.b; i++) {
                free &= !taken.contains(i);
            }
            if (!free) {
                continue;
            }
            if (form.slot(g.slot).most() == 1) {
                String lead = g.a > 0 ? toks.get(g.a - 1) : "";
                boolean rivals = false;
                boolean freeRival = false;
                boolean cued = false;
                for (Group o : single) {
                    if (o != g && !o.slot.equals(g.slot) && o.a == g.a && o.b == g.b) {
                        rivals = true;
                        boolean named = sure.contains(o.slot);
                        for (Group k : kept) {
                            named |= k.slot.equals(o.slot);
                        }
                        if (!named) {
                            freeRival = true;
                            cued |= CUES.getOrDefault(o.slot, Set.of()).contains(lead);
                        }
                    }
                }
                boolean mine = sure.contains(g.slot);
                for (Group k : kept) {
                    mine |= k.slot.equals(g.slot);
                }
                if (rivals && freeRival && (mine || cued)) {
                    continue;   // the other slot's reading of this word is the one that adds something
                }
            }
            for (int i = g.a; i < g.b; i++) {
                taken.add(i);
            }
            g.entries.sort(byQuality);
            kept.add(g);
        }
        for (Group g : steps) {
            // A size or a count never fights another slot for a word: "giant mushrooms" is the mushroom village AND
            // asks for it giant. But inside, or right before, a PART it describes the part: "big wings" are dragon
            // wings on a creature of the usual size, and "two horns" is one creature. The same inside any longer
            // phrase that is not what the wish starts from: "lots of power" is strong, not five.
            boolean describes = false;
            boolean clash = false;
            for (Group k : kept) {
                if (!anchorSlots.contains(k.slot) && !k.slot.equals(g.slot)) {
                    boolean inside = g.a >= k.a && g.b <= k.b && (g.b - g.a) < (k.b - k.a);
                    // ...unless that phrase starts with a joining word: "tiny in the sky" is a tiny thing that flies.
                    boolean before = g.b == k.a && !form.slot(k.slot).steps() && form.slot(k.slot).most() > 1
                            && !(k.a < toks.size() && STOP.contains(toks.get(k.a)));
                    describes |= inside || before;
                }
                if (k.slot.equals(g.slot) && k.a < g.b && g.a < k.b) {
                    clash = true;
                }
            }
            if (describes) {
                continue;
            }
            if (!clash) {
                g.entries.sort(byQuality);
                kept.add(g);
            }
        }
        kept.sort(Comparator.comparingInt(g -> g.a));
        Map<String, List<Group>> bySlot = new LinkedHashMap<>();
        for (Group g : kept) {
            bySlot.computeIfAbsent(g.slot, x -> new ArrayList<>()).add(g);
        }

        // A pick that goes with exactly one pick of another slot names that one too: "a bounce pit" is the ground
        // turning into one, and "drops a diamond" can only follow a mob being defeated.
        for (String sid : new ArrayList<>(bySlot.keySet())) {
            Group g0 = bySlot.get(sid).get(0);
            BuilderTree.Entry entry = form.slot(sid).entry(g0.entries.get(0).entry());
            for (Map.Entry<String, List<String>> only : new TreeMap<>(entry.only()).entrySet()) {
                // ...and one that needs a pick of a slot the words left alone gets the first it may go with: "it wears
                // a gold hat" is for a zombie or a skeleton, so with no mob named it is the zombie's (never dropped).
                BuilderTree.Slot other = form.slot(only.getKey());
                boolean needs = other != null && !anchorsOf(kind.id(), form.id()).contains(only.getKey())
                        && !only.getValue().contains(other.dflt());
                if (kind.id().equals("rule") && anchorsOf("rule", "rule").contains(only.getKey()) && g0.b - g0.a < 2
                        && !kindHits.containsKey(kind.id())) {
                    continue;                    //: a bare noun ("a cake") is the thing, not a rule that drops it
                }
                if (!bySlot.containsKey(only.getKey()) && (only.getValue().size() == 1 || needs) && other != null) {
                    Group implied = new Group(g0.a, g0.a, only.getKey(), g0.q);
                    implied.entries.add(new Hit(only.getValue().get(0), 0, Map.of(), g0.q));
                    bySlot.put(only.getKey(), new ArrayList<>(List.of(implied)));
                }
            }
        }
        impliedShape(form, bySlot);
        List<String> anchors = anchorsOf(kind.id(), form.id());
        List<String> named = new ArrayList<>();
        for (String a : anchors) {
            if (bySlot.containsKey(a)) {
                named.add(a);
            }
        }
        if (named.isEmpty()) {
            return List.of();
        }
        // A form with two anchors (an item's shape and power, a rule's when and then) needs both named, or one of
        // them and a word for the kind: "boots that make me jump high" is not a rule about jumping.
        if (anchors.size() == 2 && named.size() == 1 && !kindHits.containsKey(kind.id()) && !named.get(0).equals("shape")) {
            return List.of();
        }
        if (kind.id().equals("machine") && !kindHits.containsKey(kind.id())) {
            // a snack machine only when he names a machine ("a cake machine", "a cookie factory"). A food word
            // alone asks for the food: "a cake" was answered with four machines that make cake, rated "Not this" 12 of 12.
            return List.of();
        }
        if (kind.id().equals("machine") && form.id().equals("composed") && !bySlot.containsKey("snack_powers")) {
            // A snack machine with no power named is the stock machine, when the look has one; else it is composed.
            BuilderTree.Form ready = kind.form("ready");
            String look = bySlot.get("look").get(0).entries.get(0).entry().substring("look:".length());
            String head = look.split("_")[0];
            if (ready != null && ready.slots().get(0).entries().stream().anyMatch(e -> e.id().contains(head))) {
                return List.of();
            }
        }

        Map<String, List<String>> picks = new LinkedHashMap<>();
        Map<String, List<String>> also = new LinkedHashMap<>();
        Set<Integer> used = new TreeSet<>();
        int score = 0;
        List<Alt> alts = new ArrayList<>();
        List<String[]> swaps = new ArrayList<>();
        for (Map.Entry<String, List<Group>> en : bySlot.entrySet()) {
            String sid = en.getKey();
            List<Group> gs = en.getValue();
            BuilderTree.Slot s = form.slot(sid);
            if (s.most() == 1) {
                Group head = gs.get(0);
                if (sid.equals("from") && gs.size() > 1 && gs.get(1).a == gs.get(0).b) {
                    head = gs.get(1);   // "a robot dog" is a dog
                }
                picks.put(sid, new ArrayList<>(List.of(head.entries.get(0).entry())));
                for (int i = head.a; i < head.b; i++) {
                    used.add(i);
                }
                int w = weight(head, anchors);
                score += w;
                int n = 0;
                for (Hit e : head.entries.subList(1, head.entries.size())) {
                    alts.add(new Alt(sid, e.entry(), w * e.q() / head.q, n++));
                }
                for (Group g : gs) {
                    if (g != head) {
                        for (Hit e : g.entries) {
                            alts.add(new Alt(sid, e.entry(), w * 2 / 3, n++));
                        }
                    }
                }
                head.entries.get(0).also().forEach((slot2, extra) ->
                        also.computeIfAbsent(slot2, x -> new ArrayList<>()).addAll(extra));
            } else {
                List<String> chosen = new ArrayList<>();
                for (Group g : gs) {
                    if (chosen.size() < s.most() && !chosen.contains(g.entries.get(0).entry())) {
                        chosen.add(g.entries.get(0).entry());
                        for (int i = g.a; i < g.b; i++) {
                            used.add(i);
                        }
                        score += weight(g, anchors);
                        // A family word ("wings", "horn") names several: each other member is its own tile.
                        int n = 0;
                        for (Hit e : g.entries.subList(1, g.entries.size())) {
                            swaps.add(new String[] {sid, g.entries.get(0).entry(), e.entry(), String.valueOf(n++)});
                        }
                    }
                }
                picks.put(sid, chosen);
            }
        }
        // What a word also picks elsewhere ("unicorn" is a horse with a unicorn horn), where there is room.
        for (Map.Entry<String, List<String>> en : also.entrySet()) {
            BuilderTree.Slot target = form.slot(en.getKey());
            if (en.getKey().equals(BuilderTurnInto.FROM_SLOT) && bySlot.containsKey(BuilderTurnInto.FROM_SLOT)) {
                continue;   //: what he named changes is what changes ("smelts sand" is sand, not the smelter's ores)
            }
            if (target != null) {
                List<String> have = picks.computeIfAbsent(en.getKey(), x -> new ArrayList<>());
                for (String e : en.getValue()) {
                    if (!have.contains(e) && have.size() < target.most()) {
                        have.add(e);
                    }
                }
            }
        }
        if (picks.containsKey("powers") && !picks.get("powers").isEmpty() && picks.containsKey("from")) {
            // What the body already does needs no pick: a rocket flies, so "a rocket that flies" keeps both power slots.
            Set<String> has = bodyPowers(form, picks.get("from").get(0));
            picks.get("powers").removeIf(p -> has.contains(p.substring(p.indexOf(':') + 1)));
        }
        if (kindHits.containsKey(kind.id())) {
            score += KIND_WORD;
            used.addAll(kindHits.get(kind.id()));
        } else {
            score += KIND_PRIOR.getOrDefault(kind.id(), 0);
        }

        List<int[]> spans = new ArrayList<>();
        for (Group g : kept) {
            if (!form.slot(g.slot).steps()) {
                spans.add(new int[] {g.a, g.b});
            }
        }
        List<int[]> anchorSpans = new ArrayList<>();
        for (String a : named) {
            for (Group g : bySlot.get(a)) {
                anchorSpans.add(new int[] {g.a, g.b});
            }
        }
        List<Candidate> out = new ArrayList<>();
        int n = 0;
        List<Object[]> variants = variants(kind, form, picks, alts, bySlot.keySet(), swaps);
        for (Object[] v : variants) {
            @SuppressWarnings("unchecked")
            Pick fixed = valid(new Pick(kind.id(), form.id(), (Map<String, List<String>>) v[0]));
            if (fixed == null) {
                continue;
            }
            int bodyBonus = 0;
            if (fixed.picks().containsKey("from") && (kind.id().equals("creature") || kind.id().equals("ride"))) {
                BuilderTree.Entry body = form.slot("from").entry(fixed.first("from"));
                if ("1".equals(body.facts().get("ridden"))) {   //: a body made to be ridden (no longer a bundled power)
                    bodyBonus = kind.id().equals("ride") ? 10 : -10;   // a car is something to ride
                }
            }
            // words read only for a POWER this make could not keep ("smelts ores" on a pickaxe, which cannot
            // carry "Turn things into…") were not used by it: the tile is the closest the Builder has, not a sure match.
            @SuppressWarnings("unchecked")
            Map<String, List<String>> asked = (Map<String, List<String>>) v[0];
            Set<Integer> lost = new HashSet<>();
            for (Group g : kept) {
                if (!LOSABLE.contains(g.slot)) {
                    continue;
                }
                List<String> had = new ArrayList<>();
                for (Hit h : g.entries) {
                    if (asked.getOrDefault(g.slot, List.of()).contains(h.entry())) {
                        had.add(h.entry());
                    }
                }
                List<String> kept2 = fixed.picks().getOrDefault(g.slot, List.of());
                if (!had.isEmpty() && had.stream().noneMatch(kept2::contains)) {
                    for (int i = g.a; i < g.b; i++) {
                        lost.add(i);
                    }
                }
            }
            out.add(new Candidate(fixed, score - (int) v[1] + bodyBonus, n++, used, spans, anchorSpans, lost));
        }
        return out;
    }

    // ------------------------------------------------------------------ "turn X into Y"
    //
    // A material word ("sand", "stone", "gold") is a From pick and a To pick of "Turn things into…". Which one it is
    // comes from where it stands: after "into", "to", "become" (or "for" in "swap X for Y") it is what things turn
    // INTO; before them, or after "from" or "out of", it is what changes; and "Y out of X" makes Y from X. A word that
    // names the whole change by itself ("a magic furnace", "midas", "disappear": a To word with also-picks) needs no
    // "into". With no change word at all ("a gold sword", "a stone pickaxe"), the material is the item's look, not a
    // change; and "turns ME into…" is about him, not a block or a thing he carries.

    /** A power and the pages behind it: dropped because the item cannot carry it, its words were not used. */
    static final Set<String> LOSABLE = Set.of("power", BuilderTurnInto.FROM_SLOT, BuilderTurnInto.TO_SLOT);
    static final Set<String> TO_CUES = Set.of("into", "to", "become", "becomes", "became", "becoming");
    /** Words that may stand between a cue and its material: "into my gold", "into lots of gold". */
    static final Set<String> CUE_SKIP = Set.of("my", "your", "his", "her", "their", "our", "some", "all", "lots", "of",
            "pure", "solid", "real", "shiny", "sparkly");
    /** What a change word is about when it is not a block or a thing: a person. */
    static final Set<String> PERSON = Set.of("me", "myself", "him", "himself", "her", "herself", "them", "themselves",
            "you", "yourself", "us", "people", "player", "players", "everyone", "everybody", "someone", "somebody");

    private static boolean has(Group g, String entry) {
        return g.entries.stream().anyMatch(h -> h.entry().equals(entry));
    }

    private static boolean overlaps(Group g, List<Group> others) {
        for (Group o : others) {
            if (o != g && g.a < o.b && o.a < g.b) {
                return true;
            }
        }
        return false;
    }

    /** Does the word before this group (past "my", "lots of"…) say it is what things turn INTO, or does "out of" follow it? */
    private static boolean cued(Group g, List<String> toks, boolean verb) {
        int j = g.a - 1;
        while (j >= 0 && CUE_SKIP.contains(toks.get(j))) {
            j--;
        }
        if (j >= 0 && (TO_CUES.contains(toks.get(j)) || verb && toks.get(j).equals("for"))) {
            return true;
        }
        return g.b + 1 < toks.size() && toks.get(g.b).equals("out") && toks.get(g.b + 1).equals("of");
    }

    static final Set<String> COLOUR_SAID = Set.of("color", "colors", "colour", "colours", "colored", "coloured");

    /** A To word standing where what changes goes: after a change word, before "into", with no From word on it or between. */
    private static boolean orphanFrom(Group g, List<Group> groups, List<Group> verbs, List<String> toks) {
        boolean verbBefore = false;
        for (Group v : verbs) {
            verbBefore |= v.b <= g.a;
        }
        boolean colour = g.a > 0 && toks.get(g.a - 1).equals("in") || g.b < toks.size() && COLOUR_SAID.contains(toks.get(g.b));
        if (!verbBefore || cued(g, toks, true) || colour) {
            return false;   // "in gold", "gold colours": the item's look, not what changes
        }
        List<Group> froms = new ArrayList<>();
        for (Group o : groups) {
            if (o.slot.equals(BuilderTurnInto.FROM_SLOT)) {
                if (g.a < o.b && o.a < g.b) {
                    return false;
                }
                froms.add(o);
            }
        }
        for (int i = g.b; i < toks.size(); i++) {
            if (TO_CUES.contains(toks.get(i))) {
                for (Group o : froms) {
                    if (o.a >= g.b && o.b <= i) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * §4: a material Turn-into's pages do not list, named exactly: an installed BLOCK, found by its name
     * ({@link BuilderFind}, the 🔍 Find's own rules) where a material word stands and none of the pages' words does —
     * right after "into" ("into honey blocks") as a To, right after his change word ("turns tuff into gold") as a From.
     * Blocks only: "turns zombies into chickens" is no raw chicken. Not a material a To already names both ways (gold
     * block and ingot), and never a guess: a name the index has, word for word. tools/apprentice_match.py
     * {@code _found_materials}.
     */
    private static void foundMaterials(BuilderTree.Form form, Map<String, Group> groups, List<String> toks) {
        if (form.slot(BuilderTurnInto.TO_SLOT) == null || form.slot(BuilderTurnInto.FROM_SLOT) == null || form.slot("power") == null) {
            return;
        }
        String turn = "itempower:" + BuilderTurnInto.POWER;
        List<Group> verbs = new ArrayList<>();
        for (Group g : groups.values()) {
            if (g.slot.equals("power") && has(g, turn) && !(g.b < toks.size() && PERSON.contains(toks.get(g.b)))) {
                verbs.add(g);
            }
        }
        boolean[] covered = new boolean[toks.size()];
        for (Group g : groups.values()) {
            for (int i = g.a; i < g.b; i++) {
                covered[i] = true;
            }
        }
        List<Group> add = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) {
            if (!TO_CUES.contains(toks.get(i))) {
                continue;
            }
            final int cue = i;
            // A change is said: his change word, or what changes standing right before "into" ("turn dirt into…").
            boolean said = !verbs.isEmpty()
                    || groups.values().stream().anyMatch(g -> g.slot.equals(BuilderTurnInto.FROM_SLOT) && g.b == cue);
            if (!said) {
                continue;
            }
            int j = i + 1;
            while (j < toks.size() && CUE_SKIP.contains(toks.get(j))) {
                j++;
            }
            Group g = foundMaterial(toks, covered, j, BuilderTurnInto.TO_SLOT, BuilderTurnInto.TO_PREFIX);
            if (g != null) {
                add.add(g);
            }
        }
        for (Group v : verbs) {
            int j = v.b;
            while (j < toks.size() && (CUE_SKIP.contains(toks.get(j)) || toks.get(j).equals("the"))) {
                j++;
            }
            Group g = foundMaterial(toks, covered, j, BuilderTurnInto.FROM_SLOT, BuilderTurnInto.FROM_PREFIX);
            if (g != null && g.b < toks.size() && TO_CUES.contains(toks.get(g.b))) {
                add.add(g);   // what changes stands between the change word and "into"
            }
        }
        for (Group g : add) {
            groups.putIfAbsent(g.a + ":" + g.b + ":" + g.slot, g);
        }
    }

    /**
     * a creature an event that drops creatures does not list, named exactly right after its motion word ("rain
     * slimes", "a crowd of foxes"): any installed creature the 🔍 Find would offer (never Moddy's own, never a boss).
     * tools/apprentice_match.py {@code _found_creatures}.
     */
    private static void foundCreatures(BuilderTree.Form form, Map<String, Group> groups, List<String> toks) {
        if (form.slot("what") == null || form.slot("motion") == null) {
            return;
        }
        List<String> drops = BuilderEvents.dropMotions();
        boolean[] covered = new boolean[toks.size()];
        for (Group g : groups.values()) {
            if (g.slot.equals("what")) {
                continue;   // "slime" is also a bounce pit: a creature after "rain" is read anew
            }
            for (int i = g.a; i < g.b; i++) {
                covered[i] = true;
            }
        }
        List<Group> add = new ArrayList<>();
        for (Group m : groups.values()) {
            if (!m.slot.equals("motion") || m.entries.stream().noneMatch(h -> drops.contains(h.entry()))) {
                continue;
            }
            int j = m.b;
            while (j < toks.size() && CREATURE_SKIP.contains(toks.get(j))) {
                j++;
            }
            Group g = foundCreature(toks, covered, j);
            if (g != null) {
                add.add(g);
            }
        }
        for (Group g : add) {
            Group had = groups.get(g.a + ":" + g.b + ":" + g.slot);
            if (had == null) {
                groups.put(g.a + ":" + g.b + ":" + g.slot, g);
            } else if (had.entries.stream().noneMatch(h -> h.entry().equals(g.entries.get(0).entry()))) {
                had.entries.add(0, g.entries.get(0));   // the creature first: after "rain" it is what falls
            }
        }
    }

    /** The words between a motion and the creature it drops: "rain OF slimes", "raining DOWN pigs". */
    static final Set<String> CREATURE_SKIP = Set.of("of", "down", "some", "lots", "loads", "with", "the", "a", "an", "many");

    private static Group foundCreature(List<String> toks, boolean[] covered, int at) {
        if (at >= toks.size() || covered[at] || STOP.contains(toks.get(at))) {
            return null;
        }
        int most = 0;
        while (most < 3 && at + most < toks.size() && !CONNECT.contains(toks.get(at + most))) {
            most++;
        }
        com.moddingfromamod.content.ContentIndex ix = com.moddingfromamod.content.Found.index();
        for (int n = most; n >= 1; n--) {
            String words = String.join(" ", toks.subList(at, at + n));
            for (var h : ix.named(words, Set.of(com.moddingfromamod.content.ContentIndex.Kind.MOB), 6)) {
                String id = h.entry().id();
                if (BuilderEvents.found(id) != null) {
                    Group g = new Group(at, at + n, "what", 3);
                    g.entries.add(new Hit("what:" + id, 0, Map.of(), 3));
                    return g;
                }
            }
        }
        return null;
    }

    /**
     * The longest span from {@code at} (up to four words; the first one no pick read; no cue) that names an installed
     * block, as a group. A later word may be one a pick read ("blocks" of "honey blocks"): the longer reading wins.
     */
    private static Group foundMaterial(List<String> toks, boolean[] covered, int at, String slot, String prefix) {
        if (at >= toks.size() || covered[at] || STOP.contains(toks.get(at))) {
            return null;
        }
        int most = 0;
        while (most < 4 && at + most < toks.size() && !TO_CUES.contains(toks.get(at + most))
                && !CONNECT.contains(toks.get(at + most))) {
            most++;
        }
        com.moddingfromamod.content.ContentIndex ix = com.moddingfromamod.content.Found.index();
        for (int n = most; n >= 1; n--) {
            String words = String.join(" ", toks.subList(at, at + n));
            for (var h : ix.named(words, Set.of(com.moddingfromamod.content.ContentIndex.Kind.BLOCK), 6)) {
                String id = h.entry().id();
                BuilderFind.Result r = BuilderFind.of(id);
                if (r != null && r.block() && com.moddingfromamod.datapack.TurnInto.material(id) == null) {
                    Group g = new Group(at, at + n, slot, 3);
                    g.entries.add(new Hit(prefix + BuilderTurnInto.FOUND + id, 0, Map.of(), 3));
                    return g;
                }
            }
        }
        return null;
    }

    /** The groups of a form with "Turn things into…", read for where each material word stands; others as they are. */
    private static List<Group> turnGroups(BuilderTree.Form form, List<Group> groups, List<String> toks) {
        if (form.slot(BuilderTurnInto.TO_SLOT) == null || form.slot(BuilderTurnInto.FROM_SLOT) == null || form.slot("power") == null) {
            return groups;
        }
        String turn = "itempower:" + BuilderTurnInto.POWER;
        List<Group> verbs = new ArrayList<>();
        boolean person = false;
        for (Group g : groups) {
            if (g.slot.equals("power") && has(g, turn)) {
                if (g.b < toks.size() && PERSON.contains(toks.get(g.b))) {
                    person = true;   // "turns me into a chicken": not a block or a thing he carries (no turn reading)
                } else {
                    verbs.add(g);
                }
            }
        }
        // "turns leaves into wool" where the To page has no wool: the change he asks for is not one this Builder makes, so
        // it is no turn-into tile at all (never a quiet other target, such as what a furnace makes of leaves).
        for (int i = 0; i < toks.size() && !person; i++) {
            if (!TO_CUES.contains(toks.get(i))) {
                continue;
            }
            final int at = i;
            boolean after = false;
            for (Group g : groups) {
                after |= g.b == at && (verbs.contains(g) || g.slot.equals(BuilderTurnInto.FROM_SLOT));
            }
            int j = i + 1;
            while (j < toks.size() && CUE_SKIP.contains(toks.get(j))) {
                j++;
            }
            final int next = j;
            boolean target = false;
            for (Group g : groups) {
                target |= g.slot.equals(BuilderTurnInto.TO_SLOT) && g.a == next;
            }
            person = after && next < toks.size() && !STOP.contains(toks.get(next)) && !target;
        }
        // "turn all the gold here into dirt" where the From page has no gold: what he names as changing is a To word in
        // the From place (after his change word, before "into", nothing the From page lists between). No turn-into tile.
        for (Group g : groups) {
            if (!person && g.slot.equals(BuilderTurnInto.TO_SLOT)) {
                person = orphanFrom(g, groups, verbs, toks);
            }
        }
        List<Group> out = new ArrayList<>();
        if (person) {
            for (Group g : groups) {
                if (g.slot.equals("power")) {
                    g.entries.removeIf(h -> h.entry().equals(turn));
                    if (!g.entries.isEmpty()) {
                        out.add(g);
                    }
                } else if (!g.slot.equals(BuilderTurnInto.TO_SLOT) && !g.slot.equals(BuilderTurnInto.FROM_SLOT)) {
                    out.add(g);
                }
            }
            return out;
        }
        boolean verb = !verbs.isEmpty();
        List<Group> tos = new ArrayList<>();
        for (Group g : groups) {
            if (!g.slot.equals(BuilderTurnInto.TO_SLOT)) {
                continue;
            }
            boolean whole = false;
            boolean wholeFrom = false;
            for (Hit h : g.entries) {
                whole |= !h.also().isEmpty();
                wholeFrom |= h.also().containsKey(BuilderTurnInto.FROM_SLOT);
            }
            boolean fromBefore = false;
            for (Group o : groups) {
                fromBefore |= o.slot.equals(BuilderTurnInto.FROM_SLOT) && o.b <= g.a;
            }
            if (cued(g, toks, verb) || whole && (wholeFrom || fromBefore)) {
                tos.add(g);
            }
        }
        List<Group> froms = new ArrayList<>();
        for (Group g : groups) {
            if (g.slot.equals(BuilderTurnInto.FROM_SLOT) && !cued(g, toks, verb) && (verb || !tos.isEmpty())) {
                froms.add(g);
            }
        }
        List<Group> named = new ArrayList<>(tos);
        named.addAll(froms);
        for (Group g : groups) {
            if (g.slot.equals(BuilderTurnInto.TO_SLOT)) {
                if (tos.contains(g)) {
                    out.add(g);
                }
            } else if (g.slot.equals(BuilderTurnInto.FROM_SLOT)) {
                if (froms.contains(g)) {
                    out.add(g);
                }
            } else if (g.slot.equals("power")) {
                boolean isVerb = verbs.contains(g);
                boolean over = overlaps(g, named);
                if (!isVerb && (over || !tos.isEmpty() && !verb)) {
                    continue;   // "grass" is what changes, not the grass wand; with no other change word, the change is the power
                }
                boolean free = false;
                for (Group v : verbs) {
                    free |= !overlaps(v, named);
                }
                if (!isVerb && !named.isEmpty() && free) {
                    // a change said in full ("turn to cobbles", "turns sand into glass") is the power; another
                    // power word ("frozen", "fiery") is then how it looks, so it is ranked after every other reading.
                    g.yields = 1;
                }
                boolean another = false;
                for (Group v : verbs) {
                    another |= v != g && !overlaps(v, named);
                }
                boolean longer = false;
                for (Group n : named) {
                    longer |= g.a < n.b && n.a < g.b && g.b - g.a > n.b - n.a;
                }
                if (isVerb && over && another && !longer) {
                    continue;   // a tool word on a word he uses as a material gives way to his own change word
                }
                out.add(g);
            } else {
                out.add(g);
            }
        }
        return out;
    }

    /** "Turn things into…" with what changes (or into what) named, on no named item: a wand, which aims. */
    private static void impliedShape(BuilderTree.Form form, Map<String, List<Group>> bySlot) {
        BuilderTree.Slot shape = form.slot("shape");
        List<Group> power = bySlot.get("power");
        if (shape == null || form.slot(BuilderTurnInto.TO_SLOT) == null || power == null || bySlot.containsKey("shape")) {
            return;
        }
        Hit head = power.get(0).entries.get(0);
        String turn = "itempower:" + BuilderTurnInto.POWER;
        if (!head.entry().equals(turn) || !(bySlot.containsKey(BuilderTurnInto.FROM_SLOT) || bySlot.containsKey(BuilderTurnInto.TO_SLOT)
                || head.also().containsKey(BuilderTurnInto.FROM_SLOT) || head.also().containsKey(BuilderTurnInto.TO_SLOT))) {
            return;
        }
        List<String> fits = form.slot("power").entry(turn).only().getOrDefault("shape", List.of());
        String wand = BuilderFlow.STOCK_PREFIX + "wand";
        String pick = fits.contains(wand) ? wand : fits.isEmpty() ? "" : fits.get(0);
        if (!pick.isEmpty()) {
            Group g0 = power.get(0);
            Group implied = new Group(g0.a, g0.a, "shape", g0.q);
            implied.entries.add(new Hit(pick, 0, Map.of(), g0.q));
            bySlot.put("shape", new ArrayList<>(List.of(implied)));
        }
    }

    private static int weight(Group g, List<String> anchors) {
        int base = anchors.contains(g.slot) || SLOT_WEIGHT.containsKey(g.slot)
                ? SLOT_WEIGHT.getOrDefault(g.slot, OTHER_WEIGHT) : OTHER_WEIGHT;
        return base * (g.b - g.a) * QUALITY[g.q] / 10;
    }

    /**
     * What a body or starter does before any pick (a rocket flies, a dragon flies and never burns): its tree fact
     * {@code has}, written from {@link BuilderNatural} for every creature body (it was Builder bodies only).
     */
    static Set<String> bodyPowers(BuilderTree.Form form, String from) {
        BuilderTree.Entry e = form.slot("from").entry(from);
        return e == null ? Set.of() : Set.of(e.facts().getOrDefault("has", "").split(","));
    }

    private static Map<String, List<String>> with(Map<String, List<String>> base, String slot, String entry) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        base.forEach((s, v) -> out.put(s, new ArrayList<>(v)));
        out.put(slot, new ArrayList<>(List.of(entry)));
        return out;
    }

    /** The base pick, then each alternative, as {picks, how much less sure it is}. */
    private List<Object[]> variants(BuilderTree.Kind kind, BuilderTree.Form form, Map<String, List<String>> base,
                                    List<Alt> alts, Set<String> namedSlots, List<String[]> swaps) {
        List<Object[]> out = new ArrayList<>();
        out.add(new Object[] {base, 0});
        for (String[] w : swaps.subList(0, Math.min(4, swaps.size()))) {
            List<String> have = base.getOrDefault(w[0], List.of());
            if (have.contains(w[1]) && !have.contains(w[2])) {
                Map<String, List<String>> v = new LinkedHashMap<>();
                base.forEach((sl, vals) -> v.put(sl, new ArrayList<>(vals)));
                List<String> swapped = new ArrayList<>();
                for (String e : have) {
                    swapped.add(e.equals(w[1]) ? w[2] : e);
                }
                v.put(w[0], swapped);
                out.add(new Object[] {v, 2 + Integer.parseInt(w[3])});
            }
        }
        for (Alt a : alts.subList(0, Math.min(6, alts.size()))) {
            int full = SLOT_WEIGHT.getOrDefault(a.slot(), OTHER_WEIGHT);
            out.add(new Object[] {with(base, a.slot(), a.entry()), (a.weight() < full ? full - a.weight() : 0) + 1 + a.n()});
        }
        if (base.containsKey("scheme") && !base.get("scheme").isEmpty()) {
            // A colour word is only ever the nearest scheme ("a brown bear" is a bear): also offer it as it comes.
            Map<String, List<String>> plain = new LinkedHashMap<>();
            base.forEach((s, v) -> {
                if (!s.equals("scheme")) {
                    plain.put(s, new ArrayList<>(v));
                }
            });
            out.add(new Object[] {plain, 8});
        }
        // A required pick the words did not name: offer the first few that fit, the Builder's own order.
        List<String> unnamed = switch (kind.id()) {
            case "item" -> List.of("power", "shape");
            case "event" -> List.of("what");
            case "rule" -> List.of("does", "when");
            case "machine" -> List.of("snack_powers");
            case "magic_block" -> List.of("power");   //
            default -> List.of();
        };
        for (String sid : unnamed) {
            BuilderTree.Slot slot = form.slot(sid);
            if (slot == null || namedSlots.contains(sid) || base.containsKey(sid)) {
                continue;
            }
            int n = 0;
            for (BuilderTree.Entry e : slot.entries()) {
                Map<String, List<String>> v = with(base, sid, e.id());
                if (valid(new Pick(kind.id(), form.id(), v)) == null) {
                    continue;
                }
                if (n > 0) {
                    out.add(new Object[] {v, 3 + n});
                }
                if (++n == 4) {
                    break;
                }
            }
        }
        return out;
    }

    /** The pick made valid by dropping what cannot go together (the later-named part gives way), or null. */
    private Pick valid(Pick pick) {
        Map<String, List<String>> picks = new LinkedHashMap<>();
        pick.picks().forEach((s, v) -> {
            if (!v.isEmpty()) {
                picks.put(s, new ArrayList<>(v));
            }
        });
        for (int round = 0; round < 8; round++) {
            Pick now = new Pick(pick.kind(), pick.form(), picks);
            List<String> why = problems(tree, now);
            if (why.isEmpty()) {
                return now;
            }
            String slot = why.get(0).substring(0, why.get(0).indexOf(':'));
            if (slot.equals("parts") && why.get(0).contains("boxes") && plainer(pick.kind(), pick.form(), picks)) {
                continue;
            }
            List<String> chosen = picks.get(slot);
            if (slot.equals("parts") && chosen != null || chosen != null && chosen.size() > 1) {
                chosen.remove(chosen.size() - 1);
                if (chosen.isEmpty()) {
                    picks.remove(slot);
                }
            } else if (chosen != null && !List.of("from", "look", "motion", "shape", "when", "material").contains(slot)) {
                picks.remove(slot);
            } else {
                return null;
            }
        }
        return null;
    }

    /** Swap the costliest part for the plain part of its kit, or the cheapest of it, that costs less (bat wings → wings). */
    private boolean plainer(String kind, String formId, Map<String, List<String>> picks) {
        BuilderTree.Form form = tree.kind(kind).form(formId);
        BuilderTree.Entry body = form.slot("from").entry(picks.get("from").get(0));
        java.util.function.ToIntFunction<String> cost = p -> Integer.parseInt(body.facts().getOrDefault("cost:" + p, "0"));
        List<BuilderTree.Entry> parts = form.slot("parts").entries();
        Map<String, String> kit = new HashMap<>();
        parts.forEach(e -> kit.put(e.id(), e.facts().getOrDefault("kit", "")));
        List<String> have = picks.get("parts");
        List<String> dearest = new ArrayList<>(have);
        dearest.sort(Comparator.comparingInt(p -> -cost.applyAsInt(p)));
        List<BuilderTree.Entry> cheapest = new ArrayList<>(parts);
        cheapest.sort(Comparator.<BuilderTree.Entry>comparingInt(e -> e.id().equals("kit:" + kit.get(e.id())) ? 0 : 1)
                .thenComparingInt(e -> cost.applyAsInt(e.id())));
        for (String p : dearest) {
            for (BuilderTree.Entry e : cheapest) {
                if (kit.get(e.id()).equals(kit.get(p)) && !have.contains(e.id()) && cost.applyAsInt(e.id()) < cost.applyAsInt(p)) {
                    have.set(have.indexOf(p), e.id());
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ a pick: its key, its defaults, its check

    /** One pick, one string: the gold set and the dedupe are keyed by it (the same string the Python tools write). */
    public static String key(Pick pick) {
        StringBuilder b = new StringBuilder(pick.kind()).append('/').append(pick.form()).append(':');
        boolean first = true;
        for (Map.Entry<String, List<String>> e : new TreeMap<>(pick.picks()).entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            List<String> v = new ArrayList<>(e.getValue());
            v.sort(Comparator.naturalOrder());
            b.append(first ? "" : ";").append(e.getKey()).append('=').append(String.join(",", v));
            first = false;
        }
        return b.toString();
    }

    /** The pick with every slot it left out set to the tree's default (what pressing past the page gives). */
    public static Pick filled(BuilderTree tree, Pick pick) {
        BuilderTree.Form form = tree.kind(pick.kind()).form(pick.form());
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (BuilderTree.Slot s : form.slots()) {
            List<String> chosen = new ArrayList<>(pick.picks().getOrDefault(s.id(), List.of()));
            if (chosen.isEmpty()) {
                String d = s.dflt();
                if (!d.isEmpty() && !d.equals("as_is")) {
                    chosen.add(d);
                } else if (d.isEmpty() && s.required()) {
                    // What a later slot already names counts too (a block's trigger fits what it does).
                    Map<String, List<String>> known = new LinkedHashMap<>();
                    pick.picks().forEach((k, v) -> {
                        if (!v.isEmpty()) {
                            known.put(k, v);
                        }
                    });
                    known.putAll(out);
                    chosen.add(firstFitting(form, s, known));
                }
            }
            if (s.id().equals("who") && !mobTrigger(form, out)) {
                chosen.clear();
            }
            chosen.removeIf(String::isEmpty);
            out.put(s.id(), chosen);
        }
        // A snack machine's ingredient, unnamed, is its food's own.
        if (pick.form().equals("composed") && pick.picks().getOrDefault("ingredient", List.of()).isEmpty()) {
            BuilderTree.Entry look = form.slot("look").entry(out.get("look").get(0));
            out.put("ingredient", new ArrayList<>(List.of(look.facts().get("ingredient"))));
        }
        return new Pick(pick.kind(), pick.form(), out);
    }

    private static boolean mobTrigger(BuilderTree.Form form, Map<String, List<String>> out) {
        BuilderTree.Slot who = form.slot("who");
        List<String> when = out.getOrDefault("when", List.of());
        return who != null && !when.isEmpty() && who.entries().get(0).only().get("when").contains(when.get(0));
    }

    /**
     * An entry's fact naming the picks (of the slot it depends on) it is the FIRST choice of: an event's
     * unnamed material is its motion's own first, whatever other motion listed a shared block earlier.
     */
    public static final String FIRST_FOR = "first_for";

    private static String firstFitting(BuilderTree.Form form, BuilderTree.Slot slot, Map<String, List<String>> soFar) {
        for (BuilderTree.Entry e : slot.entries()) {
            String firstFor = e.facts().get(FIRST_FOR);
            if (firstFor == null) {
                continue;
            }
            for (String key : e.only().keySet()) {
                List<String> other = soFar.get(key);
                if (other != null && !other.isEmpty() && List.of(firstFor.split(",")).contains(other.get(0))) {
                    return e.id();
                }
            }
        }
        for (BuilderTree.Entry e : slot.entries()) {
            boolean fits = goesWith(e, soFar);
            // an entry that is the default only when every pick of another slot has a fact ("What a furnace
            // makes", when every From has a furnace result); otherwise the next that fits.
            String when = e.facts().get("defaultWhen");
            if (fits && when != null) {
                String[] sf = when.split(":", 2);
                BuilderTree.Slot other = form.slot(sf[0]);
                List<String> vals = soFar.getOrDefault(sf[0], List.of());
                fits = other != null && !vals.isEmpty() && vals.stream()
                        .allMatch(v -> other.entry(v) != null && "1".equals(other.entry(v).facts().get(sf[1])));
            }
            if (fits) {
                return e.id();
            }
        }
        return "";
    }

    private static boolean goesWith(BuilderTree.Entry e, Map<String, List<String>> soFar) {
        for (Map.Entry<String, List<String>> only : e.only().entrySet()) {
            List<String> other = soFar.get(only.getKey());
            if (other != null && !other.isEmpty() && !only.getValue().contains(other.get(0))) {
                return false;
            }
        }
        return true;
    }

    /**
     * why "Turn things into…" cannot be made from these picks, or null: make_item's own reading of its From and
     * To on this item (BuilderTurnInto.whyNot), from the tree's facts. {@code tools/apprentice_lib.py} has its twin.
     */
    static String turnProblem(BuilderTree.Form form, Map<String, List<String>> full) {
        BuilderTree.Slot fs = form.slot(BuilderTurnInto.FROM_SLOT);
        BuilderTree.Slot ts = form.slot(BuilderTurnInto.TO_SLOT);
        BuilderTree.Slot sh = form.slot("shape");
        List<String> from = full.getOrDefault(BuilderTurnInto.FROM_SLOT, List.of());
        List<String> to = full.getOrDefault(BuilderTurnInto.TO_SLOT, List.of());
        List<String> shape = full.getOrDefault("shape", List.of());
        if (fs != null && sh != null && !from.isEmpty() && !shape.isEmpty() && sh.entry(shape.get(0)) != null) {
            // an item that works on what it hits changes blocks only (BuilderTurnInto.offered): no raw food on an axe
            String trig = sh.entry(shape.get(0)).facts().getOrDefault("trigger", "");
            if (com.moddingfromamod.datapack.TurnInto.forShape(trig) == com.moddingfromamod.datapack.TurnInto.Mode.BLOCKS) {
                for (String id : from) {
                    BuilderTree.Entry e = fs.entry(id);
                    if (e != null && !"1".equals(e.facts().get("block"))) {
                        return BuilderTurnInto.FROM_SLOT + ": '" + id + "' is not a block, and a " + trig
                                + " item changes blocks only";
                    }
                }
            }
        }
        if (fs == null || ts == null || sh == null || from.isEmpty() || to.isEmpty() || shape.isEmpty()
                || ts.entry(to.get(0)) == null || sh.entry(shape.get(0)) == null) {
            return null;
        }
        Map<String, String> tf = ts.entry(to.get(0)).facts();
        String trigger = sh.entry(shape.get(0)).facts().getOrDefault("trigger", "");
        int members = 0;
        boolean block = false;
        boolean item = false;
        boolean smeltBlock = false;
        boolean smeltItem = false;
        for (String id : from) {
            BuilderTree.Entry e = fs.entry(id);
            if (e == null) {
                continue;
            }
            members += Integer.parseInt(e.facts().getOrDefault("members", "1"));
            block |= "1".equals(e.facts().get("block"));
            item |= "1".equals(e.facts().get("item"));
            smeltBlock |= "1".equals(e.facts().get("smeltBlock"));
            smeltItem |= "1".equals(e.facts().get("smeltItem"));
        }
        if (members > BuilderTurnInto.MOST_MEMBERS) {
            return BuilderTurnInto.FROM_SLOT + ": " + members + " things at once, at most " + BuilderTurnInto.MOST_MEMBERS;
        }
        boolean furnace = "1".equals(tf.get("furnace"));
        boolean blockSide = furnace ? smeltBlock : block && !tf.getOrDefault("block", "").isEmpty();
        boolean itemSide = furnace ? smeltItem : item && !tf.getOrDefault("item", "").isEmpty();
        if (com.moddingfromamod.datapack.TurnInto.effective(trigger, blockSide, itemSide) == null) {
            return BuilderTurnInto.TO_SLOT + ": '" + to.get(0) + "' cannot be made from these on a " + trigger + " item";
        }
        String sole = from.size() == 1 && fs.entry(from.get(0)) != null ? fs.entry(from.get(0)).facts().getOrDefault("sole", "") : "";
        if (!sole.isEmpty() && sole.equals(tf.getOrDefault("block", ""))) {
            return BuilderTurnInto.TO_SLOT + ": '" + to.get(0) + "' is what it is already";
        }
        return null;
    }

    /**
     * Why the Builder could not make this pick by hand; empty when it could. The cheap check the matcher steers by.
     * {@code ApprenticeMake} then presses every offered pick through the real menus, which is the check that counts.
     */
    public static List<String> problems(BuilderTree tree, Pick pick) {
        BuilderTree.Kind kind = tree.kind(pick.kind());
        BuilderTree.Form form = kind == null ? null : kind.form(pick.form());
        if (form == null) {
            return List.of("no " + pick.kind() + "/" + pick.form() + " in the tree");
        }
        List<String> out = new ArrayList<>();
        Map<String, List<String>> full = filled(tree, pick).picks();
        for (String sid : pick.picks().keySet()) {
            if (form.slot(sid) == null) {
                out.add(sid + ": this form has no such slot");
            }
        }
        for (BuilderTree.Slot s : form.slots()) {
            List<String> chosen = full.getOrDefault(s.id(), List.of());
            if (chosen.size() > s.most()) {
                out.add(s.id() + ": " + chosen.size() + " picked, at most " + s.most());
            }
            // a slot whose entries all go only with some other pick is asked only for those (a turn-into item's
            // From and To pages): required there and nowhere else.
            if (s.required() && chosen.isEmpty() && s.entries().stream().anyMatch(e -> goesWith(e, full))) {
                out.add(s.id() + ": required");
            }
            for (String c : chosen) {
                BuilderTree.Entry e = s.entry(c);
                if (e == null) {
                    out.add(s.id() + ": '" + c + "' is not offered");
                    continue;
                }
                for (Map.Entry<String, List<String>> only : new TreeMap<>(e.only()).entrySet()) {
                    List<String> other = full.get(only.getKey());
                    if (other != null && !other.isEmpty() && !only.getValue().contains(other.get(0))) {
                        out.add(s.id() + ": '" + c + "' does not go with " + only.getKey() + "=" + other.get(0));
                    }
                }
            }
            for (List<String> clash : s.clashes()) {
                if (chosen.contains(clash.get(0)) && chosen.contains(clash.get(1))) {
                    out.add(s.id() + ": " + clash.get(0) + " and " + clash.get(1) + " cannot go together");
                }
            }
        }
        List<String> parts = full.getOrDefault("parts", List.of());
        List<String> from = full.getOrDefault("from", List.of());
        if (!parts.isEmpty() && !from.isEmpty()) {
            BuilderTree.Entry body = form.slot("from").entry(from.get(0));
            if (body != null && body.facts().containsKey("partBudget")) {
                int cost = 0;
                for (String p : parts) {
                    cost += Integer.parseInt(body.facts().getOrDefault("cost:" + p, "0"));
                }
                String tooBig = BuilderLimits.parts(cost, Integer.parseInt(body.facts().get("partBudget")), body.label());
                if (tooBig != null) {
                    out.add("parts: " + cost + " boxes on a body that takes " + body.facts().get("partBudget") + " (" + tooBig + ")");
                }
            }
        }
        // no count of powers or special moves — the lowering's own limits only (BuilderLimits): one move of
        // each effect (that check is the abilities slot's clashes above), and the snack's make_item limits.
        String turn = turnProblem(form, full);
        if (turn != null) {
            out.add(turn);
        }
        String snack = BuilderLimits.problem("snack_powers", bare(full.getOrDefault("snack_powers", List.of()), "snackpower:"));
        if (snack != null) {
            out.add("snack_powers: " + snack);
        }
        return out;
    }

    private static List<String> bare(List<String> ids, String prefix) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            out.add(id.startsWith(prefix) ? id.substring(prefix.length()) : id);
        }
        return out;
    }
}
