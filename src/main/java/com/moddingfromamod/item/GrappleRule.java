package com.moddingfromamod.item;

/**
 * <b>The fling, the pure half.</b> The velocity that carries him from where he stands
 * to where the hook landed, in one arc — solved WITH vanilla's air physics for a player: each tick his
 * horizontal speed is multiplied by {@link #AIR_DRAG} and his vertical speed has {@link #GRAVITY}
 * taken off and is multiplied by {@link #FALL_DRAG}.
 *
 * <p>⚠ The first version ignored the drag (speed = distance / time), and the film caught it: a hook
 * 10.5 blocks away pulled him 7.3 — exactly what 0.91 per tick leaves of it — into the side of the
 * house instead of onto its roof. {@code GrappleRuleTest} now flies the answer through the same
 * physics and asserts where he lands.
 */
public final class GrappleRule {

    static final double GRAVITY = 0.08;
    static final double AIR_DRAG = 0.91;
    static final double FALL_DRAG = 0.98;
    /** The fastest he is ever thrown, in blocks per tick. */
    public static final double MAX_SPEED = 3.2;
    /** How far a hook may pull him from. */
    public static final double MAX_REACH = 48;
    /** How far above the point he arrives, so he comes down onto a ledge rather than into it. */
    static final double OVER = 1.5;
    /** The first tick from standing: ground friction (a grass block's 0.6) times the air's 0.91. */
    static final double GROUND_DRAG = 0.6 * 0.91;

    private GrappleRule() {
    }

    /** The flight time for a distance: long enough to arc, short enough to feel like a yank. */
    static int flightTicks(double flat) {
        return (int) Math.max(10, Math.min(30, Math.round(flat / 0.9)));
    }

    /** {vx, vy, vz} to arrive at (tx,ty+OVER,tz) after flightTicks; null if out of reach or on him. */
    public static double[] velocity(double x, double y, double z, double tx, double ty, double tz) {
        return velocity(x, y, z, tx, ty, tz, false);
    }

    /**
     * As above, knowing whether he starts on the ground: the SECOND film fell 1.4 blocks short
     * because the first tick of a standing launch loses 45% of its speed to the ground, not 9%.
     */
    public static double[] velocity(double x, double y, double z, double tx, double ty, double tz,
                                    boolean onGround) {
        double dx = tx - x, dy = ty - y, dz = tz - z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat + Math.abs(dy) < 1.5 || Math.sqrt(flat * flat + dy * dy) > MAX_REACH) {
            return null;
        }
        int t = flightTicks(flat);
        // Horizontal: d = v (1 - k^t) / (1 - k) in the air; from the ground the first step is full and
        // then the ground's drag, then the air's.
        double reach = onGround ? 1 + GROUND_DRAG * (1 - Math.pow(AIR_DRAG, t - 1)) / (1 - AIR_DRAG)
                : (1 - Math.pow(AIR_DRAG, t)) / (1 - AIR_DRAG);
        double vx = dx / reach, vz = dz / reach;
        // Vertical: height after t ticks is linear in the launch speed — rise(v) = a*v - b.
        double b = -rise(0, t), a = rise(1, t) + b;
        double vy = (dy + OVER + b) / a;
        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (speed > MAX_SPEED) {
            double k = MAX_SPEED / speed;
            vx *= k;
            vy *= k;
            vz *= k;
        }
        return new double[]{vx, vy, vz};
    }

    /** How high a launch of {@code vy} is after {@code t} ticks of a player's air physics. */
    static double rise(double vy, int t) {
        double y = 0;
        for (int i = 0; i < t; i++) {
            y += vy;
            vy = (vy - GRAVITY) * FALL_DRAG;
        }
        return y;
    }

    /** Fly a launch through the same physics for {@code t} ticks: {x, y, z} travelled. For the test. */
    static double[] fly(double[] v, int t) {
        return fly(v, t, false);
    }

    static double[] fly(double[] v, int t, boolean onGround) {
        double x = 0, y = 0, z = 0, vx = v[0], vy = v[1], vz = v[2];
        for (int i = 0; i < t; i++) {
            x += vx;
            y += vy;
            z += vz;
            double k = i == 0 && onGround ? GROUND_DRAG : AIR_DRAG;
            vx *= k;
            vz *= k;
            vy = (vy - GRAVITY) * FALL_DRAG;
        }
        return new double[]{x, y, z};
    }
}
