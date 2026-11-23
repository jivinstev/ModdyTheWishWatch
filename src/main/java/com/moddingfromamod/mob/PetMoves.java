package com.moddingfromamod.mob;

/**
 * <b>The numbers behind a pet's moves</b>: when a follower walks, when it gives up walking and
 * hops to him, and which way a ridden mob goes for the keys he is holding. Pure, so every rule is a
 * unit test; {@link PetBehaviours} only carries them out.
 */
public final class PetMoves {

    private PetMoves() {
    }

    /** Closer than this and a follower stays put. A pet that stands ON him is not following. */
    public static final double FOLLOW_CLOSE = 3.0;

    /**
     * Further than this and it hops to him, the way a tamed wolf does. Far enough that he sees it
     * walk most of the time; near enough that a pet does not get lost behind a hill.
     */
    public static final double FOLLOW_TELEPORT = 16.0;

    /**
     * as near as it needs to get. A follower whose walk ended further than this — he climbed
     * something it cannot, a fence or a drop is between them — and that has got no nearer for
     * {@link #STUCK_TICKS} hops to him, rather than standing a few blocks off for good: the hop was
     * kept for past {@link #FOLLOW_TELEPORT}, so a walk that ENDED short was retried every few ticks
     * for ever. Between {@link #FOLLOW_CLOSE} and this it is with him, so it never hops for that.
     */
    public static final double FOLLOW_NEAR_ENOUGH = FOLLOW_CLOSE + 1.5;

    /**
     * Has following stopped getting it to him? Walking ({@link #follow} says WALK), further than
     * {@link #FOLLOW_NEAR_ENOUGH}, and no nearer than its best by {@link #STUCK_PROGRESS} for
     * {@link #STUCK_TICKS} — the leash's own rule ({@link #stuck}).
     */
    public static boolean followStuck(double best, double now, long ticksSinceBest) {
        return now > FOLLOW_NEAR_ENOUGH && follow(now) == Follow.WALK && stuck(best, now, ticksSinceBest);
    }

    /** How far a finder looks, in blocks, sideways and up/down. */
    public static final int FIND_RADIUS = 10;
    public static final int FIND_HEIGHT = 6;

    /** What a follower does at this distance from its owner. */
    public enum Follow { STAY, WALK, HOP }

    public static Follow follow(double distance) {
        if (distance < FOLLOW_CLOSE) {
            return Follow.STAY;
        }
        return distance > FOLLOW_TELEPORT ? Follow.HOP : Follow.WALK;
    }

    /**
     * past this a staying mob is put back on its spot rather than walked there — it fell off
     * the castle, or something carried it off, and a walk home across the map is not "staying".
     */
    public static final double HOME_TELEPORT = 24.0;

    /**
     * What a staying mob does this far (sideways) from home, allowed {@code radius} blocks: within
     * it (and a little slack, so it does not shuffle at the edge) it is free; beyond, it walks back.
     */
    public static Follow home(double distance, int radius) {
        if (distance <= radius + 1.0) {
            return Follow.STAY;
        }
        return distance > HOME_TELEPORT ? Follow.HOP : Follow.WALK;
    }

    /** Clamp on a ridden mob's speed in blocks per tick, whatever its own attribute says. */
    /**
     * <b>The leash</b>: a creature that is somebody's — a pet, a mount, a
     * friendly creature put in his world — but neither follows him nor stays on a spot, roams freely
     * within {@link #LEASH} blocks of its home and then comes back. Without it a flying mount's bat
     * flew off and was never seen again (wish {@code 31e57054}, 2026-11-01). Once it is coming back it
     * keeps coming until it is well inside ({@link #LEASH_BACK}), so it never dithers on the edge; far
     * past even that (it was knocked off a cliff, it chased something across the map) it is put back.
     */
    public static final double LEASH = 20.0, LEASH_BACK = 8.0, LEASH_TELEPORT = 64.0;

    public enum Leash { FREE, RETURN, HOP }

    /**
     * Coming home and getting no closer for this long, it is put back instead: a flyer flies
     * home in a straight line, so a wall or a roof between them held one there for good (measured: a
     * dragon pinned 17.8 blocks off against a wall, every run).
     */
    public static final int STUCK_TICKS = 60;
    public static final double STUCK_PROGRESS = 0.5;

    /** Stuck: returning, and no nearer than its best by {@link #STUCK_PROGRESS} for {@link #STUCK_TICKS}. */
    public static boolean stuck(double best, double now, long ticksSinceBest) {
        return now > best - STUCK_PROGRESS && ticksSinceBest >= STUCK_TICKS;
    }

    public static Leash leash(double distance, boolean returning) {
        if (distance > LEASH_TELEPORT) {
            return Leash.HOP;
        }
        if (distance > LEASH || (returning && distance > LEASH_BACK)) {
            return Leash.RETURN;
        }
        return Leash.FREE;
    }

    /**
     * <b>A mount waits for its rider</b>: a rideable creature nobody is on stops still — a
     * flyer comes down — while its rider is within {@link #WAIT_RADIUS} blocks with a free hand, the
     * hand that climbs on. It is free again the moment he walks off or picks something up, so a
     * dragon is alive the rest of the time and still always catchable.
     */
    public static final double WAIT_RADIUS = 6.0;

    public static boolean waitsForRider(boolean rideable, boolean ridden, double riderDistance,
                                        boolean freeHand) {
        return rideable && !ridden && freeHand && riderDistance <= WAIT_RADIUS;
    }

    public static final double RIDE_MIN = 0.15, RIDE_MAX = 0.45;

    /**
     * The ridden mob's velocity for the keys he holds.
     *
     * @param yawDeg     where he is looking, sideways (vanilla degrees: 0 = south, 90 = west)
     * @param pitchDeg   where he is looking, up and down (negative = up)
     * @param forward    +1 forward (W), -1 back (S), 0 neither
     * @param strafe     +1 left (A), -1 right (D), 0 neither
     * @param speed      the mob's own speed attribute; clamped to [{@link #RIDE_MIN}, {@link #RIDE_MAX}]
     * @param free3d     true for a flier or a swimmer in water: it climbs and dives where he looks
     * @return {x, y, z}; y is NaN when the mob keeps its own vertical motion (gravity, jumping)
     */
    public static double[] ride(float yawDeg, float pitchDeg, float forward, float strafe,
                                double speed, boolean free3d) {
        double v = Math.max(RIDE_MIN, Math.min(RIDE_MAX, speed));
        double yaw = Math.toRadians(yawDeg);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        // Vanilla's moveRelative, which is what makes W go where he looks.
        double x = (strafe * cos - forward * sin) * v;
        double z = (forward * cos + strafe * sin) * v;
        double y = Double.NaN;
        if (free3d) {
            double pitch = Math.toRadians(pitchDeg);
            double flat = Math.cos(pitch);
            x *= flat;
            z *= flat;
            y = -Math.sin(pitch) * forward * v;
        }
        return new double[] {x, y, z};
    }
}
