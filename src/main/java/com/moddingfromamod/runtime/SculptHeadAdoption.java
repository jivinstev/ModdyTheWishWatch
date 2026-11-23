package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>Hang everything that sits ON the head FROM the head.</b>
 *
 * <h2>The bug</h2>
 *
 * <p>Head-look is a bone transform, and bone transforms are inherited: the rig turns the head bone
 * and every descendant of it ({@code RigPose.compose}, {@code RigPlan.isHeadBone}). A part that sits
 * on the head but was sculpted as a ROOT — or hung from {@code body} — gets only the body's turn.
 * So a "copy of me" whose eyes and mouth were children of {@code head} and whose big hair box was
 * not turned its face to look at the child and left its hair where it was; at a large enough turn
 * the back of the hair box ended up in front of the face. Nothing throws: the creature is correct
 * standing still, which is every photograph a sculpt is judged by.
 *
 * <h2>Why this is an assembly rule and not only a lesson</h2>
 *
 * <p>The same reason {@link SculptOrientation} flips a backwards creature rather than asking the
 * agent to remember which way is front: the tool schema already says <i>"'parent' attaches a part
 * to another so it moves with it (hair -&gt; head)"</i>, and a rule the model must remember on every
 * sculpt forever is a rule it will eventually forget. A creature whose hair cannot come off its
 * head needs no rule. The lesson line exists as well, so the agent builds it right in the first
 * place and is TOLD when this had to step in.
 *
 * <h2>What counts as "on the head" — deliberately narrow</h2>
 *
 * <p>A part is adopted by the head bone {@code H} only when all of these hold:
 * <ol>
 *   <li>it is not already under a head bone, and it is not a limb or torso by name
 *       ({@link #NEVER}) — an arm raised to the face must keep swinging with the shoulder;</li>
 *   <li>its box TOUCHES {@code H}'s box (overlapping, or within {@link #TOUCH} units on every
 *       axis);</li>
 *   <li>and EITHER its name is a head feature ({@link #FEATURE}: hair, eyes, mouth, horns, a
 *       hat...) OR it sits on the top or back of the head: its centre is inside {@code H}'s
 *       footprint in X and Z and no lower than {@code H}'s centre.</li>
 * </ol>
 * The geometric half is what catches a box called {@code top} or {@code wig2}. It was tuned
 * against the committed sculpts rather than invented: across the 18 with a head bone, overlap
 * ALONE would have dragged a collar, a shell, a hood and two whole bodies into the head (they
 * all overlap it by 1–3 units at the neck), and the centre-height clause excludes every one of
 * them while keeping the hair.
 *
 * <p>And it never changes what the creature looks like at rest: adoption is skipped when the head
 * bone, or anything above it, carries a rest tilt, because a child inherits its parent's tilt and
 * re-parenting would then MOVE the part. A part's own children come with it unchanged.
 *
 * <p>Pure and Minecraft-free.
 */
public final class SculptHeadAdoption {

    private SculptHeadAdoption() {
    }

    /** How far apart (sixteenths of a block) two boxes may be and still "touch". */
    static final float TOUCH = 1f;

    /** Name tokens that are a head feature wherever they appear. Matched as a token PREFIX. */
    private static final List<String> FEATURE = List.of(
            "hair", "fringe", "bang", "quiff", "mohawk", "ponytail", "pigtail", "wig", "curl",
            "eye", "pupil", "iris", "brow", "lash", "mouth", "lip", "tooth", "teeth", "fang",
            "tusk", "tongue", "nose", "nostril", "snout", "muzzle", "beak", "jaw", "chin",
            "cheek", "ear", "horn", "antler", "antenna", "hat", "helmet", "crown", "beard",
            "mustache", "moustache", "whisker", "glasses", "shade", "goggle", "visor", "face",
            "forehead", "skull");

    /** Name tokens that are never adopted, however close they sit: they animate on their own. */
    private static final List<String> NEVER = List.of(
            "body", "torso", "chest", "belly", "arm", "hand", "leg", "foot", "feet", "wing",
            "tail", "shell", "collar", "hood", "cape", "neck", "shoulder");

    /** Same rule as {@code RigPlan.isHeadBone}; repeated here because runtime must not import vanilla. */
    static boolean isHeadBone(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("head") || lower.equals("neck") || lower.endsWith("_head");
    }

    /**
     * The creature with every on-the-head part hung from the head. Returns the SAME list when
     * nothing needed adopting, so it is safe to call unconditionally.
     */
    public static List<CreatureSculpt.Part> adopt(List<CreatureSculpt.Part> parts) {
        Map<String, String> moves = adoptions(parts);
        if (moves.isEmpty()) {
            return parts;
        }
        List<CreatureSculpt.Part> out = new ArrayList<>(parts.size());
        for (CreatureSculpt.Part p : parts) {
            String to = moves.get(p.name());
            out.add(to == null ? p : new CreatureSculpt.Part(p.name(), to, p.pivot(), p.from(),
                    p.to(), p.colour(), p.rotation(), p.inflate(), p.pattern(), p.patternColour()));
        }
        return out;
    }

    /**
     * Which parts {@link #adopt} would re-parent, and to which head bone, in part order. Exposed
     * so the sculpt tool can SAY what it did — a silent fix teaches nothing (docs/WISH_LINTERS P4).
     */
    public static Map<String, String> adoptions(List<CreatureSculpt.Part> parts) {
        Map<String, String> out = new LinkedHashMap<>();
        if (parts == null || parts.isEmpty()) {
            return out;
        }
        Map<String, CreatureSculpt.Part> byName = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : parts) {
            byName.putIfAbsent(p.name(), p);
        }
        List<CreatureSculpt.Part> heads = new ArrayList<>();
        for (CreatureSculpt.Part p : parts) {
            // `neck` is a head bone for FOLLOW, but hair does not hang from a neck; and a head
            // whose chain is tilted would move anything re-parented under it.
            if (isHeadBone(p.name()) && !p.name().equalsIgnoreCase("neck")
                    && !chainTilted(p, byName)) {
                heads.add(p);
            }
        }
        if (heads.isEmpty()) {
            return out;
        }
        for (CreatureSculpt.Part p : parts) {
            if (underHead(p, byName) || anyToken(p.name(), NEVER)) {
                continue;
            }
            CreatureSculpt.Part best = null;
            float bestOverlap = -Float.MAX_VALUE;
            for (CreatureSculpt.Part h : heads) {
                if (h == p || !sitsOn(p, h)) {
                    continue;
                }
                float ov = overlapVolume(p, h);
                if (best == null || ov > bestOverlap) {
                    best = h;
                    bestOverlap = ov;
                }
            }
            if (best != null) {
                out.put(p.name(), best.name());
            }
        }
        return out;
    }

    /** Does {@code p} sit on head {@code h} — touching it, and a feature or on its top/back? */
    static boolean sitsOn(CreatureSculpt.Part p, CreatureSculpt.Part h) {
        float[] pl = lo(p);
        float[] ph = hi(p);
        float[] hl = lo(h);
        float[] hh = hi(h);
        for (int i = 0; i < 3; i++) {
            float gap = Math.max(hl[i] - ph[i], pl[i] - hh[i]);
            if (gap > TOUCH) {
                return false;
            }
        }
        if (anyToken(p.name(), FEATURE)) {
            return true;
        }
        float cx = (pl[0] + ph[0]) / 2f;
        float cy = (pl[1] + ph[1]) / 2f;
        float cz = (pl[2] + ph[2]) / 2f;
        return cx >= hl[0] && cx <= hh[0] && cz >= hl[2] && cz <= hh[2]
                && cy >= (hl[1] + hh[1]) / 2f;
    }

    private static boolean underHead(CreatureSculpt.Part p, Map<String, CreatureSculpt.Part> byName) {
        Set<String> seen = new LinkedHashSet<>();
        CreatureSculpt.Part at = p;
        while (at != null && seen.add(at.name())) {
            if (isHeadBone(at.name())) {
                return true;
            }
            at = at.parent() == null ? null : byName.get(at.parent());
        }
        return false;
    }

    private static boolean chainTilted(CreatureSculpt.Part p, Map<String, CreatureSculpt.Part> byName) {
        Set<String> seen = new LinkedHashSet<>();
        CreatureSculpt.Part at = p;
        while (at != null && seen.add(at.name())) {
            if (at.rotated()) {
                return true;
            }
            at = at.parent() == null ? null : byName.get(at.parent());
        }
        return false;
    }

    /** Is any camelCase / snake_case / digit-separated token of {@code name} prefixed by a word? */
    static boolean anyToken(String name, List<String> words) {
        if (name == null) {
            return false;
        }
        String spaced = name.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z]+", " ").toLowerCase(Locale.ROOT).trim();
        if (spaced.isEmpty()) {
            return false;
        }
        for (String token : spaced.split(" ")) {
            for (String w : words) {
                if (token.startsWith(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static float overlapVolume(CreatureSculpt.Part a, CreatureSculpt.Part b) {
        float v = 1f;
        for (int i = 0; i < 3; i++) {
            v *= Math.min(hi(a)[i], hi(b)[i]) - Math.max(lo(a)[i], lo(b)[i]);
        }
        return v;
    }

    private static float[] lo(CreatureSculpt.Part p) {
        return new float[]{Math.min(p.from()[0], p.to()[0]), Math.min(p.from()[1], p.to()[1]),
                Math.min(p.from()[2], p.to()[2])};
    }

    private static float[] hi(CreatureSculpt.Part p) {
        return new float[]{Math.max(p.from()[0], p.to()[0]), Math.max(p.from()[1], p.to()[1]),
                Math.max(p.from()[2], p.to()[2])};
    }
}
