package com.moddingfromamod.event.shape;

/**
 * <b>{@code moving_front}: a wall swept along a heading</b> (E2). Pure.
 *
 * <p>A tidal wave, a wall of sand, a rolling tide of slime — anything that rears up, races forward
 * and dies away. Ported from a mod repo's tidal wave, with the two rules that made it READ as one
 * kept structural rather than tuned:
 * <ul>
 *   <li>it <b>always ends at nothing</b> (a front that kept its height would cover the world forever);</li>
 *   <li>it <b>rolls for most of its journey before it breaks</b> (break at once and what you built is a
 *       splash).</li>
 * </ul>
 * A front that {@link #breaks} also CURLS: its crest leans out over its own base as it topples, which is
 * the one thing that separates a breaking wave from a wall sliding along. A front that does not break
 * (a sandstorm wall) simply slumps.
 */
public final class FrontShape {

    /** Blocks the front moves a tick: 20 a second, properly frightening. */
    public static final double SPEED = 1.0;
    /** Blocks deep, front face to back. */
    public static final int THICKNESS = 5;
    /** Share of the journey spent rolling before the crest starts to topple. */
    public static final double BREAK_AT = 0.58;
    /** Share of the journey by which only the wash is left. */
    public static final double WASH_AT = 0.86;
    /** How far the crest leans out at the height of the tumble, as a share of its height. */
    public static final double MAX_OVERHANG = 0.85;
    /** Share of the journey spent rearing up. */
    public static final double RISE_DONE_AT = 0.22;
    /** How tall a newborn front is: it rears UP out of the ground, it does not blink into being. */
    public static final double BIRTH_HEIGHT = 0.35;
    /** What is left of the height once it has crashed. */
    public static final double WASH_HEIGHT = 0.16;
    /** How much wider the wash spreads than the front that made it. */
    public static final double WASH_SPREAD = 1.4;
    /** The shortest trip, and how much further each block of height carries it. */
    public static final int MIN_TRAVEL = 70;
    public static final int TRAVEL_PER_HEIGHT = 7;
    /** However big it is, it stops. */
    public static final int MAX_TRAVEL = 400;

    /** Front-to-back profile: the crest sits one block BEHIND the face, which gives it a lip to curl. */
    private static final double[] DEPTH = {0.78, 1.00, 0.93, 0.74, 0.50};

    /** Blocks tall at full height, blocks across at full width, and whether it curls over. */
    public final int height;
    public final int width;
    public final boolean breaks;

    public FrontShape(int height, int width, boolean breaks) {
        if (height < 2 || width < 3) {
            throw new IllegalArgumentException("a front needs some height and width");
        }
        this.height = height;
        this.width = width;
        this.breaks = breaks;
    }

    /** The acts of a front's life. */
    public enum Phase { ROLLING, TUMBLING, WASH, GONE }

    public int travel() {
        return Math.min(MAX_TRAVEL, MIN_TRAVEL + height * TRAVEL_PER_HEIGHT);
    }

    /**
     * How far from where it is born it is when it breaks. A wave set off near him is born this far
     * AHEAD and rolls back toward him, so he watches it rear up and come in, and it crashes on him.
     */
    public int breaksAt() {
        return (int) Math.round(travel() * BREAK_AT);
    }

    /** Ticks from birth to nothing. */
    public int lifeTicks() {
        return (int) Math.ceil(travel() / SPEED);
    }

    public static Phase phaseAt(double p) {
        if (p >= 1.0) {
            return Phase.GONE;
        }
        if (p >= WASH_AT) {
            return Phase.WASH;
        }
        return p >= BREAK_AT ? Phase.TUMBLING : Phase.ROLLING;
    }

    /** Height now, as a share of full: rears up, holds, collapses, fades to exactly zero. */
    public static double heightFactor(double p) {
        if (p <= 0.0) {
            return BIRTH_HEIGHT;
        }
        if (p >= 1.0) {
            return 0.0;
        }
        if (p < RISE_DONE_AT) {
            return BIRTH_HEIGHT + (1.0 - BIRTH_HEIGHT) * (p / RISE_DONE_AT);
        }
        if (p < BREAK_AT) {
            return 1.0;
        }
        if (p < WASH_AT) {
            double t = (p - BREAK_AT) / (WASH_AT - BREAK_AT);
            return 1.0 - (1.0 - WASH_HEIGHT) * t * t;
        }
        return WASH_HEIGHT * (1.0 - (p - WASH_AT) / (1.0 - WASH_AT));
    }

    /** Width now, as a share of full: the wash spreads out. */
    public static double widthFactor(double p) {
        if (p < BREAK_AT) {
            return 1.0;
        }
        double t = Math.min(1.0, (p - BREAK_AT) / (1.0 - BREAK_AT));
        return 1.0 + (WASH_SPREAD - 1.0) * t;
    }

    /**
     * How far the crest hangs out over its base, as a share of current height. Zero unless it breaks.
     * It curls only once it breaks. A lean on the way in (E6, tried and reverted) read as ragged, torn
     * edges up close, because each column's top shifts by a different whole number of blocks.
     */
    public double overhang(double p) {
        if (!breaks || p < BREAK_AT || p >= WASH_AT) {
            return 0.0;
        }
        return MAX_OVERHANG * Math.sin((p - BREAK_AT) / (WASH_AT - BREAK_AT) * Math.PI);
    }

    /**
     * How far forward the block at height {@code y} of a column {@code columnHeight} tall is shifted.
     * Squared, so the lean sits at the TOP — a curl. Spread evenly it would be a ramp.
     */
    public static double leanAt(double overhangBlocks, int y, int columnHeight) {
        if (columnHeight <= 1 || overhangBlocks <= 0.0) {
            return 0.0;
        }
        double t = Math.min(1.0, Math.max(0.0, y / (double) (columnHeight - 1)));
        return overhangBlocks * t * t;
    }

    /**
     * Height across the width, {@code t} 0 in the middle and 1 at the tip. A long flat crest with
     * shoulders only at the very ends: at the cube this read as a hill of water, at the sixth power it
     * reads as a wall.
     */
    public static double crossFactor(double t) {
        double c = Math.min(1.0, Math.max(0.0, Math.abs(t)));
        double c3 = c * c * c;
        return 1.0 - c3 * c3;
    }

    public static double depthFactor(int depth) {
        return depth < 0 || depth >= DEPTH.length ? 0.0 : DEPTH[depth];
    }

    /** Blocks tall for one column of the front right now. */
    public int columnHeight(double p, double crossT, int depth) {
        return (int) Math.floor(height * heightFactor(p) * crossFactor(crossT) * depthFactor(depth));
    }

    /** Half the width now, in blocks. */
    public int halfWidthNow(double p) {
        return (int) Math.round(width * widthFactor(p) / 2.0);
    }

    /** The most cells the front can occupy at once — what its budget must cover. */
    public int peakCells() {
        return (int) Math.ceil(width * WASH_SPREAD) * THICKNESS * height;
    }

    /** True exactly when the crest starts to fall, so the crash is announced once. */
    public static boolean justBroke(double before, double now) {
        return before < BREAK_AT && now >= BREAK_AT;
    }
}
