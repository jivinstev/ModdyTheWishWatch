package com.moddingfromamod.vanilla;

/**
 * <b>How fast a rig's BODY may turn, and when it bothers to</b>. Pure.
 *
 * <p>The rig used to draw the carrier's body yaw as it stood each tick. A carrier's yaw is not a
 * drawing instruction: a bat's is its velocity's direction, which is noise while it hovers; a mob
 * standing under a player has its body dragged round after a head that is chasing a direction with
 * no meaning. Drawn raw, a creature flipped half a turn in a tick, several times a second. That
 * looks wrong on Java and is the one case a Bedrock client still draws in pieces.
 *
 * <p>So the drawn yaw is its own state, and each tick it is stepped towards what the carrier wants:
 * <ul>
 *   <li><b>a threshold</b>: a body at rest does not start turning until it is {@link #START}
 *       degrees off ({@link #START_MOVING} while it travels, so it still faces its way). Once
 *       turning it goes on until it is within {@link #STOP}, so it does not stop a threshold short;</li>
 *   <li><b>damping</b>: each tick it closes {@link #GAIN} of what is left, never more than
 *       {@link #MAX} degrees and never less than {@link #MIN}, so a turn starts briskly, slows as it
 *       arrives, and does finish. Travelling, it turns faster ({@link #MAX_MOVING}): it has
 *       somewhere to go, and must face it;</li>
 *   <li><b>hold</b>: while the caller says the wanted yaw means nothing, it stays where it is.</li>
 * </ul>
 *
 * <p>A turn the creature MEANS at once (a move aimed at something, a rider steering) is
 * {@link #snap}: unchanged from before this class.
 *
 * <p>The A/B seam is {@code rigturn}: {@code off} draws the carrier's yaw raw, as before.
 */
public final class RigTurn {

    /** Degrees off before a body at rest starts to turn. */
    public static final float START = 15f;
    /** The same while it is travelling: small, so it faces where it goes. */
    public static final float START_MOVING = 4f;
    /** A turn under way ends within this. */
    public static final float STOP = 1f;
    /** The share of what is left that one tick closes. */
    public static final float GAIN = 0.3f;
    /** The most one tick turns: 200 degrees a second, and only at the start of a large turn. */
    public static final float MAX = 10f;
    /**
     * The same while it TRAVELS. A walker that turns back along its path must not walk backwards
     * while its body comes round (filmed: the stock-walk turtle, a second of it at {@link #MAX}), so
     * a half turn on the move takes about half a second.
     */
    public static final float MAX_MOVING = 24f;
    /** The share of what is left that one tick closes while it travels. */
    public static final float GAIN_MOVING = 0.45f;
    /** The least one tick of a turn under way turns, so the tail of a turn is not endless. */
    public static final float MIN = 1.5f;

    static final boolean ON = !"off".equalsIgnoreCase(
            System.getProperty(com.moddingfromamod.ModdingFromAModMod.MODID + ".rigturn", "on"));

    private float drawn = Float.NaN;
    private boolean turning;

    /** The yaw last drawn, or NaN before the first. */
    public float drawn() {
        return drawn;
    }

    /** Take {@code yaw} at once: the first frame, an aimed move, a rider's steering. */
    public float snap(float yaw) {
        drawn = RigHeadYaw.wrap(yaw);
        turning = false;
        return drawn;
    }

    /** Stay as drawn this tick (the wanted yaw means nothing just now). */
    public float hold(float fallback) {
        if (Float.isNaN(drawn)) {
            return snap(fallback);
        }
        turning = false;
        return drawn;
    }

    /**
     * One tick towards {@code wanted}.
     *
     * @param moving is the creature travelling? Then the threshold is the small one
     * @return the yaw to draw, in {@code [-180, 180)}
     */
    public float step(float wanted, boolean moving) {
        if (Float.isNaN(drawn) || Float.isNaN(wanted)) {
            return Float.isNaN(wanted) ? drawn : snap(wanted);
        }
        float left = RigHeadYaw.wrap(wanted - drawn);
        float off = Math.abs(left);
        if (!turning && off < (moving ? START_MOVING : START)) {
            return drawn;
        }
        if (off <= STOP) {
            turning = false;
            drawn = RigHeadYaw.wrap(wanted);
            return drawn;
        }
        turning = true;
        float by = Math.min(off, Math.max(MIN,
                moving ? Math.min(MAX_MOVING, off * GAIN_MOVING) : Math.min(MAX, off * GAIN)));
        drawn = RigHeadYaw.wrap(drawn + Math.copySign(by, left));
        return drawn;
    }

    /**
     * Is a look at a point {@code dx, dy, dz} from the eye too near straight up or down for its
     * compass direction to mean anything? Within {@link #STEEP} degrees of vertical, a player
     * drifting a fraction of a block swings that direction right round.
     */
    public static boolean steep(double dx, double dy, double dz) {
        double flat = Math.sqrt(dx * dx + dz * dz);
        return Math.abs(dy) > 1.0e-6 && flat < Math.abs(dy) * STEEP_TAN;
    }

    /** Degrees from vertical inside which a look's direction is not followed. */
    public static final double STEEP = 25.0;
    private static final double STEEP_TAN = Math.tan(Math.toRadians(STEEP));
}
