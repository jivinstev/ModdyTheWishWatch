package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>A block palette in the shape the scorer actually reads, keyed by COLOUR rather than material.</b>
 *
 * <p>Two separate mistakes made every palette property on the trains ladder unscoreable, and both
 * are fixed here rather than in the snapshot, so they can be unit tested.
 *
 * <p><b>It lives in {@code wish}, not in {@code client.test}, where it was first written.</b> The
 * ladder harness needs it and so does {@link com.moddingfromamod.agent.Surroundings}, which runs on
 * the server and tells every worker what the train currently looks like. A production path reaching
 * into a package whose other occupant is {@code @EventBusSubscriber(Dist.CLIENT)} is a dedicated
 * server crash waiting for the first headless run.
 *
 * <h2>1. The shape</h2>
 *
 * <p>{@code score-continuity.py}'s {@code palette_colours} reads a <b>list</b> of
 * {@code "<name>:<percent>"} strings and splits on the LAST colon. The trains snapshot wrote a
 * {@code Map} of {@code {id: fraction}}; iterating that in Python yields its KEYS, so
 * {@code "minecraft:purple_concrete"} split into {@code ("minecraft", "purple_concrete")}, the
 * share parsed as 0, and every colour comparison failed. Measured: all 12 cells of the first trains
 * A/B tracked 3 of 6 declared properties, and the three lost were the three palette ones — on a
 * train that really was purple and gold.
 *
 * <h2>2. The key</h2>
 *
 * <p>The buildings class declares block ids, and {@code same_colour} is deliberately exact so
 * {@code palette_has:stone} is not answered by {@code stone_bricks}. For a train that is the wrong
 * question: the property a child cares about is that it is still <b>purple</b>, not that it is
 * still purple <i>concrete</i>. Left as ids, arm B choosing {@code purple_wool} where arm A chose
 * {@code purple_concrete} would score as a lost property when it is a legitimate choice — a
 * measurement that punishes the stranger for having taste.
 *
 * <p>So shares are aggregated by colour word, and a block with no colour in its name keeps its own
 * path ({@code glowstone}), which stays scoreable as {@code palette_has:glowstone}.
 */
public final class PaletteWords {

    /**
     * Longest first, so {@code light_blue_concrete} is not read as {@code blue}. The two-word
     * colours are exactly why this cannot be a plain "split on the first underscore".
     */
    private static final List<String> COLOURS = List.of(
            "light_blue", "light_gray", "blue", "black", "brown", "cyan", "gray", "green",
            "lime", "magenta", "orange", "pink", "purple", "red", "white", "yellow",
            // Not dyes, but they are how a child names a block's colour: "make it gold".
            "gold", "golden", "copper", "diamond", "emerald", "netherite", "iron", "quartz");

    private PaletteWords() {
    }

    /**
     * The colour a block id reads as, or its own name when it has none.
     *
     * <p>The colour must be a whole leading SEGMENT — {@code redstone_lamp} is not
     * {@code red}, and that distinction is the same whole-word rule the MeatFilter needed for
     * {@code cod} against {@code cocoa_beans}.
     */
    public static String colourOf(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return "";
        }
        String path = blockId.toLowerCase(Locale.ROOT);
        int colon = path.lastIndexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        // Strip a blockstate, so `create:controls[facing=east]` is `controls`.
        int bracket = path.indexOf('[');
        if (bracket >= 0) {
            path = path.substring(0, bracket);
        }
        for (String colour : COLOURS) {
            if (path.equals(colour) || path.startsWith(colour + "_")) {
                return colour.equals("golden") ? "gold" : colour;
            }
        }
        return path;
    }

    /**
     * {@code ["purple:58", "gold:32", …]} — biggest share first, percentages of the whole.
     *
     * @param counts how many blocks of each id the design contains
     */
    public static List<String> of(Map<String, Integer> counts) {
        int total = 0;
        for (int n : counts.values()) {
            total += n;
        }
        if (total == 0) {
            return List.of();
        }
        Map<String, Integer> byColour = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            byColour.merge(colourOf(e.getKey()), e.getValue(), Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(byColour.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted) {
            out.add(e.getKey() + ":" + Math.round(e.getValue() * 100.0 / total));
        }
        return out;
    }
}
