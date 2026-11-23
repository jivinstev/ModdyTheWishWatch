package com.moddingfromamod.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>Does a ready-made part come out WHOLE and EVEN?</b> (F1 grading: "in many cases the parts
 * were disjoint, in some cases they were asymmetrical").
 *
 * <p>{@link SculptConnectivity} judges a creature by each rotated box's axis-aligned bounds, which are larger than
 * the box, so it never refuses a good creature, and it also passed ram horns whose curl floated off the head and a
 * mane that sat on one side. This is stricter and narrower: it is run on the KITS (machine-made geometry with one
 * right answer), never on what an agent or a child designed, so it cannot make a creature boring. Two rules:
 * <ol>
 *   <li><b>whole</b>: every box of the kit, turned by its real rotation about its real pivot, touches (within
 *       {@link #TOUCH_PX}) the part it hangs on or another box of the kit that does;</li>
 *   <li><b>even</b>: the kit as a whole is its own mirror image across the anchor's middle (x), within
 *       {@link #MIRROR_PX} — a pair of horns, ears or wings is a pair, and a tail, a snout or a mane is centred.</li>
 * </ol>
 * Pure and Minecraft-free; {@code KitLintTest} runs it on every kit and style over several anchor shapes.
 */
public final class KitLint {

    /** A gap a viewer would not see. */
    public static final double TOUCH_PX = 0.35;

    /** How far a box may sit from its mirror twin. */
    public static final double MIRROR_PX = 0.6;

    private KitLint() {
    }

    /** What is wrong with these kit parts on {@code anchor}; empty when they are whole and even. */
    public static List<String> problems(List<CreatureSculpt.Part> kit, CreatureSculpt.Part anchor) {
        List<String> out = new ArrayList<>();
        Map<String, CreatureSculpt.Part> byName = new HashMap<>();
        byName.put(anchor.name().toLowerCase(Locale.ROOT), anchor);
        for (CreatureSculpt.Part p : kit) {
            byName.put(p.name().toLowerCase(Locale.ROOT), p);
        }
        // WHOLE: a flood from the anchor through boxes that really touch.
        List<CreatureSculpt.Part> reached = new ArrayList<>();
        List<CreatureSculpt.Part> frontier = new ArrayList<>(List.of(anchor));
        List<CreatureSculpt.Part> left = new ArrayList<>(kit);
        while (!frontier.isEmpty()) {
            CreatureSculpt.Part from = frontier.remove(frontier.size() - 1);
            reached.add(from);
            for (int i = left.size() - 1; i >= 0; i--) {
                CreatureSculpt.Part p = left.get(i);
                if (gap(p, from, byName) <= TOUCH_PX) {
                    frontier.add(p);
                    left.remove(i);
                }
            }
        }
        for (CreatureSculpt.Part p : left) {
            double best = Double.MAX_VALUE;
            for (CreatureSculpt.Part r : reached) {
                best = Math.min(best, gap(p, r, byName));
            }
            out.add(p.name() + " floats " + String.format(Locale.ROOT, "%.1f", best) + " px from the rest");
        }
        // EVEN: every box has a mirror twin across the anchor's middle.
        double mid = (anchor.from()[0] + anchor.to()[0]) / 2.0;
        List<double[][]> boxes = new ArrayList<>();
        for (CreatureSculpt.Part p : kit) {
            boxes.add(corners(p, byName));
        }
        for (int i = 0; i < kit.size(); i++) {
            double[][] mirrored = mirror(boxes.get(i), mid);
            double best = Double.MAX_VALUE;
            for (double[][] other : boxes) {
                best = Math.min(best, setDistance(mirrored, other));
            }
            if (best > MIRROR_PX) {
                out.add(kit.get(i).name() + " has no mirror twin (off by "
                        + String.format(Locale.ROOT, "%.1f", best) + " px)");
            }
        }
        return out;
    }

    /**
     * <b>Advice for a creature a person or an agent designed</b>: parts that the lenient one-piece check passes (it reads a
     * turned box by its axis-aligned bounds) but that really stand more than {@code minGapPx} from every other part
     * once turned — so they will look apart. Never a refusal and never about symmetry: a design may be lopsided on
     * purpose (an eye patch, one big claw), and the creativity is the point. At most {@code most} lines.
     */
    public static List<String> looksApart(List<CreatureSculpt.Part> parts, double minGapPx, int most) {
        List<String> out = new ArrayList<>();
        if (parts == null || parts.size() < 2) {
            return out;
        }
        Map<String, CreatureSculpt.Part> byName = new HashMap<>();
        for (CreatureSculpt.Part p : parts) {
            byName.put(p.name().toLowerCase(Locale.ROOT), p);
        }
        for (CreatureSculpt.Part p : parts) {
            if (!p.rotated() && (p.parent() == null || byName.get(p.parent().toLowerCase(Locale.ROOT)) == null
                    || !byName.get(p.parent().toLowerCase(Locale.ROOT)).rotated())) {
                continue;   // an unturned box is judged exactly by the one-piece check already
            }
            double best = Double.MAX_VALUE;
            String nearest = "";
            for (CreatureSculpt.Part q : parts) {
                if (q == p) {
                    continue;
                }
                double g = gap(p, q, byName);
                if (g < best) {
                    best = g;
                    nearest = q.name();
                }
                if (best <= minGapPx) {
                    break;
                }
            }
            if (best > minGapPx) {
                out.add(p.name() + " is " + String.format(Locale.ROOT, "%.1f", best) + " px from " + nearest
                        + " once it is turned");
                if (out.size() >= most) {
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ geometry

    /** The 8 corners of a box after its own rotation and every ancestor's, about each one's pivot. */
    static double[][] corners(CreatureSculpt.Part p, Map<String, CreatureSculpt.Part> byName) {
        float[] f = p.from(), t = p.to();
        double[][] pts = new double[8][];
        for (int i = 0; i < 8; i++) {
            pts[i] = new double[]{(i & 1) == 0 ? f[0] : t[0], (i & 2) == 0 ? f[1] : t[1], (i & 4) == 0 ? f[2] : t[2]};
        }
        for (double[] c : pts) {
            toWorld(c, p, byName);
        }
        return pts;
    }

    private static void toWorld(double[] c, CreatureSculpt.Part p, Map<String, CreatureSculpt.Part> byName) {
        CreatureSculpt.Part bone = p;
        for (int hop = 0; bone != null && hop < 16; hop++) {
            if (bone.rotated()) {
                rotate(c, bone.pivot() == null ? new float[]{0, 0, 0} : bone.pivot(), bone.rotation(), false);
            }
            bone = bone.parent() == null ? null : byName.get(bone.parent().toLowerCase(Locale.ROOT));
        }
    }

    /** A world point in {@code p}'s own unrotated box frame: every rotation undone, root first. */
    private static double[] toLocal(double[] w, CreatureSculpt.Part p, Map<String, CreatureSculpt.Part> byName) {
        List<CreatureSculpt.Part> chain = new ArrayList<>();
        CreatureSculpt.Part bone = p;
        for (int hop = 0; bone != null && hop < 16; hop++) {
            chain.add(bone);
            bone = bone.parent() == null ? null : byName.get(bone.parent().toLowerCase(Locale.ROOT));
        }
        double[] c = w.clone();
        for (int i = chain.size() - 1; i >= 0; i--) {
            CreatureSculpt.Part b = chain.get(i);
            if (b.rotated()) {
                rotate(c, b.pivot() == null ? new float[]{0, 0, 0} : b.pivot(), b.rotation(), true);
            }
        }
        return c;
    }

    /**
     * X then Y then Z about the pivot, <b>as the game draws it</b>; {@code inverse} undoes it (Z, Y, X, negated).
     *
     * <p>⚠ Y and Z turn the OTHER way from a right-handed rotation in sculpt space: the rig mirrors the geometry
     * through x and applies the raw angles ({@code RigTransform.mirrorX}), and conjugating by that mirror flips Y
     * and Z and leaves X. The first version of this used the textbook signs (as {@code SculptConnectivity} does), so
     * every part it called whole was whole in a mirror the game does not draw: the floppy ears it passed stood UP
     * and OUT in the film (F1 regrade, 2026-11-09). The pre-F1 horns and wings were authored against the game.
     */
    private static void rotate(double[] c, float[] pv, float[] deg, boolean inverse) {
        double s = inverse ? -1 : 1;
        double rx = Math.toRadians(deg[0]) * s, ry = -Math.toRadians(deg[1]) * s, rz = -Math.toRadians(deg[2]) * s;
        double x = c[0] - pv[0], y = c[1] - pv[1], z = c[2] - pv[2];
        if (!inverse) {
            double y1 = y * Math.cos(rx) - z * Math.sin(rx), z1 = y * Math.sin(rx) + z * Math.cos(rx);
            double x2 = x * Math.cos(ry) + z1 * Math.sin(ry), z2 = -x * Math.sin(ry) + z1 * Math.cos(ry);
            double x3 = x2 * Math.cos(rz) - y1 * Math.sin(rz), y3 = x2 * Math.sin(rz) + y1 * Math.cos(rz);
            c[0] = x3 + pv[0];
            c[1] = y3 + pv[1];
            c[2] = z2 + pv[2];
        } else {
            double x1 = x * Math.cos(rz) - y * Math.sin(rz), y1 = x * Math.sin(rz) + y * Math.cos(rz);
            double x2 = x1 * Math.cos(ry) + z * Math.sin(ry), z2 = -x1 * Math.sin(ry) + z * Math.cos(ry);
            double y3 = y1 * Math.cos(rx) - z2 * Math.sin(rx), z3 = y1 * Math.sin(rx) + z2 * Math.cos(rx);
            c[0] = x2 + pv[0];
            c[1] = y3 + pv[1];
            c[2] = z3 + pv[2];
        }
    }

    /** The smallest distance between two turned boxes: A's surface and inside, sampled, measured to B exactly. */
    static double gap(CreatureSculpt.Part a, CreatureSculpt.Part b, Map<String, CreatureSculpt.Part> byName) {
        return Math.min(oneWay(a, b, byName), oneWay(b, a, byName));
    }

    private static double oneWay(CreatureSculpt.Part a, CreatureSculpt.Part b, Map<String, CreatureSculpt.Part> byName) {
        float[] f = a.from(), t = a.to();
        int n = 6;
        double best = Double.MAX_VALUE;
        float[] bf = b.from(), bt = b.to();
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= n; j++) {
                for (int k = 0; k <= n; k++) {
                    double[] pt = {f[0] + (t[0] - f[0]) * i / n, f[1] + (t[1] - f[1]) * j / n, f[2] + (t[2] - f[2]) * k / n};
                    toWorld(pt, a, byName);
                    double[] l = toLocal(pt, b, byName);
                    double s = 0;
                    for (int ax = 0; ax < 3; ax++) {
                        double lo = Math.min(bf[ax], bt[ax]), hi = Math.max(bf[ax], bt[ax]);
                        double d = Math.max(0, Math.max(lo - l[ax], l[ax] - hi));
                        s += d * d;
                    }
                    best = Math.min(best, Math.sqrt(s));
                    if (best == 0) {
                        return 0;
                    }
                }
            }
        }
        return best;
    }

    private static double[][] mirror(double[][] box, double mid) {
        double[][] m = new double[box.length][];
        for (int i = 0; i < box.length; i++) {
            m[i] = new double[]{2 * mid - box[i][0], box[i][1], box[i][2]};
        }
        return m;
    }

    /** How far apart two corner sets are: the worst corner's distance to its nearest partner. */
    private static double setDistance(double[][] a, double[][] b) {
        double worst = 0;
        for (double[] p : a) {
            double best = Double.MAX_VALUE;
            for (double[] q : b) {
                double dx = p[0] - q[0], dy = p[1] - q[1], dz = p[2] - q[2];
                best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            worst = Math.max(worst, best);
        }
        return worst;
    }
}
