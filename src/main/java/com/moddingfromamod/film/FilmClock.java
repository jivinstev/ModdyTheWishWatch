package com.moddingfromamod.film;

/**
 * <b>A modded client's clock for a film path: the server's play ticks, smooth between frames</b>
 * (Minecraft-free; the smooth film camera, 2026-11-16).
 *
 * <p>The server evaluates a {@link CameraKeys} path once a tick at {@code (gameTime − start) × 50 ms}.
 * A modded client draws it every frame instead, at {@code (ticks + partialTick) × 50 ms}, so the picture
 * moves at the frame rate rather than in 20 Hz steps. Which "ticks"? Not the client's game time as it
 * stands: the server re-sends its game time once a second, and a packet that lands on the other side
 * of a tick boundary moves the client's clock by a whole tick, which on the picture is exactly the
 * step this exists to remove. So the clock counts its OWN ticks, one per client tick, and only LEANS
 * toward the game time ({@link #LEAN} of the difference a tick, never more than {@link #MAX_LEAN}): a one-tick wobble or a
 * server stall it catches up on is absorbed as at most a 5% change of speed nobody can see, and only a
 * disagreement of {@link #SNAP_TICKS} or more (a {@code /tick freeze}, a sleep, a re-play) is a snap.
 *
 * <p>{@link #at} never runs backwards: a frame drawn just after a tick whose lean was negative would
 * otherwise go back a hair, which is a judder too.
 */
public final class FilmClock {

    /** The share of the game-time error taken back each tick. */
    static final double LEAN = 0.05;

    /**
     * The most the lean may change the clock's speed: 6%, which nobody sees (and above a 5%-slow server, so it is still followed)., teaser take T7-c: eight wishes
     * built at once and a server tick took 0.69 s; the server's time fell 14 ticks behind, the clock (then snapping at
     * 3 ticks) jumped back three times, and the film stuttered. A film is timed to a voice that does not wait for the
     * server, so a stall the server then catches up on is ridden out at nearly constant speed.
     */
    static final double MAX_LEAN = 0.06;

    /** An error this many ticks or more is not a stall but a freeze, a sleep or a re-play: the clock jumps. */
    static final double SNAP_TICKS = 100;

    private final long startGameTime;
    private double elapsed = Double.NaN;
    private double lastAt = Double.NEGATIVE_INFINITY;
    private int snaps;

    /** @param startGameTime the server level's game time on the path's play tick 0 */
    public FilmClock(long startGameTime) {
        this.startGameTime = startGameTime;
    }

    /** One client tick has run, and the client level's game time is now {@code gameTime}. */
    public void tick(long gameTime) {
        double want = gameTime - startGameTime;
        if (Double.isNaN(elapsed)) {
            elapsed = want;
            return;
        }
        elapsed += 1;
        double err = want - elapsed;
        if (Math.abs(err) >= SNAP_TICKS) {
            elapsed = want;
            lastAt = Double.NEGATIVE_INFINITY;   // a snap is a jump on purpose; let it go back too
            snaps++;
        } else {
            elapsed += Math.max(-MAX_LEAN, Math.min(MAX_LEAN, err * LEAN));
        }
    }

    /** Whether the clock has been told the game time at least once. */
    public boolean started() {
        return !Double.isNaN(elapsed);
    }

    /**
     * Play ticks (fractional) at this frame: the counted ticks plus the frame's {@code partialTick}
     * (0 ≤ p &lt; 1), never less than the last frame's. Never negative.
     */
    public double at(double partialTick) {
        if (Double.isNaN(elapsed)) {
            return 0;
        }
        double t = Math.max(0, elapsed + partialTick);
        if (t < lastAt) {
            t = lastAt;
        }
        lastAt = t;
        return t;
    }

    /** The path's milliseconds at this frame: {@link #at} × 50. */
    public double ms(double partialTick) {
        return at(partialTick) * CameraKeys.MS_PER_TICK;
    }

    /** How many times the clock has jumped to the game time (for the log). */
    public int snaps() {
        return snaps;
    }
}
