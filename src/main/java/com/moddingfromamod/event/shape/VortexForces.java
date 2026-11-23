package com.moddingfromamod.event.shape;

import com.moddingfromamod.event.EventScale;
import com.moddingfromamod.event.catching.CatchRules;

/**
 * <b>What a vortex does to one thing it caught, this tick</b> (EVENTS_PLAN §16.2). Pure.
 *
 * <p>The answer is ONE new velocity that REPLACES the old one (never added to it: {@link CatchRules}' rule, paid for
 * by an earlier mod's surf ride overshooting 20 blocks on a real client), plus whether it was let go this tick (thrown out of a
 * funnel's top, spat out of a drain) so the motion can give it a soft landing. Coordinates are relative to the
 * vortex's foot: {@code dx}, {@code dz} across, {@code dy} up from the foot (a drain's are negative: below the
 * surface).
 */
public final class VortexForces {

    private VortexForces() {
    }

    /** The result: the new velocity, how hard it was caught (0..1), and whether it was let go. */
    public record Push(double vx, double vy, double vz, double strength, boolean released) {
        public static final Push NONE = new Push(0, 0, 0, 0, false);

        public boolean caught() {
            return strength > 0;
        }
    }

    /** How fast the wall spins at its foot, blocks a tick — faster the bigger it is. */
    public static double spin(EventScale s) {
        return switch (s) {
            case LITTLE -> 0.30;
            case MEDIUM -> 0.42;
            case BIG -> 0.52;
            case GIGANTIC -> 0.60;
        };
    }

    /** Ticks a drain holds what it caught before it spits it out. */
    public static final int DRAIN_HOLD = 80;

    /**
     * A funnel. {@code liftable} false (too heavy for this size) gives only a sideways tug, never a lift: a little
     * dust devil cannot pick him up, it can only pull at him.
     */
    public static Push funnel(EventScale s, double dx, double dy, double dz, double vy, boolean liftable) {
        Vortex.Size z = Vortex.size(s);
        if (dy < -1.5 || dy > z.height() + 2) {
            return Push.NONE;
        }
        double h = Math.max(0, dy);
        double r = Vortex.funnelRadius(z, h);
        double d = Math.hypot(dx, dz);
        double edge = r + 1.5;
        if (d > edge) {
            return Push.NONE;
        }
        double strength = d <= r ? 1.0 : 1.0 - (d - r) / 1.5;
        double[] t = tangent(dx, dz, d);
        double[] n = inward(dx, dz, d);
        double around = spin(s) * strength;
        if (!liftable) {
            double tug = Math.min(0.18, around * 0.5);
            return new Push(t[0] * tug, vy, t[1] * tug, strength * 0.5, false);
        }
        if (h > z.height() * 0.85) {
            // Thrown out of the top, outward and a little up; it lands softly (the motion's job).
            double out = 0.75;
            return new Push(-n[0] * out + t[0] * around * 0.5, 0.35, -n[1] * out + t[1] * around * 0.5, strength, true);
        }
        double target = r * 0.75;
        double in = clamp((d - target) * 0.15, -0.3, 0.3);
        double up = Math.max(vy, CatchRules.MAX_LIFT * 0.8 * strength);
        return new Push(t[0] * around + n[0] * in, up, t[1] * around + n[1] * in, strength, false);
    }

    /**
     * A drain: swept round, drawn in and sunk; after {@link #DRAIN_HOLD} ticks caught, spat out up and away.
     * {@code depthBelow} is how far below the rim it is (positive down).
     */
    public static Push drain(EventScale s, double dx, double depthBelow, double dz, int ticksCaught, boolean liftable) {
        Vortex.Size z = Vortex.size(s);
        double depth = Vortex.drainDepth(z);
        if (depthBelow < -2 || depthBelow > depth + 1.5) {
            return Push.NONE;
        }
        double d = Math.hypot(dx, dz);
        double r = Vortex.drainRadius(z, Math.max(0, depthBelow));
        double edge = z.top() + 2;
        if (d > edge) {
            return Push.NONE;
        }
        double strength = d <= z.top() ? 1.0 : 1.0 - (d - z.top()) / 2.0;
        double[] t = tangent(dx, dz, d);
        double[] n = inward(dx, dz, d);
        double around = spin(s) * 0.8 * strength;
        if (!liftable) {
            double tug = Math.min(0.15, around * 0.5);
            return new Push(t[0] * tug, 0, t[1] * tug, strength * 0.5, false);
        }
        if (ticksCaught >= DRAIN_HOLD) {
            return new Push(-n[0] * 0.6, 1.1, -n[1] * 0.6, strength, true);
        }
        double in = clamp((d - r * 0.6) * 0.12, -0.25, 0.25);
        double down = depthBelow < depth ? -0.12 : 0.0;
        return new Push(t[0] * around + n[0] * in, down, t[1] * around + n[1] * in, strength, false);
    }

    /**
     * A spiral: blown round hard near the eye wall and gently far out; the eye is still. Light things near the wall
     * are lifted a little. Only the sideways speed is replaced unless it lifts.
     */
    public static Push spiral(EventScale s, double dx, double dy, double dz, double vy, boolean light) {
        double reach = Vortex.spiralRadius(s);
        double eye = Vortex.spiralEye(s);
        double d = Math.hypot(dx, dz);
        if (d > reach || d < eye * 0.8 || dy < -2 || dy > Vortex.spiralAltitude(s) + 4) {
            return Push.NONE;
        }
        // Strongest just outside the eye, a quarter of that at the edge.
        double wall = eye + (reach - eye) * 0.2;
        double strength = d <= wall ? Math.max(0.2, (d - eye * 0.8) / (wall - eye * 0.8))
                : 1.0 - 0.75 * (d - wall) / (reach - wall);
        strength = clamp(strength, 0, 1);
        double[] t = tangent(dx, dz, d);
        double[] n = inward(dx, dz, d);
        double around = spin(s) * 0.85 * strength;
        boolean lifts = light && strength > 0.6;
        double up = lifts ? Math.max(vy, 0.25 * strength) : vy;
        return new Push(t[0] * around + n[0] * 0.04, up, t[1] * around + n[1] * 0.04, strength, false);
    }

    /** Anticlockwise seen from above (x east, z south): the way a tornado turns north of the equator. */
    static double[] tangent(double dx, double dz, double d) {
        if (d < 1e-6) {
            return new double[] {1, 0};
        }
        return new double[] {dz / d, -dx / d};
    }

    static double[] inward(double dx, double dz, double d) {
        if (d < 1e-6) {
            return new double[] {0, 0};
        }
        return new double[] {-dx / d, -dz / d};
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
