package com.moddingfromamod.species;

import java.util.Random;

/**
 * <b>What a creature living an {@link Archetype} does next</b> — the decision, pure, so every
 * archetype's choices are a unit test. {@code CreatureLife} carries the decision out on the carrier.
 *
 * <p>A decision is taken when the last one is done (it arrived, or its time ran out): stand and look
 * about, wander to a spot near home (near its herd's middle, for a herd animal), flit, circle, perch,
 * or swim. Everything is relative to HOME — the leash's home (PetBehaviours), so an archetype never
 * wanders off its leash — and to the ground under it, so a flyer's height means height above the
 * ground, not above the sea.
 */
public final class ArchetypeLife {

    private ArchetypeLife() {
    }

    /** What it should do now. {@code x,y,z} is where to go (or look, for STAND); {@code ticks} how long. */
    public enum Act { STAND, WALK, FLY, PERCH, SWIM, TO_RIDER, PARK }

    public record Intent(Act act, double x, double y, double z, double speed, int ticks) {
    }

    /**
     * What it knows. {@code ground} is the ground's height under it; {@code groundAt} answers the same
     * for any (x, z), so a flight target can be put at a height over THAT ground; {@code perchY} is the
     * highest block near home (a glider's perch), NaN when there is none; {@code herdX/Z} the middle of
     * its own kind nearby, NaN when it is alone; {@code riderDist} how far its owner is (NaN when away).
     */
    public record World(double x, double y, double z, double homeX, double homeY, double homeZ,
                        double ground, java.util.function.DoubleBinaryOperator groundAt,
                        double perchX, double perchY, double perchZ,
                        double herdX, double herdZ, boolean inWater,
                        double riderX, double riderY, double riderZ, double riderDist,
                        boolean flies, long time) {
    }

    /** The next thing to do. */
    public static Intent next(Archetype a, World w, Random r) {
        Archetype.Life l = a.life();
        if (l.gait() == Archetype.Gait.PARKED) {
            // A vehicle stays where it was left, on the ground under it (a flying one comes down).
            return new Intent(Act.PARK, w.x(), w.ground(), w.z(), 0, l.wanderEvery());
        }
        boolean airborne = l.gait() == Archetype.Gait.FLIT || l.gait() == Archetype.Gait.GLIDE
                || (w.flies() && l.flyMax() > 0);
        // A mount or a pack animal comes over to its owner when he is about but not right by it.
        if (l.comesToRider() && !Double.isNaN(w.riderDist()) && w.riderDist() > 4 && w.riderDist() < 16
                && r.nextDouble() < 0.6) {
            // At its own full pace (it is coming over to HIM), for up to eight seconds.
            return new Intent(Act.TO_RIDER, w.riderX(), w.riderY(), w.riderZ(), Math.max(1.0, l.speed()), 160);
        }
        if (l.gait() == Archetype.Gait.SWIM) {
            return swim(l, w, r);
        }
        if (airborne) {
            return fly(a, l, w, r);
        }
        if (r.nextDouble() < l.pause()) {
            // Graze, look about: stand for a while, facing the player if he is close.
            int t = 40 + r.nextInt(Math.max(1, l.wanderEvery()));
            if (!Double.isNaN(w.riderDist()) && w.riderDist() <= l.lookRange()) {
                return new Intent(Act.STAND, w.riderX(), w.riderY() + 1.6, w.riderZ(), 0, t);
            }
            return new Intent(Act.STAND, w.x() + r.nextGaussian(), w.ground(), w.z() + r.nextGaussian(), 0, t);
        }
        double[] p = wanderSpot(l, w, r);
        return new Intent(Act.WALK, p[0], w.groundAt().applyAsDouble(p[0], p[1]), p[1], l.speed(),
                l.wanderEvery() + r.nextInt(Math.max(1, l.wanderEvery())));
    }

    /** A spot within its wander radius of home — pulled towards its herd's middle when it has one. */
    static double[] wanderSpot(Archetype.Life l, World w, Random r) {
        double cx = w.homeX(), cz = w.homeZ();
        if (l.herdRadius() > 0 && !Double.isNaN(w.herdX())) {
            cx = (cx + w.herdX() * 2) / 3;
            cz = (cz + w.herdZ() * 2) / 3;
        }
        double ang = r.nextDouble() * Math.PI * 2;
        double d = Math.sqrt(r.nextDouble()) * l.wanderRadius();
        return new double[] {cx + Math.cos(ang) * d, cz + Math.sin(ang) * d};
    }

    private static Intent fly(Archetype a, Archetype.Life l, World w, Random r) {
        double lo = l.flyMin() > 0 ? l.flyMin() : 2, hi = Math.max(lo, l.flyMax() > 0 ? l.flyMax() : 6);
        // A glider perches on the highest spot near home now and then, and sits there a while.
        if (l.perchEvery() > 0 && !Double.isNaN(w.perchY()) && r.nextInt(Math.max(1, l.perchEvery() / 20)) == 0) {
            return new Intent(Act.PERCH, w.perchX(), w.perchY() + 1, w.perchZ(), l.speed(), 160 + r.nextInt(160));
        }
        if (l.circleRadius() > 0) {
            // The next point round its circle: a steady loop over home, a little higher or lower each time.
            double ang = Math.atan2(w.z() - w.homeZ(), w.x() - w.homeX()) + 0.6;
            double cx = w.homeX() + Math.cos(ang) * l.circleRadius();
            double cz = w.homeZ() + Math.sin(ang) * l.circleRadius();
            double gy = w.groundAt().applyAsDouble(cx, cz);
            return new Intent(Act.FLY, cx, gy + lo + r.nextDouble() * (hi - lo), cz, l.speed(), l.wanderEvery());
        }
        // A flitter: a short hop through the air to a spot near home, low over the ground; lands now and then.
        double[] p = wanderSpot(l, w, r);
        double gy = w.groundAt().applyAsDouble(p[0], p[1]);
        if (r.nextDouble() < l.pause()) {
            return new Intent(Act.PERCH, p[0], gy, p[1], l.speed(), 40 + r.nextInt(60));
        }
        return new Intent(Act.FLY, p[0], gy + lo + r.nextDouble() * (hi - lo), p[1], l.speed(),
                l.wanderEvery() + r.nextInt(Math.max(1, l.wanderEvery())));
    }

    private static Intent swim(Archetype.Life l, World w, Random r) {
        if (!w.inWater()) {
            // Out of the water it does not wander the land: it stays put until somebody puts it back.
            return new Intent(Act.STAND, w.x(), w.y(), w.z(), 0, 40);
        }
        double[] p = wanderSpot(l, w, r);
        double depth = w.y() + (r.nextDouble() - 0.5) * 3;
        return new Intent(Act.SWIM, p[0], depth, p[1], l.speed(), l.wanderEvery() + r.nextInt(Math.max(1, l.wanderEvery())));
    }

    /**
     * <b>An archer's footwork</b>: with a target, it keeps between {@link #ARCHER_NEAR} and
     * {@link #ARCHER_FAR} blocks — backs off when it is closer, closes in when farther, and otherwise
     * strafes round it (the skeleton's {@code RangedBowAttackGoal}: 15 blocks, strafing). Returns the
     * spot to move to, or null to stand and shoot.
     */
    // NEAR above the ranged move's own minimum (Abilities.Kind.RANGED: 6), or it keeps its distance
    // and never shoots (measured, 2026-11-02: it held 5-6 blocks off a zombie and hurt it not once).
    public static final double ARCHER_NEAR = 7, ARCHER_FAR = 13;

    public static double[] keepDistance(double x, double z, double tx, double tz, boolean strafeLeft) {
        double dx = x - tx, dz = z - tz;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1e-6) {
            return new double[] {x + ARCHER_NEAR, z};
        }
        double ux = dx / d, uz = dz / d;
        if (d < ARCHER_NEAR) {
            return new double[] {tx + ux * (ARCHER_NEAR + 2), tz + uz * (ARCHER_NEAR + 2)};
        }
        if (d > ARCHER_FAR) {
            return new double[] {tx + ux * (ARCHER_FAR - 2), tz + uz * (ARCHER_FAR - 2)};
        }
        double s = strafeLeft ? 1 : -1;
        return new double[] {x - uz * 3 * s, z + ux * 3 * s};
    }

    /**
     * <b>A climber meets a wall</b>: walking into it, it goes up it (the spider's {@code onClimbable}).
     * The upward speed to give it this tick, or NaN to leave it alone.
     */
    public static final double CLIMB_SPEED = 0.2;

    public static double climb(boolean againstWall, boolean moving) {
        return againstWall && moving ? CLIMB_SPEED : Double.NaN;
    }

    /**
     * <b>A swooper's approach</b>: while far it flies at a point above its target, and dives at it once
     * close — the phantom's swoop. The height to aim at above the target.
     */
    public static double swoopHeight(double flatDistance) {
        return flatDistance > 5 ? 4 : 0;
    }
}
