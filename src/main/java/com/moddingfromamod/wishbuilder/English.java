package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * English plurals, both ways. One table,
 * {@code apprentice/inflections.json}, read here and by tools/english.py (the reference; {@code EnglishTest} writes
 * this class's answers over every installed name and the sync compares them).
 *
 * <p>{@link #singular} is what a typed plural is read as, before {@link Apprentice#stem} strips the regular endings
 * ("geese" → goose, "potatoes" → potato, "cookies" → cookie). {@link #plural} is how a found thing is said for
 * several ("16 golden apples", "3 real foxes", "16 dirt"). {@link #withArticle} is how a thing is said for one ("a pair
 * of shears", "some oak planks", "The Last Sword"), in every place Moddy says one. Pure: no Minecraft type.
 */
public final class English {

    private static final Map<String, String> PLURAL = new HashMap<>();
    private static final List<String[]> SUFFIXES = new ArrayList<>();
    private static final Map<String, String> READING = new HashMap<>();
    private static final Map<String, String> NAMES = new HashMap<>();
    private static final Set<String> SAME = new HashSet<>();
    private static final Set<String> MASS = new HashSet<>();
    private static final Set<String> ALREADY = new HashSet<>();
    private static final Set<String> BREAKS = new HashSet<>();
    private static final Set<String> PAIR = new HashSet<>();
    private static final Set<String> PROPER = new HashSet<>();

    static {
        try (InputStream in = English.class.getClassLoader().getResourceAsStream("apprentice/inflections.json")) {
            if (in == null) {
                throw new IllegalStateException("apprentice/inflections.json is not on the classpath");
            }
            JsonObject t = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            t.getAsJsonObject("plural").entrySet().forEach(e -> PLURAL.put(e.getKey(), e.getValue().getAsString()));
            t.getAsJsonObject("suffixes").entrySet().forEach(e -> SUFFIXES.add(new String[] {e.getKey(), e.getValue().getAsString()}));
            SUFFIXES.sort((a, b) -> a[0].length() != b[0].length() ? b[0].length() - a[0].length() : a[0].compareTo(b[0]));
            t.getAsJsonObject("reading").entrySet().forEach(e -> READING.put(e.getKey(), e.getValue().getAsString()));
            t.getAsJsonObject("names").entrySet().forEach(e -> NAMES.put(e.getKey(), e.getValue().getAsString()));
            t.getAsJsonArray("same").forEach(x -> SAME.add(x.getAsString()));
            t.getAsJsonArray("mass").forEach(x -> MASS.add(x.getAsString()));
            t.getAsJsonArray("already").forEach(x -> ALREADY.add(x.getAsString()));
            t.getAsJsonArray("breaks").forEach(x -> BREAKS.add(x.getAsString()));
            t.getAsJsonArray("pair").forEach(x -> PAIR.add(x.getAsString()));
            t.getAsJsonArray("proper").forEach(x -> PROPER.add(x.getAsString()));
        } catch (IOException e) {
            throw new IllegalStateException("apprentice/inflections.json could not be read", e);
        }
    }

    private English() {
    }

    /** The word a typed (lower-case) plural is read as: the table's irregulars only; the stem does the rest. */
    public static String singular(String word) {
        return READING.getOrDefault(word, word);
    }

    static String pluralWord(String w) {
        String p = PLURAL.get(w);
        if (p != null) {
            return p;
        }
        for (String[] s : SUFFIXES) {
            if (w.endsWith(s[0])) {
                return w.substring(0, w.length() - s[0].length()) + s[1];
            }
        }
        if (w.length() > 1 && w.endsWith("y") && "aeiou".indexOf(w.charAt(w.length() - 2)) < 0) {
            return w.substring(0, w.length() - 1) + "ies";
        }
        if (w.endsWith("s") || w.endsWith("x") || w.endsWith("z") || w.endsWith("ch") || w.endsWith("sh")) {
            return w + "es";
        }
        return w + "s";
    }

    private static int head(String[] words) {
        for (int i = 1; i < words.length; i++) {
            if (BREAKS.contains(words[i].toLowerCase(Locale.ROOT))) {
                return i - 1;
            }
        }
        return words.length - 1;
    }

    /** A name said for several: its head word inflected (the word before "of", "o'", "with" or "on", else the last). */
    public static String plural(String name, boolean mob) {
        String whole = NAMES.get(name);
        if (whole != null) {
            return whole;
        }
        String[] words = name.split(" ", -1);
        int h = head(words);
        String w = words[h];
        String low = w.toLowerCase(Locale.ROOT);
        if (ALREADY.contains(low) || SAME.contains(low) || (!mob && MASS.contains(low))) {
            return name;
        }
        String p = pluralWord(low);
        if (!w.isEmpty() && Character.isUpperCase(w.charAt(0))) {
            p = Character.toUpperCase(p.charAt(0)) + p.substring(1);
        }
        words[h] = p;
        return String.join(" ", words);
    }

    /** Is this item's head word a material ("dirt", "glass")? Said "16 dirt". */
    public static boolean mass(String name) {
        String[] words = name.toLowerCase(Locale.ROOT).split(" ", -1);
        return MASS.contains(words[head(words)]);
    }

    /** {@link #withArticle(String, int, boolean)} for a thing (not a creature). */
    public static String withArticle(String name, int n) {
        return withArticle(name, n, false);
    }

    /**
     * A name said with its article, its case kept as given: "a golden apple", "an oak log"; no article after a
     * leading "The" ("The Last Sword") or for a known proper name ("Moddy"); "a pair of shears" for a pair noun and
     * "some oak planks" for any other head word that is already plural; "some sugar" for a material. For several it is
     * the count and {@link #plural} ("16 golden apples", "16 dirt"). A creature ({@code mob}) is never a material or a
     * plural: "a Drowned". The a/an is the first letter's, except a "uni-" word and "UFO" ("a unicorn", which the old
     * always-"a" look line said right); the other sound exceptions ("an hour", "a eu-") are not here yet.
     */
    public static String withArticle(String name, int n, boolean mob) {
        String t = name == null ? "" : name.strip();
        if (t.isEmpty()) {
            return t;
        }
        boolean the = leadingThe(t);
        if (n > 1) {
            String body = the ? t.substring(4).strip() : t;
            return n + " " + plural(body.isEmpty() ? t : body, mob);
        }
        if (the || PROPER.contains(t.toLowerCase(Locale.ROOT))) {
            return t;
        }
        String[] words = t.toLowerCase(Locale.ROOT).split(" ", -1);
        String head = words[head(words)];
        if (!mob) {
            if (PAIR.contains(head)) {
                return "a pair of " + t;
            }
            if (ALREADY.contains(head) || MASS.contains(head)) {
                return "some " + t;
            }
        }
        return (vowelSound(words[0]) ? "an " : "a ") + t;
    }

    /** Is a thing with this name said as several even for one ("some oak planks"; "a pair of shears" is one)? */
    public static boolean saidAsMany(String name) {
        String t = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || leadingThe(t) || PROPER.contains(t)) {
            return false;
        }
        String[] words = t.split(" ", -1);
        String head = words[head(words)];
        return ALREADY.contains(head) && !PAIR.contains(head);
    }

    /**
     * Is this name a title, said with its capitals ("The Last Sword", "Zorbo's Shell", "Crown of Countless Stars",
     * "Moddy", "TNT"), rather than a common noun said in lower case ("golden apple")? A leading "The", a known proper
     * name, a possessive of a name (not of a common word), a capital inside a word, or a capitalised word after
     * "of" ("Eye of Ender" too, which is said as a name).
     */
    public static boolean proper(String name) {
        String t = name == null ? "" : name.strip();
        if (t.isEmpty()) {
            return false;
        }
        if (leadingThe(t) || PROPER.contains(t.toLowerCase(Locale.ROOT))) {
            return true;
        }
        boolean afterBreak = false;
        for (String w : t.split(" ", -1)) {
            if (w.isEmpty()) {
                continue;
            }
            int own = w.indexOf("'s");
            if (Character.isUpperCase(w.charAt(0)) && own > 0
                    && !Apprentice.english().contains(w.substring(0, own).toLowerCase(Locale.ROOT))) {
                return true;   // "Zorbo's Shell"; but "Dragon's Breath" is a dragon's, said "some dragon's breath"
            }
            for (int i = 1; i < w.length(); i++) {
                if (Character.isUpperCase(w.charAt(i)) && Character.isLetter(w.charAt(i - 1))) {
                    return true;
                }
            }
            if (afterBreak && Character.isUpperCase(w.charAt(0))) {
                return true;
            }
            if (w.equals("of")) {   // "Crown of Countless Stars"; "Carrot on a Stick" and "Boat with Chest" are things
                afterBreak = true;
            }
        }
        return false;
    }

    /**
     * A found item's name said in a sentence: {@link #withArticle}, in lower case unless it may be a title ({@code
     * titled}: another mod's item or one of ours, never vanilla's, whose names are all common nouns: "a block of coal")
     * and is {@link #proper}: "a pair of shears", "some oak planks", but "The Last Sword", "a Crown of Countless Stars".
     */
    public static String said(String name, int n, boolean titled) {
        String out = withArticle(name, n);
        return titled && proper(name) ? out : out.toLowerCase(Locale.ROOT);
    }

    private static boolean leadingThe(String t) {
        String low = t.toLowerCase(Locale.ROOT);
        return low.startsWith("the ") || low.startsWith("the-");
    }

    private static boolean vowelSound(String first) {
        if (first.isEmpty() || "aeiou".indexOf(first.charAt(0)) < 0) {
            return false;
        }
        return !(first.startsWith("uni") || first.equals("ufo"));
    }

    /** The answers tools/english.py checks: every name's plural, and every reading. */
    public static Map<String, String> readings() {
        return Map.copyOf(READING);
    }
}
