package com.moddingfromamod.runtime.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptCritique;

/**
 * <b>Did this edit quietly change what the creature IS?</b> — the whole-body check, done against
 * the previous version rather than against a declaration.
 *
 * <h2>Why it cannot be the declared-palette check</h2>
 * {@link DeclaredPalette} asks "did you build what you said you would", and on an inheriting sculpt
 * the agent says <i>"two black wings"</i>. Grading that against the merged body reports a correct
 * diff as a catastrophic failure and instructs the agent to re-sculpt until it passes — which it
 * can only do by rebuilding the entire creature. Measured in every one of thirteen ladder runs.
 *
 * <p>So the edit is graded on the edit. But dropping the whole-body check entirely would reinstate
 * the failure that motivated it in the first place: <b>six consecutive Spikor revisions drifted in
 * colour and nothing ever said so.</b> Every individual edit was locally fine; the creature was not.
 *
 * <p>This is the missing third thing. It needs <b>no declaration at all</b> — it compares the
 * creature to its own previous version — so an edit cannot confuse it, and it is precisely the
 * Spikor check.
 *
 * <h2>It informs; it does not command</h2>
 * Three separate mechanisms have now been caught telling the agent to do the wrong thing in an
 * authoritative voice (a phantom reference verdict, a declared-palette MISSED on a diff, and a
 * lesson pointing at a post-hoc mirror). Every one was obeyed. So this reports a fact and asks a
 * question; it never says "fix it".
 *
 * <p>Pure and Minecraft-free, so the whole rule is a unit test.
 */
public final class PaletteDrift {

    /**
     * How much of the creature a colour must gain or lose before it is worth mentioning.
     *
     * <p>Twenty points. Small drift is how a creature stays alive across edits — repainting a hand
     * is not a betrayal — and a check that fires on every edit is one the agent learns to skim.
     */
    public static final double NOTABLE = 0.20;

    private PaletteDrift() {
    }

    /**
     * What changed between two versions, in the child's terms, or "" if nothing worth saying did.
     *
     * @param childWords the wish being granted; a colour he asked to change is never reported
     */
    public static String between(List<CreatureSculpt.Part> before,
                                 List<CreatureSculpt.Part> after, String childWords) {
        if (before == null || before.isEmpty() || after == null || after.isEmpty()) {
            return "";   // a creation has nothing to drift from
        }
        Map<String, Double> was = SculptCritique.palette(before);
        Map<String, Double> now = SculptCritique.palette(after);
        List<String> moved = new ArrayList<>();
        for (String bucket : new TreeSet<>(union(was, now))) {
            double a = was.getOrDefault(bucket, 0.0);
            double b = now.getOrDefault(bucket, 0.0);
            if (Math.abs(b - a) < NOTABLE || mentioned(childWords, bucket)) {
                continue;
            }
            moved.add(plain(bucket) + " " + pct(a) + " → " + pct(b));
        }
        if (moved.isEmpty()) {
            return "";
        }
        // THE HEADLINE SOFTENS; THE REPORT DOES NOT.
        //
        // A blanket "if the wish names any colour, say nothing" was the first attempt and it
        // silenced the exact wish this exists for — "give him tiny black WINGS" names a colour.
        // So the comparison always runs, with only the colours he actually named exempted, and the
        // wording changes instead: on a colour wish the change is expected and merely worth noting;
        // on any other wish it is the Spikor drift and worth flagging.
        String head = repaintRequested(was, now, childWords)
                ? "This edit also changed: "
                : "⚠ HE DID NOT ASK FOR A COLOUR CHANGE, but this edit moved: ";
        return head + String.join(", ", moved)
                + ". If that was not deliberate, the creature has drifted — six frog knights in a row "
                + "did exactly this and nobody noticed. Say so in your note either way.";
    }

    /**
     * Did he ask for a <b>repaint</b>, as opposed to a coloured addition?
     *
     * <p>Naming a colour is not enough to tell those apart, and two earlier cuts of this rule got it
     * wrong in both directions. "give him tiny black WINGS" names black — and is the exact wish that
     * destroyed a creature, so it must keep the full flag. "make him white instead" also names a
     * colour, and there the flag would be the tool arguing with the customer.
     *
     * <p>What separates them is whether the colour he named <b>took over</b>. Black arrives as a 4%
     * pair of wings; white arrives as 71% of the creature. So: a repaint is a colour he asked for
     * that then grew by more than {@link #NOTABLE}. An explicit "repaint"/"colour" word counts too,
     * since that says it outright.
     */
    static boolean repaintRequested(Map<String, Double> was, Map<String, Double> now,
                                    String childWords) {
        if (childWords == null || childWords.isBlank()) {
            return false;
        }
        String said = childWords.toLowerCase(Locale.ROOT);
        for (String word : new String[] {"colour", "color", "repaint", "recolour", "recolor"}) {
            if (said.contains(word)) {
                return true;
            }
        }
        for (String bucket : union(was, now)) {
            if (!mentioned(childWords, bucket)) {
                continue;
            }
            double grew = now.getOrDefault(bucket, 0.0) - was.getOrDefault(bucket, 0.0);
            if (grew >= NOTABLE) {
                return true;   // he named it and it took the creature over: that is a repaint
            }
        }
        return false;
    }

    private static java.util.Set<String> union(Map<String, Double> a, Map<String, Double> b) {
        java.util.Set<String> all = new TreeSet<>(a.keySet());
        all.addAll(b.keySet());
        return all;
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    /**
     * A colour the child himself brought up is not drift — it is the point of the wish.
     *
     * <p>Bucket names are not words a child types, so each carries the plain words that mean it.
     * Shared shape with the same rule in {@code ReferenceVerdict}: the child outranks the tool.
     */
    static boolean mentioned(String childWords, String bucket) {
        if (childWords == null || childWords.isBlank() || bucket == null) {
            return false;
        }
        String said = childWords.toLowerCase(Locale.ROOT);
        for (String word : switch (bucket) {
            case "tan/orange" -> new String[] {"orange", "tan", "brown", "ginger"};
            case "purple/pink" -> new String[] {"purple", "pink", "violet"};
            case "grey" -> new String[] {"grey", "gray", "silver"};
            default -> new String[] {bucket};
        }) {
            if (said.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String plain(String bucket) {
        return switch (bucket) {
            case "tan/orange" -> "orange-brown";
            case "purple/pink" -> "purple";
            default -> bucket;
        };
    }
}
