package com.moddingfromamod.vanilla;

/**
 * <b>How a flying creature's carrier gets somewhere</b>. Pure.
 *
 * <p>A {@code flies} species rides a bat ({@code CarrierPool}), and a bat has no use for
 * navigation: its own AI picks a random spot every few ticks and nudges its velocity a tenth of
 * the way there. So "follow him" or "chase that" cannot be a path; it is a velocity, set every
 * tick, that outweighs the bat's own nudge nine to one.
 */
public final class RigFlight {

    private RigFlight() {
    }

    /** Blocks a tick at speed 1.0 (a bat's own dash is about this). */
    public static final double BASE_SPEED = 0.3;
    /** Close enough: stop steering and let it hover. */
    public static final double ARRIVED = 1.5;
    /** How far above a player a following flier hovers, so it is not in his face. */
    public static final double HOVER = 2.0;
    /** How much of the wanted velocity is taken each tick (the rest is what it had). */
    public static final double TURN = 0.35;

    /**
     * The velocity towards {@code (tx,ty,tz)} from {@code (x,y,z)}, eased from {@code (vx,vy,vz)}.
     * Zero wanted when already {@link #ARRIVED}, so it slows and hovers there.
     *
     * @param speed a multiple of {@link #BASE_SPEED}
     * @return {x, y, z}
     */
    public static double[] velocity(double x, double y, double z, double tx, double ty, double tz,
                                    double vx, double vy, double vz, double speed) {
        double dx = tx - x;
        double dy = ty - y;
        double dz = tz - z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double wx = 0;
        double wy = 0;
        double wz = 0;
        if (len > ARRIVED) {
            double s = BASE_SPEED * Math.max(0, speed) / len;
            wx = dx * s;
            wy = dy * s;
            wz = dz * s;
        }
        return new double[]{vx + (wx - vx) * TURN, vy + (wy - vy) * TURN, vz + (wz - vz) * TURN};
    }

    public static boolean arrived(double x, double y, double z, double tx, double ty, double tz) {
        double dx = tx - x;
        double dy = ty - y;
        double dz = tz - z;
        return dx * dx + dy * dy + dz * dz <= ARRIVED * ARRIVED;
    }
}
