package com.moddingfromamod.health;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Clock;

/**
 * <b>A wish either settles or times out</b> — the state machine behind {@code WishStatus.TIMED_OUT}
 * (plan §6: <i>"a wish either settles or times out — today it can do neither"</i>).
 *
 * <p>Two ceilings, because two different things go wrong and they want different numbers:
 *
 * <ul>
 *   <li><b>{@link Reason#NEVER_CLAIMED}</b> — no worker ever took it. That is an agent-side
 *       failure (not signed in, rate-limited, every worker deaf) and the child is looking at a bar
 *       that nobody is behind. Short ceiling, {@value #DEFAULT_CLAIM_TIMEOUT_S} s by default,
 *       because nothing is going to change by waiting.</li>
 *   <li><b>{@link Reason#RAN_TOO_LONG}</b> — a worker took it and never finished. Long ceiling,
 *       {@value #DEFAULT_RUN_TIMEOUT_S} s, which is deliberately the MCP client's own timeout
 *       ({@code AgentSession.MCP_CLIENT_TIMEOUT_MS}): past that point the CLI has already given up
 *       on the call, so a run still open is a run nothing is driving.</li>
 *   <li><b>{@link Reason#NO_PROGRESS}</b> — a worker took it and has made no tool call
 *       for {@value #DEFAULT_NO_PROGRESS_S} s (and has none in flight). A healthy worker is never
 *       that quiet: across 664 granted claims — 89 live on dedicated servers (2026-09-24…10-14) and 575
 *       archived real-agent grants — the longest silence between a claim or one call's end and
 *       the next call's start was <b>67 s</b> (48 s live), p99 34 s. A worker that has gone quiet
 *       for minutes is wedged or gone — on a live server, 2026-10-13, a wish sat "claimed" by a process
 *       that no longer existed for the whole 905 s — so the run is ended there, the worker
 *       recycled, and the child told, instead of waiting out {@link Reason#RAN_TOO_LONG}.</li>
 * </ul>
 *
 * <p><b>Pure, and the clock is handed in.</b> CLAUDE.md's rule: a GameTest server ticks as fast as
 * it can, so a ceiling expressed in real seconds cannot be asserted in ticks — it fails while the
 * feature works and reads as a dead feature. The timing is asserted in a unit test with a
 * {@link com.moddingfromamod.marshal.FakeClock}; the GameTest proves only that the sweeper is
 * wired to the wish book and the bar.
 *
 * <p><b>Ages are measured from the wish, never from the sweep.</b> The sweeper runs every 15 s, so
 * a policy that counted ticks-since-noticed would time a wish out between 120 s and 135 s depending
 * on where the wish landed in the cycle — a number nobody could reproduce.
 */
public final class WishTimeoutPolicy {

    /** {@code -Dmoddingfromamod.claimTimeoutS=120} */
    public static final String CLAIM_PROPERTY = ModdingFromAModMod.MODID + ".claimTimeoutS";

    /** {@code -Dmoddingfromamod.runTimeoutS=900} */
    public static final String RUN_PROPERTY = ModdingFromAModMod.MODID + ".runTimeoutS";

    /** {@code -Dmoddingfromamod.noProgressS=300} */
    public static final String NO_PROGRESS_PROPERTY = ModdingFromAModMod.MODID + ".noProgressS";

    /** Two minutes with nobody listening is already longer than a child will wait. */
    public static final long DEFAULT_CLAIM_TIMEOUT_S = 120L;

    /** The MCP client's own per-call timeout: past it, nothing is driving the call any more. */
    public static final long DEFAULT_RUN_TIMEOUT_S = 900L;

    /**
     * Five minutes with no tool call from the worker holding a wish: 4.5x the longest
     * healthy silence measured (67 s over 664 granted claims, see the class note), so a slow
     * authoring turn or an API retry never trips it, and still ten minutes sooner than
     * {@link #DEFAULT_RUN_TIMEOUT_S} for a child watching a bar nothing is behind.
     */
    public static final long DEFAULT_NO_PROGRESS_S = 300L;

    /** Why a wish was given up on. The wire names land in the action log and the journal. */
    public enum Reason {
        /** Admitted, acknowledged, and no worker ever took it. */
        NEVER_CLAIMED("never claimed"),
        /** A worker took it and never finished. */
        RAN_TOO_LONG("ran too long"),
        /** A worker took it and then stopped making tool calls. */
        NO_PROGRESS("no progress");

        private final String wire;

        Reason(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /**
     * One live wish, as much of it as this policy is allowed to know.
     *
     * @param startedMs      when a worker took it, or 0 when none has
     * @param lastActivityMs when the holding worker's last tool call started or ended, or 0 for none
     * @param toolsInFlight  the holding worker's tool calls that have started and not returned
     */
    public record Candidate(UUID wishId, long createdMs, long startedMs, long lastActivityMs,
                            int toolsInFlight) {

        /**
         * A live run as the sweep sees it — its claim, and the holding worker's tool-call stamps
         *. The ONE place a run becomes a candidate, so the sweep and its tests agree.
         */
        public static Candidate of(com.moddingfromamod.marshal.WishRun r) {
            // a wish a worker has taken off the queue counts as claimed from that moment, while the
            // worker decides its pickup gates and waits for its brief: somebody IS behind it. The run and
            // no-progress ceilings still bound that wait.
            long started = r.assignedMs() > 0 ? r.assignedMs() : r.pickedUpMs();
            return new Candidate(r.wishId(), r.waitingSinceMs(), started, r.lastActivityMs(),
                    r.toolsInFlight());
        }

        /** A candidate with no activity record — judged by the two ceilings alone. */
        public Candidate(UUID wishId, long createdMs, long startedMs) {
            this(wishId, createdMs, startedMs, 0L, 0);
        }

        public boolean claimed() {
            return startedMs > 0;
        }
    }

    /** A wish that has run out of time, and how far past the ceiling it is. */
    public record Expiry(UUID wishId, Reason reason, long ageS) {

        /** What the journal, the action log and the {@code MODDY-ALERT} line all say. */
        public String line() {
            return reason.wire() + (reason == Reason.NO_PROGRESS ? " for " : " after ") + ageS + "s";
        }
    }

    private final long claimTimeoutS;
    private final long runTimeoutS;
    private final long noProgressS;
    private final Clock clock;

    /** The two ceilings only — no no-progress watchdog. */
    public WishTimeoutPolicy(long claimTimeoutS, long runTimeoutS, Clock clock) {
        this(claimTimeoutS, runTimeoutS, 0L, clock);
    }

    /** @param noProgressS the no-progress watchdog; 0 or less switches it off */
    public WishTimeoutPolicy(long claimTimeoutS, long runTimeoutS, long noProgressS, Clock clock) {
        this.claimTimeoutS = claimTimeoutS;
        this.runTimeoutS = runTimeoutS;
        this.noProgressS = noProgressS;
        this.clock = clock == null ? Clock.system() : clock;
    }

    /**
     * The shipped policy: the two properties, or the defaults.
     *
     * <p>A bad value is the defaults rather than a crash, and it is <b>not</b> silent: a server
     * whose launcher typo'd a timeout must not be one that never times a wish out, which is
     * indistinguishable from the bug this class exists to fix.
     */
    public static WishTimeoutPolicy fromProperties() {
        // The runtime knobs win over the boot properties; the sweeper builds a fresh policy
        // every pass, so a moved knob reaches every open wish at the next sweep.
        return new WishTimeoutPolicy(
                com.moddingfromamod.config.ModdySettings.longOr("wish.claimTimeoutSeconds",
                        seconds(CLAIM_PROPERTY, DEFAULT_CLAIM_TIMEOUT_S)),
                com.moddingfromamod.config.ModdySettings.longOr("wish.runTimeoutSeconds",
                        seconds(RUN_PROPERTY, DEFAULT_RUN_TIMEOUT_S)),
                seconds(NO_PROGRESS_PROPERTY, DEFAULT_NO_PROGRESS_S),
                Clock.system());
    }

    private static long seconds(String property, long fallback) {
        String v = System.getProperty(property, "").trim();
        if (v.isEmpty()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(v);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public long claimTimeoutS() {
        return claimTimeoutS;
    }

    public long runTimeoutS() {
        return runTimeoutS;
    }

    public long noProgressS() {
        return noProgressS;
    }

    /** Has this one run out? */
    public Optional<Expiry> judge(Candidate c) {
        return judge(c, clock.nowMs());
    }

    /** @param nowMs the moment to judge against, for a test that would rather not sleep */
    public Optional<Expiry> judge(Candidate c, long nowMs) {
        return judge(c, nowMs, 0L);
    }

    /**
     * @param lastSaturatedMs the last moment EVERY worker was busy granting a wish, or 0
     *
     * <p><b>A wish queued behind a full pool is not a wish nobody is behind</b>. The claim
     * ceiling exists for "not signed in, rate-limited, every worker deaf" — nothing is going to
     * take the wish however long it waits. When every worker is busy on somebody's wish, somebody
     * IS behind it: the child is shown "2 wishes ahead of yours" ({@code QueueNotice}) and the
     * queue moves as they finish. Measured from creation alone, that wish was told "Moddy fell
     * asleep" at two minutes while the pool was working normally — and a wish takes p50 117 s, so
     * one full pool and one more wish was enough.
     *
     * <p>So the claim clock runs only while there was a free worker: it is measured from the later
     * of the wish's creation and the last moment the pool was full. It cannot wait for ever
     * either, because every busy worker is itself under {@link Reason#RAN_TOO_LONG}'s ceiling.
     */
    public Optional<Expiry> judge(Candidate c, long nowMs, long lastSaturatedMs) {
        if (c == null || c.createdMs() <= 0) {
            return Optional.empty();
        }
        if (c.claimed()) {
            long ranS = (nowMs - c.startedMs()) / 1000L;
            if (ranS >= runTimeoutS) {
                return Optional.of(new Expiry(c.wishId(), Reason.RAN_TOO_LONG, ranS));
            }
            // QUIET FOR TOO LONG — measured from the later of the claim and the last tool
            // call's start or end, so a worker that wedges mid-wish is caught as surely as one that
            // never starts. Never while a call is in flight: a slow tool body is the server working,
            // and RAN_TOO_LONG still bounds it.
            if (noProgressS > 0 && c.toolsInFlight() <= 0) {
                long quietS = (nowMs - Math.max(c.startedMs(), c.lastActivityMs())) / 1000L;
                if (quietS >= noProgressS) {
                    return Optional.of(new Expiry(c.wishId(), Reason.NO_PROGRESS, quietS));
                }
            }
            return Optional.empty();
        }
        long waitedS = (nowMs - Math.max(c.createdMs(), lastSaturatedMs)) / 1000L;
        return waitedS >= claimTimeoutS
                ? Optional.of(new Expiry(c.wishId(), Reason.NEVER_CLAIMED, waitedS))
                : Optional.empty();
    }

    /** Every wish that has run out, in the order given. */
    public List<Expiry> sweep(Collection<Candidate> live, long nowMs) {
        return sweep(live, nowMs, 0L);
    }

    /** @see #judge(Candidate, long, long) */
    public List<Expiry> sweep(Collection<Candidate> live, long nowMs, long lastSaturatedMs) {
        List<Expiry> out = new ArrayList<>();
        if (live == null) {
            return out;
        }
        for (Candidate c : live) {
            judge(c, nowMs, lastSaturatedMs).ifPresent(out::add);
        }
        return out;
    }

    /**
     * Is every worker busy granting a wish? {@code false} with no workers at all, because then
     * nobody is behind the bar — which is exactly the case the claim ceiling is for.
     */
    public static boolean saturated(int workers, int busy) {
        return workers > 0 && busy >= workers;
    }

    /** @see #sweep(Collection, long) */
    public List<Expiry> sweep(Collection<Candidate> live) {
        return sweep(live, clock.nowMs());
    }
}
