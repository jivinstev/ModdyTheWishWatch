package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>Which colours he asked for, and what that means for each kind</b>:
 * <ul>
 *   <li>one colour, for a creature or an item ("a red cat") — the SOLID colour;</li>
 *   <li>two or more colours, or a theme ("a red and orange cat", "an ocean dragon", "light blue like ice") — the
 *       closest THREE-TONE scheme;</li>
 *   <li>one colour, for a BUILDING ("a red house") — the three-tone scheme led by it, unless he says all / just / only /
 *       solid red; and since that could go either way, the solid is the next tile and the page is never sure.</li>
 * </ul>
 * What he SAID is read by the matcher ({@code Apprentice.colourSaid}, from the phrases it read, misspellings included);
 * this holds the pure parts. tools/apprentice_match.py {@code with_colours} is the twin, from the tree's export.
 */
public final class Colours {

    private Colours() {
    }

    /** Words that say ONE colour all over, even on a building. */
    public static final Set<String> ALL_OVER = Set.of("all", "just", "only", "solid", "entirely", "completely", "totally", "plain");

    /** Words that join colours ("red and orange", "red n orange"): a phrase of only these and colours is not a theme. */
    public static final Set<String> JOINS = Set.of("and", "n", "&", "with", "or");

    /** Mood words, not themes: "a fluffy white bunny" is white. They never override a colour he named. */
    public static final Set<String> MOODS = Set.of("fluffy", "fancy", "fiery", "gooey", "slimy", "cloudy", "leafy", "frosty",
            "snowy", "pastel", "molten");

    /** The solids and Gold (one colour all over), in the menu's order. */
    public static List<Schemes.Scheme> solids() {
        List<Schemes.Scheme> out = new ArrayList<>(Schemes.SOLIDS);
        out.add(Schemes.byId("gold"));
        return out;
    }

    /** The three-tone schemes tied closest to two or more named colours (at least two in each), best first. */
    public static List<String> closestAll(List<String> named) {
        Set<String> want = new LinkedHashSet<>(named);
        List<String> out = new ArrayList<>();
        int bestN = 1;
        for (Schemes.Scheme s : Schemes.THREE_TONE) {
            List<String> hues = Schemes.HUES.getOrDefault(s.id(), List.of());
            int n = (int) want.stream().filter(hues::contains).count();
            if (n > bestN) {
                out.clear();
                bestN = n;
            }
            if (n == bestN) {
                out.add(s.id());
            }
        }
        return out;
    }

    /** Did he say one colour all over ("all red", "just red")? */
    public static boolean allOver(List<String> toks) {
        return toks.stream().anyMatch(ALL_OVER::contains);
    }

    /** For the Python twin. */
    static com.google.gson.JsonObject toJson() {
        com.google.gson.JsonObject j = new com.google.gson.JsonObject();
        com.google.gson.JsonObject words = new com.google.gson.JsonObject();
        for (Schemes.Scheme s : solids()) {
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            s.words().forEach(w -> a.add(w.replace("~", "").strip()));
            words.add(s.id(), a);
        }
        j.add("words", words);
        com.google.gson.JsonObject lead = new com.google.gson.JsonObject();
        new java.util.TreeMap<>(Schemes.LEAD).forEach(lead::addProperty);
        j.add("lead", lead);
        com.google.gson.JsonObject hues = new com.google.gson.JsonObject();
        for (Schemes.Scheme s : Schemes.THREE_TONE) {
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            Schemes.HUES.getOrDefault(s.id(), List.of()).forEach(a::add);
            hues.add(s.id(), a);
        }
        j.add("hues", hues);
        com.google.gson.JsonObject themes = new com.google.gson.JsonObject();
        for (Schemes.Scheme s : Schemes.THREE_TONE) {
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            s.words().forEach(w -> a.add(w.replace("~", "").strip()));
            themes.add(s.id(), a);
        }
        j.add("themes", themes);
        com.google.gson.JsonArray joins = new com.google.gson.JsonArray();
        JOINS.stream().sorted().forEach(joins::add);
        j.add("joins", joins);
        com.google.gson.JsonArray moods = new com.google.gson.JsonArray();
        MOODS.stream().sorted().forEach(moods::add);
        j.add("moods", moods);
        com.google.gson.JsonArray all = new com.google.gson.JsonArray();
        ALL_OVER.stream().sorted().forEach(all::add);
        j.add("allOver", all);
        return j;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
