package com.moddingfromamod.event.shape;

/**
 * <b>{@code slide}: a hillside thrown downhill</b> (E2). Pure.
 *
 * <p>An earlier mod's landslide rules. Run-out and width scale with the drop; the depth of ground that lets go is
 * capped; the heap is cube-root high. And the part that took two rounds to get right: a block has to
 * be THROWN downhill, hardest from the crown, with a kick up to clear the step in front of it — a
 * block let go with no velocity lands back in its own footprint, and "847 blocks launched" was true
 * while nothing had moved. The scar fans out and the rubble scatters sideways, or the landing restacks
 * itself in tidy layers.
 */
public final class SlideShape {

    public static final int SCAN = 16;
    public static final int MIN_DROP = 6;
    public static final double RUNOUT_PER_DROP = 1.6;
    public static final int MAX_RUNOUT = 40;
    public static final double WIDTH_PER_DROP = 0.9;
    public static final int MAX_WIDTH = 24;
    public static final int MAX_SCATTER = 3;

    private SlideShape() {
    }

    public static boolean steepEnough(int drop) {
        return drop >= MIN_DROP;
    }

    public static int runOut(int drop) {
        return steepEnough(drop) ? Math.min(MAX_RUNOUT, (int) Math.round(drop * RUNOUT_PER_DROP)) : 0;
    }

    public static int width(int drop) {
        return steepEnough(drop) ? Math.max(3, Math.min(MAX_WIDTH, (int) Math.round(drop * WIDTH_PER_DROP))) : 0;
    }

    public static int depth(int drop) {
        return steepEnough(drop) ? Math.max(1, Math.min(4, drop / 8)) : 0;
    }

    public static int pileHeight(int moved) {
        return Math.max(1, Math.min(5, (int) Math.round(Math.cbrt(Math.max(1, moved)))));
    }

    /**
     * How far past the foot of the scar a block lands, {@code t} being where it sat in the scar: 1 at
     * the crown (thrown hardest, lands furthest), 0 at the toe.
     */
    public static int landing(int runOut, double t) {
        double c = Math.max(0, Math.min(1, t));
        return (int) Math.round(runOut * (0.35 + 0.65 * c));
    }

    /** Sideways scatter for one block, from a seed, in [-MAX_SCATTER, MAX_SCATTER]. */
    public static int scatter(long seed) {
        long h = seed * 0x9E3779B97F4A7C15L;
        h ^= h >>> 31;
        return (int) Math.floorMod(h, 2 * MAX_SCATTER + 1) - MAX_SCATTER;
    }

    /** How wide the scar is a share {@code t} of the way down: it fans out toward the toe. */
    public static int scarHalfWidth(int width, double t) {
        return Math.max(1, (int) Math.round(width / 2.0 * (0.6 + 0.4 * (1 - t))));
    }
}
