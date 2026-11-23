package com.moddingfromamod.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>Did the sculpt actually build the parts the reference named?</b>
 *
 * <p>This exists because <b>telling the agent did not work, twice.</b> Omega Lad's reference says
 * {@code beak, wing} at MEDIUM confidence from five sources; the brief said "every part named there
 * must exist in what you build"; a second pass added "in particular: this character has a beak. If
 * the beak does not survive into your sculpt, you have built something else." The reference was
 * delivered on four separate cache hits and the agent built {@code shades, collarL, hairswoop,
 * smirk1..4} both times — a man in sunglasses.
 *
 * <p>So this stops being a matter of wording. It is the same move that already worked for colour:
 * {@code SculptCritique} does not ask the agent to get the palette right, it <i>reports the palette
 * it actually produced</i> — level 2 of this repo's own ladder, a check inside the authoring tool,
 * reported back before the reveal. Colour was followed exactly in both failing runs; shape was the
 * axis with no check behind it.
 *
 * <p><b>It reports; it never refuses.</b> A child's wish must not fail because a wiki called
 * something a flipper, and the agent is allowed to disagree with a reference for a reason — it just
 * has to do so knowingly rather than by quietly dropping the word.
 *
 * <p>Pure and Minecraft-free, so it is unit-tested.
 */
public final class ReferencePartCheck {

    /**
     * Parts any humanoid has. Missing one of these says nothing about whether the right creature
     * was built, so they are never reported — a warning that fires on every sculpt is a warning
     * nobody reads.
     */
    private static final Set<String> UNREMARKABLE = Set.of(
            "head", "body", "arm", "leg", "eye", "hair", "nose", "hat", "shoe",
            "mouth", "neck", "cheek");

    /**
     * Words a builder may reasonably use for the same feature. Deliberately small: the cost of a
     * missing synonym is one unnecessary nudge, and the cost of an over-eager one is a check that
     * silently passes a creature with no beak.
     */
    private static final java.util.Map<String, Set<String>> ALSO_CALLED = java.util.Map.ofEntries(
            java.util.Map.entry("beak", Set.of("bill", "snoot")),
            java.util.Map.entry("wing", Set.of("flipper")),
            java.util.Map.entry("fin", Set.of("flipper", "fluke")),
            java.util.Map.entry("snout", Set.of("muzzle", "nose")),
            java.util.Map.entry("tooth", Set.of("teeth", "fang", "tusk")),
            java.util.Map.entry("claw", Set.of("talon", "nail")),
            java.util.Map.entry("horn", Set.of("antler")),
            java.util.Map.entry("shell", Set.of("carapace")),
            java.util.Map.entry("spike", Set.of("spine", "quill")),
            // round 2's words. "hooves" does not contain "hoof", so it is spelled out.
            java.util.Map.entry("hoof", Set.of("hooves")),
            java.util.Map.entry("tentacle", Set.of("tendril")),
            java.util.Map.entry("gill", Set.of("frill")),
            java.util.Map.entry("antenna", Set.of("feeler")),
            java.util.Map.entry("whisker", Set.of("barbel")));

    private ReferencePartCheck() {
    }

    /**
     * Reference parts that are distinctive AND appear nowhere in the sculpt's part names.
     *
     * @param referenceParts the vocabulary words the reference named
     * @param sculptPartNames the names the agent gave its boxes — free-form, so this is a substring
     *                        match rather than an equality test
     */
    public static List<String> missing(List<String> referenceParts, List<String> sculptPartNames) {
        if (referenceParts == null || referenceParts.isEmpty()
                || sculptPartNames == null || sculptPartNames.isEmpty()) {
            return List.of();
        }
        String haystack = String.join(" ", sculptPartNames).toLowerCase(Locale.ROOT);
        List<String> gone = new ArrayList<>();
        for (String want : referenceParts) {
            String w = want == null ? "" : want.toLowerCase(Locale.ROOT).trim();
            if (w.isEmpty() || UNREMARKABLE.contains(w)) {
                continue;
            }
            if (haystack.contains(w)) {
                continue;
            }
            boolean synonym = ALSO_CALLED.getOrDefault(w, Set.of()).stream()
                    .anyMatch(haystack::contains);
            if (!synonym) {
                gone.add(w);
            }
        }
        return List.copyOf(gone);
    }

    /**
     * What to tell the agent, or "" when there is nothing to say.
     *
     * <p>Names what is missing and what it implies, because "you are missing a beak" invites adding
     * a beak to a man in sunglasses, and the actual problem is that the whole creature is the wrong
     * kind of thing.
     */
    public static String report(List<String> referenceParts, List<String> sculptPartNames,
                                String character) {
        List<String> gone = missing(referenceParts, sculptPartNames);
        if (gone.isEmpty()) {
            return "";
        }
        return "⚠ The reference for " + character + " names " + String.join(", ", gone)
                + " and your sculpt has no part by that name. These are the features that make "
                + "this character recognisable, so this usually means the creature is the wrong "
                + "KIND of thing rather than that a detail is missing — check whether you have "
                + "built what you remember instead of what was looked up. If you disagree with "
                + "the reference, say so in your note and why.";
    }
}
