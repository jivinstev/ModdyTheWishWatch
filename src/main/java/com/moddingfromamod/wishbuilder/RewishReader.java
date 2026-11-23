package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Reader B: the deterministic local first pass over a typed wish</b> (measured as). The Java
 * twin of {@code tools/rewish_reader.py}, which is the reference: {@code RewishReaderSyncTest} holds the two equal on
 * every wish of the re-wish corpus.
 *
 * <p>One typed wish, read against his things and the starters on his watch, says: an EDIT of one of his things (with the
 * Builder's own operators, read with the Builder's own words — {@link BuilderTree} entries' {@code words}, the same the
 * Apprentice reads, never a second vocabulary), a COPY (his thing or a starter), a COMMAND (undo / go / stop) or NEW;
 * and how sure: SURE (act on it), CLOSE (offer it), NOTHING (the wish goes on as typed). An edit is SURE only when the
 * thing is certain and every content word is read as an operator that fits that kind of thing: one word it cannot read
 * makes it NOTHING, the whole wish goes to the Magician, never half of it. Misspellings are read with the Apprentice's
 * own typo rules.
 *
 * <p>Pure: no Minecraft. Measured on the sealed test (corpus/rewish/README.md): 202/202 SURE right.
 */
public final class RewishReader {

    public enum Intent { NEW, EDIT, COPY, COMMAND }

    public enum Band { SURE, CLOSE, NOTHING }

    /** One of his things: its id, what it is (creature / item / building), its name. */
    public record Thing(String id, String what, String name) {
    }

    /** What the reader says. {@code label}: a thing's id, {@code "starter:<id>"}, or null. */
    public record Answer(Intent intent, String label, List<BuilderEdit.Op> ops, String verb, Band band, List<String> unread,
                         List<String> candidates) {
        public Answer {
            candidates = List.copyOf(candidates);
        }

        Answer(Intent intent, String label, List<BuilderEdit.Op> ops, String verb, Band band, List<String> unread) {
            this(intent, label, ops, verb, band, unread, List.of());
        }

        static Answer nothing() {
            return new Answer(Intent.NEW, null, List.of(), "", Band.NOTHING, List.of());
        }
    }

    private record Item(String slot, String value, boolean weak) {
    }

    static final Set<String> STOP = set(("a an the and but with to so in on at for into is was are be can could should would "
            + "will please pls plz of my mine me i im you your his her its this that these those it him he she they them make "
            + "made give get got have has do does did want wanna let lets just really very too also now some more much way "
            + "can't cant should shud like be become turn turns be able").split("\\s+"));
    static final Set<String> POINT = set("it", "him", "her", "that", "those", "them", "this", "these", "he", "she", "they");
    static final Set<String> AGAIN = set("another", "again", "agen", "agian", "agin", "anuther", "anothr", "same", "copy",
            "twin", "second", "one", "more", "remake", "redo");
    static final Set<String> CHANGE_CUES = set("make", "turn", "give", "change", "let", "can", "should", "needs", "need",
            "want", "put", "add");
    static final Set<String> NEW_CUES = set("new", "like", "build", "summon", "spawn", "create");
    static final List<List<String>> UNDO = phrases("undo", "take it back", "take that back", "go back", "undo that", "never mind");
    static final List<List<String>> GO = phrases("take me to", "where is", "wheres", "show me", "go to", "bring me to",
            "teleport me to", "tp me to", "find");
    static final Set<String> SIZE_UP = set("bigger", "biger", "larger", "taller", "huger", "giant", "huge", "gigantic",
            "enormous", "massive", "ginormous", "big");
    static final Set<String> SIZE_DOWN = set("smaller", "smaler", "littler", "tinier", "tiny", "small", "little", "mini",
            "shorter");
    static final Set<String> FASTER = set("faster", "quicker", "speedier", "fast", "quick", "speedy");
    static final Set<String> SLOWER = set("slower", "slow");
    static final Set<String> STRONGER = set("stronger", "strong", "powerful", "powerfuller");
    static final Set<String> WEAKER = set("weaker", "weak");
    static final Set<String> SLOTS = set("powers", "temper", "abilities", "parts", "scheme", "outside");
    static final Map<String, Set<String>> FITS = Map.ofEntries(Map.entry("powers", set("creature")),
            Map.entry("temper", set("creature")), Map.entry("abilities", set("creature")), Map.entry("parts", set("creature")),
            Map.entry("speed", set("creature")), Map.entry("size", set("creature", "building")),
            Map.entry("scheme", set("creature", "item", "building")), Map.entry("outside", set("building")),
            Map.entry("strength", set("item")), Map.entry("itemlook", set("item")), Map.entry("power", set("item")));
    /**
     * an item's own slots, read ONLY when every thing the wish names is an item: its LOOK and its POWER,
     * from the Builder's item kind alone (a magic block also has a "power" slot, which this never reads).
     */
    static final Set<String> ITEM_SLOTS = set("itemlook", "power");
    static final String TURN_INTO = "itempower:" + BuilderTurnInto.POWER;
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9']+");

    private final Map<List<String>, List<Item>> vocab;
    /** the vocabulary read for an item: {@link #vocab}'s item-fitting entries plus the item's look and power. */
    private final Map<List<String>, List<Item>> itemVocab;
    private final Map<String, String[][]> starterTerms;

    /** A reader over this world's Builder (its tree's words) and the starters' own terms (templates.json). */
    public RewishReader(BuilderTree tree) {
        this.vocab = vocabOf(tree);
        this.itemVocab = itemVocabOf(tree, vocab);
        this.starterTerms = loadStarterTerms();
    }

    // ── words ────────────────────────────────────────────────────────────────────────────────────────────────────

    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher((text == null ? "" : text).toLowerCase(Locale.ROOT).replace('’', '\''));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static Map<List<String>, List<Item>> vocabOf(BuilderTree tree) {
        Map<List<String>, List<Item>> out = new LinkedHashMap<>();
        for (BuilderTree.Kind k : tree.kinds()) {
            for (BuilderTree.Form f : k.forms()) {
                for (BuilderTree.Slot s : f.slots()) {
                    if (!SLOTS.contains(s.id())) {
                        continue;
                    }
                    for (BuilderTree.Entry e : s.entries()) {
                        for (String w : e.words()) {
                            boolean weak = w.endsWith("~");
                            List<String> ph = tokens(weak ? w.substring(0, w.length() - 1) : w);
                            if (!ph.isEmpty()) {
                                Item item = new Item(s.id(), e.id(), weak);
                                List<Item> l = out.computeIfAbsent(List.copyOf(ph), x -> new ArrayList<>());
                                if (!l.contains(item)) {
                                    l.add(item);
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static Map<List<String>, List<Item>> itemVocabOf(BuilderTree tree, Map<List<String>, List<Item>> base) {
        Map<List<String>, List<Item>> out = new LinkedHashMap<>();
        base.forEach((ph, items) -> {
            List<Item> fit = items.stream().filter(x -> FITS.getOrDefault(x.slot(), Set.of()).contains("item")).toList();
            if (!fit.isEmpty()) {
                out.put(ph, new ArrayList<>(fit));
            }
        });
        // a word the reader already read for an item (a colour) keeps that reading: looks and powers only add
        Set<List<String>> already = new java.util.HashSet<>(out.keySet());
        for (BuilderTree.Kind k : tree.kinds()) {
            if (!k.id().equals("item")) {
                continue;
            }
            for (BuilderTree.Form f : k.forms()) {
                for (BuilderTree.Slot s : f.slots()) {
                    if (!ITEM_SLOTS.contains(s.id())) {
                        continue;
                    }
                    for (BuilderTree.Entry e : s.entries()) {
                        if (e.id().equals(TURN_INTO)) {
                            continue;   // needs its From and To pages: never a typed change ("turn my crown into a top hat")
                        }
                        // a look's own name too ("donut"): a word another MAKE owns (the snack machine's) means this look
                        // when he is changing one of his items
                        List<String> words = new ArrayList<>(e.words());
                        if (s.id().equals(BuilderLooks.SLOT) && e.label() != null && !e.label().isBlank()) {
                            words.add(e.label());
                        }
                        for (String w : words) {
                            boolean weak = w.endsWith("~");
                            List<String> ph = tokens(weak ? w.substring(0, w.length() - 1) : w);
                            if (!ph.isEmpty() && !already.contains(ph)) {
                                Item item = new Item(s.id(), e.id(), weak);
                                List<Item> l = out.computeIfAbsent(List.copyOf(ph), x -> new ArrayList<>());
                                if (!l.contains(item)) {
                                    l.add(item);
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    static String stem(String w) {
        for (String suf : new String[] {"ing", "es", "s", "ed"}) {
            if (w.length() > 4 && w.endsWith(suf)) {
                return w.substring(0, w.length() - suf.length());
            }
        }
        return w;
    }

    private static Map<String, String[][]> loadStarterTerms() {
        Map<String, String[][]> out = new LinkedHashMap<>();
        try (InputStream in = RewishReader.class.getClassLoader().getResourceAsStream("starter-library/templates.json")) {
            if (in == null) {
                return out;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement t : root.getAsJsonArray("templates")) {
                JsonObject o = t.getAsJsonObject();
                out.put(o.get("id").getAsString(), new String[][] {stems(o.getAsJsonArray("all")), stems(o.getAsJsonArray("any"))});
            }
        } catch (java.io.IOException | RuntimeException e) {
            // no starter terms: starters are never named, and a copy of one goes on as typed
        }
        return out;
    }

    private static String[] stems(JsonArray a) {
        if (a == null) {
            return new String[0];
        }
        String[] out = new String[a.size()];
        for (int i = 0; i < a.size(); i++) {
            out[i] = stem(a.get(i).getAsString());
        }
        return out;
    }

    // ── the typo rules (the Apprentice's own) ────────────────────────────────────────────────────────────────────

    private static String group(String w) {
        if (AGAIN.contains(w)) {
            return "again";
        }
        if (POINT.contains(w)) {
            return "point";
        }
        if (SIZE_UP.contains(w)) {
            return "up";
        }
        if (SIZE_DOWN.contains(w)) {
            return "down";
        }
        if (FASTER.contains(w)) {
            return "fast";
        }
        if (SLOWER.contains(w)) {
            return "slow";
        }
        if (STRONGER.contains(w)) {
            return "strong";
        }
        if (WEAKER.contains(w)) {
            return "weak";
        }
        return w;
    }

    static String unstretch(String t) {
        return t.replaceAll("(.)\\1{2,}", "$1$1");
    }

    /** Each typed word neither known nor real English, put back to the ONE known word the Apprentice's rules say it is. */
    static List<String> respell(List<String> toks, Set<String> words) {
        return respell(toks, words, Set.of());
    }

    /**
     * when a typo fits two words ("bolck": block, black), the one in his own things' names or the starters
     * ({@code prefer}) is what he meant; with none or both there, it stays as typed. tools/rewish_reader.py respell.
     */
    static List<String> respell(List<String> toks, Set<String> words, Set<String> prefer) {
        Set<String> english = Apprentice.english();
        List<String> out = new ArrayList<>();
        for (String t : toks) {
            if (words.contains(t) || STOP.contains(t) || english.contains(t) || t.length() < 3 || t.chars().allMatch(Character::isDigit)) {
                out.add(t);
                continue;
            }
            String u = unstretch(t);
            if (words.contains(u)) {
                out.add(u);
                continue;
            }
            Map<String, String> found = new LinkedHashMap<>();
            for (String w : words) {
                if (w.length() < 3) {
                    continue;
                }
                boolean hit = Apprentice.smallSlip(u, w) || Apprentice.slip3(u, w);
                if (!hit && u.length() >= 5 && w.length() >= 4) {
                    String a1 = Apprentice.sound(u, true);
                    String a2 = Apprentice.sound(u, false);
                    if (a1.length() >= 3 && a1.equals(Apprentice.sound(w, true)) || a2.length() >= 3 && a2.equals(Apprentice.sound(w, false))) {
                        hit = true;
                    }
                }
                if (!hit && u.length() >= 5 && Apprentice.lead(u).substring(0, 1).equals(Apprentice.lead(w).substring(0, 1))
                        && Apprentice.distance(Apprentice.lead(u), Apprentice.lead(w), 1) <= 1) {
                    hit = true;
                }
                if (!hit && u.length() >= 6 && u.length() < 8 && w.length() >= 5 && Apprentice.oneConsonantOff(u, w)
                        && u.charAt(0) == w.charAt(0) && Apprentice.distance(u, w, 2) <= 2) {
                    hit = true;
                }
                if (hit) {
                    // Within one meaning, the closest word, then the first in alphabetical order (never set order).
                    String g = group(w);
                    String was = found.get(g);
                    if (was == null || closer(u, w, was)) {
                        found.put(g, w);
                    }
                }
            }
            List<String> his = found.values().stream().filter(prefer::contains).toList();
            out.add(found.size() == 1 ? found.values().iterator().next() : his.size() == 1 ? his.get(0) : t);
        }
        return out;
    }

    private static boolean closer(String typed, String w, String than) {
        int a = Apprentice.distance(typed, w, 3);
        int b = Apprentice.distance(typed, than, 3);
        return a != b ? a < b : w.compareTo(than) < 0;
    }

    /** the words of his own things' names and the starters: a typo between two words prefers these. */
    Set<String> ownWords(List<Thing> things, List<String> starters) {
        Set<String> ws = new HashSet<>();
        for (Thing t : things) {
            ws.addAll(tokens(t.name()));
            ws.addAll(idWords(t.id(), false));
        }
        for (String sid : starters) {
            String[][] terms = starterTerms.get(sid);
            if (terms != null) {
                ws.addAll(Arrays.asList(terms[0]));
                ws.addAll(Arrays.asList(terms[1]));
            }
        }
        return ws;
    }

    Set<String> knownWords(List<Thing> things, List<String> starters) {
        Set<String> ws = new HashSet<>();
        for (Set<String> s : List.of(POINT, AGAIN, CHANGE_CUES, NEW_CUES, SIZE_UP, SIZE_DOWN, FASTER, SLOWER, STRONGER, WEAKER)) {
            ws.addAll(s);
        }
        ws.addAll(List.of("undo", "stop", "where", "take", "show", "back", "teleport", "find", "bring", "end"));
        for (List<String> ph : vocab.keySet()) {
            ws.addAll(ph);
        }
        for (Thing t : things) {
            ws.addAll(tokens(t.name()));
            ws.addAll(idWords(t.id(), false));
        }
        for (String sid : starters) {
            String[][] terms = starterTerms.get(sid);
            if (terms != null) {
                ws.addAll(Arrays.asList(terms[0]));
                ws.addAll(Arrays.asList(terms[1]));
            }
        }
        return ws;
    }

    private static List<String> idWords(String id, boolean dropStop) {
        List<String> out = new ArrayList<>();
        for (String w : id.toLowerCase(Locale.ROOT).split("[_\\d]+")) {
            if (w.length() > 2 && (!dropStop || !STOP.contains(w))) {
                out.add(w);
            }
        }
        return out;
    }

    // ── what the wish names ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code used}: every word any of his things matched. {@code named}: the words the WINNING things matched —
     * only those are never read as a change; a word only a losing thing matched may still be one ("make goldie gold"
     * beside his Gold Bowl), and is never counted as unread when nothing reads it.
     */
    private record Names(List<String> ids, Set<Integer> used, Set<Integer> named) {
        Names(List<String> ids, Set<Integer> used) {
            this(ids, used, used);
        }
    }

    /** Is token i inside a noun phrase that starts with "a"/"an" ("a flying pig")? Then it describes a NEW thing. */
    static boolean introducedByA(List<String> toks, int i) {
        int j = i - 1;
        while (j >= 0 && !STOP.contains(toks.get(j))) {
            j--;
        }
        return j >= 0 && (toks.get(j).equals("a") || toks.get(j).equals("an"));
    }

    private static Names findNames(List<String> toks, List<Thing> things) {
        Map<String, Integer> hits = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        Map<String, Set<Integer>> usedBy = new LinkedHashMap<>();
        String text = String.join(" ", toks);
        for (Thing th : things) {
            List<String> nameToks = tokens(th.name());
            List<String> name = new ArrayList<>();
            for (String w : nameToks) {
                if (!STOP.contains(w) && w.length() > 2) {
                    name.add(w);
                }
            }
            String full = String.join(" ", nameToks);
            if (!full.isEmpty() && Pattern.compile("\\b" + Pattern.quote(full) + "\\b").matcher(text).find()) {
                hits.put(th.id(), 3);
                for (int i = 0; i < toks.size(); i++) {
                    if (nameToks.contains(toks.get(i))) {
                        used.add(i);
                        usedBy.computeIfAbsent(th.id(), k -> new HashSet<>()).add(i);
                    }
                }
                continue;
            }
            List<String> idw = idWords(th.id(), true);
            int score = 0;
            for (int i = 0; i < toks.size(); i++) {
                String w = toks.get(i);
                if (name.contains(w) || idw.contains(w) || w.endsWith("s") && name.contains(w.substring(0, w.length() - 1))) {
                    score++;
                    used.add(i);
                    usedBy.computeIfAbsent(th.id(), k -> new HashSet<>()).add(i);
                }
            }
            if (score > 0) {
                hits.put(th.id(), score);
            }
        }
        // A whole name that sits only inside a LONGER whole name the wish says is not named: "the mossy hut" is his Mossy
        // Hut, never also a thing called Hut (a gate's, in a long Gate B run).
        Map<String, String> fullOf = new LinkedHashMap<>();
        for (Thing th : things) {
            String full = String.join(" ", tokens(th.name()));
            if (hits.getOrDefault(th.id(), 0) == 3 && !full.isEmpty()
                    && Pattern.compile("\\b" + Pattern.quote(full) + "\\b").matcher(text).find()) {
                fullOf.put(th.id(), full);
            }
        }
        for (var a : fullOf.entrySet()) {
            String rest = text;
            for (String longer : fullOf.values()) {
                if (longer.length() > a.getValue().length()
                        && Pattern.compile("\\b" + Pattern.quote(a.getValue()) + "\\b").matcher(longer).find()) {
                    rest = Pattern.compile("\\b" + Pattern.quote(longer) + "\\b").matcher(rest).replaceAll(" | ");
                }
            }
            if (!Pattern.compile("\\b" + Pattern.quote(a.getValue()) + "\\b").matcher(rest).find()) {
                hits.remove(a.getKey());
            }
        }
        if (hits.isEmpty()) {
            return new Names(List.of(), used);
        }
        int best = hits.values().stream().max(Integer::compare).orElse(0);
        List<String> ids = new ArrayList<>();
        Set<Integer> named = new HashSet<>();
        hits.forEach((k, v) -> {
            if (v == best) {
                ids.add(k);
                named.addAll(usedBy.getOrDefault(k, Set.of()));
            }
        });
        return new Names(ids, used, named);
    }

    private List<String> findStarters(List<String> toks, List<String> starters) {
        Set<String> have = new HashSet<>();
        for (String t : toks) {
            if (!STOP.contains(t)) {
                have.add(stem(t));
            }
        }
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (String sid : starters) {
            String[][] terms = starterTerms.get(sid);
            if (terms == null) {
                continue;
            }
            String[] need = terms[0];
            if (need.length > 0 && Arrays.stream(need).allMatch(have::contains)) {
                Set<String> needSet = Set.of(need);
                int extra = 0;
                for (String w : terms[1]) {
                    if (have.contains(w) && !needSet.contains(w)) {
                        extra++;
                    }
                }
                hits.put("starter:" + sid, need.length * 2 + extra);
            }
        }
        if (hits.isEmpty()) {
            return List.of();
        }
        int best = hits.values().stream().max(Integer::compare).orElse(0);
        List<String> out = new ArrayList<>();
        hits.forEach((k, v) -> {
            if (v == best) {
                out.add(k);
            }
        });
        return out;
    }

    // ── the operators ────────────────────────────────────────────────────────────────────────────────────────────

    private record Ops(List<BuilderEdit.Op> ops, List<String> unread) {
    }

    private Ops readOps(List<String> toks, Names names) {
        return readOps(toks, names, vocab);
    }

    /** The operators in the wish, never reading a word of the thing it names (the WINNERS' words, {@code named}). */
    private Ops readOps(List<String> toks, Names names, Map<List<String>, List<Item>> vocab) {
        List<BuilderEdit.Op> ops = new ArrayList<>();
        Set<Integer> read = new HashSet<>(names.named());
        int i = 0;
        while (i < toks.size()) {
            if (read.contains(i)) {
                i++;
                continue;
            }
            Item hit = null;
            int hn = 0;
            for (int n = 4; n >= 1; n--) {
                if (i + n > toks.size()) {
                    continue;
                }
                List<Item> v = vocab.get(toks.subList(i, i + n));
                if (v == null) {
                    continue;
                }
                List<Item> strong = v.stream().filter(x -> !x.weak()).toList();
                List<Item> cands = strong.isEmpty() ? v : strong;
                Set<String> distinct = new HashSet<>();
                cands.forEach(c -> distinct.add(c.slot() + "\u0000" + c.value()));
                if (cands.size() == 1 || distinct.size() == 1) {
                    hit = cands.get(0);
                    hn = n;
                    break;
                }
            }
            String w = toks.get(i);
            if (hit != null) {
                ops.add(opOf(hit.slot(), hit.value()));
                for (int j = i; j < i + hn; j++) {
                    read.add(j);
                }
                i += hn;
                continue;
            }
            if (SIZE_UP.contains(w) || SIZE_DOWN.contains(w)) {
                ops.add(BuilderEdit.Op.step("size", SIZE_UP.contains(w) ? 1 : -1));
            } else if (FASTER.contains(w) || SLOWER.contains(w)) {
                ops.add(BuilderEdit.Op.step("speed", FASTER.contains(w) ? 1 : -1));
            } else if (STRONGER.contains(w) || WEAKER.contains(w)) {
                ops.add(BuilderEdit.Op.step("strength", STRONGER.contains(w) ? 1 : -1));
            } else {
                i++;
                continue;
            }
            read.add(i);
            i++;
        }
        Set<String> filler = set("and", "also", "too", "please", "him", "her");
        List<String> unread = new ArrayList<>();
        for (int j = 0; j < toks.size(); j++) {
            String t = toks.get(j);
            if (!read.contains(j) && !names.used().contains(j) && !STOP.contains(t) && !POINT.contains(t) && !CHANGE_CUES.contains(t) && !filler.contains(t)) {
                unread.add(t);
            }
        }
        return new Ops(List.copyOf(new LinkedHashSet<>(ops)), unread);
    }

    private static BuilderEdit.Op opOf(String slot, String value) {
        return slot.equals("scheme") || slot.equals("temper") || ITEM_SLOTS.contains(slot) ? BuilderEdit.Op.set(slot, value)
                : BuilderEdit.Op.add(slot, value);
    }

    /**
     * is his item named as ITSELF: pointed at, or by its whole name, or by its head noun (the last word of its name:
     * "my wand" for Speedy Wand)? Never by a word that decorates it ("my dragon" is not his Dragon Shield).
     */
    static boolean namedAsItself(List<String> toks, Thing thing, List<String> named) {
        if (!named.contains(thing.id())) {
            return true;   // pointed at ("make it a crown"): what he last made
        }
        List<String> name = tokens(thing.name());
        if (name.isEmpty()) {
            return false;
        }
        String text = String.join(" ", toks);
        if (Pattern.compile("\\b" + Pattern.quote(String.join(" ", name)) + "\\b").matcher(text).find()) {
            return true;
        }
        String head = name.get(name.size() - 1);
        return toks.contains(head) || toks.contains(head + "s");
    }

    /**
     * does a typed change read this Builder slot on this kind of thing? The words of its entries (the tree's own),
     * or a change word (bigger, faster, stronger), and only where the slot fits that kind. {@code BuilderPathCoverageTest}
     * holds the register to it.
     */
    public static boolean readsSlot(String treeKind, String slot) {
        if (slot.equals("mobsize")) {
            slot = "size";   //: a creature's size is its named size, read as bigger/smaller (BuilderEdit's alias)
        }
        String k = kindOf(treeKind);
        if (!FITS.getOrDefault(slot, Set.of()).contains(k)) {
            return false;
        }
        if (ITEM_SLOTS.contains(slot)) {
            return treeKind.equals("item");   // a magic block's "power" is never read: the item kind's alone
        }
        return SLOTS.contains(slot) || slot.equals("size") || slot.equals("speed") || slot.equals("strength");
    }

    static String kindOf(String what) {
        String w = what == null ? "" : what.toLowerCase(Locale.ROOT);
        return switch (w) {
            case "creature", "species", "mob", "ride" -> "creature";
            case "building", "build" -> "building";
            default -> "item";
        };
    }

    static boolean fits(List<BuilderEdit.Op> ops, String what) {
        String k = kindOf(what);
        return ops.stream().allMatch(o -> FITS.getOrDefault(o.slot(), Set.of()).contains(k));
    }

    private static int starts(List<String> toks, List<List<String>> phrases) {
        for (List<String> p : phrases) {
            if (toks.size() >= p.size() && toks.subList(0, p.size()).equals(p)) {
                return p.size();
            }
        }
        return 0;
    }

    // ── the reading ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Read {@code wish} against his {@code things}, the {@code starters} on his watch (their ids) and {@code last}, the id
     * of what his last wish made or changed (or null).
     */
    public Answer read(String wish, List<Thing> things, List<String> starters, String last) {
        List<String> toks = respell(tokens(wish), knownWords(things, starters), ownWords(things, starters));
        Answer out = Answer.nothing();
        if (toks.isEmpty()) {
            return out;
        }
        Names names = findNames(toks, things);
        if (!names.ids().isEmpty() && names.named().stream().allMatch(i -> introducedByA(toks, i))) {
            // "a flying pig" is a new pig, never his Piggy. Asked of the words the WINNING things matched (an earlier issue's rule):
            // a word only a losing thing matched ("ride" in another thing's *_ride_* id) never makes the winner his.
            names = new Names(List.of(), Set.of());
        }
        if (!names.ids().isEmpty() && (toks.get(0).equals("a") || toks.get(0).equals("an"))) {
            // A wish that STARTS "a …" describes something new: one of his things counts only by its whole name
            // ("a hat for Sparky"), never by a word its id or name shares ("a green dino i can ride" is not his *_ride_*).
            String text = String.join(" ", toks);
            List<String> kept = new ArrayList<>();
            for (Thing th : things) {
                String full = String.join(" ", tokens(th.name()));
                if (names.ids().contains(th.id()) && !full.isEmpty()
                        && Pattern.compile("\\b" + Pattern.quote(full) + "\\b").matcher(text).find()) {
                    kept.add(th.id());
                }
            }
            if (kept.size() != names.ids().size()) {
                names = kept.isEmpty() ? new Names(List.of(), Set.of()) : new Names(kept, names.used(), names.named());
            }
        }
        List<String> st = findStarters(toks, starters);
        boolean pointing = false;
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);
            if (POINT.contains(t) && !(set("that", "those", "this", "these").contains(t) && i > 0
                    && !STOP.contains(toks.get(i - 1)) && !CHANGE_CUES.contains(toks.get(i - 1)) && !AGAIN.contains(toks.get(i - 1)))) {
                pointing = true;
                break;
            }
        }
        List<String> referent = !names.ids().isEmpty() ? names.ids() : (pointing && last != null ? List.of(last) : List.of());

        // ── a command: undo / go / stop, at the START of the wish ──
        int u = starts(toks, UNDO);
        if (u > 0) {
            List<String> rest = toks.subList(u, toks.size());
            if (rest.isEmpty() || rest.stream().allMatch(t -> POINT.contains(t) || STOP.contains(t) || t.equals("wish") || t.equals("last"))) {
                return new Answer(Intent.COMMAND, null, List.of(), "undo", Band.SURE, List.of());
            }
        }
        int g = starts(toks, GO);
        if (g > 0) {
            List<String> rest = toks.subList(g, toks.size());
            if (!rest.isEmpty() && !set("make", "build", "get").contains(rest.get(0)) && !names.ids().isEmpty()) {
                boolean one = names.ids().size() == 1;
                return new Answer(Intent.COMMAND, one ? names.ids().get(0) : null, List.of(), "go", one ? Band.SURE : Band.CLOSE,
                        List.of(), names.ids());
            }
        }
        if (toks.get(0).equals("stop") || toks.size() >= 3 && toks.subList(0, 3).equals(List.of("make", "it", "stop"))) {
            long rest = toks.subList(1, toks.size()).stream()
                    .filter(t -> !STOP.contains(t) && !POINT.contains(t) && !t.equals("stop")).count();
            if (rest <= 2) {
                return new Answer(Intent.COMMAND, null, List.of(), "stop", rest == 0 ? Band.SURE : Band.CLOSE, List.of());
            }
        }

        // ── "like my X": a NEW one like it, never his X changed ──
        if (toks.contains("like") && (!names.ids().isEmpty() || pointing)) {
            return new Answer(Intent.NEW, names.ids().size() == 1 ? names.ids().get(0) : null, List.of(), "", Band.NOTHING, List.of());
        }

        // ── a copy: "another X", "X again", "one more" ──
        boolean again = toks.stream().anyMatch(AGAIN::contains);
        boolean sized = toks.stream().anyMatch(t -> SIZE_UP.contains(t) || SIZE_DOWN.contains(t));
        if (again && !sized) {
            Ops o = readOps(toks, names);
            List<String> rest = o.unread().stream().filter(t -> !AGAIN.contains(t)).toList();
            if (o.ops().isEmpty() && !st.isEmpty() && names.ids().isEmpty()) {
                Set<String> terms = new HashSet<>();
                for (String sid : st) {
                    String[][] tt = starterTerms.get(sid.substring("starter:".length()));
                    if (tt != null) {
                        terms.addAll(Arrays.asList(tt[0]));
                        terms.addAll(Arrays.asList(tt[1]));
                    }
                }
                List<String> left = rest.stream().filter(t -> !terms.contains(stem(t))).toList();
                if (left.size() <= 1) {
                    return new Answer(Intent.COPY, st.size() == 1 ? st.get(0) : null, List.of(), "",
                            st.size() == 1 && left.isEmpty() ? Band.SURE : Band.CLOSE, List.of(), st);
                }
            }
            List<String> target = !names.ids().isEmpty() ? names.ids()
                    : (last != null && (pointing || rest.isEmpty()) ? List.of(last) : List.of());
            if (o.ops().isEmpty() && rest.size() <= 1) {
                if (target.size() == 1) {
                    return new Answer(Intent.COPY, target.get(0), List.of(), "", rest.isEmpty() ? Band.SURE : Band.CLOSE, List.of(),
                            target);
                }
                if (target.size() > 1) {
                    return new Answer(Intent.COPY, null, List.of(), "", Band.CLOSE, List.of(), target);
                }
                return new Answer(Intent.COPY, null, List.of(), "", Band.NOTHING, List.of());
            }
        }

        // ── an edit: a referent and a change ──
        if (!referent.isEmpty() || pointing && last == null) {
            Ops first = readOps(toks, names);
            Map<String, Thing> byId = new LinkedHashMap<>();
            things.forEach(t -> byId.put(t.id(), t));
            final List<String> named = names.ids();
            if (!referent.isEmpty() && referent.stream().allMatch(x -> byId.containsKey(x)
                    && kindOf(byId.get(x).what()).equals("item") && namedAsItself(toks, byId.get(x), named))) {
                // his ITEM: its look and power are read too, and only what fits an item (a creature's "glows"
                // never competes with the item's "light up the dark"); typos respelt against those words as well
                Set<String> known = knownWords(things, starters);
                itemVocab.keySet().forEach(known::addAll);
                List<String> itoks = respell(tokens(wish), known, ownWords(things, starters));
                if (itoks.size() == toks.size()) {
                    first = readOps(itoks, names, itemVocab);
                }
            }
            final Ops o = first;
            boolean newWords = toks.stream().anyMatch(NEW_CUES::contains);
            if (o.ops().isEmpty() && o.unread().isEmpty()) {
                return out;
            }
            if (newWords && referent.isEmpty()) {
                return out;
            }
            if (referent.isEmpty()) {
                return new Answer(Intent.EDIT, null, o.ops(), "", Band.NOTHING, o.unread());
            }
            Map<String, Thing> by = new LinkedHashMap<>();
            things.forEach(t -> by.put(t.id(), t));
            List<String> fitting = referent.stream().filter(x -> by.containsKey(x) && fits(o.ops(), by.get(x).what())).toList();
            if (!o.ops().isEmpty() && fitting.isEmpty()) {
                return new Answer(Intent.EDIT, referent.size() == 1 ? referent.get(0) : null, List.of(), "", Band.NOTHING, o.unread());
            }
            List<String> ref = fitting.isEmpty() ? referent : fitting;
            String label = ref.size() == 1 ? ref.get(0) : null;
            Band band = label != null && !o.ops().isEmpty() && o.unread().isEmpty() ? Band.SURE
                    : (!o.ops().isEmpty() && o.unread().isEmpty() ? Band.CLOSE : Band.NOTHING);
            return new Answer(Intent.EDIT, label, o.unread().isEmpty() ? o.ops() : List.of(), "", band, o.unread(),
                    band != Band.NOTHING ? ref : List.of());
        }
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private static Set<String> set(String... words) {
        return Set.of(new LinkedHashSet<>(Arrays.asList(words)).toArray(String[]::new));
    }

    private static List<List<String>> phrases(String... ps) {
        List<List<String>> out = new ArrayList<>();
        for (String p : ps) {
            out.add(List.of(p.split(" ")));
        }
        return out;
    }
}
