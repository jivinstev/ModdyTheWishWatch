package com.moddingfromamod.runtime.gate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.moddingfromamod.runtime.CreatureSculpt;

/**
 * <b>Did this edit keep the creature, or replace it?</b> — the shape half of {@link PaletteDrift}.
 *
 * <h2>Why colour alone was not enough</h2>
 * Colour survives a rebuild almost for free: the agent re-reads the child's words, and those words
 * still say "bright orange with green stripes". What does <i>not</i> survive is everything he never
 * had to say twice — the wings from two wishes ago, the shape of the head, the one big eye.
 *
 * <p>Measured on one set of runs (commit {@code be64a04}, arm B ×3): <b>colour continuity 1.00,
 * part survival 0.36</b> — against an arm A sitting at 1.00 on both. A perfect score on the colour
 * axis while barely a third of the creature survived the hand-off. It was being replaced by a
 * different creature of the same colour, and the colour gate was green throughout.
 *
 * <p>So structure is graded too, and it is graded the same way colour is: against the creature's own
 * previous version, with no declaration to be confused by.
 *
 * <h2>Why a removal the child asked for is not drift</h2>
 * The same rule {@link PaletteDrift#repaintRequested} learned, at the cost of three attempts: a
 * warning that fires on every change is one the agent stops reading. "take off his wings" is a wish,
 * and losing the wings is the wish being granted. Only unrequested loss is drift.
 *
 * <h2>It informs; it does not command</h2>
 * Deliberately no "fix it". Four mechanisms have now been caught instructing the agent in an
 * authoritative voice and being obeyed into a worse outcome — and here the obvious command, "put the
 * parts back", would be satisfied by yet another from-scratch rebuild, which is the thing this
 * exists to catch. It names what was lost and names the option that would have kept it.
 *
 * <p>Pure and Minecraft-free, so the whole rule is a unit test.
 */
public final class ShapeDrift {

    /**
     * Losing this share of the previous body is a replacement, not an edit.
     *
     * <p>A half. Some overlap is meaningless — a rebuild reuses "body" and "head" by reflex, which
     * is why name-based survival is generous by design and this threshold is not near 1.0.
     */
    public static final double REPLACED = 0.5;

    private ShapeDrift() {
    }

    /**
     * What this edit lost, in the child's terms, or "" if nothing worth saying did.
     *
     * @param before     the creature as it stood before this sculpt; empty for a creation
     * @param after      the resolved creature, base and diff merged — <b>not</b> the parts sent
     * @param childWords the wish being granted; a removal he asked for is never reported
     */
    public static String between(List<CreatureSculpt.Part> before,
                                 List<CreatureSculpt.Part> after, String childWords) {
        if (before == null || before.isEmpty() || after == null || after.isEmpty()) {
            return "";   // a creation has nothing to lose
        }
        if (removalRequested(childWords)) {
            return "";
        }
        Set<String> kept = names(after);
        List<String> lost = new ArrayList<>();
        for (String name : names(before)) {
            if (!kept.contains(name)) {
                lost.add(name);
            }
        }
        if (lost.isEmpty()) {
            return "";
        }
        double share = (double) lost.size() / names(before).size();
        String what = lost.size() + " of " + names(before).size() + " parts he already had: "
                + String.join(", ", lost);
        // The wording carries the finding. A couple of dropped boxes is an edit that went slightly
        // wide; losing half the body is the creature being replaced by a new one that happens to
        // share its name, which is what thirteen of thirteen ladder runs actually did.
        return share >= REPLACED
                ? "⚠ THIS REPLACED THE CREATURE INSTEAD OF EDITING IT — it dropped " + what
                        + ". He never asked for those to go; they are simply things he did not "
                        + "repeat this time. Sculpting with start_from keeps them. Say in your "
                        + "note whether the creature he had is still there."
                : "This edit dropped " + what + ", which he did not ask for. If that was not "
                        + "deliberate, start_from would have kept them. Mention it in your note.";
    }

    /**
     * Did he ask for something to go?
     *
     * <p>Deliberately generous — a false "he asked" costs one unreported drift, while a false "he
     * did not" trains the agent to skim a warning that matters.
     */
    static boolean removalRequested(String childWords) {
        if (childWords == null || childWords.isBlank()) {
            return false;
        }
        String said = childWords.toLowerCase(Locale.ROOT);
        for (String phrase : new String[] {
                "remove", "delete", "take off", "take away", "get rid", "without", "no more",
                "lose the", "cut off", "chop off", "shorter", "smaller"}) {
            if (said.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    /** Part names, in order, de-duplicated — the same identity the ladder scorer measures on. */
    private static Set<String> names(List<CreatureSculpt.Part> parts) {
        Set<String> out = new LinkedHashSet<>();
        for (CreatureSculpt.Part p : parts) {
            if (p.name() != null && !p.name().isBlank()) {
                out.add(p.name());
            }
        }
        return out;
    }
}
