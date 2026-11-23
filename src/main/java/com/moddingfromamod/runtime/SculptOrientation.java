package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>Turn a creature the right way round.</b>
 *
 * <h2>The bug</h2>
 *
 * <p>A sculpted creature's <b>front is model −Z</b> — verified on screen, not derived: a probe
 * creature with a red slab at +Z and a blue slab at −Z, given body-yaw 0 and photographed from
 * directly in front of where it was facing, shows <b>blue</b>
 * ({@code FacingProbeClientTest}).
 *
 * <p>The agent sometimes builds the opposite way, and a creature built face-at-+Z renders walking
 * tail-first, with anything aimed "forward" — a fire breath, a beam — coming out behind it. A live
 * agent hit this and spent roughly four minutes summoning marker entities to work the convention out
 * empirically, which is the single largest slice of wish latency measured so far and produces
 * nothing the child sees.
 *
 * <p><b>How often, measured — and the wrong number that was believed first.</b> This class was
 * written on the evidence of ten consecutive sculpts (two arms of an A/B over five creatures), in
 * <i>all</i> of which the snout sat at positive z, and it said so: "10 of 10". Auditing the full
 * recorded corpus instead — 113 sculpts with any facing cue — gives the <b>opposite</b> majority:
 * <b>23 built face-at-+Z, 90 face-at-−Z</b>. Ten consecutive runs of one A/B are ten samples of one
 * prompt on one afternoon, not a convention. The lesson is not that the mirror is wrong (it fires on
 * the evidence of each individual creature, so it was always right per-creature) but that any claim
 * of the form "the agent always does X" needs the corpus behind it, and is what it costs to
 * hard-code the majority guess instead: kits assumed +Z-front and so landed a tail on the belly of
 * 90 of those 113 creatures.
 *
 * <h2>Why this is a flip and not a lesson</h2>
 *
 * <p>The obvious alternative is to tell the agent "build faces at −Z" in {@code lessons/creature.md}.
 * That is a rule it must remember on every sculpt forever, it does nothing for the creatures already
 * built, and this project has measured what happens to rules: they are obeyed precisely, which is
 * also how a wrong one does damage. A creature that cannot come out backwards needs no rule.
 *
 * <p>So the model is mirrored at assembly time when its own anatomy says it is facing the wrong way.
 * The agent keeps building the way it naturally does — consistently or not — and the
 * renderer gets what it expects.
 *
 * <h2>Mirroring safely</h2>
 *
 * <p>Reflecting z inverts handedness, so rotations have to come with it: a tilt about <b>X</b> or
 * <b>Y</b> negates, a tilt about <b>Z</b> does not. Getting that wrong would leave every spike
 * raked the wrong way, which is exactly the class of defect a human spotted by eye earlier today.
 *
 * <p>Textures are unaffected: a sculpted part is a single flat colour or a pattern drawn across its
 * whole UV net, so swapping which face is north changes nothing on screen. This would <b>not</b> be
 * safe for an imported model with per-face art, which is why it keys on {@code sculpt} anatomy.
 *
 * <p>Pure and Minecraft-free.
 */
public final class SculptOrientation {

    private SculptOrientation() {
    }

    /** Parts that are unambiguously at the front of an animal. */
    private static final Set<String> FRONT = Set.of(
            "snout", "nose", "mouth", "jaw", "muzzle", "beak", "eye", "tusk", "chin",
            "whisker", "nostril", "tooth", "teeth", "fang", "bill",
            // the face words agents actually name parts with. Without them a pineapple
            // whose face was `sunglassL` + `smile`, and an astronaut whose only face cue was a
            // `visor` beside a `backpack` sent on the front, came out facing AWAY from the child —
            // 3 of 112 first sculpts on the 2026-10-27 panels, each judged worse for "no face".
            "smile", "grin", "visor", "glass", "goggle", "lens", "monocle", "pupil", "brow",
            "cheek", "face", "beard", "moustache", "mustache");

    /** Parts that are unambiguously at the back. */
    private static final Set<String> BACK = Set.of(
            "tail", "shell", "back", "rump", "carapace");

    /**
     * Which way this creature is built, as a sign: <b>+1</b> when its face is at positive Z,
     * <b>-1</b> when at negative Z, and <b>0</b> when its anatomy says nothing either way.
     *
     * <p>Exposed because the KITS need it. They used to place a tail or a shell at the
     * anchor's minimum Z, taking the +Z-front convention for granted — and the agent is not
     * consistent about it. Across the 113 recorded sculpts that carry a facing cue, 23 are built
     * facing +Z and <b>90 facing -Z</b> — so a "back" kit landed on the front, and the tail came out
     * of the belly, on four creatures in five.
     */
    public static int frontSign(List<CreatureSculpt.Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return 0;
        }
        double frontZ = 0;
        int frontN = 0;
        double backZ = 0;
        int backN = 0;
        for (CreatureSculpt.Part p : parts) {
            String n = p.name().toLowerCase(Locale.ROOT);
            double z = (p.from()[2] + p.to()[2]) / 2.0;
            if (matches(n, FRONT)) {
                frontZ += z;
                frontN++;
            } else if (matches(n, BACK)) {
                backZ += z;
                backN++;
            }
        }
        if (frontN > 0 && backN > 0) {
            return frontZ / frontN > backZ / backN ? 1 : -1;
        }
        if (frontN > 0) {
            return frontZ / frontN > 0 ? 1 : -1;
        }
        if (backN > 0) {
            return backZ / backN > 0 ? -1 : 1;
        }
        return 0;   // no evidence: leave whatever convention the caller already had
    }

    /**
     * Does this creature's own anatomy say it was built facing the wrong way?
     *
     * <p>Answers <b>false</b> whenever there is no evidence either way — a creature with no named
     * face and no named tail is left exactly as it was. Silently mirroring something on a guess is
     * worse than leaving it.
     */
    public static boolean facesBackwards(List<CreatureSculpt.Part> parts) {
        // The renderer treats model -Z as the creature's front, so a creature built face-at-+Z is
        // the one that needs mirroring.
        return frontSign(parts) > 0;
    }

    /** Is this a part only a face has (an eye, a nose, fangs, a smile)? */
    public static boolean isFaceWord(String bone) {
        return bone != null && matches(bone.toLowerCase(Locale.ROOT), FRONT);
    }

    private static boolean matches(String bone, Set<String> words) {
        for (String w : words) {
            if (bone.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The creature, mirrored front-to-back if its anatomy says it needs it.
     *
     * <p>Returns the same list when no change is needed, so this is safe to call unconditionally.
     */
    public static List<CreatureSculpt.Part> faceForward(List<CreatureSculpt.Part> parts) {
        if (!facesBackwards(parts)) {
            return parts;
        }
        List<CreatureSculpt.Part> out = new ArrayList<>(parts.size());
        for (CreatureSculpt.Part p : parts) {
            out.add(mirrorZ(p));
        }
        return out;
    }

    /** One part reflected through the XY plane. */
    static CreatureSculpt.Part mirrorZ(CreatureSculpt.Part p) {
        float[] from = {p.from()[0], p.from()[1], -p.to()[2]};
        float[] to = {p.to()[0], p.to()[1], -p.from()[2]};
        float[] pivot = {p.pivot()[0], p.pivot()[1], -p.pivot()[2]};
        float[] rot = p.rotation() == null ? CreatureSculpt.NO_ROTATION
                // Reflecting z flips handedness: X and Y tilts reverse, a Z tilt does not.
                : new float[]{-p.rotation()[0], -p.rotation()[1], p.rotation()[2]};
        return new CreatureSculpt.Part(p.name(), p.parent(), pivot, from, to, p.colour(),
                rot, p.inflate(), p.pattern(), p.patternColour());
    }
}
