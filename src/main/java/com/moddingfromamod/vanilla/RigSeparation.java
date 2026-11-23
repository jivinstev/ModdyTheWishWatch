package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.runtime.CreatureSculpt;

/**
 * <b>Stop two atoms from drawing the same surface.</b>
 *
 * <p>An atom rig draws every box of a creature as its own {@code item_display}. When two boxes
 * present a face on the SAME plane, facing the SAME way — Spikor's mouth flush with the front of
 * his snout, a cat's pupil flush with its eye — the depth buffer cannot choose between them, and
 * which one wins is decided per pixel per frame. The owner filmed it on the live server: the dark
 * mouth slit on Spikor's snout appears in some frames and vanishes in others as the camera moves.
 *
 * <h2>Why this exists when {@code FightingFaces} already did</h2>
 *
 * <p>{@code runtime/FightingFaces} (2026-08-11) solved exactly this, inside
 * {@code BedrockGeometry.fromJson}, for a renderer that no longer exists. The atom rig never
 * parses through {@code BedrockGeometry}: it sizes every display from the raw sculpt
 * ({@link RigPlan#atomSizeUnits}), so for the whole life of the rig NO displayed creature got the
 * separation. And FightingFaces skipped every box under a rotated bone, which is every face part of
 * the corpus Spikor (his head is tilted −10°) — so it saw 0 of his 14 fights even when it ran.
 *
 * <h2>What this does differently, and why each difference</h2>
 *
 * <ol>
 *   <li><b>It runs in the planner</b> ({@link RigPlan#atoms}), so it decides what the rig DRAWS.
 *       Each group carries a {@link Ref} and {@link RigTransform#atomise} adds its growth to the
 *       display's scale — the same seam {@link RigPlan#PATTERN_LIFT_BLOCKS} already uses.</li>
 *   <li><b>Tilted boxes are compared in their own frame.</b> Two boxes whose whole rotation —
 *       every ancestor's plus their own — is the same rigid turn are coplanar in the world exactly
 *       when they are coplanar in that turn's frame. So boxes are bucketed by their composed
 *       rotation, and each bucket is compared axis-aligned after un-turning it. Spikor's mouth and
 *       snout both hang off his tilted head: one bucket, one comparison, one fix.</li>
 *   <li><b>It grows only along the axis that fights.</b> FightingFaces grew a box on every face,
 *       which is invisible at 1/320 of a block and very visible the moment the growth has to clear
 *       a pattern overlay (below): a 1-unit eye grown on every face by half a unit is a 2-unit eye.
 *       Grown only along its fighting axis, the eye is exactly as wide and tall as it was authored
 *       and stands proud of the head by a hair. The growth is still SYMMETRIC along that axis — the
 *       centre never moves — so FightingFaces' argument against translating (a boot proud on one
 *       side and recessed on the other) still holds.</li>
 *   <li><b>It is in BLOCKS, solved at the rig's own scale.</b> Depth-buffer resolution is a
 *       property of the camera, not of the creature, so a separation that rode the creature scale
 *       would float a big creature's details off its skin and leave a small one's in the noise.
 *       And the pattern lift is in blocks while the sculpt is in units, so whether a box authored
 *       half a unit proud of a patterned body lands EXACTLY on that body's overlay depends on the
 *       scale — measured: Spikor armB's shell rim does at scale 1.0 and not at his own 1.2. A
 *       scale-free answer cannot see that, so the growth is solved per scale, lazily, once per
 *       scale per plan ({@link Ref#blocks}); a rig's scale is fixed for its life.</li>
 *   <li><b>A patterned neighbour's overlay is cleared.</b> A patterned box is two displays, the
 *       second lifted {@link RigPlan#PATTERN_LIFT_BLOCKS} clear on every face and opaque wherever
 *       the pattern draws. A detail on that surface, grown by a hair, would sit UNDER that overlay:
 *       hidden wherever the pattern has ink, fighting nothing, gone. So a detail sharing a
 *       patterned box's surface stands a hair in front of the pattern — where the child's eye
 *       expects it, on the visible skin.</li>
 * </ol>
 *
 * <h2>Who gives way</h2>
 *
 * <p>Always the smaller box (by volume; ties by sculpt order, so the answer is deterministic), and
 * always forward, out of the surface it shares. So an eye on a head gives way to the head, a pupil
 * to the eye, a glint to the pupil — every conflict is resolved by the smaller part moving outward,
 * which is a partial order, so the passes converge (in practice by the second or third).
 *
 * <p>Pure and Minecraft-free (JOML only, as {@link RigPlan} is), so the whole corpus is checked in
 * a unit test ({@code RigSeparationTest}) against what {@link RigTransform#placeAtom} places.
 */
public final class RigSeparation {

    private RigSeparation() {
    }

    /**
     * <b>One step of separation, in BLOCKS</b> — how far a detail stands proud of the surface it
     * was authored flush with.
     *
     * <p>{@code 1/128} of a block: an eighth of a pixel at vanilla's 16 texels per block, so no one
     * can see a detail stand proud by it. The depth buffer can: Minecraft's near plane is 0.05 and a
     * 24-bit buffer resolves roughly {@code z² · 1.2e-6} blocks at distance {@code z} — 0.001 at 30
     * blocks, and this at about 80. FightingFaces' 0.05 sculpt units was 1/320 of a block at scale 1,
     * and shrank with a small creature.
     */
    public static final float STEP_BLOCKS = 1f / 128f;

    /**
     * A box may not grow further out of its authored surface than this, however deep the chain of
     * details stacked on it. Eight steps plus two pattern lifts; no committed creature gets near it,
     * and past it a detail is left where it was authored rather than quietly reshaped.
     */
    public static final float MAX_BLOCKS = 8 * STEP_BLOCKS + 2 * RigPlan.PATTERN_LIFT_BLOCKS;

    /** Two faces closer than this, in blocks, are one surface. Twice the gate's own threshold. */
    static final double COPLANAR_BLOCKS = 2e-3;

    /** Shared extent, per in-plane axis, in blocks, below which two faces merely touch. */
    static final double TOUCH_BLOCKS = 1e-4;

    /** Two composed rotations closer than this (1 − |q·q'|) are the same rigid turn. */
    static final double SAME_TURN = 1e-6;

    static final int MAX_PASSES = 8;

    private static volatile boolean enabled = !"off".equalsIgnoreCase(System.getProperty(
            com.moddingfromamod.ModdingFromAModMod.MODID + ".rigseparation", "on"));

    /**
     * <b>Test seam, not a feature flag</b> — the Gate A check's control arm and the "before" film
     * ({@code -Dmoddingfromamod.rigseparation=off}). A shipped build always separates; see
     * {@code docs/COMPAT_SHIMS.md} on why a switch whose off-arm nobody runs is not a rollback.
     * Read when a plan is MADE, so a rig keeps the answer it was planned with.
     */
    public static void setEnabledForTest(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    private static final float[] NOTHING = {0, 0, 0};

    /** One group's handle into its plan's separation: which part it is, and whose plan. */
    public record Ref(Plan plan, int index) {

        /** The group with nothing to clear — every hand-built group and the grouped plans. */
        public static final Ref NONE = new Ref(null, -1);

        /**
         * Growth per FACE along each local axis, in blocks, for a rig drawn at {@code scale}.
         *
         * <p>⚠ <b>Shared and READ-ONLY</b> — not a copy. This is on the per-display, per-write path
         * ({@code RigTransform.atomise}, every animated tick), and a clone per call was measured
         * as a third of a cold plan's cost; every caller only reads it.
         */
        public float[] blocks(double scale) {
            return plan == null ? NOTHING : plan.at(scale)[index];
        }
    }

    /**
     * <b>One creature's separation problem</b>, solved per scale on first ask.
     *
     * <p>Holds the parts, not an answer: the answer depends on the scale the rig is drawn at, and
     * {@link RigPlan#atoms} does not know it. Every group of one plan shares one of these, so the
     * pairwise pass runs once per creature per scale, not once per display.
     */
    public static final class Plan {
        private final List<CreatureSculpt.Part> parts;
        private final Map<String, CreatureSculpt.Part> byName;
        private final boolean withPatterns;
        private final boolean on;
        private final Map<Double, float[][]> byScale = new ConcurrentHashMap<>();
        /** Each part's ancestor chain, handed over by the planner, which has already built it. */
        private final float[][] chainR;
        private final float[][] chainT;

        Plan(List<CreatureSculpt.Part> parts, Map<String, CreatureSculpt.Part> byName,
             boolean withPatterns) {
            this.parts = parts;   // the planner's own list, never mutated after planning
            this.byName = byName;
            this.withPatterns = withPatterns;
            this.on = enabled;
            this.chainR = new float[parts.size()][];
            this.chainT = new float[parts.size()][];
        }

        /** The planner's own chain for part {@code k}, so the solve need not compose it again. */
        void chain(int k, float[] rotationXyzw, float[] translation) {
            chainR[k] = rotationXyzw;
            chainT[k] = translation;
        }

        /** The last answer, so the per-write path is two field reads, not a boxed map lookup. */
        private volatile Last last;

        private record Last(double scale, float[][] answer) {
        }

        float[][] at(double scale) {
            Last l = last;
            if (l != null && l.scale() == scale) {
                return l.answer();
            }
            float[][] answer = solved(scale);
            last = new Last(scale, answer);
            return answer;
        }

        private float[][][] chains() {
            float[][][] out = new float[parts.size()][][];
            for (int k = 0; k < out.length; k++) {
                out[k] = chainR[k] == null ? null : new float[][]{chainR[k], chainT[k]};
            }
            return out;
        }

        private float[][] solved(double scale) {
            return byScale.computeIfAbsent(scale, s -> on
                    ? SOLVED.get(new Shape(parts, withPatterns, s),
                            () -> solve(parts, byName, withPatterns, s, chains()))
                    : new float[parts.size()][3]);
        }
    }

    /**
     * <b>A shape already solved is not solved again.</b> The pairwise solve is ~50 µs for a
     * 62-box Spikor (ATOM_RIG_COST §9), paid once per PLAN — and a server plans one species many
     * times: every spawn of the same starter, every re-adoption when a chunk loads. Keyed on the
     * sculpt's whole CONTENT (every coordinate of every part, compared in full, never a hash
     * alone), the scale and whether overlays are drawn; bounded, least-recently-used first out.
     * The answer is a pure function of that key, so a hit is exactly the answer a solve would give.
     */
    static final class Solved {
        private final int capacity;
        private final Map<Shape, float[][]> map;

        Solved(int capacity) {
            this.capacity = capacity;
            this.map = new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Shape, float[][]> eldest) {
                    return size() > Solved.this.capacity;
                }
            };
        }

        float[][] get(Shape key, java.util.function.Supplier<float[][]> solve) {
            synchronized (map) {
                float[][] hit = map.get(key);
                if (hit != null) {
                    return hit;
                }
            }
            float[][] answer = solve.get();
            synchronized (map) {
                map.put(key, answer);
            }
            return answer;
        }

        int size() {
            synchronized (map) {
                return map.size();
            }
        }
    }

    static final Solved SOLVED = new Solved(512);

    /** What a solve depends on, compared by value: the parts' every number, the scale, the mode. */
    static final class Shape {
        private final List<CreatureSculpt.Part> parts;
        private final boolean withPatterns;
        private final double scale;
        private final int hash;

        Shape(List<CreatureSculpt.Part> parts, boolean withPatterns, double scale) {
            this.parts = parts;
            this.withPatterns = withPatterns;
            this.scale = scale;
            int h = Boolean.hashCode(withPatterns) * 31 + Double.hashCode(scale);
            for (CreatureSculpt.Part p : parts) {
                h = h * 31 + partHash(p);
            }
            this.hash = h;
        }

        /** Primitive arithmetic only: this runs once per plan, often on a barely-warm JIT. */
        private static int partHash(CreatureSculpt.Part p) {
            int h = p.name() == null ? 0 : p.name().hashCode();
            h = h * 31 + Float.floatToIntBits(p.inflate());
            h = h * 31 + floats(p.from());
            h = h * 31 + floats(p.to());
            h = h * 31 + floats(p.pivot());
            return h * 31 + floats(p.rotation());
        }

        private static int floats(float[] a) {
            if (a == null) {
                return 0;
            }
            int h = 1;
            for (float f : a) {
                h = h * 31 + Float.floatToIntBits(f);
            }
            return h;
        }

        private static boolean same(CreatureSculpt.Part a, CreatureSculpt.Part b) {
            return java.util.Objects.equals(a.name(), b.name())
                    && java.util.Objects.equals(a.parent(), b.parent())
                    && java.util.Objects.equals(a.pattern(), b.pattern())
                    && a.inflate() == b.inflate()
                    && java.util.Arrays.equals(a.from(), b.from())
                    && java.util.Arrays.equals(a.to(), b.to())
                    && java.util.Arrays.equals(a.pivot(), b.pivot())
                    && java.util.Arrays.equals(a.rotation(), b.rotation());
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Shape k) || k.hash != hash || k.withPatterns != withPatterns
                    || k.scale != scale || k.parts.size() != parts.size()) {
                return false;
            }
            for (int i = 0; i < parts.size(); i++) {
                if (!same(parts.get(i), k.parts.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Plan the separation for these parts; {@link RigPlan#atoms} hands each group a {@link Ref}. */
    static Plan plan(List<CreatureSculpt.Part> parts, Map<String, CreatureSculpt.Part> byName,
                     boolean withPatterns) {
        return new Plan(parts, byName, withPatterns);
    }

    // ── the solver ───────────────────────────────────────────────────────────────────────────

    /**
     * <b>How far each part's display must grow, per face, per LOCAL axis, in blocks, at this
     * scale.</b> Public for the perf probe and the tests; the rig asks through {@link Ref}.
     */
    public static float[][] solve(List<CreatureSculpt.Part> parts,
                                  Map<String, CreatureSculpt.Part> byName,
                                  boolean withPatterns, double scale) {
        return solve(parts, byName, withPatterns, scale, new float[parts.size()][][]);
    }

    static float[][] solve(List<CreatureSculpt.Part> parts,
                           Map<String, CreatureSculpt.Part> byName,
                           boolean withPatterns, double scale, float[][][] chains) {
        int n = parts.size();
        float[][] g = new float[n][3];
        if (n < 2) {
            return g;
        }
        Box[] boxes = new Box[n];
        for (int i = 0; i < n; i++) {
            boxes[i] = box(parts.get(i), byName, withPatterns, scale, chains[i]);
        }
        // Only pairs in one rigid turn can ever share a plane, and only on an axis where their faces
        // are within REACH of each other (the most any rule can grow a box, plus a lift) while they
        // overlap, padded by that reach, on the other two. Found ONCE, so the passes below walk the
        // few dozen pairs that can matter rather than every pair of every creature: measured on
        // Spikor (64 displays) it is what takes the solve from ~95 us to (see ATOM_RIG_COST §9).
        double reach = MAX_BLOCKS + RigPlan.PATTERN_LIFT_BLOCKS + COPLANAR_BLOCKS;
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Box bi = boxes[i];
            for (int j = i + 1; j < n; j++) {
                Box bj = boxes[j];
                int near = 0;
                int axes = 0;
                for (int ax = 0; ax < 3; ax++) {
                    if (bi.lo[ax] - reach < bj.hi[ax] && bj.lo[ax] - reach < bi.hi[ax]) {
                        near++;
                    }
                    if (Math.abs(bi.lo[ax] - bj.lo[ax]) < reach
                            || Math.abs(bi.hi[ax] - bj.hi[ax]) < reach) {
                        axes |= 1 << ax;
                    }
                }
                if (near < 3 || axes == 0 || !sameTurn(bi, bj)) {
                    continue;
                }
                // [smaller, bigger, axis mask]: bigger volume holds its ground; ties to sculpt order
                boolean iBigger = bi.volume >= bj.volume;
                pairs.add(iBigger ? new int[]{j, i, axes} : new int[]{i, j, axes});
            }
        }
        if (pairs.isEmpty()) {
            return g;
        }
        float lift = RigPlan.PATTERN_LIFT_BLOCKS;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean moved = false;
            for (int[] pr : pairs) {
                Box s = boxes[pr[0]];
                Box b = boxes[pr[1]];
                float[] gs = g[pr[0]];
                float[] gb = g[pr[1]];
                for (int ax = 0; ax < 3; ax++) {
                    if ((pr[2] & (1 << ax)) == 0) {
                        continue;
                    }
                    float want = gs[ax];
                    for (int side = -1; side <= 1; side += 2) {
                        // d > 0: the smaller box's skin face is IN FRONT of the bigger's.
                        double d = side * (face(s, gs, ax, side) - face(b, gb, ax, side));
                        // 1. skin on skin, and skin on the bigger box's ink (and ink on ink,
                        //    which is the same gap): stand STEP in front of the outermost
                        //    surface the bigger box draws there.
                        if (overlaps(s, gs, 0, b, gb, 0, ax)) {
                            double front = b.patterned ? lift : 0;
                            boolean onSurface = d > -COPLANAR_BLOCKS;          // not buried in it
                            if (onSurface && d < front + STEP_BLOCKS - 1e-6) {
                                want = (float) Math.max(want, gs[ax] + front + STEP_BLOCKS - d);
                            }
                        }
                        // 2. the bigger box's ink over only the smaller's skin OVERHANG: a box
                        //    flush with the lifted pattern surface itself shares the plane.
                        if (b.patterned && Math.abs(d - lift) < COPLANAR_BLOCKS
                                && overlaps(s, gs, 0, b, gb, lift, ax)) {
                            want = (float) Math.max(want, gs[ax] + lift + STEP_BLOCKS - d);
                        }
                        // 4. ink on ink over only the two overhangs: two patterned boxes meeting
                        //    edge to edge on one plane, whose overlays overlap by a strip.
                        if (s.patterned && b.patterned && Math.abs(d) < COPLANAR_BLOCKS
                                && overlaps(s, gs, lift, b, gb, lift, ax)) {
                            want = (float) Math.max(want, gs[ax] + STEP_BLOCKS - d);
                        }
                        // 5. a smaller PATTERNED box recessed into the bigger one by less than a
                        //    lift: its skin is hidden but its ink is in front of the bigger box's
                        //    face, so its pattern is painted onto its neighbour. Symmetric growth
                        //    for another fight can push a box into exactly this band on its far
                        //    side (measured: a frog's spots at scale 0.5), so it is resolved the
                        //    one way growth can: through, to stand a step proud.
                        if (s.patterned && d <= -COPLANAR_BLOCKS && d > -lift - COPLANAR_BLOCKS
                                && overlaps(s, gs, 0, b, gb, 0, ax)) {
                            want = (float) Math.max(want, gs[ax] + STEP_BLOCKS
                                    + (b.patterned ? lift : 0) - d);
                        }
                        // 3. the smaller box's OWN ink on the bigger's skin: a patterned box
                        //    recessed exactly one lift. Clear the ink, keep the recess.
                        if (s.patterned && Math.abs(d + lift) < COPLANAR_BLOCKS
                                && overlaps(s, gs, lift, b, gb, 0, ax)) {
                            want = (float) Math.max(want, gs[ax] + STEP_BLOCKS - (d + lift));
                        }
                    }
                    if (want > gs[ax] + 1e-7f && want <= MAX_BLOCKS + 1e-6f) {
                        gs[ax] = want;
                        moved = true;
                    }
                }
            }
            if (!moved) {
                break;
            }
        }
        return g;
    }

    /**
     * Every exactly-coplanar, same-facing pair among these parts, in sculpt units and scale-free —
     * what an AUTHOR did, as opposed to what the rig must do about it. For the sculpt critique.
     *
     * @return {@code {i, j, axis}} per pair and axis
     */
    public static List<int[]> authoredFights(List<CreatureSculpt.Part> parts,
                                             Map<String, CreatureSculpt.Part> byName) {
        List<int[]> out = new ArrayList<>();
        int n = parts.size();
        Box[] boxes = new Box[n];
        for (int i = 0; i < n; i++) {
            boxes[i] = box(parts.get(i), byName, false, RigPlan.UNITS_PER_BLOCK, null); // 1 unit
        }
        float[] zero = {0, 0, 0};
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (!sameTurn(boxes[i], boxes[j])) {
                    continue;
                }
                for (int ax = 0; ax < 3; ax++) {
                    boolean coplanar = false;
                    for (int side = -1; side <= 1; side += 2) {
                        coplanar |= Math.abs(face(boxes[i], zero, ax, side)
                                - face(boxes[j], zero, ax, side)) < 1e-3;
                    }
                    if (coplanar && sharesArea(boxes[i], boxes[j], ax, 1e-3)) {
                        out.add(new int[]{i, j, ax});
                    }
                }
            }
        }
        return out;
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    /**
     * A part's box in the frame of its composed rotation {@code Q}, in blocks at one scale: the
     * authored box plus {@code Q⁻¹·t}, where the part draws {@code m ↦ Q·m + t}. Two parts with the
     * same {@code Q} are then compared as plain axis-aligned boxes.
     */
    private record Box(double[] lo, double[] hi, double[] q, double volume, boolean patterned) {
    }

    private static Box box(CreatureSculpt.Part p, Map<String, CreatureSculpt.Part> byName,
                           boolean withPatterns, double scale, float[][] known) {
        float[][] chain = known != null ? known : RigPlan.ancestorChain(p, byName);
        org.joml.Quaterniond cr = new org.joml.Quaterniond(
                chain[0][0], chain[0][1], chain[0][2], chain[0][3]);
        org.joml.Vector3d ct = new org.joml.Vector3d(chain[1][0], chain[1][1], chain[1][2]);
        org.joml.Quaterniond own = p.rotated()
                ? new org.joml.Quaterniond(RigTransform.rotation(p.rotation()))
                : new org.joml.Quaterniond();
        org.joml.Vector3d pv = p.pivot() != null && p.pivot().length >= 3
                ? new org.joml.Vector3d(p.pivot()[0], p.pivot()[1], p.pivot()[2])
                : new org.joml.Vector3d();
        // own: m -> pv + R(m - pv) = R m + (pv - R pv); then the chain: x -> C x + ct.
        org.joml.Vector3d ownT = new org.joml.Vector3d(pv)
                .sub(new org.joml.Vector3d(pv).rotate(own));
        org.joml.Quaterniond q = new org.joml.Quaterniond(cr).mul(own).normalize();
        org.joml.Vector3d t = new org.joml.Vector3d(ownT).rotate(cr).add(ct);
        // Back into the turn's own frame: Q⁻¹(Q m + t) = m + Q⁻¹ t.
        org.joml.Vector3d shift = new org.joml.Vector3d(t)
                .rotate(new org.joml.Quaterniond(q).conjugate());
        if (q.w < 0) {
            q.set(-q.x, -q.y, -q.z, -q.w);   // q and -q are one turn
        }
        double k = scale / RigPlan.UNITS_PER_BLOCK;
        double inf = p.inflate();
        double[] lo = new double[3];
        double[] hi = new double[3];
        double[] sh = {shift.x, shift.y, shift.z};
        double vol = 1;
        for (int ax = 0; ax < 3; ax++) {
            lo[ax] = (Math.min(p.from()[ax], p.to()[ax]) - inf + sh[ax]) * k;
            hi[ax] = (Math.max(p.from()[ax], p.to()[ax]) + inf + sh[ax]) * k;
            vol *= Math.max(0, hi[ax] - lo[ax]);
        }
        boolean patterned = withPatterns && p.patterned()
                && AtomLibrary.patternModelData(p.pattern()) > 0;
        return new Box(lo, hi, new double[]{q.x, q.y, q.z, q.w}, vol, patterned);
    }

    private static boolean sameTurn(Box a, Box b) {
        double dot = a.q[0] * b.q[0] + a.q[1] * b.q[1] + a.q[2] * b.q[2] + a.q[3] * b.q[3];
        return 1 - Math.abs(dot) < SAME_TURN;
    }

    /** The grown box's face on this axis and side ({@code -1} low, {@code +1} high). */
    private static double face(Box b, float[] g, int ax, int side) {
        return side < 0 ? b.lo[ax] - g[ax] : b.hi[ax] + g[ax];
    }

    /**
     * Do these two grown boxes, each widened by {@code padA}/{@code padB} on every face (a pattern
     * overlay is its box plus one lift), share area in the plane across {@code axis}?
     */
    private static boolean overlaps(Box a, float[] ga, double padA, Box b, float[] gb, double padB,
                                    int axis) {
        for (int i = 0; i < 3; i++) {
            if (i == axis) {
                continue;
            }
            double lo = Math.max(a.lo[i] - ga[i] - padA, b.lo[i] - gb[i] - padB);
            double hi = Math.min(a.hi[i] + ga[i] + padA, b.hi[i] + gb[i] + padB);
            if (hi - lo <= TOUCH_BLOCKS) {
                return false;
            }
        }
        return true;
    }

    private static boolean sharesArea(Box a, Box b, int axis, double touch) {
        for (int i = 0; i < 3; i++) {
            if (i == axis) {
                continue;
            }
            if (Math.min(a.hi[i], b.hi[i]) - Math.max(a.lo[i], b.lo[i]) <= touch) {
                return false;
            }
        }
        return true;
    }
}
