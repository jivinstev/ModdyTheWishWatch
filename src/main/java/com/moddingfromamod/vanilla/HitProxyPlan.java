package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * <b>How many invisible proxy mobs a rig needs so that clicks AND arrows land where the creature is
 * drawn </b> — decided per creature, at wish time, by measuring. Minecraft-free.
 *
 * <p>A vanilla hitbox is one axis-aligned, square-footprint box per entity. made the carrier
 * the best single box for the drawn creature, and for a long or sprawling body the best single box
 * is still bad in one of two directions: tight, and a click on the head goes through it; loose, and
 * a click on the air beside it lands. The measured answer is a small CORE carrier plus a few
 * invisible proxy mobs over the parts it cannot cover, every hit on a proxy forwarded to the
 * carrier ({@code HitProxies}). But a pig-shaped creature needs none, and every proxy is a real
 * entity the server ticks and tracks, so the number is chosen here rather than fixed:
 *
 * <ol>
 *   <li>For k = 0, 1, … {@link #MAX_PROXIES}, and for each of three cores (the shipped F2
 *       carrier, then tighter F0.5 and F0.25 ones), place k proxies — the pieces of the drawn
 *       boxes the core leaves out, grouped into k parts, each part covered by the peaceful land
 *       mob and scale whose box fits it (F1 and F2 both tried) — and score the result the way
 *       Minecraft picks: clicks against the exact boxes, arrows against boxes grown by
 *       {@value #ARROW_MARGIN} ({@code ProjectileUtil.getEntityHitResult}).</li>
 *   <li>At the smallest k where any candidate meets {@link Bounds#VANILLA} on all four numbers,
 *       the best of those wins, so a creature is given the fewest entities that make it behave
 *       like a vanilla mob.</li>
 *   <li>If none does, the one closest to the bounds wins (fewest entities on a tie).</li>
 * </ol>
 *
 * <p>The body turns and a vanilla box does not, so every candidate is scored with the body at 0°,
 * 22.5° and 45° and the three are averaged: a proxy over a snake's head is a square that stays
 * square while the head swings round a circle. Vanilla's own mobs pay the same price (a cow turned
 * 45° pokes its corners out of its box), and the bounds were measured the same way.
 */
public final class HitProxyPlan {

    private HitProxyPlan() {
    }

    /** A projectile tests an entity's box grown by this much ({@code ProjectileUtil}, 1.21.4). */
    public static final double ARROW_MARGIN = 0.3;

    /** The most proxies a creature is ever given. an earlier issue's cap on extra entities is 6. */
    public static final int MAX_PROXIES = 6;

    /**
     * <b>The planner's version — part of the key of every plan saved with a creature</b>
     * ({@link HitProxies#remember}). A plan is saved on the carrier so a creature coming back from
     * disk is not re-planned (the join stall: ~35 plans at 40–100 ms each on the server thread).
     * A saved plan is reused only when every input matches, and the inputs say nothing about THIS
     * code — so when this class (or anything it calls: {@link RigBase}, {@link CarrierPool},
     * {@link RigFit}) starts answering differently for the same inputs, bump this and every saved
     * plan is re-planned on its next load. {@code HitProxyPlanVersionTest} fails when the output
     * over the committed creatures changes and this did not.
     */
    public static final int VERSION = 2;   // 2: counts leg_row parts as legs, which changes the plans of committed creatures

    /** Parallel rays per side of the grid, per bearing. 16 bearing/pitch pairs x 24 x 24 per body yaw. */
    static final int GRID = 16;

    /**
     * Body yaws every candidate is scored at, and the score is their MEAN: a creature is seen
     * turned every way, and 0 to 45 degrees covers every way for a box (it repeats each quarter
     * turn and mirrors about the eighth).
     */
    static final double[] BODY_YAWS = {0, Math.PI / 8, Math.PI / 4};

    /** A fraction of aimed shots that land, and a fraction of landed shots that were at air. */
    public record Score(double hit, double air) {
    }

    /**
     * What a creature must do to count as behaving like a vanilla mob, against the vanilla range
     * this instrument measures on vanilla's own model shapes (zombie, pig, cow, spider, chicken;
     * {@code HitProxyPlanTest}): clicks land 0.81-0.99 and are on air 0.13-0.57 of the time;
     * arrows land 1.00 and are on air 0.56-0.82.
     *
     * <p>The hit floors sit ABOVE the worst vanilla mob (a chicken's 0.81), because a chicken is
     * small enough that nobody notices, while a missed click on a dragon's head is the bug the
     * issue is about. Click air is held to 0.50, an earlier issue's median target, so the corpus median lands
     * under it; arrow air is the worst vanilla mob's (a chicken's 0.82).
     */
    public record Bounds(double clickHit, double clickAir, double arrowHit, double arrowAir) {
        public static final Bounds VANILLA = new Bounds(0.95, 0.50, 0.98, 0.82);

        public boolean met(Score click, Score arrow) {
            return click.hit() >= clickHit && click.air() <= clickAir
                    && arrow.hit() >= arrowHit && arrow.air() <= arrowAir;
        }

        /** How far outside the bounds, summed over the four numbers; 0 when met. */
        public double shortfall(Score click, Score arrow) {
            return Math.max(0, clickHit - click.hit()) + Math.max(0, click.air() - clickAir)
                    + Math.max(0, arrowHit - arrow.hit()) + Math.max(0, arrow.air() - arrowAir);
        }
    }

    /**
     * One proxy mob, in the rig's own frame (the displays' frame, before the body's yaw): which mob,
     * at what {@code minecraft:scale}, how big that makes it, and where its feet go.
     */
    public record Proxy(String id, double scale, double width, double height,
                        double x, double y, double z) {
        RigFit.Box box() {
            return new RigFit.Box(x - width / 2, y, z - width / 2, x + width / 2, y + height,
                    z + width / 2);
        }
    }

    /**
     * The plan: the carrier, its proxies, and the worst-yaw score that chose them.
     *
     * @param tried how many candidates were scored before this one won — the planner's own cost
     */
    public record Plan(RigBase.Choice core, List<Proxy> proxies, Score click, Score arrow,
                       boolean met, int tried) {
        public int extraEntities() {
            return proxies.size();
        }
    }

    /**
     * Choose the carrier and its proxies for a rig.
     *
     * @param atoms          the rig's drawn boxes in rig space, in blocks ({@link RigFit#atoms})
     * @param candidates     every mob that could be a carrier or a proxy, with live sizes
     * @param carrierAllowed which of them may carry this creature where it stands ({@link
     *                       CarrierPool#allowed}); {@code null} for any
     * @param proxyAllowed   which may stand in as a proxy ({@link #proxyPool})
     */
    public static Plan plan(List<RigFit.Atom> atoms, List<RigBase.Candidate> candidates,
                            Collection<String> carrierAllowed, Collection<String> proxyAllowed,
                            Bounds bounds) {
        return plan(atoms, candidates, carrierAllowed, proxyAllowed, bounds, Double.NaN);
    }

    /**
     * @param seatCap for a creature he RIDES, the top of its back over the carrier's feet, in
     *                blocks; NaN for none. A rider sits on the carrier's own seat, a little under
     *                the top of its box, and nothing in vanilla can lower a seat (only raise it,
     *                {@code RideSeat}) — so the carrier is fitted to the creature BELOW its back,
     *                no carrier taller than the back is kept, and what stands above it (a raised
     *                head, a neck, wings) is left to the proxies, which is what they are for.
     */
    public static Plan plan(List<RigFit.Atom> atoms, List<RigBase.Candidate> candidates,
                            Collection<String> carrierAllowed, Collection<String> proxyAllowed,
                            Bounds bounds, double seatCap) {
        if (atoms.isEmpty()) {
            RigBase.Choice core = RigBase.chooseByFit(candidates, new RigFit.Box(-0.25, 0, -0.25,
                    0.25, 0.5, 0.25), carrierAllowed);
            return new Plan(core, List.of(), new Score(0, 0), new Score(0, 0), false, 0);
        }
        RigFit.Box drawn = RigFit.bounds(atoms);
        boolean capped = Double.isFinite(seatCap) && seatCap > drawn.minY() && seatCap < drawn.maxY();
        RigFit.Box rig = capped ? new RigFit.Box(drawn.minX(), drawn.minY(), drawn.minZ(),
                drawn.maxX(), seatCap, drawn.maxZ()) : drawn;
        // Three cores, loosest first: the shipped F2 carrier (nothing but itself, for a creature
        // that is its own box), then two tighter ones that leave the limbs to proxies.
        List<RigBase.Choice> cores = new ArrayList<>();
        cores.add(RigBase.chooseByFit(candidates, rig, carrierAllowed));
        for (double beta : CORE_BETAS) {
            RigBase.Choice c = RigBase.chooseBy(candidates, carrierAllowed, rig,
                    b -> fBeta(rig, b, beta));
            if (c != null && !cores.contains(c)) {
                cores.add(c);
            }
        }
        cores.removeIf(java.util.Objects::isNull);
        if (Double.isFinite(seatCap)) {
            List<RigBase.Choice> low = new ArrayList<>(cores);
            low.removeIf(c -> c.height() > seatCap + SEAT_SLACK);
            if (low.isEmpty() && seatCap > 0) {
                // None fits under the back (a flier has one carrier, the bat, sized to the whole
                // creature): shrink each until it does. The proxies cover what it then leaves out.
                for (RigBase.Choice c : cores) {
                    double k = seatCap / c.height();
                    if (c.scale() * k < RigBase.SCALE_MIN) {
                        continue;
                    }
                    low.add(new RigBase.Choice(c.id(), c.scale() * k, c.actualWidth() * k,
                            c.wantWidth(), c.height() * k));
                }
            }
            if (!low.isEmpty()) {
                cores = low;
            }
        }
        return planAround(atoms, cores, candidates, proxyAllowed, bounds);
    }

    /**
     * Proxies for a rig whose carrier is already standing (a rig adopted on load keeps the carrier
     * it was saved with — replacing a living mob would lose its health, name and leash).
     */
    public static Plan planAround(List<RigFit.Atom> atoms, RigBase.Choice core,
                                  List<RigBase.Candidate> candidates,
                                  Collection<String> proxyAllowed, Bounds bounds) {
        return planAround(atoms, List.of(core), candidates, proxyAllowed, bounds);
    }

    /** One way of covering the creature: a core, and proxies over what it leaves out. */
    private record Candidate(RigBase.Choice core, List<Proxy> proxies) {
        List<RigFit.Box> targets() {
            List<RigFit.Box> out = new ArrayList<>();
            out.add(RigFit.Box.carrier(core.actualWidth(), core.height()));
            for (Proxy p : proxies) {
                out.add(p.box());
            }
            return out;
        }
    }

    /** How far a ridden creature's carrier may stand over its back, in blocks (its seat is lower). */
    static final double SEAT_SLACK = 0.05;

    /** The tighter cores tried after the shipped one, as the F-beta each is fitted by. */
    static final double[] CORE_BETAS = {0.5, 0.25};

    /** How loosely a proxy mob is fitted over its part: tight (F1) and covering (F2). */
    static final double[] PROXY_BETAS = {1, 2};

    private static Plan planAround(List<RigFit.Atom> atoms, List<RigBase.Choice> cores,
                                   List<RigBase.Candidate> candidates,
                                   Collection<String> proxyAllowed, Bounds bounds) {
        List<Cell> cells = cells(atoms);
        // Every candidate first, because the ray grid must cover every box any of them could put
        // in the world: a click on air beside an overhanging proxy is exactly what is measured.
        List<List<Candidate>> byK = new ArrayList<>();
        for (int k = 0; k <= MAX_PROXIES; k++) {
            List<Candidate> atK = new ArrayList<>();
            for (RigBase.Choice core : cores) {
                if (k == 0) {
                    atK.add(new Candidate(core, List.of()));
                    continue;
                }
                // Proxies go where the CORE is not: the cells whose middle it leaves out.
                RigFit.Box coreBox = RigFit.Box.carrier(core.actualWidth(), core.height());
                List<Cell> outside = new ArrayList<>();
                for (Cell c : cells) {
                    if (!coreBox.contains(c.x(), c.y(), c.z())) {
                        outside.add(c);
                    }
                }
                if (outside.size() < k) {
                    continue;
                }
                List<RigFit.Box> parts = clusters(outside, k);
                for (double beta : PROXY_BETAS) {
                    atK.add(new Candidate(core, proxies(parts, beta, candidates, proxyAllowed)));
                }
            }
            byK.add(atK);
        }
        RigFit.Box span = RigFit.bounds(atoms);
        for (List<Candidate> atK : byK) {
            for (Candidate c : atK) {
                for (RigFit.Box t : c.targets()) {
                    span = union(span, t);
                }
            }
        }
        Rays rays = new Rays(atoms, span);

        // The fewest entities that meet the bounds; among those, the one that lands most shots
        // and puts fewest on air. If nothing meets them, the one that falls least short.
        Plan best = null;
        double bestShort = Double.MAX_VALUE;
        int tried = 0;
        for (List<Candidate> atK : byK) {
            Plan metAtK = null;
            double metGoodness = -Double.MAX_VALUE;
            for (Candidate c : atK) {
                Score[] s = rays.score(c.targets());
                tried++;
                double shortfall = bounds.shortfall(s[0], s[1]);
                if (shortfall == 0) {
                    double goodness = s[0].hit() + s[1].hit() - s[0].air() - s[1].air();
                    if (goodness > metGoodness + 1e-9) {
                        metGoodness = goodness;
                        metAtK = new Plan(c.core(), c.proxies(), s[0], s[1], true, 0);
                    }
                } else if (shortfall < bestShort - 1e-9) {
                    // strictly better only: on a tie the earlier (fewer entities) one stays
                    bestShort = shortfall;
                    best = new Plan(c.core(), c.proxies(), s[0], s[1], false, 0);
                }
            }
            if (metAtK != null) {
                return withTried(metAtK, tried);
            }
        }
        return best == null ? null : withTried(best, tried);
    }

    private static Plan withTried(Plan p, int tried) {
        return new Plan(p.core(), p.proxies(), p.click(), p.arrow(), p.met(), tried);
    }

    /**
     * The mobs that may stand in as a proxy: a peaceful land walker (never drowns, never dries
     * out), and never the iron golem, which zombies hunt — an invisible, invulnerable golem would
     * draw a zombie into beating on the air beside a creature for ever.
     */
    public static List<String> proxyPool() {
        List<String> out = new ArrayList<>(CarrierPool.allowed(CarrierPool.Move.WALK, false));
        out.remove("iron_golem");
        return out;
    }

    /**
     * <b>Mobs a Bedrock client offers to RIDE</b>. Geyser draws its touch Ride/Mount button from
     * the Java entity TYPE under his finger ({@code AbstractHorseEntity.testMobInteraction}: every horse,
     * llama and camel, read from the pinned Geyser), never from what Moddy would do, so an invisible camel
     * proxy put a Ride button over a dragon nobody could ride, and pressing it did nothing. A creature he cannot ride is never given one of these as a hitbox.
     */
    public static final java.util.Set<String> LOOKS_RIDEABLE = java.util.Set.of("camel", "llama", "horse");

    /** {@link #proxyPool()}, without {@link #LOOKS_RIDEABLE} for a creature he cannot ride. */
    public static List<String> proxyPool(boolean rideable) {
        List<String> out = proxyPool();
        if (!rideable) {
            out.removeAll(LOOKS_RIDEABLE);
        }
        return out;
    }

    /** Does this plan put a {@link #LOOKS_RIDEABLE} mob over the creature? */
    public static boolean looksRideable(Plan plan) {
        if (plan == null) {
            return false;
        }
        if (LOOKS_RIDEABLE.contains(plan.core().id())) {
            return true;
        }
        for (Proxy p : plan.proxies()) {
            if (LOOKS_RIDEABLE.contains(p.id())) {
                return true;
            }
        }
        return false;
    }

    /** One proxy per part: the allowed mob and scale whose box fits the part best (F-beta). */
    static List<Proxy> proxies(List<RigFit.Box> parts, double beta,
                               List<RigBase.Candidate> candidates, Collection<String> allowed) {
        List<Proxy> out = new ArrayList<>();
        for (RigFit.Box part : parts) {
            RigFit.Box local = new RigFit.Box(-part.sizeX() / 2, 0, -part.sizeZ() / 2,
                    part.sizeX() / 2, part.sizeY(), part.sizeZ() / 2);
            RigBase.Choice ch = RigBase.chooseBy(candidates, allowed, local,
                    b -> fBeta(local, b, beta));
            if (ch == null) {
                continue;
            }
            out.add(new Proxy(ch.id(), ch.scale(), ch.actualWidth(), ch.height(),
                    part.centreX(), part.minY(), part.centreZ()));
        }
        return out;
    }

    /** F-beta of a box against the rig by volume: beta < 1 favours a tight box, > 1 a covering one. */
    static double fBeta(RigFit.Box rig, RigFit.Box box, double beta) {
        double i = box.intersect(rig).volume();
        double r = rig.volume() <= 0 ? 0 : i / rig.volume();
        double p = box.volume() <= 0 ? 0 : i / box.volume();
        double b2 = beta * beta;
        double den = b2 * p + r;
        return den <= 0 ? 0 : (1 + b2) * p * r / den;
    }

    /**
     * A small piece of one drawn box: its middle, the volume it stands for, and its world bounds.
     * Clustering pieces rather than whole boxes is what lets a proxy cover HALF a snake — a long
     * body drawn as three boxes could otherwise never be split.
     */
    record Cell(double x, double y, double z, double w, RigFit.Box bounds) {
    }

    /** Pieces per axis per drawn box, at most. */
    static final int CELLS_PER_AXIS = 6;

    /**
     * Every drawn box cut into pieces about a twelfth of the creature's longest side (at most
     * {@value #CELLS_PER_AXIS} per axis), each piece's bounds taken from its own turned corners.
     */
    static List<Cell> cells(List<RigFit.Atom> atoms) {
        RigFit.Box rig = RigFit.bounds(atoms);
        double step = Math.max(1e-3, Math.max(rig.sizeX(), Math.max(rig.sizeY(), rig.sizeZ())) / 12);
        List<Cell> out = new ArrayList<>();
        for (RigFit.Atom a : atoms) {
            Vector3f s = a.s();
            int nx = Math.max(1, Math.min(CELLS_PER_AXIS, (int) Math.round(Math.abs(s.x) / step)));
            int ny = Math.max(1, Math.min(CELLS_PER_AXIS, (int) Math.round(Math.abs(s.y) / step)));
            int nz = Math.max(1, Math.min(CELLS_PER_AXIS, (int) Math.round(Math.abs(s.z) / step)));
            double w = Math.abs((double) s.x * s.y * s.z) / (nx * ny * nz);
            for (int i = 0; i < nx; i++) {
                for (int j = 0; j < ny; j++) {
                    for (int k = 0; k < nz; k++) {
                        double[] lo = {-0.5 + (double) i / nx, -0.5 + (double) j / ny,
                                -0.5 + (double) k / nz};
                        double[] hi = {lo[0] + 1.0 / nx, lo[1] + 1.0 / ny, lo[2] + 1.0 / nz};
                        RigFit.Box b = null;
                        for (int c = 0; c < 8; c++) {
                            Vector3f m = new Vector3f(
                                    (float) ((c & 1) == 0 ? lo[0] : hi[0]),
                                    (float) ((c & 2) == 0 ? lo[1] : hi[1]),
                                    (float) ((c & 4) == 0 ? lo[2] : hi[2]));
                            m.rotate(a.r()).mul(s).rotate(a.l()).add(a.t());
                            RigFit.Box pt = new RigFit.Box(m.x, m.y, m.z, m.x, m.y, m.z);
                            b = b == null ? pt : union(b, pt);
                        }
                        out.add(new Cell((b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2,
                                (b.minZ() + b.maxZ()) / 2, Math.max(1e-9, w), b));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Group pieces into {@code k} parts — volume-weighted k-means on their middles, seeded
     * farthest-point from the heaviest piece so it is deterministic — and return each part's
     * bounds.
     */
    static List<RigFit.Box> clusters(List<Cell> cells, int k) {
        int n = cells.size();
        k = Math.min(k, n);
        double[][] c = new double[n][];
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            Cell a = cells.get(i);
            c[i] = new double[]{a.x(), a.y(), a.z()};
            w[i] = a.w();
        }
        double[][] cent = new double[k][];
        int first = 0;
        for (int i = 1; i < n; i++) {
            if (w[i] > w[first]) {
                first = i;
            }
        }
        cent[0] = c[first].clone();
        for (int m = 1; m < k; m++) {
            int far = 0;
            double fd = -1;
            for (int i = 0; i < n; i++) {
                double best = Double.MAX_VALUE;
                for (int q = 0; q < m; q++) {
                    best = Math.min(best, dist2(c[i], cent[q]));
                }
                if (best > fd) {
                    fd = best;
                    far = i;
                }
            }
            cent[m] = c[far].clone();
        }
        int[] asg = new int[n];
        for (int it = 0; it < 30; it++) {
            boolean moved = false;
            for (int i = 0; i < n; i++) {
                int bq = 0;
                for (int q = 1; q < k; q++) {
                    if (dist2(c[i], cent[q]) < dist2(c[i], cent[bq])) {
                        bq = q;
                    }
                }
                moved |= asg[i] != bq;
                asg[i] = bq;
            }
            if (!moved && it > 0) {
                break;
            }
            for (int q = 0; q < k; q++) {
                double sw = 0;
                double x = 0;
                double y = 0;
                double z = 0;
                for (int i = 0; i < n; i++) {
                    if (asg[i] == q) {
                        sw += w[i];
                        x += w[i] * c[i][0];
                        y += w[i] * c[i][1];
                        z += w[i] * c[i][2];
                    }
                }
                if (sw > 0) {
                    cent[q] = new double[]{x / sw, y / sw, z / sw};
                }
            }
        }
        List<RigFit.Box> out = new ArrayList<>();
        for (int q = 0; q < k; q++) {
            RigFit.Box b = null;
            for (int i = 0; i < n; i++) {
                if (asg[i] == q) {
                    b = b == null ? cells.get(i).bounds() : union(b, cells.get(i).bounds());
                }
            }
            if (b != null) {
                out.add(b);
            }
        }
        return out;
    }

    private static double dist2(double[] a, double[] b) {
        double x = a[0] - b[0];
        double y = a[1] - b[1];
        double z = a[2] - b[2];
        return x * x + y * y + z * z;
    }

    static RigFit.Box union(RigFit.Box a, RigFit.Box b) {
        return new RigFit.Box(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()),
                Math.min(a.minZ(), b.minZ()), Math.max(a.maxX(), b.maxX()),
                Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ()));
    }

    static RigFit.Box grow(RigFit.Box b, double m) {
        return new RigFit.Box(b.minX() - m, b.minY() - m, b.minZ() - m, b.maxX() + m,
                b.maxY() + m, b.maxZ() + m);
    }

    /**
     * The ray grid, with each ray's "does it meet the drawn creature" answered ONCE per body yaw:
     * that is the expensive half (a rotated box per atom), and it does not depend on the targets,
     * so scoring thirteen candidates costs one pass over the atoms and thirteen over a few boxes.
     *
     * <p>Same rays as {@link RigFit#rays}: 8 bearings x (level, 30° down), parallel rays over a
     * square that covers everything. A ray that meets a drawn box is an AIMED shot; it lands if it
     * crosses a target. A ray that crosses a target and no drawn box landed on AIR.
     */
    static final class Rays {
        final double[][] origin;
        final double[][] dir;
        final boolean[] onRig;

        /** The body yaws this grid was cast at; the score is their mean. */
        final double[] yaws;

        Rays(List<RigFit.Atom> atoms, RigFit.Box span) {
            this(atoms, span, BODY_YAWS);
        }

        Rays(List<RigFit.Atom> atoms, RigFit.Box span, double[] yaws) {
            this.yaws = yaws;
            Fast[] fast = new Fast[atoms.size()];
            for (int i = 0; i < fast.length; i++) {
                fast[i] = new Fast(atoms.get(i));
            }
            // Everything turns about the carrier's feet, so the grid is centred on the vertical
            // axis and wide enough for the span at any yaw.
            double reach = 0;
            for (double x : new double[]{span.minX(), span.maxX()}) {
                for (double z : new double[]{span.minZ(), span.maxZ()}) {
                    reach = Math.max(reach, Math.hypot(x, z));
                }
            }
            reach += ARROW_MARGIN;
            double y0 = span.minY() - ARROW_MARGIN;
            double y1 = span.maxY() + ARROW_MARGIN;
            double cy = (y0 + y1) / 2;
            double radius = Math.sqrt(reach * reach + (y1 - cy) * (y1 - cy));
            int per = 8 * 2 * GRID * GRID;
            int total = per * yaws.length;
            origin = new double[total][];
            dir = new double[total][];
            onRig = new boolean[total];
            int r = 0;
            for (int yi = 0; yi < yaws.length; yi++) {
                // Turning the body by +a is the same as turning every ray by -a in the body frame.
                double cb = Math.cos(-yaws[yi]);
                double sb = Math.sin(-yaws[yi]);
                for (int bearing = 0; bearing < 8; bearing++) {
                    for (double pitch : new double[]{0, Math.toRadians(30)}) {
                        double yaw = Math.PI / 4 * bearing + Math.PI / 16;
                        double[] d = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch),
                                Math.cos(yaw) * Math.cos(pitch)};
                        double[] u = {Math.cos(yaw), 0, Math.sin(yaw)};
                        double[] v = {d[1] * u[2] - d[2] * u[1], d[2] * u[0] - d[0] * u[2],
                                d[0] * u[1] - d[1] * u[0]};
                        for (int i = 0; i < GRID; i++) {
                            for (int j = 0; j < GRID; j++) {
                                double a = radius * (2.0 * (i + 0.5) / GRID - 1);
                                double b = radius * (2.0 * (j + 0.5) / GRID - 1);
                                double[] o = {a * u[0] + b * v[0] - d[0] * 2 * radius,
                                        cy + a * u[1] + b * v[1] - d[1] * 2 * radius,
                                        a * u[2] + b * v[2] - d[2] * 2 * radius};
                                origin[r] = o;
                                dir[r] = d;
                                double[] bo = turn(o, cb, sb);
                                double[] bd = turn(d, cb, sb);
                                for (Fast f : fast) {
                                    if (f.hits(bo, bd)) {
                                        onRig[r] = true;
                                        break;
                                    }
                                }
                                r++;
                            }
                        }
                    }
                }
            }
        }

        /** {click, arrow}, each the mean over the body yaws. */
        Score[] score(List<RigFit.Box> targets) {
            double[] sum = new double[4];
            for (int yi = 0; yi < yaws.length; yi++) {
                Score[] s = scoreAt(yi, targets);
                sum[0] += s[0].hit();
                sum[1] += s[0].air();
                sum[2] += s[1].hit();
                sum[3] += s[1].air();
            }
            int n = yaws.length;
            return new Score[]{new Score(sum[0] / n, sum[1] / n), new Score(sum[2] / n, sum[3] / n)};
        }

        /** {click, arrow} with the body turned to {@code yaws[yi]}. */
        Score[] scoreAt(int yi, List<RigFit.Box> targets) {
            // The body turned by yaws[yi]: a target's centre turns with it, its box
            // stays axis-aligned (a vanilla hitbox never rotates).
            double c = Math.cos(yaws[yi]);
            double s = Math.sin(yaws[yi]);
            List<RigFit.Box> turned = new ArrayList<>(targets.size());
            for (RigFit.Box t : targets) {
                double[] m = turn(new double[]{t.centreX(), 0, t.centreZ()}, c, s);
                double hx = t.sizeX() / 2;
                double hz = t.sizeZ() / 2;
                turned.add(new RigFit.Box(m[0] - hx, t.minY(), m[2] - hz, m[0] + hx,
                        t.maxY(), m[2] + hz));
            }
            return count(yi, turned);
        }

        /** {click, arrow} for one body yaw: its own slice of the rays, both margins in one pass. */
        private Score[] count(int yi, List<RigFit.Box> targets) {
            long[] seen = new long[2];
            long[] hit = new long[2];
            long[] boxed = new long[2];
            long[] air = new long[2];
            double[][][] b = new double[2][targets.size()][];
            for (int m = 0; m < 2; m++) {
                for (int i = 0; i < targets.size(); i++) {
                    RigFit.Box t = grow(targets.get(i), m == 0 ? 0 : ARROW_MARGIN);
                    b[m][i] = new double[]{t.minX(), t.minY(), t.minZ(), t.maxX(), t.maxY(),
                            t.maxZ()};
                }
            }
            int per = origin.length / yaws.length;
            for (int r = yi * per; r < (yi + 1) * per; r++) {
                // grown boxes first: a ray that misses them misses the exact ones too
                boolean grown = false;
                for (int m = 1; m >= 0; m--) {
                    boolean onBox = false;
                    if (m == 1 || grown) {
                        for (double[] t : b[m]) {
                            if (crosses(origin[r], dir[r], t[0], t[1], t[2], t[3], t[4], t[5])) {
                                onBox = true;
                                break;
                            }
                        }
                    }
                    grown = m == 1 ? onBox : grown;
                    if (onRig[r]) {
                        seen[m]++;
                        if (onBox) {
                            hit[m]++;
                        }
                    }
                    if (onBox) {
                        boxed[m]++;
                        if (!onRig[r]) {
                            air[m]++;
                        }
                    }
                }
            }
            Score[] out = new Score[2];
            for (int m = 0; m < 2; m++) {
                out[m] = new Score(seen[m] == 0 ? 0 : (double) hit[m] / seen[m],
                        boxed[m] == 0 ? 0 : (double) air[m] / boxed[m]);
            }
            return out;
        }
    }

    /**
     * {click, arrow} for any set of targets over a rig — the planner's own instrument, for a test
     * that wants to score something the planner did not propose (a vanilla mob in its own box).
     */
    public static Score[] score(List<RigFit.Atom> atoms, List<RigFit.Box> targets) {
        RigFit.Box span = RigFit.bounds(atoms);
        for (RigFit.Box t : targets) {
            span = union(span, t);
        }
        return new Rays(atoms, span).score(targets);
    }

    /**
     * {click, arrow} for a creature and its targets exactly as they stand — nothing turned. For an
     * instrument that reads a live rig out of the world, where the body has already turned.
     */
    public static Score[] scoreAsPlaced(List<RigFit.Atom> atoms, List<RigFit.Box> targets) {
        RigFit.Box span = RigFit.bounds(atoms);
        for (RigFit.Box t : targets) {
            span = union(span, t);
        }
        return new Rays(atoms, span, new double[]{0}).score(targets);
    }

    /**
     * Does the ray (forward of its origin) cross the box? {@link RigFit#slab}'s answer without its
     * two arrays a call — this runs some ten million times for a creature that needs proxies.
     */
    static boolean crosses(double[] o, double[] d, double x0, double y0, double z0,
                           double x1, double y1, double z1) {
        double tMin = 0;
        double tMax = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            double lo = i == 0 ? x0 : i == 1 ? y0 : z0;
            double hi = i == 0 ? x1 : i == 1 ? y1 : z1;
            if (Math.abs(d[i]) < 1e-12) {
                if (o[i] < lo || o[i] > hi) {
                    return false;
                }
                continue;
            }
            double a = (lo - o[i]) / d[i];
            double b = (hi - o[i]) / d[i];
            tMin = Math.max(tMin, Math.min(a, b));
            tMax = Math.min(tMax, Math.max(a, b));
            if (tMin > tMax) {
                return false;
            }
        }
        return true;
    }

    /** Turn a point about the vertical axis by the angle whose cosine and sine are given. */
    static double[] turn(double[] p, double c, double s) {
        return new double[]{p[0] * c - p[2] * s, p[1], p[0] * s + p[2] * c};
    }

    /**
     * An atom's rotated box as one matrix and one translation, so a ray test is two
     * matrix-vector products and a slab — {@link RigFit.Atom#enter} allocates six objects a call,
     * which is the whole of the planner's cost at 18,000 rays times 60 atoms.
     */
    static final class Fast {
        final RigFit.Box bounds;
        final Matrix3f m;
        final Vector3f t;
        final boolean flat;

        Fast(RigFit.Atom a) {
            bounds = a.bounds();
            t = new Vector3f(a.t());
            Vector3f s = a.s();
            flat = Math.abs(s.x) < 1e-6f || Math.abs(s.y) < 1e-6f || Math.abs(s.z) < 1e-6f;
            m = flat ? new Matrix3f()
                    : new Matrix3f().set(new Quaternionf(a.r()).conjugate())
                            .mul(new Matrix3f().scaling(1 / s.x, 1 / s.y, 1 / s.z))
                            .mul(new Matrix3f().set(new Quaternionf(a.l()).conjugate()));
        }

        boolean hits(double[] o, double[] d) {
            if (flat || !crosses(o, d, bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                return false;
            }
            double ox = o[0] - t.x;
            double oy = o[1] - t.y;
            double oz = o[2] - t.z;
            double[] po = {m.m00 * ox + m.m10 * oy + m.m20 * oz,
                    m.m01 * ox + m.m11 * oy + m.m21 * oz,
                    m.m02 * ox + m.m12 * oy + m.m22 * oz};
            double[] pd = {m.m00 * d[0] + m.m10 * d[1] + m.m20 * d[2],
                    m.m01 * d[0] + m.m11 * d[1] + m.m21 * d[2],
                    m.m02 * d[0] + m.m12 * d[1] + m.m22 * d[2]};
            return crosses(po, pd, -0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        }
    }
}
