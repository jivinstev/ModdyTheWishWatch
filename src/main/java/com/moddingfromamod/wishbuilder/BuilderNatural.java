package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>What a body or a starter does just by being itself</b>: a dragon flies and is fireproof, a rabbit hops, a
 * raptor pounces, a phoenix flies, glows and never burns, a hamster naps. Every Builder and Apprentice make of it gets
 * these without a pick, so the two ways in agree and the default is the one a child expects.
 *
 * <p>Before only a Builder BODY's {@code flies} came along, and a starter species brought nothing: the
 * lowering's own power list always replaced the starter's file, so a Builder pet dragon walked and burned, and a
 * Builder T-Rex could not pounce.
 *
 * <p>What counts: the powers in {@link #POWERS}, read off the body's species file and its sculpt, plus {@link #EXTRA}
 * for a starter whose shared file lacks one its kind obviously has. Mood words, {@code rideable} (the kind says
 * whether he rides it) and {@code persistent} are not natural: those stay his picks.
 *
 * <p>Pure apart from reading the shipped files. {@code tools/behaviour-score.py} {@code body_powers} is its Python twin
 * and {@code BuilderNaturalTest} holds the two to the same answers.
 */
public final class BuilderNatural {

    private BuilderNatural() {
    }

    /** The powers a body brings by itself, in this order. */
    public static final List<String> POWERS = List.of("flies", "hops", "leaps", "fireproof", "glowing", "naps");

    /**
     * A starter whose shared species file lacks what its kind obviously does. The pet dragon's file is the wish
     * library's too (a Magician's {@code start_from}), so the Builder adds it here rather than changing that file.
     */
    static final Map<String, List<String>> EXTRA = Map.of("species/pet_dragon", List.of("flies"));

    /**
     * The natural powers of ready-made {@code preset} ({@code species/pet_dragon}, {@code bodies/rabbit}, with or
     * without the {@code preset:} prefix), given its stored {@code sculpt} (may be null).
     */
    public static List<String> of(String preset, JsonObject sculpt) {
        String id = preset == null ? "" : Catalogue.bare(preset);
        Set<String> found = new LinkedHashSet<>();
        add(found, sculpt);
        if (id.startsWith("species/") || id.startsWith("bodies/")) {
            add(found, LibraryPresets.SHIPPED.species(id));
        }
        found.addAll(EXTRA.getOrDefault(id, List.of()));
        List<String> out = new ArrayList<>();
        for (String p : POWERS) {
            if (found.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Does it fly by itself? */
    public static boolean flies(String preset, JsonObject sculpt) {
        return of(preset, sculpt).contains("flies");
    }

    private static void add(Set<String> into, JsonObject j) {
        if (j != null && j.has("powers") && j.get("powers").isJsonArray()) {
            for (JsonElement e : j.getAsJsonArray("powers")) {
                if (e.isJsonPrimitive()) {
                    into.add(e.getAsString());
                }
            }
        }
    }
}
