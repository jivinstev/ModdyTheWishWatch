package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.runtime.CreatureSculpt;

/**
 * <b>Does the invisible carrier stand where the creature is? </b> — the instrument, pure and
 * Minecraft-free apart from JOML, so every committed creature is measured in a unit test.
 *
 * <p>The atom rig draws a creature as item displays that follow an INVISIBLE vanilla mob. Hitting,
 * interacting, the glow outline, the name plate and collision all answer for that mob's box, not
 * for what the child sees. Nothing measured whether the two agree, and a video showed
 * they did not: a cow-shaped outline at the tail end of a long whale.
 *
 * <h2>What is measured, and in which frame</h2>
 *
 * <p>The rig is measured exactly as it is DRAWN: every atom's four-part display transform
 * ({@link RigTransform#placeAtom}) applied to the unit cube's corners, i.e.
 * {@code world = T + L·(S ⊙ (R·m))} with {@code m ∈ [-½,½]³}. That is the only honest source: a
 * box read off the sculpt's {@code from/to} ignores rotations and ancestor chains, and a size read
 * off {@code CreatureAnatomy.widthBlocks} is twice the reach from the ORIGIN — which, for a sculpt
 * whose origin is at its tail, is twice its length.
 *
 * <p>Coordinates are relative to the carrier's feet, in blocks, at a cardinal yaw. The carrier's
 * box is square and centred on its feet, so a quarter or half turn only swaps or negates the
 * rig's horizontal axes and leaves every number here unchanged. (At 45° the rig's box grows and
 * the carrier's does not turn at all: a property of a vanilla hitbox, not of this choice.)
 *
 * <h2>Three numbers, because "fit" has three failures</h2>
 *
 * <ul>
 *   <li><b>{@code hit}</b> — of the volume the child SEES, the fraction inside the hitbox. This is
 *       "can I hit it where I see it". Measured on the real rotated boxes by
 *       sampling, not on the bounding box, because an L-shaped creature's bounding box is mostly
 *       air.</li>
 *   <li><b>{@code fill}</b> — of the hitbox, the fraction holding creature. Low fill is an
 *       invisible wall you bump into and a sword swing that lands on air.</li>
 *   <li><b>{@code iou}</b> — the two together as one ranking number (volume IoU), plus the
 *       bounding-box IoU the issue asked for, which is cheaper and is what spawn optimises.</li>
 * </ul>
 */
public final class RigFit {

    private RigFit() {
    }

    /** An axis-aligned box in blocks, relative to the carrier's feet. */
    public record Box(double minX, double minY, double minZ,
                      double maxX, double maxY, double maxZ) {

        public double sizeX() {
            return Math.max(0, maxX - minX);
        }

        public double sizeY() {
            return Math.max(0, maxY - minY);
        }

        public double sizeZ() {
            return Math.max(0, maxZ - minZ);
        }

        public double volume() {
            return sizeX() * sizeY() * sizeZ();
        }

        public double centreX() {
            return (minX + maxX) / 2;
        }

        public double centreZ() {
            return (minZ + maxZ) / 2;
        }

        public boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        /** The overlap, or an empty box. */
        public Box intersect(Box o) {
            return new Box(Math.max(minX, o.minX), Math.max(minY, o.minY),
                    Math.max(minZ, o.minZ), Math.min(maxX, o.maxX), Math.min(maxY, o.maxY),
                    Math.min(maxZ, o.maxZ));
        }

        public double iou(Box o) {
            double i = intersect(o).volume();
            double u = volume() + o.volume() - i;
            return u <= 0 ? 0 : i / u;
        }

        /** A vanilla hitbox: {@code width × height × width}, feet at y=0, centred on the feet. */
        public static Box carrier(double width, double height) {
            return new Box(-width / 2, 0, -width / 2, width / 2, height, width / 2);
        }
    }

    /** One atom as drawn: its transform, kept so a point can be tested against the TRUE box. */
    public record Atom(org.joml.Vector3f t, org.joml.Quaternionf l, org.joml.Vector3f s,
                       org.joml.Quaternionf r, Box bounds) {

        /** Is this world point inside the (possibly rotated) box? */
        public boolean contains(double x, double y, double z) {
            if (!bounds.contains(x, y, z)) {
                return false;
            }
            org.joml.Vector3f p = new org.joml.Vector3f((float) x, (float) y, (float) z).sub(t);
            p.rotate(new org.joml.Quaternionf(l).conjugate());
            if (Math.abs(s.x) < 1e-6f || Math.abs(s.y) < 1e-6f || Math.abs(s.z) < 1e-6f) {
                return false;
            }
            p.div(s);
            p.rotate(new org.joml.Quaternionf(r).conjugate());
            float e = 0.5f + 1e-4f;
            return Math.abs(p.x) <= e && Math.abs(p.y) <= e && Math.abs(p.z) <= e;
        }

        /** Distance along a ray to where it enters the atom's TRUE (rotated) box, or -1. */
        public double enter(double[] o, double[] d) {
            org.joml.Quaternionf li = new org.joml.Quaternionf(l).conjugate();
            org.joml.Quaternionf ri = new org.joml.Quaternionf(r).conjugate();
            if (Math.abs(s.x) < 1e-6f || Math.abs(s.y) < 1e-6f || Math.abs(s.z) < 1e-6f) {
                return -1;
            }
            org.joml.Vector3f po = new org.joml.Vector3f((float) o[0], (float) o[1], (float) o[2])
                    .sub(t).rotate(li).div(s).rotate(ri);
            org.joml.Vector3f pd = new org.joml.Vector3f((float) d[0], (float) d[1], (float) d[2])
                    .rotate(li).div(s).rotate(ri);
            return slab(new double[]{po.x, po.y, po.z}, new double[]{pd.x, pd.y, pd.z},
                    -0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        }
    }

    /** Every atom of a plan, placed at rest, in blocks relative to the carrier's feet. */
    public static List<Atom> atoms(List<RigPlan.Group> groups, double scale) {
        List<Atom> out = new ArrayList<>(groups.size());
        for (RigPlan.Group g : groups) {
            if (g.boxCount() != 1) {
                continue;
            }
            // AS AUTHORED: an earlier issue's separation is for the depth buffer, not for fit or for hits.
            RigTransform.Placed p = RigTransform.placeAtomAsAuthored(g, scale);
            double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int c = 0; c < 8; c++) {
                org.joml.Vector3f m = new org.joml.Vector3f(
                        (c & 1) == 0 ? -0.5f : 0.5f,
                        (c & 2) == 0 ? -0.5f : 0.5f,
                        (c & 4) == 0 ? -0.5f : 0.5f);
                m.rotate(p.rightRotation()).mul(p.scale()).rotate(p.leftRotation())
                        .add(p.translation());
                lo[0] = Math.min(lo[0], m.x);
                lo[1] = Math.min(lo[1], m.y);
                lo[2] = Math.min(lo[2], m.z);
                hi[0] = Math.max(hi[0], m.x);
                hi[1] = Math.max(hi[1], m.y);
                hi[2] = Math.max(hi[2], m.z);
            }
            out.add(new Atom(new org.joml.Vector3f(p.translation()),
                    new org.joml.Quaternionf(p.leftRotation()), new org.joml.Vector3f(p.scale()),
                    new org.joml.Quaternionf(p.rightRotation()),
                    new Box(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2])));
        }
        return out;
    }


    /** Ray against an axis-aligned box: entry distance, or -1 when it misses. */
    static double slab(double[] o, double[] d, double x0, double y0, double z0,
                       double x1, double y1, double z1) {
        double[] lo = {x0, y0, z0};
        double[] hi = {x1, y1, z1};
        double tMin = 0;
        double tMax = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (o[i] < lo[i] || o[i] > hi[i]) {
                    return -1;
                }
                continue;
            }
            double a = (lo[i] - o[i]) / d[i];
            double b = (hi[i] - o[i]) / d[i];
            tMin = Math.max(tMin, Math.min(a, b));
            tMax = Math.min(tMax, Math.max(a, b));
            if (tMin > tMax) {
                return -1;
            }
        }
        return tMin;
    }

    /**
     * <b>"Can I hit it where I see it", asked the way Minecraft asks it</b>: parallel rays aimed at
     * the creature from eight directions, level and from 30° above (a child looks down at most
     * creatures). A ray the child would aim — one that meets a drawn box — is a HIT when it also
     * crosses the carrier's box, because vanilla picks an entity by its box and nothing else.
     *
     * @return {@code {hit, phantom}}: of the rays that meet the creature, the fraction that meet
     *     the hitbox; of the rays that meet the hitbox, the fraction that meet no creature — a click
     *     on thin air that lands anyway
     */
    public static double[] rays(List<Atom> atoms, Box carrier) {
        Box rig = bounds(atoms);
        if (rig == null) {
            return new double[]{0, 0};
        }
        double cx = rig.centreX();
        double cy = (rig.minY() + rig.maxY()) / 2;
        double cz = rig.centreZ();
        double radius = 0.5 * Math.sqrt(sq(rig.sizeX()) + sq(rig.sizeY()) + sq(rig.sizeZ()));
        Box both = new Box(Math.min(rig.minX(), carrier.minX()), Math.min(rig.minY(),
                carrier.minY()), Math.min(rig.minZ(), carrier.minZ()), Math.max(rig.maxX(),
                carrier.maxX()), Math.max(rig.maxY(), carrier.maxY()), Math.max(rig.maxZ(),
                carrier.maxZ()));
        radius = Math.max(radius, 0.5 * Math.sqrt(sq(both.sizeX()) + sq(both.sizeY())
                + sq(both.sizeZ())));
        int n = 32;
        long seen = 0;
        long hit = 0;
        long boxed = 0;
        long phantom = 0;
        for (int yawStep = 0; yawStep < 8; yawStep++) {
            for (double pitch : new double[]{0, Math.toRadians(30)}) {
                double yaw = Math.PI / 4 * yawStep + Math.PI / 16;
                double[] d = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch),
                        Math.cos(yaw) * Math.cos(pitch)};
                // two axes across the view
                double[] u = {Math.cos(yaw), 0, Math.sin(yaw)};
                double[] v = {d[1] * u[2] - d[2] * u[1], d[2] * u[0] - d[0] * u[2],
                        d[0] * u[1] - d[1] * u[0]};
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        double a = radius * (2.0 * (i + 0.5) / n - 1);
                        double b = radius * (2.0 * (j + 0.5) / n - 1);
                        double[] o = {cx + a * u[0] + b * v[0] - d[0] * 2 * radius,
                                cy + a * u[1] + b * v[1] - d[1] * 2 * radius,
                                cz + a * u[2] + b * v[2] - d[2] * 2 * radius};
                        boolean onRig = false;
                        for (Atom at : atoms) {
                            if (at.enter(o, d) >= 0) {
                                onRig = true;
                                break;
                            }
                        }
                        boolean onBox = slab(o, d, carrier.minX(), carrier.minY(),
                                carrier.minZ(), carrier.maxX(), carrier.maxY(),
                                carrier.maxZ()) >= 0;
                        if (onRig) {
                            seen++;
                            if (onBox) {
                                hit++;
                            }
                        }
                        if (onBox) {
                            boxed++;
                            if (!onRig) {
                                phantom++;
                            }
                        }
                    }
                }
            }
        }
        return new double[]{seen == 0 ? 0 : (double) hit / seen,
                boxed == 0 ? 0 : (double) phantom / boxed};
    }

    private static double sq(double x) {
        return x * x;
    }

    /** The box around every atom, or null when there are none. */
    public static Box bounds(List<Atom> atoms) {
        if (atoms.isEmpty()) {
            return null;
        }
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (Atom a : atoms) {
            Box b = a.bounds();
            x0 = Math.min(x0, b.minX());
            y0 = Math.min(y0, b.minY());
            z0 = Math.min(z0, b.minZ());
            x1 = Math.max(x1, b.maxX());
            y1 = Math.max(y1, b.maxY());
            z1 = Math.max(z1, b.maxZ());
        }
        return new Box(x0, y0, z0, x1, y1, z1);
    }

    /**
     * <b>Where a rider sits: the top of the drawn creature over the carrier's middle</b>
     * (2026-10-11). A rider is seated over the middle of the carrier's box, and the rig is centred
     * on the creature's mass, so the right seat is the highest point of the creature in a small
     * column there — its back, not the top of a raised head beside it. In blocks over the carrier's
     * feet; {@code NaN} when no part of the creature is under the middle at all.
     *
     * @param radius half the column's width, in blocks
     */
    public static double backTop(List<Atom> atoms, double radius) {
        Box all = bounds(atoms);
        if (all == null) {
            return Double.NaN;
        }
        double step = 1.0 / 64;
        double[][] column = {{0, 0}, {radius, 0}, {-radius, 0}, {0, radius}, {0, -radius}};
        double top = Double.NaN;
        for (double[] c : column) {
            for (double y = all.maxY(); y >= all.minY(); y -= step) {
                if (Double.isFinite(top) && y <= top) {
                    break;
                }
                boolean in = false;
                for (Atom a : atoms) {
                    if (a.contains(c[0], y, c[1])) {
                        in = true;
                        break;
                    }
                }
                if (in) {
                    top = y;
                    break;
                }
            }
        }
        return top;
    }

    /**
     * <b>How high over the carrier's feet a rig's head is </b> -- where the name plate floats,
     * before vanilla's lift.
     *
     * <p>Vanilla floats a name over the top of the entity's box. For a rig that entity is the
     * CARRIER, and (the carrier is chosen by fit) and (a tighter carrier plus hit
     * proxies) the carrier's box can be SHORTER than the creature as drawn: the first rig-films run
     * found Spikor's plate drawn inside his crown, 4.12 blocks up a creature drawn ~4.7 tall. So the
     * plate goes over whichever is higher, the carrier's box or the rig's own drawn top. Anything
     * read off the carrier is a fact about the carrier.
     *
     * @param carrierHeight the carrier's box height, in blocks
     * @param drawnTop      the top of the rig's resting box ({@link #bounds}), in blocks over the
     *                      carrier's feet; 0 for a rig with no atoms
     */
    public static double headTop(double carrierHeight, double drawnTop) {
        return Math.max(carrierHeight, drawnTop);
    }

    /** The rig's resting box, from parts ALREADY in rig space (mirrored, and centred if at all). */
    public static Box restBounds(List<CreatureSculpt.Part> rigSpaceParts, double scale) {
        return bounds(atoms(RigPlan.atoms(rigSpaceParts), scale));
    }

    // ===================== (a) centring =====================

    /**
     * <b>Parts in rig space: mirrored into world space, then slid so the creature's MASS centre is
     * over the carrier's feet.</b> The one conversion every rig path goes through (spawn, adopt,
     * every driver), so the displays, the animation pivots and the carrier can never disagree about
     * where the middle is.
     *
     * <p>A sculpt's origin is wherever the agent put it. Most bipeds are authored about their feet,
     * but a whale authored from its tail had its carrier — and so its hitbox, outline and turning
     * point — at the tail (an earlier issue's video, 2.06 blocks off in the synthetic re-creation). A turn
     * pivots about the carrier's feet, so this also makes the creature turn about its middle rather
     * than swing its body round one end.
     *
     * <p><b>Mass, not the bounding box's middle</b> — measured against it in
     * {@code RigFitCorpusTest} (V7 against V8: the same to two decimals on every aggregate), and
     * chosen for the case the aggregate hides: a real sculpt in the corpus has its mouth floating
     * two blocks in front of its body, and the box's middle is the empty air between them.
     *
     * <p>Only X and Z move. The feet stay at y=0: a creature authored hovering is meant to hover,
     * and the carrier stands on the ground whatever the rig does.
     */
    public static List<CreatureSculpt.Part> intoRigSpace(List<CreatureSculpt.Part> rawParts) {
        return centreFootprint(RigTransform.mirrorIntoWorldSpace(rawParts));
    }

    /** As {@link #intoRigSpace}, for parts that are already mirrored. */
    public static List<CreatureSculpt.Part> centreFootprint(List<CreatureSculpt.Part> mirrored) {
        float[] c = massCentreUnits(mirrored);
        if (c[0] == 0f && c[1] == 0f) {
            return mirrored;
        }
        return shift(mirrored, -c[0], -c[1]);
    }

    /**
     * The middle of the bounding box of parts, {@code {x, z}} in sculpt units at scale 1 — the
     * alternative {@link #intoRigSpace} was measured against and did not choose.
     */
    public static float[] footprintCentreUnits(List<CreatureSculpt.Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return new float[]{0f, 0f};
        }
        Box b = restBounds(parts, 1.0);
        if (b == null) {
            return new float[]{0f, 0f};
        }
        return new float[]{Math.round(b.centreX() * RigPlan.UNITS_PER_BLOCK * 16f) / 16f,
                Math.round(b.centreZ() * RigPlan.UNITS_PER_BLOCK * 16f) / 16f};
    }

    /**
     * How far forward (blocks, at {@code scale}) a RAW-sculpt landmark moves once the rig is
     * centred: {@code CreatureAnatomy} measures {@code forward} from the sculpt's origin and the
     * carrier now stands under the footprint's middle. Mirroring leaves Z alone, so the raw parts'
     * Z centre is the rig's.
     */
    public static double forwardShiftBlocks(List<CreatureSculpt.Part> rawParts, double scale) {
        float cz = massCentreUnits(RigTransform.mirrorIntoWorldSpace(rawParts))[1];
        // model faces -Z, so forward = -z/16; z' = z - cz  =>  forward' = forward + cz/16
        return cz / RigPlan.UNITS_PER_BLOCK * scale;
    }

    /**
     * The creature's MASS centre, {@code {x, z}} in sculpt units at scale 1: every drawn box
     * weighted by its volume. Unlike the bounding box's middle, a floating mouth two blocks in front
     * of a body (a real sculpt in the corpus) does not drag it into the empty air between them.
     */
    public static float[] massCentreUnits(List<CreatureSculpt.Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return new float[]{0f, 0f};
        }
        double w = 0;
        double x = 0;
        double z = 0;
        for (Atom a : atoms(RigPlan.atoms(parts), 1.0)) {
            double v = Math.abs((double) a.s().x * a.s().y * a.s().z);
            w += v;
            x += v * a.t().x;
            z += v * a.t().z;
        }
        if (w <= 0) {
            return new float[]{0f, 0f};
        }
        return new float[]{Math.round(x / w * RigPlan.UNITS_PER_BLOCK * 16f) / 16f,
                Math.round(z / w * RigPlan.UNITS_PER_BLOCK * 16f) / 16f};
    }

    /** Every part moved by {@code (dx, 0, dz)} sculpt units — boxes and pivots together. */
    public static List<CreatureSculpt.Part> shift(List<CreatureSculpt.Part> parts,
                                                  float dx, float dz) {
        List<CreatureSculpt.Part> out = new ArrayList<>(parts.size());
        for (CreatureSculpt.Part p : parts) {
            out.add(new CreatureSculpt.Part(p.name(), p.parent(), add(p.pivot(), dx, dz),
                    add(p.from(), dx, dz), add(p.to(), dx, dz), p.colour(), p.rotation(),
                    p.inflate(), p.pattern(), p.patternColour()));
        }
        return out;
    }

    private static float[] add(float[] v, float dx, float dz) {
        if (v == null || v.length < 3) {
            return v;
        }
        return new float[]{v[0] + dx, v[1], v[2] + dz};
    }

    // ===================== the measurement =====================

    /**
     * One creature against one carrier box.
     *
     * @param iou          bounding-box IoU, rig box against carrier box
     * @param volumeIou    IoU on the drawn boxes themselves (sampled)
     * @param hit          fraction of the drawn creature inside the hitbox — "can I hit it where I
     *                     see it"
     * @param fill         fraction of the hitbox holding creature
     * @param centreOffset horizontal distance, blocks, from the bounding box's middle to the carrier
     * @param massOffset   horizontal distance, blocks, from the creature's mass centre to the
     *                     carrier — what {@link #intoRigSpace} zeroes
     * @param widthErr     carrier width against the footprint's SHORT side, as a fraction
     * @param lengthErr    carrier width against the footprint's LONG side, as a fraction
     * @param heightErr    carrier height against the rig's top, as a fraction
     */
    public record Report(Box rig, Box carrier, double iou, double volumeIou, double hit,
                         double fill, double centreOffset, double massOffset, double widthErr,
                         double lengthErr, double heightErr) {
    }

    /**
     * Points inside the drawn creature on a cubic grid, at most {@code max} of them (evenly
     * thinned), each standing for {@code cell} cubic blocks — what a mass-weighted fit scores
     * against. Deterministic.
     *
     * @return {@code {points..., {cell}}}: the last element is a one-element array holding the
     *     volume one point stands for
     */
    public static List<double[]> samples(List<Atom> atoms, int max) {
        Box rig = bounds(atoms);
        List<double[]> pts = new ArrayList<>();
        if (rig == null) {
            pts.add(new double[]{0});
            return pts;
        }
        double step = Math.max(1e-3,
                Math.max(rig.sizeX(), Math.max(rig.sizeY(), rig.sizeZ())) / 24);
        for (double x = rig.minX() + step / 2; x < rig.maxX(); x += step) {
            for (double y = rig.minY() + step / 2; y < rig.maxY(); y += step) {
                for (double z = rig.minZ() + step / 2; z < rig.maxZ(); z += step) {
                    for (Atom a : atoms) {
                        if (a.contains(x, y, z)) {
                            pts.add(new double[]{x, y, z});
                            break;
                        }
                    }
                }
            }
        }
        double cell = step * step * step;
        if (pts.size() > max) {
            List<double[]> thin = new ArrayList<>(max);
            double k = (double) pts.size() / max;
            for (int i = 0; i < max; i++) {
                thin.add(pts.get((int) (i * k)));
            }
            cell *= k;
            pts = thin;
        }
        pts.add(new double[]{cell});
        return pts;
    }

    /** Samples along the rig's longest side; the grid is cubic. Deterministic. */
    public static final int SAMPLES = 40;

    public static Report measure(List<Atom> atoms, double carrierWidth, double carrierHeight) {
        Box rig = bounds(atoms);
        Box car = Box.carrier(carrierWidth, carrierHeight);
        if (rig == null) {
            return new Report(null, car, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        double step = Math.max(rig.sizeX(), Math.max(rig.sizeY(), rig.sizeZ())) / SAMPLES;
        step = Math.max(step, 1e-3);
        long inRig = 0;
        long inBoth = 0;
        for (double x = rig.minX() + step / 2; x < rig.maxX(); x += step) {
            for (double y = rig.minY() + step / 2; y < rig.maxY(); y += step) {
                for (double z = rig.minZ() + step / 2; z < rig.maxZ(); z += step) {
                    boolean seen = false;
                    for (Atom a : atoms) {
                        if (a.contains(x, y, z)) {
                            seen = true;
                            break;
                        }
                    }
                    if (seen) {
                        inRig++;
                        if (car.contains(x, y, z)) {
                            inBoth++;
                        }
                    }
                }
            }
        }
        double cell = step * step * step;
        double rigVol = inRig * cell;
        double both = inBoth * cell;
        double carVol = car.volume();
        double hit = inRig == 0 ? 0 : (double) inBoth / inRig;
        double fill = carVol <= 0 ? 0 : Math.min(1, both / carVol);
        double union = rigVol + carVol - both;
        double vIou = union <= 0 ? 0 : both / union;
        double shortSide = Math.min(rig.sizeX(), rig.sizeZ());
        double longSide = Math.max(rig.sizeX(), rig.sizeZ());
        double mw = 0;
        double mx = 0;
        double mz = 0;
        for (Atom a : atoms) {
            double v = Math.abs((double) a.s().x * a.s().y * a.s().z);
            mw += v;
            mx += v * a.t().x;
            mz += v * a.t().z;
        }
        return new Report(rig, car, rig.iou(car), vIou, hit, fill,
                Math.hypot(rig.centreX(), rig.centreZ()),
                mw <= 0 ? 0 : Math.hypot(mx / mw, mz / mw),
                frac(carrierWidth, shortSide), frac(carrierWidth, longSide),
                frac(carrierHeight, rig.maxY()));
    }

    private static double frac(double got, double want) {
        return want <= 0 ? 0 : (got - want) / want;
    }
}
