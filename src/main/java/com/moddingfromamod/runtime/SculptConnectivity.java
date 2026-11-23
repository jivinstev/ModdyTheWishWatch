package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>Is the creature ONE piece?</b>
 *
 * <p>{@link SculptCritique#floating} asked whether each part touches <i>some</i> other part, so two
 * islands that each hold together passed: a cat whose four legs touch each other in pairs, a head
 * and its ears hovering beside a body. It also tested the boxes as sent, while the model draws each
 * part as a bone inside its parent — a tilted tail carries its tip with it, and a box that touches
 * on paper can be drawn a block away. A child sees a creature in pieces either way.
 *
 * <p>This builds the part graph from the REST pose (each box's corners turned by its own rotation
 * and then by every ancestor's, the way the model nests them), splits it into connected
 * components, and calls the one with the most volume the creature. Everything else is an island,
 * reported with the nearest part of the creature, the gap in pixels and the one move that closes it.
 *
 * <p>Gaps of {@link #SNAP_PX} or less are closed here, deterministically, and said out loud: a
 * one-pixel slit is never what anybody drew, and fixing it costs the agent no turn. Anything wider
 * might be deliberate, so it goes back to the agent instead (the tool refuses it once).
 *
 * <p>Pure and Minecraft-free. Rotated boxes are tested by their axis-aligned bounds, which are a
 * little larger than the box, so the error runs toward calling a part connected — the direction
 * that never refuses a good creature.
 */
public final class SculptConnectivity {

    /** Boxes this close count as touching — the same tolerance the critique uses. */
    static final float TOUCH = 0.01f;

    /** A gap this narrow is closed for the agent rather than sent back. One pixel = 1/16 block. */
    public static final float SNAP_PX = 1.0f;

    /** The smallest shared face that counts as joined, and the overlap a suggested move aims for. */
    static final float CONTACT = 1.0f;
    static final float OVERLAP = 1.0f;
    /** Cubic pixels: a loose piece this small may rest on an edge (a brow, a tuft); see {@link #islands}. */
    static final double DETAIL = 8.0;

    private SculptConnectivity() {
    }

    /** One piece standing apart from the creature, and the move that would join it. */
    public record Island(List<String> parts, String nearest, float gapPx, float[] move, boolean aligned) {

        /** For an automatic close: just far enough to meet, never further. */
        float[] snap() {
            float[] m = move.clone();
            for (int ax = 0; ax < 3; ax++) {
                if (m[ax] != 0) {
                    m[ax] -= Math.signum(m[ax]) * OVERLAP;
                }
            }
            return m;
        }

        public String describe() {
            return String.join(", ", parts) + " — " + (gapPx < TOUCH ? "only touches " + nearest
                    + " at an edge or corner" : fmt(gapPx) + " px from " + nearest)
                    + "; move " + (parts.size() == 1 ? "it" : "them together") + " by ["
                    + fmt(move[0]) + ", " + fmt(move[1]) + ", " + fmt(move[2]) + "]";
        }
    }

    /** What the check found and what it already fixed. */
    public record Result(List<CreatureSculpt.Part> parts, List<Island> snapped,
                         List<Island> islands) {

        public boolean onePiece() {
            return islands.isEmpty();
        }

        /** A fingerprint of what is still apart, so "the same sculpt again" can be recognised. */
        public String fingerprint() {
            List<String> names = new ArrayList<>();
            for (Island i : islands) {
                names.addAll(i.parts());
            }
            names.sort(null);
            return String.join(",", names);
        }
    }

    /** Check the creature, closing the gaps that are too narrow to be meant. */
    public static Result check(List<CreatureSculpt.Part> parts) {
        if (parts == null || parts.size() < 2) {
            return new Result(parts == null ? List.of() : parts, List.of(), List.of());
        }
        List<CreatureSculpt.Part> now = new ArrayList<>(parts);
        List<Island> snapped = new ArrayList<>();
        // Each snap can only join, so this ends in at most parts.size() rounds.
        for (int round = 0; round < parts.size(); round++) {
            List<Island> apart = islands(now);
            final List<CreatureSculpt.Part> cur = now;
            Island close = apart.stream()
                    .filter(i -> i.aligned() && i.gapPx() <= SNAP_PX && translatable(cur, i.parts()))
                    .findFirst().orElse(null);
            if (close == null) {
                return new Result(now, snapped, apart);
            }
            now = shifted(now, close.parts(), close.snap());
            snapped.add(close);
        }
        return new Result(now, snapped, islands(now));
    }

    /** Every island apart from the main body, nearest first. Empty when the creature is one piece. */
    public static List<Island> islands(List<CreatureSculpt.Part> parts) {
        List<Island> out = new ArrayList<>();
        if (parts == null || parts.size() < 2) {
            return out;
        }
        int n = parts.size();
        float[][] box = new float[n][];
        Map<String, CreatureSculpt.Part> byName = byName(parts);
        for (int i = 0; i < n; i++) {
            box[i] = restBounds(parts.get(i), byName);
        }
        int[] comp = new int[n];
        java.util.Arrays.fill(comp, -1);
        int comps = 0;
        for (int i = 0; i < n; i++) {
            if (comp[i] >= 0) {
                continue;
            }
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            q.add(i);
            comp[i] = comps;
            while (!q.isEmpty()) {
                int a = q.poll();
                for (int b = 0; b < n; b++) {
                    if (comp[b] < 0 && joined(box[a], box[b])) {
                        comp[b] = comps;
                        q.add(b);
                    }
                }
            }
            comps++;
        }
        if (comps == 1) {
            return out;
        }
        double[] vol = new double[comps];
        for (int i = 0; i < n; i++) {
            vol[comp[i]] += volume(box[i]);
        }
        int main = 0;
        for (int c = 1; c < comps; c++) {
            if (vol[c] > vol[main]) {
                main = c;
            }
        }
        for (int c = 0; c < comps; c++) {
            if (c == main) {
                continue;
            }
            List<String> names = new ArrayList<>();
            float best = Float.MAX_VALUE;
            int bi = -1, bm = -1;
            for (int i = 0; i < n; i++) {
                if (comp[i] != c) {
                    continue;
                }
                names.add(parts.get(i).name());
                for (int m = 0; m < n; m++) {
                    if (comp[m] == main) {
                        float g = gap(box[i], box[m]);
                        if (g < best) {
                            best = g;
                            bi = i;
                            bm = m;
                        }
                    }
                }
            }
            // A DETAIL may rest on an edge; anything bigger must share a face. Requiring a face of
            // everything sent five archived creatures back for eyebrows on the corner of a face, a
            // mouth on the edge of a muzzle and a tail tuft, which read as attached at game scale,
            // while the case that mattered was a whole witch's hat on an ear's corner. The size is
            // the LOOSE piece's, never the part it touches (the ear is a 2×2×2 detail, the hat is not).
            double loose = 0;
            for (int i = 0; i < n; i++) {
                if (comp[i] == c) {
                    loose += volume(box[i]);
                }
            }
            if (best <= TOUCH && loose <= DETAIL) {
                continue;
            }
            out.add(new Island(names, parts.get(bm).name(), best, toward(box[bi], box[bm], OVERLAP),
                    aligned(box[bi], box[bm])));
        }
        out.sort(Comparator.comparingDouble(Island::gapPx));
        return out;
    }

    /** The rest-pose bounds: own rotation, then each ancestor's about its own pivot. */
    static float[] restBounds(CreatureSculpt.Part p, Map<String, CreatureSculpt.Part> byName) {
        float[] f = p.from(), t = p.to();
        double[][] pts = new double[8][];
        for (int i = 0; i < 8; i++) {
            pts[i] = new double[]{
                    (i & 1) == 0 ? Math.min(f[0], t[0]) : Math.max(f[0], t[0]),
                    (i & 2) == 0 ? Math.min(f[1], t[1]) : Math.max(f[1], t[1]),
                    (i & 4) == 0 ? Math.min(f[2], t[2]) : Math.max(f[2], t[2])};
        }
        CreatureSculpt.Part bone = p;
        for (int hop = 0; bone != null && hop < 16; hop++) {
            if (bone.rotated()) {
                float[] pv = bone.pivot() == null ? new float[]{0, 0, 0} : bone.pivot();
                for (double[] c : pts) {
                    rotate(c, pv, bone.rotation());
                }
            }
            bone = bone.parent() == null ? null
                    : byName.get(bone.parent().toLowerCase(Locale.ROOT));
            if (bone == p) {
                break;
            }
        }
        float[] out = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                      -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (double[] c : pts) {
            for (int a = 0; a < 3; a++) {
                out[a] = (float) Math.min(out[a], c[a]);
                out[a + 3] = (float) Math.max(out[a + 3], c[a]);
            }
        }
        return out;
    }

    /** X then Y then Z about the pivot — the order {@link SculptCritique#bounds} uses. */
    private static void rotate(double[] c, float[] pv, float[] deg) {
        double rx = Math.toRadians(deg[0]), ry = Math.toRadians(deg[1]), rz = Math.toRadians(deg[2]);
        double x = c[0] - pv[0], y = c[1] - pv[1], z = c[2] - pv[2];
        double y1 = y * Math.cos(rx) - z * Math.sin(rx);
        double z1 = y * Math.sin(rx) + z * Math.cos(rx);
        double x2 = x * Math.cos(ry) + z1 * Math.sin(ry);
        double z2 = -x * Math.sin(ry) + z1 * Math.cos(ry);
        double x3 = x2 * Math.cos(rz) - y1 * Math.sin(rz);
        double y3 = x2 * Math.sin(rz) + y1 * Math.cos(rz);
        c[0] = x3 + pv[0];
        c[1] = y3 + pv[1];
        c[2] = z2 + pv[2];
    }

    /** Straight-line distance between two boxes; 0 when they touch or overlap. */
    static float gap(float[] a, float[] b) {
        double s = 0;
        for (int ax = 0; ax < 3; ax++) {
            double d = Math.max(0, Math.max(b[ax] - a[ax + 3], a[ax] - b[ax + 3]));
            s += d * d;
        }
        return (float) Math.sqrt(s);
    }

    /**
     * <b>Joined means sharing a FACE</b>, at least {@link #CONTACT} by {@link #CONTACT}: the boxes overlap
     * (or meet) on every axis, and by a real amount on at least two of them. A box that meets another
     * only along an edge or at a corner is not joined (the witch cat): told to move its hat
     * onto the cat, the agent made exactly the move it was given, the brim's corner came to rest on
     * an ear's corner, the hat sat 8 px off to one side of the head, and the old "touching" test
     * called it one piece.
     */
    static boolean joined(float[] a, float[] b) {
        int faces = 0;
        for (int ax = 0; ax < 3; ax++) {
            float ov = Math.min(a[ax + 3], b[ax + 3]) - Math.max(a[ax], b[ax]);
            if (ov < -TOUCH) {
                return false;
            }
            if (ov >= CONTACT - TOUCH) {
                faces++;
            }
        }
        return faces >= 2;
    }

    /** Do the two boxes already line up face to face, so only a hairline along one axis parts them? */
    static boolean aligned(float[] a, float[] b) {
        int faces = 0;
        for (int ax = 0; ax < 3; ax++) {
            if (Math.min(a[ax + 3], b[ax + 3]) - Math.max(a[ax], b[ax]) >= CONTACT - TOUCH) {
                faces++;
            }
        }
        return faces >= 2;
    }

    /**
     * The move that makes box {@code a} share a face with box {@code b}, overlapping it by
     * {@code by} on every axis where it does not already: the agent is told to move into the part,
     * not onto its edge, because an edge is exactly where the witch cat's hat came to rest.
     */
    static float[] toward(float[] a, float[] b, float by) {
        float[] m = new float[3];
        for (int ax = 0; ax < 3; ax++) {
            float ov = Math.min(a[ax + 3], b[ax + 3]) - Math.max(a[ax], b[ax]);
            float need = Math.min(by, Math.min(a[ax + 3] - a[ax], b[ax + 3] - b[ax]));
            if (ov >= need - TOUCH) {
                continue;
            }
            // toward b's middle, just far enough
            boolean up = (a[ax] + a[ax + 3]) < (b[ax] + b[ax + 3]);
            m[ax] = up ? (b[ax] + need) - a[ax + 3] : (b[ax + 3] - need) - a[ax];
        }
        return m;
    }

    /** The island moved as a whole; a part's box is in model space, so each moves itself. */
    private static List<CreatureSculpt.Part> shifted(List<CreatureSculpt.Part> parts,
                                                     List<String> names, float[] d) {
        java.util.Set<String> island = lower(names);
        List<CreatureSculpt.Part> out = new ArrayList<>();
        for (CreatureSculpt.Part p : parts) {
            out.add(island.contains(p.name().toLowerCase(Locale.ROOT)) ? SculptBase.moved(p, d) : p);
        }
        return out;
    }

    /**
     * Can the island be moved by a plain translation? Not if a part hangs inside a TILTED bone that
     * stays put: the tilt turns the move, and the pixel it was meant to close stays open. Those are
     * left for the agent rather than half-fixed.
     */
    private static boolean translatable(List<CreatureSculpt.Part> parts, List<String> names) {
        java.util.Set<String> island = lower(names);
        Map<String, CreatureSculpt.Part> byName = byName(parts);
        for (String n : island) {
            CreatureSculpt.Part bone = byName.get(n);
            for (int hop = 0; bone != null && bone.parent() != null && hop < 16; hop++) {
                bone = byName.get(bone.parent().toLowerCase(Locale.ROOT));
                if (bone != null && bone.rotated()
                        && !island.contains(bone.name().toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static java.util.Set<String> lower(List<String> names) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String s : names) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static Map<String, CreatureSculpt.Part> byName(List<CreatureSculpt.Part> parts) {
        Map<String, CreatureSculpt.Part> m = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : parts) {
            m.putIfAbsent(p.name().toLowerCase(Locale.ROOT), p);
        }
        return m;
    }

    private static double volume(float[] b) {
        return (double) (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
    }

    private static String fmt(float v) {
        return Math.abs(v - Math.round(v)) < 0.05f ? Integer.toString(Math.round(v))
                : String.format(Locale.ROOT, "%.1f", v);
    }
}
