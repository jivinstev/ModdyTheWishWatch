package com.moddingfromamod.testing;

import java.util.List;

/**
 * <b>The script a wished creature is put through after a headless grant</b> (chunk 0) — pure,
 * so its shape is unit-tested and the server side only executes it.
 *
 * <p>Each phase sets up one situation a behaviour wish can be about, and {@code
 * tools/behaviour-judge.py} reads what happened in it: does it hurt the monsters only while it
 * WALKS (not as a constant aura, the shipped T-rex's miss), does it carry its rider and never hurt
 * him, does it leap when he jumps, does it make a sound when it is hit. Enemies are fresh at the
 * start of every phase, so a phase's damage is that phase's.
 *
 * <p>Ticks, not seconds: the exercise runs on a dedicated server, which ticks at 20 a second, and
 * what it measures (a cooldown, a ramp, a jump) is itself timed in ticks.
 */
public final class ExercisePlan {

    /** What the harness does to the creature (and the stand-in child) during a phase. */
    public enum Drive {
        /** Nothing: whatever it does on its own. */
        NONE,
        /**
         * HELD STILL among the monsters: navigation stopped and no sideways motion, so "only when it
         * walks" can be told from "all the time". The idle phase never was still — creatures roamed
         * 16-20 blocks in it (chunk 3, harness fault 17).
         */
        STILL,
        /** Walks it, from the arena centre, out along +z past the monsters and back. */
        WALK,
        /** From the arena centre, the child climbs on and rides it back and forth through the monsters. */
        RIDE,
        /** Mounted; the child presses jump a few times (the input a real rider sends). */
        RIDER_JUMP,
        /** Mounted; the child HOLDS jump and forward (how a flying mount is told to climb). */
        FLY,
        /** Off it, standing near; the child jumps a few times. */
        CHILD_JUMP,
        /** He punches it, then it is topped up to a quarter of its health lost. */
        HURT,
        /** It is killed. */
        DEATH
    }

    public record Phase(String name, int ticks, Drive drive) {
    }

    /** The order matters only at the ends: settle first, and the death last (it ends the creature). */
    public static final List<Phase> PHASES = List.of(
            new Phase("settle", 40, Drive.NONE),
            new Phase("idle", 200, Drive.NONE),
            new Phase("still", 100, Drive.STILL),
            new Phase("walk", 240, Drive.WALK),
            new Phase("ride", 200, Drive.RIDE),
            new Phase("rider_jump", 120, Drive.RIDER_JUMP),
            new Phase("fly", 120, Drive.FLY),
            new Phase("child_jump", 120, Drive.CHILD_JUMP),
            new Phase("hurt", 100, Drive.HURT),
            new Phase("death", 60, Drive.DEATH));

    /**
     * <b>The filming script</b>: a creature's own moves, watched. Mostly standing among the
     * monsters — where every sculpt move fires — then hit, so a heal shows, then killed.
     */
    public static final List<Phase> FILM = List.of(
            new Phase("settle", 40, Drive.NONE),
            new Phase("idle", 400, Drive.NONE),
            new Phase("hurt", 160, Drive.HURT),
            new Phase("death", 60, Drive.DEATH));

    /** A film of what it does as it walks (on_step): standing still first, so the difference shows. */
    public static final List<Phase> FILM_WALK = List.of(
            new Phase("settle", 40, Drive.NONE),
            new Phase("still", 80, Drive.STILL),
            new Phase("walk", 240, Drive.WALK));

    /** A film of him riding it, then jumping it (his jump, its landing): no hurt, no death. */
    public static final List<Phase> FILM_RIDE = List.of(
            new Phase("settle", 40, Drive.NONE),
            new Phase("ride", 140, Drive.RIDE),
            new Phase("rider_jump", 160, Drive.RIDER_JUMP));

    /** A film of its VOICE: held still while it is about (its own footfalls buried a giant's grunts), then hit, then killed. */
    public static final List<Phase> FILM_VOICE = List.of(
            new Phase("settle", 40, Drive.NONE),
            new Phase("idle", 360, Drive.STILL),
            new Phase("hurt", 160, Drive.HURT),
            new Phase("death", 60, Drive.DEATH));

    /**
     * A film of how it LIVES: left to itself for half a minute, nothing driving it, so what it
     * does is its archetype's — a dragon circling and perching, a pack trotting together, a mount
     * coming over to him.
     */
    public static final List<Phase> FILM_LIFE = List.of(
            new Phase("settle", 40, Drive.NONE),
            new Phase("life", 600, Drive.NONE));

    /**
     * A film of a THROW: held still with one monster at the near end of the ranged band
     * ({@link #THROW_ENEMY}), watched side on ({@link #THROW_CAMERA}), so every throw crosses the
     * frame from the hand to the monster — the shot the owner could not see at close range.
     */
    public static final List<Phase> FILM_THROW = List.of(
            new Phase("settle", 40, Drive.NONE),
            new Phase("throw", 520, Drive.STILL));

    private static volatile List<Phase> active = PHASES;

    /** Which script runs: {@link #PHASES}, the default, or {@link #FILM}. */
    public static void use(List<Phase> phases) {
        active = phases;
    }

    public static List<Phase> active() {
        return active;
    }

    /** Where things stand, in blocks from the creature's spot (x, z). */
    public static final double[][] ENEMIES = {{3, 1}, {-3, 5}, {3, 9}};
    /** One monster out of melee range, for the moves that want a distant target (shoot, charge, blink). */
    public static final double[] FAR_ENEMY = {0, -12};
    public static final double[] FRIEND = {-6, -2};
    public static final double[] CHILD = {7, 0};
    /**
     * The throw film's one monster: 7.5 blocks off, near the short end of where a ranged move fires
     * (6). Not 6.5: the creature stands a little off its spot, and at 6.5 it was 5.7 from the
     * monster in one take and never threw.
     */
    public static final double[] THROW_ENEMY = {0, -7.5};
    /** The throw film's camera: side on to the line from the creature to that monster. */
    public static final double[] THROW_CAMERA = {8, -3.75};
    /** How far the walk goes along +z before it turns back. */
    public static final double WALK_TO = 10;
    /** The ticks into RIDER_JUMP / CHILD_JUMP at which a jump is pressed. */
    public static final int[] JUMPS_AT = {10, 50, 90};
    /** The tick into HURT at which it is hit, and into DEATH at which it is killed. */
    public static final int HIT_AT = 5;
    public static final int KILL_AT = 5;
    /** The arena cleared round it: a floor, and air above, so terrain is never the finding. */
    public static final int ARENA_RADIUS = 22;
    public static final int ARENA_HEIGHT = 20;

    private ExercisePlan() {
    }

    public static int totalTicks() {
        return active.stream().mapToInt(Phase::ticks).sum();
    }

    /** The phase running at {@code tick} since the exercise began, or null once it is over. */
    public static Phase at(int tick) {
        int t = tick;
        for (Phase p : active) {
            if (t < p.ticks()) {
                return p;
            }
            t -= p.ticks();
        }
        return null;
    }

    /** Ticks into the phase running at {@code tick}. */
    public static int into(int tick) {
        int t = tick;
        for (Phase p : active) {
            if (t < p.ticks()) {
                return t;
            }
            t -= p.ticks();
        }
        return -1;
    }

    /** True on the ticks a jump is pressed in a jumping phase. */
    public static boolean jumpNow(Phase p, int into) {
        if (p == null || (p.drive() != Drive.RIDER_JUMP && p.drive() != Drive.CHILD_JUMP)) {
            return false;
        }
        for (int at : JUMPS_AT) {
            if (into == at) {
                return true;
            }
        }
        return false;
    }

    /** Where the walk wants it at {@code into}: out to WALK_TO over the first half, then back. */
    public static double walkZ(int into, int ticks) {
        return into < ticks / 2 ? WALK_TO : 0.0;
    }
}
