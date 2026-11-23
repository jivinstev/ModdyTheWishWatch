package com.moddingfromamod.runtime;

/**
 * <b>Every duration that decides how long a body can be held for a photograph — in one place.</b>
 *
 * <p>Minecraft-free and unit-tested, so the relationships BETWEEN these numbers are assertable
 * without booting a world. That is the whole point: individually each was defensible, and the
 * question nobody could answer from any one file was <i>how long can a child actually be frozen,
 * and what is safe to wait for that?</i>
 *
 * <h2>Why this class exists rather than five constants in three files</h2>
 *
 * <p>The capture session ends on a timer, not when {@code photograph} returns —
 * {@code PhotographTool} never calls {@code release()}. So a body stays borrowed for
 * {@link #IDLE_EXIT_MS} after the last shot, capped at {@link #MAX_SESSION_MS}, and both are
 * enforced by a ticker that only looks every {@link #TICK_INTERVAL} ticks. <b>The real worst case is
 * therefore none of those three numbers; it is a sum of all of them</b>, and anything that waits for
 * a body to come back has to be sized against that sum or it fires its fallback on a perfectly
 * healthy run.
 *
 * <p>{@link #settleWaitMs()} is that sum plus a buffer, <b>derived and never typed</b>. Typing it
 * would make it a fourth independent number that drifts the first time any of the other three
 * changes — which is precisely the failure {@code CaptureTimingsTest} exists to make impossible.
 * ({@code PhotographTool.attemptsToOutlast} already worked this way for the camera-retry budget;
 * this is the same idea applied to the whole family.)
 *
 * <h2>The two caps answer different questions and must not be confused</h2>
 *
 * <p>{@link #MAX_SESSION_MS} is a promise to a CHILD: he is never held still longer than this,
 * whatever goes wrong, and being cut off mid-burst is the correct outcome.
 * {@link #MAX_CAMERA_HOLD_MS} is an anti-wedge backstop for a lease with no body behind it, where
 * cutting off mid-burst would hand the camera to a second wish halfway through the first one's
 * four-angle orbit. Six times longer, deliberately. Anything waiting on a <b>child</b> sizes itself
 * against the first; nothing should ever size itself against the larger one by accident.
 */
public final class CaptureTimings {

    private CaptureTimings() {
    }

    /** Release the child this long after the last photo — long enough to cover a quick re-shoot. */
    public static final long IDLE_EXIT_MS = 4_000;

    /** Hard cap: a child is NEVER frozen longer than this, whatever goes wrong. */
    public static final long MAX_SESSION_MS = 10_000;

    /** The same backstop for a lease with NO FREEZE behind it — a camera, or a headless probe. */
    public static final long MAX_CAMERA_HOLD_MS = 60_000;

    /** How long a wish may wait in line for the camera before its place is dropped. */
    public static final long MAX_WAIT_MS = 60_000;

    /** How often the session ticker looks, in ticks. ~4x a second on a healthy server. */
    public static final int TICK_INTERVAL = 5;

    /** Minecraft's nominal tick, in milliseconds. A server under load runs SLOWER, never faster. */
    public static final long MS_PER_TICK = 50;

    /**
     * How much slower than nominal the ticker is assumed to be able to run and still be healthy.
     *
     * <p>The granularity that matters is wall-clock, not ticks: the caps themselves are compared in
     * milliseconds, but they are only ever <i>checked</i> on the ticker, so a server ticking at a
     * tenth of normal speed checks ten times less often. Ten is generous — a server that slow is
     * already failing at everything else — and the cost of being generous is paid only in a fault,
     * because the wait returns the moment the body is free.
     */
    public static final int SLOW_SERVER_FACTOR = 10;

    /**
     * Head-room on top of the worst case, for everything not modelled here: the exit work itself,
     * scheduler jitter, and the fact that a wait which is exactly long enough is a coin toss.
     */
    public static final long SETTLE_BUFFER_MS = 1_000;

    /** The longest a check can be late by, on a server ticking {@link #SLOW_SERVER_FACTOR}x slow. */
    public static long tickCheckMs() {
        return (long) TICK_INTERVAL * MS_PER_TICK * SLOW_SERVER_FACTOR;
    }

    /**
     * <b>How long to wait for a borrowed body to be handed back before giving up on it.</b>
     *
     * <p>Derived, so it cannot disagree with what it is waiting for. A caller that waits this long
     * and is still refused is looking at a genuine fault — the watchdog that "ALWAYS releases him"
     * did not — which is a different thing from an ordinary busy moment and should be reported as
     * one.
     */
    public static long settleWaitMs() {
        return MAX_SESSION_MS + tickCheckMs() + SETTLE_BUFFER_MS;
    }

    /**
     * How long the LONGEST-blocking tool can block for, worst case: the settle wait, plus one hop
     * onto the server thread to actually answer the question afterwards.
     *
     * <p>Exists to be compared against the MCP client's own per-tool timeout. A tool that can block
     * for longer than its caller will wait produces a transport error rather than an answer, which
     * reads as a broken bridge rather than as a slow one — see {@code ToolBlockBudgetTest}.
     */
    public static long maxToolBlockMs(long serverHopBudgetMs) {
        return settleWaitMs() + serverHopBudgetMs;
    }
}
