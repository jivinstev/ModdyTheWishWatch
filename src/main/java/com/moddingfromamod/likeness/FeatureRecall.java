package com.moddingfromamod.likeness;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.moddingfromamod.reference.ReferenceSpec;
import com.moddingfromamod.runtime.CreatureSculpt;

/**
 * <b>G3 — does Spikor actually have spikes?</b>
 *
 * <p>The most literal possible reading of the complaint that started this work, and deliberately
 * so. {@link ReferenceSpec#parts()} already lists the real character's features as a bounded enum,
 * sourced from pictures inside the researcher sandbox — for Spikor: {@code shell, hair, body, tooth,
 * eye, head, spike, nose, claw, leg, snout, arm}. This asks how many of them the sculpt actually
 * built.
 *
 * <h2>Why the most trivial grader here may be the most useful one</h2>
 *
 * <p>It is the cheapest to <b>calibrate</b>. Every other candidate produces a number a human has to
 * be talked into trusting; this one produces a list a human checks by eye in a second — "it says he
 * has no spikes; does he have spikes?" Given that the calibration round is the thing that decides
 * which grader we optimise against, being obviously-checkable is a real property and not a
 * consolation.
 *
 * <p>It is also the natural regression gate for part-kits: if {@code spike_row} ships and
 * spike recall does not move, the kit is not being used — which is this repo's single most-repeated
 * failure, seven capabilities built and never called.
 *
 * <h2>The limit, stated so it is not discovered as a surprise</h2>
 *
 * <p><b>Presence is not placement.</b> A bone called {@code spike1} stuck on an ankle scores exactly
 * like a spike row down the spine. {@link SilhouetteIou} is what covers placement, and the two must
 * be reported side by side and <b>never averaged</b> — an average of "has the right features" and
 * "in the right places" is a number that cannot be acted on.
 *
 * <p>Pure and Minecraft-free.
 */
public final class FeatureRecall {

    private FeatureRecall() {
    }

    /**
     * Structural words that are not <i>features</i>. A creature having a body and a head says
     * nothing about whether it looks like the character — every sculpt has those — so counting them
     * would inflate every score toward a common floor and flatten exactly the differences this is
     * meant to expose.
     */
    private static final Set<String> UNINFORMATIVE = Set.of("body", "head", "leg", "arm");

    /**
     * @param recall    fraction of the reference's informative features present, 0..1
     * @param precision fraction of the sculpt's bones that correspond to a named feature
     * @param present   the features that were built
     * @param missing   the features the reference names and the sculpt does not have
     * @param scored    false when there was no reference, so this reads as unknown not as zero
     */
    public record Score(double recall, double precision, List<String> present,
                        List<String> missing, boolean scored) {

        public static Score unscored() {
            return new Score(0, 0, List.of(), List.of(), false);
        }

        public String band() {
            if (!scored) {
                return "UNKNOWN";
            }
            if (recall >= 0.8) {
                return "ALL_THERE";
            }
            return recall >= 0.5 ? "PARTIAL" : "MISSING_FEATURES";
        }

        @Override
        public String toString() {
            if (!scored) {
                return "no reference";
            }
            return String.format("recall %.2f, precision %.2f (%s)%s",
                    recall, precision, band(),
                    missing.isEmpty() ? "" : " — missing: " + String.join(", ", missing));
        }
    }

    /**
     * Bones nobody can see, so a named feature can be required to be VISIBLE and not merely
     * present. This is the one idea worth keeping from the vision-likeness grader: its real
     * edge over name-matching was catching a feature that exists but is buried — and
     * {@code SculptCritique} already answers that geometrically, for free and deterministically,
     * where a model call would have cost a photograph and a sandbox.
     */
    private static Set<String> hidden(List<CreatureSculpt.Part> parts) {
        try {
            return new java.util.HashSet<>(
                    com.moddingfromamod.runtime.SculptCritique.buried(parts));
        } catch (RuntimeException e) {
            return Set.of();   // a visibility check must never cost a creature its score
        }
    }

    /** Which of the character's named features the sculpt actually built. */
    public static Score of(List<CreatureSculpt.Part> parts, ReferenceSpec reference) {
        if (parts == null || parts.isEmpty() || reference == null
                || reference.parts() == null || reference.parts().isEmpty()) {
            return Score.unscored();
        }
        List<String> wanted = new ArrayList<>();
        for (String f : reference.parts()) {
            String w = f == null ? "" : f.trim().toLowerCase(Locale.ROOT);
            if (!w.isEmpty() && !UNINFORMATIVE.contains(w)) {
                wanted.add(w);
            }
        }
        if (wanted.isEmpty()) {
            return Score.unscored();
        }

        Set<String> buried = hidden(parts);
        Set<String> present = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        int matchedBones = 0;
        for (String feature : wanted) {
            boolean found = false;
            for (CreatureSculpt.Part p : parts) {
                // A feature that is entirely hidden inside the creature does not count as built.
                // Spikor with a spike nobody can see does not have spikes.
                if (matches(p.name(), feature) && !buried.contains(p.name())) {
                    found = true;
                    break;
                }
            }
            (found ? present : missing).add(feature);
        }
        for (CreatureSculpt.Part p : parts) {
            for (String feature : wanted) {
                if (matches(p.name(), feature)) {
                    matchedBones++;
                    break;
                }
            }
        }
        return new Score((double) present.size() / wanted.size(),
                (double) matchedBones / parts.size(),
                new ArrayList<>(present), new ArrayList<>(missing), true);
    }

    /**
     * Loose bone matching, the way {@code UvRegions} already resolves {@code wing} to
     * {@code wingL}/{@code wingR}.
     *
     * <p>Prefix and containment, both needed: a kit emits {@code spike_row0}, a hand-sculpt emits
     * {@code backSpike2}, and a grader that only accepted one of those would report a feature
     * missing purely because of who built it — which would make the part-kit adoption number lie in
     * the flattering direction.
     */
    public static boolean matches(String bone, String feature) {
        if (bone == null || feature == null || feature.isEmpty()) {
            return false;
        }
        String b = bone.toLowerCase(Locale.ROOT);
        if (b.contains(feature)) {
            return true;
        }
        // A couple of synonyms that are the same feature under a different word, kept short: a long
        // synonym table is a way of quietly grading the same thing twice.
        return switch (feature) {
            case "spike" -> b.contains("spine") || b.contains("thorn") || b.contains("barb");
            case "claw" -> b.contains("talon") || b.contains("nail");
            case "tooth" -> b.contains("teeth") || b.contains("fang") || b.contains("tusk");
            case "hair" -> b.contains("mane") || b.contains("fur");
            case "snout" -> b.contains("muzzle") || b.contains("nose") || b.contains("beak");
            case "shell" -> b.contains("carapace");
            case "horn" -> b.contains("antler");
            // round 2's words: "hooves" does not contain "hoof".
            case "hoof" -> b.contains("hooves");
            case "tentacle" -> b.contains("tendril");
            default -> false;
        };
    }
}
