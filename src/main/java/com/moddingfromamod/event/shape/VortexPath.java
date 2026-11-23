package com.moddingfromamod.event.shape;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * <b>Where a vortex's foot is at each tick</b> (EVENTS_PLAN §16.1). Pure and SEEDED: the same design at
 * the same spot walks the same way, so the camera ({@code EventBuild.reach}) can plan from the very path the motion
 * will take, and a test can assert it.
 *
 * <p>{@code FOLLOW} is the one path that depends on him, so it is not here as a schedule: {@link #chase} is its
 * one step, and the motion calls it with where he is.
 */
public final class VortexPath {

    /** A point on the ground plane. */
    public record At(double x, double z) {
        public double distanceTo(At o) {
            return Math.hypot(x - o.x, z - o.z);
        }
    }

    private final Vortex.Path path;
    private final At origin;
    /** Unit vector he was looking along (the way a front rolls); a line crosses it. */
    private final double hx;
    private final double hz;
    private final double speed;
    private final double ring;
    private final double tether;
    private final long seed;
    private final List<At> walk = new ArrayList<>();
    private double wanderHeading;
    private final double phase;

    /**
     * @param origin the aimed spot
     * @param hx,hz  the way he was looking (any length; normalised)
     * @param speed  blocks a tick
     * @param ring   a circle's radius
     * @param tether how far a wander may stray from the spot
     */
    public VortexPath(Vortex.Path path, At origin, double hx, double hz, double speed, double ring, double tether,
                      long seed) {
        this.path = path;
        this.origin = origin;
        double len = Math.hypot(hx, hz);
        this.hx = len < 1e-9 ? 0 : hx / len;
        this.hz = len < 1e-9 ? 1 : hz / len;
        this.speed = speed;
        this.ring = Math.max(2, ring);
        this.tether = Math.max(2, tether);
        this.seed = seed;
        Random r = new Random(seed);
        this.wanderHeading = r.nextDouble() * Math.PI * 2;
        this.phase = r.nextDouble() * Math.PI * 2;
    }

    public Vortex.Path path() {
        return path;
    }

    /**
     * Where the foot is at {@code tick} of a life of {@code life} ticks. A {@code LINE} crosses his view: it starts
     * half its run to his left and passes through the aimed spot at the middle of its life, so he watches the whole
     * swath go by. FOLLOW answers its start (the aimed spot); the motion moves it with {@link #chase}.
     */
    public At at(int tick, int life) {
        int t = Math.max(0, tick);
        return switch (path) {
            case STAY, FOLLOW -> origin;
            case LINE -> {
                // Across his view: his right is (-hz, hx) for Minecraft's look vector (x east, z south).
                double rx = -hz, rz = hx;
                double run = speed * life;
                double along = -run / 2 + speed * Math.min(t, life);
                yield new At(origin.x() + rx * along, origin.z() + rz * along);
            }
            case CIRCLE -> {
                double w = speed / ring;
                double a = phase + w * t;
                yield new At(origin.x() + Math.cos(a) * ring, origin.z() + Math.sin(a) * ring);
            }
            case WANDER -> wander(t);
        };
    }

    /** The unit way a LINE travels (his right); zero for any other path. */
    public double[] heading() {
        return path == Vortex.Path.LINE ? new double[] {-hz, hx} : new double[] {0, 0};
    }

    /** Where a LINE starts, for the camera's tracking shot. */
    public At lineStart(int life) {
        return at(0, life);
    }

    public double speed() {
        return speed;
    }

    /**
     * A smooth random walk: the heading drifts a little each tick (seeded), and once it is past its tether it
     * turns home, so a dust devil dances about where he aimed rather than leaving.
     */
    private At wander(int t) {
        if (walk.isEmpty()) {
            walk.add(origin);
        }
        // Extended in order and cached, from nothing but the seed's heading and phase: deterministic.
        while (walk.size() <= t) {
            int i = walk.size();
            At last = walk.get(i - 1);
            // Smooth drift: two slow sines with seeded phases, never a jitter.
            double drift = 0.06 * Math.sin(i * 0.045 + phase) + 0.035 * Math.sin(i * 0.11 + phase * 2.3);
            wanderHeading += drift;
            double dx = last.x() - origin.x(), dz = last.z() - origin.z();
            double out = Math.hypot(dx, dz);
            if (out > tether * 0.7) {
                // Turn toward home, harder the further out it is.
                double home = Math.atan2(-dz, -dx);
                double diff = Math.atan2(Math.sin(home - wanderHeading), Math.cos(home - wanderHeading));
                double pull = Math.min(1, (out - tether * 0.7) / (tether * 0.3)) * 0.12;
                wanderHeading += diff * pull;
            }
            walk.add(new At(last.x() + Math.cos(wanderHeading) * speed, last.z() + Math.sin(wanderHeading) * speed));
        }
        return walk.get(t);
    }

    /**
     * One FOLLOW step: from {@code from} toward {@code him} at {@code speed}, stopping {@code gap} short. He can
     * outrun a little one; a gigantic one keeps up.
     */
    public static At chase(At from, At him, double speed, double gap) {
        double dx = him.x() - from.x(), dz = him.z() - from.z();
        double d = Math.hypot(dx, dz);
        if (d <= gap || d < 1e-9) {
            return from;
        }
        double step = Math.min(speed, d - gap);
        return new At(from.x() + dx / d * step, from.z() + dz / d * step);
    }

    /** The box (minX, minZ, maxX, maxZ) the foot covers from tick {@code from} to {@code to}, for the camera. */
    public double[] bounds(int from, int to, int life) {
        double x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (int t = Math.max(0, from); t <= to; t += 2) {
            At a = at(t, life);
            x0 = Math.min(x0, a.x());
            z0 = Math.min(z0, a.z());
            x1 = Math.max(x1, a.x());
            z1 = Math.max(z1, a.z());
        }
        return new double[] {x0, z0, x1, z1};
    }
}
