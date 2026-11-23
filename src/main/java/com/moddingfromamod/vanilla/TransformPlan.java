package com.moddingfromamod.vanilla;

/**
 * <b>The timeline of a creature's TRANSFORMATION</b> — Minecraft-free, unit-tested.
 *
 * <p>An edit that cannot morph the creature's own displays used to pop: a new carrier (it now
 * flies) or a new set of displays on the same carrier (a different number of parts). The owner,
 * 2026-10-25: make it read as an intentional transformation — the old body fades out and the new
 * one fades in, in parallel, through a big fade to and from white. A display entity has no
 * opacity, so "fade" is a white-out and a swap:
 *
 * <pre>
 *   0 ────── WHITEN ──────► 15 ── SWAP ──► 25 ────── COLOUR ──────► 40 ticks
 *   old: colours → white,    old shrinks    (old gone)
 *        full-bright, swell  to nothing
 *                            new grows      new: white → its colours
 *                            from nothing
 * </pre>
 *
 * <p>Every colour step is a whole-rig item rewrite (one per display), so colours move every
 * {@link #STEP} ticks rather than every tick; the size changes are ONE write each, eased by the
 * client over the phase ({@code interpolation_duration}), which is why they are smooth for free.
 */
public final class TransformPlan {

    private TransformPlan() {
    }

    /** Ticks the old body takes to go white. */
    public static final int WHITEN = 15;
    /** Ticks the two bodies cross over: the old shrinks to nothing while the new grows from it. */
    public static final int SWAP = 10;
    /** Ticks the new body takes to colour in from white. */
    public static final int COLOUR = 15;
    /** A colour is written every STEP ticks (5 steps a phase). */
    public static final int STEP = 3;
    /** How much the white body swells while it whitens. */
    public static final float SWELL = 1.08f;
    /** The size the arriving body starts at, and the leaving body ends at. Not 0: a zero scale
     *  is a degenerate matrix some drivers draw as a flash at the origin. */
    public static final float TINY = 0.02f;
    /**
     * The longest the white body waits at the swap for the new one to be visible to every client
     * (a rig veiled until its wish's early reveal, {@code RigVeil}); past it the swap goes anyway.
     */
    public static final int VEIL_WAIT_MAX = 200;
    /** The new body is hung this many ticks before the swap, so every client has its TINY start. */
    public static final int HANG_LEAD = 2;

    public static int total() {
        return WHITEN + SWAP + COLOUR;
    }

    public enum Phase { WHITEN, SWAP, COLOUR, DONE }

    /** The phase at {@code t} ticks since the transformation began (the veil wait excluded). */
    public static Phase phaseAt(int t) {
        if (t < WHITEN) {
            return Phase.WHITEN;
        }
        if (t < WHITEN + SWAP) {
            return Phase.SWAP;
        }
        if (t < total()) {
            return Phase.COLOUR;
        }
        return Phase.DONE;
    }

    /** Is {@code t} a tick a colour is written on? */
    public static boolean colourStep(int t) {
        return t >= 0 && t % STEP == 0;
    }

    /**
     * How white the LEAVING body is at {@code t}: 0 at the start, 1 by the end of WHITEN, and white
     * from then on. Stepped, so it reaches 1 on the last step of the phase rather than after it.
     */
    public static float leavingWhiteness(int t) {
        if (t <= 0) {
            return 1f / steps(WHITEN);
        }
        return Math.min(1f, (float) (t / STEP + 1) / steps(WHITEN));
    }

    /**
     * How white the ARRIVING body is at {@code t}: white until the swap ends, then back to its own
     * colours over COLOUR, reaching exactly 0 on the phase's last step.
     */
    public static float arrivingWhiteness(int t) {
        int c = t - (WHITEN + SWAP);
        if (c < 0) {
            return 1f;
        }
        int n = steps(COLOUR);
        int k = Math.min(n, c / STEP + 1);
        return Math.max(0f, 1f - (float) k / n);
    }

    private static int steps(int phase) {
        return Math.max(1, (phase + STEP - 1) / STEP);
    }

    /**
     * One display's translation scaled by {@code f} about the body's centre {@code c}, so a body
     * shrinks into itself and grows out of itself rather than collapsing to its feet.
     */
    public static float[] about(float[] t, float[] c, float f) {
        return new float[] {c[0] + (t[0] - c[0]) * f, c[1] + (t[1] - c[1]) * f, c[2] + (t[2] - c[2]) * f};
    }

    /** The centre of a body: the mean of its displays' translations (empty → the origin). */
    public static float[] centre(java.util.List<float[]> translations) {
        if (translations == null || translations.isEmpty()) {
            return new float[] {0f, 0f, 0f};
        }
        double x = 0, y = 0, z = 0;
        for (float[] t : translations) {
            x += t[0];
            y += t[1];
            z += t[2];
        }
        int n = translations.size();
        return new float[] {(float) (x / n), (float) (y / n), (float) (z / n)};
    }
}
