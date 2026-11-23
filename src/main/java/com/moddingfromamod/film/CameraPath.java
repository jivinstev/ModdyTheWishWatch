package com.moddingfromamod.film;

/**
 * <b>Where a site-film camera is on each tick, and where it points</b> (Minecraft-free).
 *
 * <p>The first films were jostly: the onlooker was a player the server teleported whenever he drifted
 * and re-aimed EVERY tick at a target that itself bobbed (a creature's middle, a flying dragon), so
 * each tick's small error reached the screen as a shake and each teleport as a jump. The camera now
 * moves by EASING: every tick it closes a fixed fraction of the distance to where it should be, for
 * both its position and the point it looks at, so a target's jitter is filtered out and a new spot is
 * reached in a glide rather than a cut. A deliberate pan is the same thing with a moving target
 * ({@link #orbit}).
 */
public final class CameraPath {

    private CameraPath() {
    }

    /**
     * One eased point. The first {@link #step} snaps to the target, so a film does not open on a
     * camera flying in from the origin; after that each step closes {@code rate} of the gap.
     */
    public static final class Ease {
        private final double rate;
        private double x, y, z;
        private boolean set;

        /** {@code rate} in (0, 1]: 1 follows exactly; 0.05 takes about two seconds to settle. */
        public Ease(double rate) {
            if (!(rate > 0 && rate <= 1)) {
                throw new IllegalArgumentException("rate must be in (0, 1]: " + rate);
            }
            this.rate = rate;
        }

        public double[] step(double tx, double ty, double tz) {
            if (!set) {
                x = tx;
                y = ty;
                z = tz;
                set = true;
            } else {
                x += (tx - x) * rate;
                y += (ty - y) * rate;
                z += (tz - z) * rate;
            }
            return new double[] {x, y, z};
        }

        public boolean started() {
            return set;
        }
    }

    /**
     * Minecraft's yaw and pitch, in degrees, for an eye looking along (dx, dy, dz): yaw 0 faces +z
     * (south) and grows clockwise seen from above (90 faces -x, west); pitch is positive looking DOWN.
     */
    public static float[] yawPitch(double dx, double dy, double dz) {
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
        return new float[] {wrap(yaw), pitch};
    }

    static float wrap(float deg) {
        float d = deg % 360f;
        if (d >= 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    /**
     * A slow pan: the point ({@code px}, {@code pz}) turned about ({@code cx}, {@code cz}) by an angle
     * that runs from {@code -sweepDeg/2} to {@code +sweepDeg/2} as {@code t} goes from 0 to
     * {@code duration}, smoothly (it starts and ends at rest), and held at either end outside that.
     */
    public static double[] orbit(double cx, double cz, double px, double pz, double sweepDeg, double t, double duration) {
        double u = duration <= 0 ? 1 : Math.max(0, Math.min(1, t / duration));
        double s = u * u * (3 - 2 * u);   // smoothstep: no lurch at the start or the end
        double a = Math.toRadians(sweepDeg * (s - 0.5));
        double dx = px - cx, dz = pz - cz;
        double c = Math.cos(a), sn = Math.sin(a);
        return new double[] {cx + dx * c - dz * sn, cz + dx * sn + dz * c};
    }
}
