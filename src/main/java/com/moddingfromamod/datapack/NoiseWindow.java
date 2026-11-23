package com.moddingfromamod.datapack;

/**
 * <b>One wish datapack's noise over the last {@value #WINDOW} ticks</b> — the pure half of
 * {@link NoiseMeter}. A tick is LOUD when the pack played a sound or a big particle burst in it.
 * A pack loud on at least half the window is THROTTLED: from then on it may make one loud emission
 * every {@value #ONE_EVERY} ticks; everything else it does is untouched. It is let go again once it
 * is loud on under a quarter (it keeps being counted while throttled — by what it TRIED to emit —
 * so a pack that calms down is noticed).
 *
 * <p>Why these numbers: a roar every 5 s, a stomp on landing, a chomp while something is being
 * bitten are all far under half the ticks; the T-Rex's knockback telegraph was every one.
 * Measured before shipping over every production and starter pack: only that one trips it.
 */
public final class NoiseWindow {

    /** 30 seconds of ticks. */
    public static final int WINDOW = 600;
    /** Loud on this many of the window's ticks is a drone. */
    public static final int ON_AT = WINDOW / 2;
    /** ...and under this many lets it go. */
    public static final int OFF_UNDER = WINDOW / 4;
    /** A throttled pack's allowance: one loud emission this often. */
    public static final int ONE_EVERY = 20;

    private final boolean[] loud = new boolean[WINDOW];
    private long last = Long.MIN_VALUE;
    private int count;
    private boolean throttled;
    private long lastAllowed = Long.MIN_VALUE;

    /**
     * A loud emission this pack is about to make at {@code tick}. Returns whether it may: always,
     * unless the pack is throttled and has had its one for this {@value #ONE_EVERY}-tick stretch.
     */
    public boolean emit(long tick) {
        advance(tick);
        int slot = (int) Math.floorMod(tick, (long) WINDOW);
        if (!loud[slot]) {
            loud[slot] = true;
            count++;
        }
        if (!throttled && count >= ON_AT) {
            throttled = true;
        } else if (throttled && count < OFF_UNDER) {
            throttled = false;
        }
        if (!throttled) {
            return true;
        }
        if (lastAllowed == Long.MIN_VALUE || tick - lastAllowed >= ONE_EVERY) {
            lastAllowed = tick;
            return true;
        }
        return lastAllowed == tick;          // a second sound in the tick that was allowed
    }

    /** Clear the slots for every tick that passed with nothing loud in it. */
    private void advance(long tick) {
        if (last == Long.MIN_VALUE || tick - last >= WINDOW) {
            java.util.Arrays.fill(loud, false);
            count = 0;
        } else {
            for (long t = last + 1; t <= tick; t++) {
                int slot = (int) Math.floorMod(t, (long) WINDOW);
                if (loud[slot]) {
                    loud[slot] = false;
                    count--;
                }
            }
        }
        if (last == Long.MIN_VALUE || tick > last) {
            last = tick;
        }
        if (throttled && count < OFF_UNDER) {
            throttled = false;
        }
    }

    public boolean throttled() {
        return throttled;
    }

    /** How many of the last {@value #WINDOW} ticks were loud. */
    public int loudTicks() {
        return count;
    }
}
