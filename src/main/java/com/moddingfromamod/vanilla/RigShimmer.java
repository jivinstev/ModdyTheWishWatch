package com.moddingfromamod.vanilla;

/**
 * <b>The colour wave on a creature that is still being wished into shape</b> — Minecraft-free.
 *
 * <p>The creature is shown from its first sculpt, rather than hidden behind
 * the pillar until the end, and while the agent is still working on it, it shimmers. A red → green
 * → blue wave sweeps up through the body, so a child can tell "still being made" from "done"
 * without reading anything. The chat line says the same thing in words
 * ({@code WishFeedback.appearing}).
 *
 * <h2>Why PASTEL, and not pure red, green and blue</h2>
 *
 * <p>The rig's colour channel MULTIPLIES each box's own colour ({@link RigTint#multiply}, which is
 * exactly what vanilla's shader does). Pure blue {@code 0x0000FF} times a gold box
 * {@code 0xE0B341} is {@code 0x000041}, almost black, so a pure wave would turn a gold dragon into a
 * flickering black one. Mixing each hue with white keeps every channel at or above
 * {@link #FLOOR}, so the creature reads as its own colours with a rainbow running through it.
 *
 * <h2>Why it steps, and how often</h2>
 *
 * <p>Every colour change re-sends each box's item to every viewer, and a display's colour does not
 * interpolate. So the wave is stepped: a new frame every {@link #WRITE_EVERY} ticks (5 a second) is
 * enough to read as a shimmer and costs one small update per box per frame, only while a wish is
 * running. {@link #PERIOD_TICKS} is one full trip round the colour wheel.
 */
public final class RigShimmer {

    private RigShimmer() {
    }

    /** One full trip round red → green → blue, in game ticks (2 s). */
    public static final int PERIOD_TICKS = 40;

    /** A new frame of the wave every this many ticks. */
    public static final int WRITE_EVERY = 4;

    /** The lowest any channel of the tint may go, out of 255 — what keeps it pastel. */
    public static final int FLOOR = 150;

    /** How far up the body the wave is spread, in fractions of a turn of the colour wheel. */
    public static final float BAND = 0.5f;

    /** Is this a tick the wave writes on? Anchored on game time, so every shimmering rig steps together. */
    public static boolean writesOn(long gameTime) {
        return Math.floorMod(gameTime, WRITE_EVERY) == 0;
    }

    /**
     * The tint for one box at one moment.
     *
     * @param gameTime the level's game time
     * @param height   where the box sits in the body, 0 at the feet and 1 at the top; the wave
     *                 travels upwards, so a box higher up is a little further round the wheel
     * @return 0xRRGGBB, every channel within {@code FLOOR..255}
     */
    public static int tintFor(long gameTime, float height) {
        float h = clamp01(height);
        float turn = (Math.floorMod(gameTime, PERIOD_TICKS) / (float) PERIOD_TICKS) - h * BAND;
        turn -= (float) Math.floor(turn);
        return pastel(hue(turn));
    }

    /** A fully saturated hue for {@code turn} in 0..1: red at 0, green at a third, blue at two thirds. */
    static int hue(float turn) {
        float t = turn * 3f;
        int seg = (int) Math.floor(t) % 3;
        float f = t - (float) Math.floor(t);
        int up = Math.round(255 * f);
        int down = 255 - up;
        return switch (seg) {
            case 0 -> (down << 16) | (up << 8);         // red → green
            case 1 -> (down << 8) | up;                 // green → blue
            default -> (up << 16) | down;               // blue → red
        };
    }

    /** Lift every channel into {@code FLOOR..255}, keeping the hue. */
    static int pastel(int rgb) {
        int r = lift((rgb >> 16) & 0xFF);
        int g = lift((rgb >> 8) & 0xFF);
        int b = lift(rgb & 0xFF);
        return (r << 16) | (g << 8) | b;
    }

    private static int lift(int c) {
        return FLOOR + Math.round((255 - FLOOR) * (c / 255f));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
