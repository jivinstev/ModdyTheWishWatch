package com.moddingfromamod.seam;

/**
 * <b>Which GameTest is holding the Server thread, and for how long</b>. The state a Gate B stall watchdog
 * reads, and the pure rule that decides a stall.
 *
 * <p>A GameTest's {@code timeoutTicks} counts TICKS. A test that blocks the Server thread inside its own tick (a
 * {@code managedBlock} that never returns: {@code placeNewPlayer} waiting for chunks a GameTest server never has time
 * to load) makes no tick, so its timeout never fires and Gate B sits silent until something outside kills it: 77 and
 * 82 minutes on two PR gates, and four nightly regression builds with no verdict (2026-11-11 → 11-20). Minecraft's own
 * watchdog only runs on a dedicated server. The tick-time view here is what lets a stall fail fast and say WHICH test.
 *
 * <p>Written by {@code GameTestCrashGuardMixin} (around every test's tick) and by the harness's tick listener; it lives
 * here, outside {@code testing}, because that package never ships and a mixin in the jar must not reach it.
 * Nothing here runs on a player's server: no {@code GameTestInfo} ever ticks there.
 */
public final class GameTestStall {

    private static volatile String ticking;
    private static volatile long tickingSince;
    private static volatile long lastTickEnd = System.nanoTime();

    private GameTestStall() {
    }

    /** A test's tick starts (the mixin). */
    public static void enter(String test) {
        tickingSince = System.nanoTime();
        ticking = test;
    }

    /** That tick is over, however it ended. */
    public static void exit() {
        ticking = null;
    }

    /** The server finished a tick (the harness's tick listener), or the watch begins. */
    public static void tickEnded() {
        lastTickEnd = System.nanoTime();
    }

    /** What the watchdog reads now: null when the Server thread is moving. */
    public static String stalledNow(long limitNanos) {
        return verdict(System.nanoTime(), lastTickEnd, ticking, tickingSince, limitNanos);
    }

    /**
     * The rule, pure: the Server thread has not finished a tick for longer than {@code limitNanos}. The answer names
     * the test whose tick it is stuck in when there is one (the test to blame), and says plainly when there is none
     * (the server's own tick: a mod tick handler, the chunk system, a dead worker it waits on).
     *
     * @return null while ticks are coming; otherwise one line for the log
     */
    public static String verdict(long nowNanos, long lastTickEndNanos, String tickingTest, long tickingSinceNanos,
                                 long limitNanos) {
        long quiet = nowNanos - lastTickEndNanos;
        if (quiet <= limitNanos) {
            return null;
        }
        String where = tickingTest != null
                ? "inside the tick of " + tickingTest + " (" + (nowNanos - tickingSinceNanos) / 1_000_000_000L
                        + " s in it) — that test is blocking the Server thread, so no timeoutTicks can fire"
                : "outside any test's tick (the server's own tick, or something it waits on) — the stacks below say what";
        return "the Server thread has not finished a tick for " + quiet / 1_000_000_000L + " s, " + where;
    }
}
