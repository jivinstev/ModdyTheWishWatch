package com.moddingfromamod.vanilla;

import java.util.Set;

import com.moddingfromamod.species.Species;

/**
 * <b>What a creature's species asks it to hunt and to run from, on its rig carrier</b>
 *. Pure: no Minecraft here, so every row of the table is a unit test.
 *
 * <p>It is {@code WishMobEntity.rebuildGoals} as data. That method composed vanilla goals, and a
 * carrier cannot take goals: half the carriers run a {@code Brain}, and a cow has no attack damage
 * to hit with. So {@code RigInstincts} does the same job by steering the carrier's navigation and
 * landing its own hit, and this record says what to steer towards.
 *
 * <ul>
 *   <li><b>{@code flees}</b>: {@code coward} or {@code skittish}. Runs from a player who comes
 *       close, and from whatever just hurt it ({@code PanicGoal}'s trigger);</li>
 *   <li><b>{@code fights}</b>: {@code guardian} and {@code hunter} always; {@code coward} and
 *       {@code companion} never; {@code wild} when it is hostile ({@link Species#isHostile}). A
 *       fighter hits back whoever hurts it ({@code HurtByTargetGoal});</li>
 *   <li><b>what it hunts</b> on sight: a {@code guardian} hunts monsters and never a player (the
 *       iron golem's contract); a {@code hunter} hunts players and animals; any other fighter
 *       hunts players;</li>
 *   <li><b>{@code leaps}</b>: a fighter with the {@code leaps} power pounces
 *       ({@code LeapAtTargetGoal}).</li>
 * </ul>
 */
public record RigInstinct(boolean flees, boolean fights, boolean huntsPlayers,
                          boolean huntsMonsters, boolean huntsAnimals, boolean leaps) {

    public static final RigInstinct NONE = new RigInstinct(false, false, false, false, false, false);

    /** How far it looks for something to hunt ({@code NearestAttackableTargetGoal}'s 16). */
    public static final double SIGHT = 16.0;
    /** How close a player comes before a skittish creature runs. */
    public static final double FLEE_START = 6.0;
    /** How far it runs each time it decides to. */
    public static final double FLEE_DISTANCE = 10.0;
    /** A melee mob's reach past its own box, horizontally ({@code Mob.getAttackBoundingBox}). */
    public static final double REACH = 0.828;
    /**
     * A target it cannot get to — behind a wall, up a ledge, across a fence — is given up after this
     * long with no step nearer, and left alone for {@link #GIVE_UP_FOR} (a bruiser stood 2 blocks
     * from a husk it could not touch for the last 11 seconds of every film, at every speed).
     */
    public static final int GIVE_UP_TICKS = 60, GIVE_UP_FOR = 200;
    public static final double GIVE_UP_PROGRESS = 0.5;

    /** Out of reach and no nearer than its best by {@link #GIVE_UP_PROGRESS} for {@link #GIVE_UP_TICKS}. */
    public static boolean givesUp(double best, double now, long ticksSinceBest) {
        return now > best - GIVE_UP_PROGRESS && ticksSinceBest >= GIVE_UP_TICKS;
    }

    /** Ticks between two hits ({@code MeleeAttackGoal}'s 20). */
    public static final int HIT_EVERY = 20;
    /** A pounce only from this close, and no closer ({@code LeapAtTargetGoal}: 2 to 4 blocks). */
    public static final double LEAP_MIN = 2.0;
    public static final double LEAP_MAX = 4.0;
    /** The pounce's lift ({@code LeapAtTargetGoal} as {@code WishMobEntity} built it). */
    public static final double LEAP_Y = 0.4;
    /** Chase and flee speed, as multiples of the carrier's own (Melee 1.0 plus a little; Panic 1.4). */
    public static final double CHASE_SPEED = 1.2;
    public static final double FLEE_SPEED = 1.4;
    public static RigInstinct of(Species.Base base, Set<Species.Power> powers) {
        Set<Species.Power> p = powers == null ? Set.of() : powers;
        Species.Base b = base == null ? Species.Base.WILD : base;
        boolean flees = b == Species.Base.COWARD || p.contains(Species.Power.SKITTISH);
        // Species.isHostile, restated on the parts it is made of so a null species cannot throw.
        boolean hostile = !p.contains(Species.Power.FRIENDLY) && !p.contains(Species.Power.SKITTISH);
        boolean fights = switch (b) {
            case GUARDIAN, HUNTER -> true;
            case COWARD, COMPANION -> false;
            case WILD -> hostile;
        };
        if (!fights) {
            return new RigInstinct(flees, false, false, false, false, false);
        }
        boolean players = b != Species.Base.GUARDIAN;
        boolean monsters = b == Species.Base.GUARDIAN;
        boolean animals = b == Species.Base.HUNTER;
        return new RigInstinct(flees, true, players, monsters, animals,
                p.contains(Species.Power.LEAPS));
    }

    public static RigInstinct of(Species sp) {
        if (sp != null && sp.archetype() == com.moddingfromamod.species.Archetype.VEHICLE) {
            return NONE;   //: a car neither hunts nor runs, whatever its temperament says
        }
        return sp == null ? NONE : of(sp.base(), sp.powers());
    }

    /**
     * <b>The instinct a REAL mob's behaviour profile asks for</b>. The same table as
     * {@link #of}, with one difference: {@code wild} (or no base at all) adds NO fighting. On a rig
     * carrier "wild" is the carrier's stand-in for a species' nature — a hostile species must be
     * made to hunt, because its cow or horse never would. A real mob already HAS its nature: a
     * zombie hunts on its own and a cow never does, so the shared layer laying "hunts players" over
     * a profiled cow that was only made fireproof would be inventing a fight nobody asked for. A real
     * mob therefore gets the shared fighting only from an explicitly named guardian or hunter, and
     * still runs from a {@code skittish} or {@code coward} profile.
     */
    public static RigInstinct forRealMob(Species.Base base, Set<Species.Power> powers) {
        if (base == null || base == Species.Base.WILD) {
            boolean flees = powers != null && powers.contains(Species.Power.SKITTISH);
            return flees ? new RigInstinct(true, false, false, false, false, false) : NONE;
        }
        return of(base, powers);
    }

    public boolean isEmpty() {
        return !flees && !fights;
    }

    /** Does it look for anything to hunt, as opposed to only hitting back? */
    public boolean hunts() {
        return huntsPlayers || huntsMonsters || huntsAnimals;
    }

    /**
     * Where to run to: {@link #FLEE_DISTANCE} on from {@code self}, straight away from the threat.
     * A threat standing exactly on it gives a direction anyway (east), rather than NaN.
     */
    public static double[] awayFrom(double sx, double sz, double tx, double tz) {
        double dx = sx - tx;
        double dz = sz - tz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        return new double[]{sx + dx / len * FLEE_DISTANCE, sz + dz / len * FLEE_DISTANCE};
    }

    /** Is the target at pouncing distance (centre to centre, horizontally)? */
    public static boolean canLeap(double distance) {
        return distance >= LEAP_MIN && distance <= LEAP_MAX;
    }

    /**
     * The pounce, as {@code LeapAtTargetGoal.start} computes it: towards the target at 0.4 a tick
     * plus a fifth of the motion it already had, and {@link #LEAP_Y} up.
     *
     * @return {x, y, z} velocity
     */
    public static double[] leap(double dx, double dz, double mx, double mz) {
        double len = Math.sqrt(dx * dx + dz * dz);
        double ux = len < 1e-6 ? 0 : dx / len;
        double uz = len < 1e-6 ? 0 : dz / len;
        return new double[]{ux * 0.4 + mx * 0.2, LEAP_Y, uz * 0.4 + mz * 0.2};
    }

    /**
     * The hit a species lands: its own {@code damage}, as {@code WishMobEntity} set its attack
     * attribute (a species that leaves it out reads 3). A zero stays zero.
     */
    public static float hit(double speciesDamage) {
        return (float) Math.max(0, speciesDamage);
    }
}
