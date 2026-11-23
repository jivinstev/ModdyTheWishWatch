package com.moddingfromamod.event.shape;

/**
 * <b>{@code countdown}: a clock that ends in something</b> (E2). Pure.
 *
 * <p>From a mod repo's castle self-destruct: long enough to be fair, short enough not to be boring;
 * milestone warnings early and EVERY second for the last ten, with a beep that rises — a warning
 * every second for a whole minute is spam, and a silent final ten seconds is a jump scare.
 */
public final class Countdown {

    public static final int MIN_SECONDS = 5;
    public static final int MAX_SECONDS = 120;
    public static final int TPS = 20;

    public final int seconds;

    public Countdown(int seconds) {
        this.seconds = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
    }

    public int ticks() {
        return seconds * TPS;
    }

    /** Seconds left at this age (rounded up), 0 at the end. */
    public int secondsLeft(int age) {
        return Math.max(0, (int) Math.ceil((ticks() - age) / (double) TPS));
    }

    /** Is this second worth announcing? The start, round milestones, then every one of the last ten. */
    public boolean announces(int left) {
        if (left <= 0) {
            return false;
        }
        return left == seconds || left <= 10 || left == 15 || left == 20 || left % 30 == 0;
    }

    /** The beep's pitch at this many seconds left: rises toward the end, within Minecraft's 0.5..2. */
    public float pitch(int left) {
        double t = 1.0 - Math.min(10, Math.max(0, left)) / 10.0;
        return (float) (0.6 + 1.4 * t);
    }
}
