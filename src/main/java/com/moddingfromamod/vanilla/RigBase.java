package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Which vanilla mob carries the hitbox, and how wrong that hitbox is</b> — decision D1 of the
 * vanilla-creature spike, reduced to a number.
 *
 * <h2>The trap this exists to measure, not to hide</h2>
 *
 * <p>{@code WishMobEntity} overrides {@code getDefaultDimensions} and hands Minecraft a box derived
 * from the model itself, so a sculpted creature is exactly as big as it looks. That override was
 * written to fix a real bug: the hitbox used to be a fixed 0.6x1.95 player box that ignored the
 * model, and it LIED to an agent trying to aim a fire breath.
 *
 * <p><b>A vanilla base cannot do that.</b> A vanilla client must be sent a vanilla entity type, so
 * the base is a real {@code Zombie} or {@code Ravager} with the dimensions Mojang gave it. The only
 * per-entity lever is the {@code minecraft:scale} attribute, and scale is <b>uniform</b>: it
 * multiplies width and height together and cannot change their ratio. So unless a creature happens
 * to share a vanilla mob's proportions, the box is the wrong SHAPE however it is scaled — the
 * previous bug's twin, arriving by a door the fix does not cover.
 *
 * <p>This class picks the least-wrong base and <b>reports the residual</b>, so the spike's Q4
 * answer is a measurement rather than a hope.
 *
 * <h2>Height is matched, width is the error</h2>
 *
 * <p>Scale is chosen to match HEIGHT, and the width mismatch is what is left over. That is not
 * arbitrary: height decides where the head is, and it is where a player aims and where another
 * mob's attack reaches. A creature whose box is the right height and slightly too narrow is
 * fightable; one scaled to match width is the wrong height everywhere, and you swing over it.
 *
 * <h2>No hardcoded dimension table, deliberately</h2>
 *
 * <p>The first cut of this read vanilla's dimensions out of {@code EntityType}'s bytecode and got
 * them <b>wrong</b> — the parser mis-paired names with sizes and confidently reported a warden as
 * 0.6x1.95. A table that is wrong in the reassuring direction would make Q4 report a smaller error
 * than the truth. So candidates are passed IN, measured at runtime from the live registry, and this
 * class stays pure arithmetic with nothing to be stale about.
 */
public final class RigBase {

    private RigBase() {
    }

    /**
     * <b>The base may not be a hostile mob, and this is not a harness artifact.</b>
     *
     * <p>{@code Mob.checkDespawn} discards anything whose {@code shouldDespawnInPeaceful()} is true
     * the moment the difficulty is PEACEFUL, and {@code Monster} returns true. Measured in Gate C:
     * a Ravager base was spawned, reported by the server, and <b>never reached the client at
     * all</b> — the client census listed the six item_displays and no ravager — because vanilla had
     * already removed it. {@code setPersistenceRequired()} does not help; the peaceful branch runs
     * first.
     *
     * <p>So on a Peaceful world a Monster-based creature would lose its hitbox, its health and its
     * targetability while the rig floated on regardless: a creature a child can see and cannot
     * touch. That is the same class of defect as the fixed player hitbox
     * {@code WishMobEntity#getDefaultDimensions} was written to fix, arriving through the
     * difficulty setting.
     *
     * <p>Candidates therefore carry {@code peacefulSafe} and {@link #choose} will not return one
     * that is not. The flag is decided at runtime by asking whether a created instance is an
     * {@code Enemy}, not from a list here that could go stale.
     */
    public static final String PEACEFUL_RULE =
            "a Monster base is discarded on Peaceful, so the base must be a non-hostile Mob";

    /**
     * A vanilla entity type offered as a base, with the dimensions the live registry reports.
     *
     * @param id     registry path, e.g. {@code "ravager"}
     * @param width  its natural width in blocks, at scale 1
     * @param height its natural height in blocks, at scale 1
     */
    public record Candidate(String id, float width, float height, boolean peacefulSafe) {

        /** Back-compat for tests that do not care about the peaceful rule. */
        public Candidate(String id, float width, float height) {
            this(id, width, height, true);
        }

        public float ratio() {
            return height <= 0 ? 0 : width / height;
        }
    }

    /**
     * The chosen base and the price of choosing it.
     *
     * @param id          the vanilla entity type to spawn
     * @param scale       the {@code minecraft:scale} attribute value that matches the target height
     * @param actualWidth the width the hitbox will really have, in blocks
     * @param wantWidth   the width the model really needs, in blocks
     * @param height      the height, which is matched exactly
     */
    public record Choice(String id, double scale, double actualWidth, double wantWidth,
                         double height) {

        /** Signed width error in blocks: positive means the box is WIDER than the creature. */
        public double widthErrorBlocks() {
            return actualWidth - wantWidth;
        }

        /** Width error as a fraction of the width wanted. 0.0 is exact. */
        public double widthErrorFraction() {
            return wantWidth <= 0 ? 0 : Math.abs(actualWidth - wantWidth) / wantWidth;
        }

        /**
         * Is the box narrower than the creature — the failure that MATTERS?
         *
         * <p>Too wide is an invisible collision you bump into; annoying. Too narrow is the bug the
         * mod path already fixed once: shoulders you can see and cannot hit, and a snout the agent
         * aims a fire jet from that is outside the entity entirely.
         */
        public boolean tooNarrow() {
            return actualWidth < wantWidth - 1e-4;
        }
    }

    /**
     * Minecraft refuses a scale attribute outside this range, so a creature needing more cannot be
     * matched on height at all. Read from vanilla's own {@code Attributes.SCALE} bounds.
     */
    public static final double SCALE_MIN = 0.0625;
    public static final double SCALE_MAX = 16.0;

    /**
     * The least-wrong base for a creature of {@code wantWidth} x {@code wantHeight} blocks.
     *
     * <p>Ranks on the width error left after matching height, then on ratio closeness, then by id
     * so the answer is stable — an unstable choice would make two runs of the same creature spawn
     * different bases and turn a photograph A/B into a coin toss.
     *
     * @return the choice, or null when no candidate can reach the height within vanilla's scale
     *     limits
     */
    /**
     * <b>How the single uniform scale is spent</b> — and it is a real choice, not a detail.
     *
     * <p>{@code minecraft:scale} multiplies width and height together, so a base's width/height
     * ratio is fixed and the scale can only be spent on ONE of the two dimensions. Which one
     * changes what kind of wrong the hitbox is.
     */
    public enum Fit {
        /**
         * Scale so the HEIGHT is exact and let the width fall where it may.
         *
         * <p>Height decides where the head is, where a player aims and where another mob's attack
         * reaches, so this is the right default — but it leaves the box too NARROW whenever the
         * base is slimmer than the creature, which is the failure that matters: shoulders you can
         * see and cannot hit.
         */
        HEIGHT,
        /**
         * Scale so the box <b>covers</b> the model in both dimensions — {@code max} of the two
         * required scales — trading a little overshoot for never being narrow.
         *
         * <p>Too wide is an invisible collision you bump into; annoying, and recoverable by looking
         * at it. Too narrow is the bug {@code WishMobEntity#getDefaultDimensions} was written to
         * fix. When the ratio error is small — and swept over the whole registry it usually is —
         * COVER converts a 1.6% narrow box into a 1.6% oversized one, which is strictly the better
         * failure.
         */
        COVER
    }

    /** {@link #choose(List, double, double, Fit)} with the historical {@link Fit#HEIGHT} policy. */
    public static Choice choose(List<Candidate> candidates, double wantWidth, double wantHeight) {
        return choose(candidates, wantWidth, wantHeight, Fit.HEIGHT);
    }

    /** As {@link #choose(List, double, double)}, spending the uniform scale per {@code fit}. */
    public static Choice choose(List<Candidate> candidates, double wantWidth, double wantHeight,
                                Fit fit) {
        if (candidates == null || candidates.isEmpty() || wantHeight <= 0) {
            return null;
        }
        Choice best = null;
        double bestScore = Double.MAX_VALUE;
        for (Candidate c : candidates) {
            if (c.height() <= 0 || c.width() <= 0) {
                continue;
            }
            double scale = wantHeight / c.height();
            if (fit == Fit.COVER && wantWidth > 0) {
                scale = Math.max(scale, wantWidth / c.width());
            }
            if (scale < SCALE_MIN || scale > SCALE_MAX) {
                continue;   // vanilla will clamp it, and a clamped box is not the box we asked for
            }
            if (!c.peacefulSafe()) {
                continue;   // see the peaceful rule above — a Monster base is not a base at all
            }
            Choice here = new Choice(c.id(), scale, c.width() * scale, wantWidth,
                    c.height() * scale);
            // Rank on the WORSE of the two errors. Ranking on width alone is right for HEIGHT (its
            // height error is zero by construction) and wrong for COVER, where every candidate that
            // covers on width scores a perfect 0 and the tie is then broken by id — which would
            // pick alphabetically rather than well.
            double score = Math.max(here.widthErrorFraction(),
                    heightErrorFraction(here, wantHeight));
            if (best == null || score < bestScore - 1e-9
                    || (Math.abs(score - bestScore) <= 1e-9 && here.id().compareTo(best.id()) < 0)) {
                best = here;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * <b>The carrier and scale whose box best fits the rig AS DRAWN.</b>
     *
     * <p>{@link #choose} matched HEIGHT against {@code CreatureAnatomy.heightBlocks} and WIDTH
     * against {@code widthBlocks}, and the second is twice the reach from the sculpt's ORIGIN: the
     * box it asked for was on median 61% thin air to a click (the {@code phantom} column of
     * {@code docs/VANILLA_RIG_PARITY.md} §13), and for a creature authored from one end it was
     * stood at that end.
     *
     * <p>The score is F2 on the two bounding boxes — recall (how much of the creature's box the
     * hitbox covers) weighted twice precision (how much of the hitbox is creature). Chosen over
     * plain IoU by the corpus: IoU cut the thin-air clicks further (0.48 against 0.59) and gave up
     * aimed hits to do it (median 0.989, worst 0.38), and the order was "can I hit it where
     * I see it" first. Two boxes, so the search is a few thousand multiplications and needs no probe
     * entities.
     *
     * @param allowed ids the creature's movement class permits ({@link CarrierPool#allowed}), or
     *                null for all
     * @return the choice (its {@code wantWidth} is the footprint's short side), or null when
     *     nothing can be scaled into range
     */
    public static Choice chooseByFit(List<Candidate> candidates, RigFit.Box rig,
                                     java.util.Collection<String> allowed) {
        if (rig == null) {
            return null;
        }
        double rigVol = rig.volume();
        return chooseBy(candidates, allowed, rig, box -> {
            double i = box.intersect(rig).volume();
            double recall = rigVol <= 0 ? 0 : i / rigVol;
            double precision = box.volume() <= 0 ? 0 : i / box.volume();
            double d = 4 * precision + recall;
            return d <= 0 ? 0 : 5 * precision * recall / d;
        });
    }

    /**
     * The generic search behind {@link #chooseByFit}: every allowed, peaceful-safe candidate, each at
     * the scale that maximises {@code score} of its box, best score wins, ties by id.
     */
    public static Choice chooseBy(List<Candidate> candidates, java.util.Collection<String> allowed,
                                  RigFit.Box rig,
                                  java.util.function.ToDoubleFunction<RigFit.Box> score) {
        if (candidates == null || rig == null || rig.maxY() <= 0) {
            return null;
        }
        Choice best = null;
        double bestScore = -1;
        for (Candidate c : candidates) {
            if (c.height() <= 0 || c.width() <= 0 || !c.peacefulSafe()
                    || (allowed != null && !allowed.contains(c.id()))) {
                continue;
            }
            double s = bestScale(c, score);
            double v = score.applyAsDouble(RigFit.Box.carrier(c.width() * s, c.height() * s));
            if (best == null || v > bestScore + 1e-9
                    || (Math.abs(v - bestScore) <= 1e-9 && c.id().compareTo(best.id()) < 0)) {
                best = new Choice(c.id(), s, c.width() * s,
                        Math.min(rig.sizeX(), rig.sizeZ()), c.height() * s);
                bestScore = v;
            }
        }
        return best;
    }

    /**
     * The scale in {@code [SCALE_MIN, SCALE_MAX]} maximising one candidate's score: a coarse log
     * sweep, then a golden-section refine around the best sample. The sweep is the insurance
     * against a score that is not unimodal.
     */
    static double bestScale(Candidate c, java.util.function.ToDoubleFunction<RigFit.Box> score) {
        java.util.function.DoubleUnaryOperator f = s -> score.applyAsDouble(
                RigFit.Box.carrier(c.width() * s, c.height() * s));
        int n = 64;
        double lo = Math.log(SCALE_MIN);
        double hi = Math.log(SCALE_MAX);
        double bestS = SCALE_MIN;
        double bestV = -1;
        for (int i = 0; i <= n; i++) {
            double s = Math.exp(lo + (hi - lo) * i / n);
            double v = f.applyAsDouble(s);
            if (v > bestV + 1e-12) {
                bestV = v;
                bestS = s;
            }
        }
        double step = (hi - lo) / n;
        double a = Math.max(lo, Math.log(bestS) - step);
        double b = Math.min(hi, Math.log(bestS) + step);
        double g = (Math.sqrt(5) - 1) / 2;
        for (int i = 0; i < 30; i++) {
            double x1 = b - g * (b - a);
            double x2 = a + g * (b - a);
            if (f.applyAsDouble(Math.exp(x1)) >= f.applyAsDouble(Math.exp(x2))) {
                b = x2;
            } else {
                a = x1;
            }
        }
        double s = Math.exp((a + b) / 2);
        return f.applyAsDouble(s) >= bestV ? s : bestS;
    }

    /** The signed height error a {@link Fit#COVER} choice paid for never being narrow. */
    public static double heightErrorFraction(Choice choice, double wantHeight) {
        return choice == null || wantHeight <= 0 ? 0
                : Math.abs(choice.height() - wantHeight) / wantHeight;
    }

    private static boolean better(Choice a, Choice b) {
        double ea = a.widthErrorFraction();
        double eb = b.widthErrorFraction();
        if (Math.abs(ea - eb) > 1e-9) {
            return ea < eb;
        }
        return a.id().compareTo(b.id()) < 0;
    }

    /**
     * The same ranking over every candidate, worst last — for a spike report that has to say how
     * much better the best option was than the obvious one.
     */
    public static List<Choice> rank(List<Candidate> candidates, double wantWidth,
                                    double wantHeight) {
        List<Choice> out = new ArrayList<>();
        if (candidates == null || wantHeight <= 0) {
            return out;
        }
        for (Candidate c : candidates) {
            if (c.height() <= 0 || c.width() <= 0) {
                continue;
            }
            double scale = wantHeight / c.height();
            if (scale < SCALE_MIN || scale > SCALE_MAX) {
                continue;
            }
            out.add(new Choice(c.id(), scale, c.width() * scale, wantWidth, wantHeight));
        }
        out.sort((x, y) -> better(x, y) ? -1 : better(y, x) ? 1 : 0);
        return out;
    }
}
