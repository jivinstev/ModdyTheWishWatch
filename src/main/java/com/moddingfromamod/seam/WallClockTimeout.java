package com.moddingfromamod.seam;

/**
 * <b>When a GameTest's {@code timeoutTicks} has really run out</b> — pure, for {@code GameTestWallClockMixin}.
 *
 * <p>A test times out once it is past its ticks AND past their length at 20 ticks a second (capped at
 * {@link #MOST_MS}). Measured on 1.21.8: one Gate B ran batches at 18, 95 and 174 ticks a second, and the release
 * smoke that failed {@code KeptIsNotAPropGameTest} ran its batch at ~240 — 1202 ticks in five seconds, while its
 * {@code finish_building} was still taking a 48-pose photograph in real time. A tick budget alone is therefore a
 * different length of time in every batch.
 */
public final class WallClockTimeout {
    /**
     * The longest the wall-clock floor ever holds a test: one minute. It only lengthens a test that would otherwise TIME
     * OUT, so it is paid on a red run, never a green one; kept at a minute so a case already paced to its own deadline
     * ({@code RealTime}, the Bedrock batch's 90 s) is not held twice as long when it fails.
     */
    public static final long MOST_MS = 60_000L;
    /** A tick at the rate a test's author means: 20 a second. */
    public static final long MS_PER_TICK = 50L;

    private WallClockTimeout() {
    }

    /** The least wall-clock time a test with this tick budget runs before it may time out. */
    public static long floorMs(int timeoutTicks) {
        return Math.min(MOST_MS, Math.max(0, timeoutTicks) * MS_PER_TICK);
    }

    /** Has a test that has run {@code ticks} ticks over {@code ranMs} of wall clock used up its budget? */
    public static boolean timedOut(int ticks, int timeoutTicks, long ranMs) {
        return ticks > timeoutTicks && ranMs >= floorMs(timeoutTicks);
    }
}
