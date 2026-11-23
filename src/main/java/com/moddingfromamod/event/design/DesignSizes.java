package com.moddingfromamod.event.design;

import com.moddingfromamod.event.EventScale;
import com.moddingfromamod.event.shape.FrontShape;
import com.moddingfromamod.event.shape.StampShape;

/**
 * <b>How big each motion is at each of the four sizes</b> (E4). Pure. A child says "a big
 * wave" or "a gigantic flood"; this turns the word into the shape's own numbers, and every number
 * here fits inside that size's budget (D2) — asserted, not assumed, by {@code DesignSizesTest}.
 */
public final class DesignSizes {

    private DesignSizes() {
    }

    private static int pick(EventScale s, int little, int medium, int big, int gigantic) {
        return switch (s) {
            case LITTLE -> little;
            case MEDIUM -> medium;
            case BIG -> big;
            case GIGANTIC -> gigantic;
        };
    }

    public static int waveHeight(EventScale s) {
        return pick(s, 5, 8, 12, 18);
    }

    public static int waveWidth(EventScale s) {
        return pick(s, 12, 20, 32, 48);
    }

    public static FrontShape wave(EventScale s, boolean breaks) {
        return new FrontShape(waveHeight(s), waveWidth(s), breaks);
    }

    public static int floodDepth(EventScale s) {
        return pick(s, 3, 6, 10, 16);
    }

    public static int stampRadius(EventScale s) {
        return pick(s, 6, 10, 16, 24);
    }

    /** A rough upper bound on the cells a stamp can change: a cylinder of its reach and depth. */
    public static long stampCells(StampShape.Form form, EventScale s) {
        int r = stampRadius(s);
        double reach = r * (1 + StampShape.RIM_REACH);
        double tall = form == StampShape.Form.CONE ? r * StampShape.CONE_HEIGHT
                : 2 * r / StampShape.WIDTH_TO_DEPTH + r * StampShape.RIM_HEIGHT + 2;
        return Math.round(Math.PI * reach * reach * tall);
    }

    /**
     * The falling rock's half-width, in proportion to the crater of the same size (about a quarter to
     * a third of {@link #stampRadius}). It is display entities now, so a bigger one costs
     * nothing to draw, but a rock was judged 1.7 times this oversized for the crater it makes.
     */
    public static int bodyRadius(EventScale s) {
        return pick(s, 2, 3, 4, 6);
    }

    public static int skyRadius(EventScale s) {
        return pick(s, 10, 16, 24, 32);
    }

    public static int skyPerTick(EventScale s) {
        return pick(s, 1, 2, 3, 4);
    }

    /**
     * The most creatures one shower drops. Enough to be a downpour, few enough that the herd it leaves
     * does not slow his world: every one is a mob the server thinks for until undo.
     */
    /** A crowd's count when he named none: a group, a herd, a big herd, a huge one. */
    public static int crowd(EventScale s) {
        return pick(s, 8, 16, 32, 64);
    }

    public static int skyCreatures(EventScale s) {
        return pick(s, 24, 40, 64, 96);
    }

    /** Ticks it rains for: ten seconds, whatever the size — a bigger shower is a wider, thicker one. */
    public static final int SKY_TICKS = 200;

    /** how long a transform's ripple takes to reach its edge — long enough to watch it spread. */
    public static int transformTicks(EventScale s) {
        return pick(s, 30, 40, 60, 80);
    }

    public static int cracks(EventScale s) {
        return pick(s, 3, 4, 5, 6);
    }

    public static int crackLength(EventScale s) {
        return pick(s, 12, 18, 24, 32);
    }

    public static int crackDepth(EventScale s) {
        return pick(s, 4, 6, 8, 10);
    }
}
