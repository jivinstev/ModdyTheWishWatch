package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which mods this run MEANT to boot, and which of them are not here —.
 *
 * <p>A Gate D cell whose wish names content from a mod that was not booted produces a confident PASS
 * about a question nobody asked. Measured on a wish the child really typed — a burger-restaurant
 * train built from his own mod's restaurant blocks — where {@code find_content} returned
 * <b>36</b> of those blocks in the real session and they shaped the whole design. Without the mod
 * the agent improvises with concrete and is <b>right to</b>; the run passes; the number is not
 * comparable to the thing it is being compared against.
 *
 * <h2>Why the tool has to say it, not just the harness</h2>
 *
 * <p>The launcher half of this is fixed — {@code run-gated.command} no longer defaults
 * {@code SMOKEJARS} to Create alone, so {@code client-test.sh}'s auto-selection applies. What that
 * cannot fix is what the agent is TOLD. {@code find_content} answers an empty search with
 * <i>"No mod provides it, so now it IS right to build a lookalike"</i> — a statement about the whole
 * world, and <b>false</b> when the harness simply failed to boot the mod that does provide it. The
 * agent then builds a lookalike on the strength of an incomplete world and is never wrong at any
 * step.
 *
 * <p>So the expectation is declared by whoever launched the run, and the claim is qualified when it
 * does not hold. Absent the property this is inert, which is what keeps a real child's session — and
 * anyone who never sets it — completely unaffected.
 *
 * <p>Pure: ids in, sentence out.
 */
public final class ExpectedMods {

    /**
     * Comma-separated mod ids this run intended to boot, set by the launcher from the jars it
     * actually resolved. Deliberately a property rather than something inferred: only the caller
     * knows what the run was FOR, and guessing it from the wish text is the clever version that
     * would cry wolf.
     */
    public static final String PROPERTY = "moddingfromamod.expectmods";

    private ExpectedMods() {}

    /** The ids named by the property, lowercased and de-duplicated; empty when it is unset. */
    public static Set<String> expected() {
        return parse(System.getProperty(PROPERTY));
    }

    static Set<String> parse(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String s : raw.split(",")) {
            String id = s.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    /** Expected but not loaded, in the order they were named. */
    public static List<String> absent(Collection<String> expected, Collection<String> loaded) {
        List<String> out = new ArrayList<>();
        if (expected == null) {
            return out;
        }
        Set<String> have = new LinkedHashSet<>();
        if (loaded != null) {
            for (String s : loaded) {
                if (s != null) {
                    have.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        for (String id : expected) {
            if (id != null && !have.contains(id.trim().toLowerCase(Locale.ROOT))) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * What to append to <i>"nothing installed matches X"</i> when the world is incomplete.
     *
     * <p>Empty when every expected mod is present — which is the normal case, and a qualifier that
     * fired on it would be noise the agent learns to skip.
     *
     * <p>It contradicts the sentence it follows on purpose. "No mod provides it" and "the mod that
     * provides it did not boot" call for opposite actions, and only one of them is worth a
     * lookalike.
     */
    public static String qualifier(Collection<String> expected, Collection<String> loaded) {
        List<String> missing = absent(expected, loaded);
        if (missing.isEmpty()) {
            return "";
        }
        return "\n  ⚠ BUT THIS WORLD IS INCOMPLETE — it was supposed to have "
                + String.join(", ", missing) + " loaded and does not. If the child asked for "
                + "something from " + (missing.size() == 1 ? "that mod" : "those mods")
                + ", a lookalike is NOT the right answer and this run cannot be compared with one "
                + "where it booted. Say that you could not find it rather than building a "
                + "substitute.";
    }
}
