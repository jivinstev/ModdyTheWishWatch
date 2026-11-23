package com.moddingfromamod.health;

import java.util.Optional;

/**
 * <b>"Moddy is tired"</b>: the one degraded mode (decision D8).
 *
 * <p>D8 asked whether Moddy should be <i>open</i> or <i>closed</i> when the safety grader is down,
 * and the answer was <b>neither</b>: Moddy is <b>closed for new grants</b> and <b>open for cached
 * wishes and re-wishes</b>, with the lexicon (L0, local, never down) grading every input the whole
 * time. The child is told Moddy is tired; the operator is alerted. One response shape, three causes:
 *
 * <ul>
 *   <li>{@link Cause#GRADER_UNREACHABLE} — the L1 grader's most recent attempt got <b>no answer</b>
 *       (no CLI, a timeout, a non-zero exit). A reply that came back and would not parse is NOT
 *       this: the grader was reachable, and that case stays decision 12.2's {@code open}.</li>
 *   <li>{@link Cause#AGENT_UNRESPONSIVE} — a wish waited the whole claim ceiling <b>while there was
 *       free capacity</b> and nobody took it ({@code NEVER_CLAIMED}), and no worker has
 *       parked on {@code await_wish} since. That is measured behaviour, not a log string: signed
 *       out, rate-limited and deaf all look like this, and so does anything nobody has named yet.
 *       ⚠ A wish queued behind a <b>full</b> pool is not this — see {@code WishTimeoutPolicy}.</li>
 *   <li>{@link Cause#OVER_QUOTA} — the subscription's own usage window is exhausted: set
 *       by {@link QuotaWatch} from a server tool's latest READ of the CLI's
 *       {@code /usage}, never from a modelled estimate. {@code ModdyTiredGameTest} drives the
 *       response through this seam.</li>
 *   <li>{@link Cause#SPEND_CAP} — this world's model spend for the UTC day has reached its cap
 *       (D18): set by {@link SpendGuardWatch} from a server spend guard's
 *       latest measurement. It ends by itself at the day boundary ({@code untilMs}),
 *       even when nothing reads the file again.</li>
 * </ul>
 *
 * <p><b>Never stuck, in either direction.</b> Each cause clears on the evidence that it is over
 * (a grade answered, a worker parked, the quota lifted) — and the two that are inferred from a
 * failure also EXPIRE after a window, so a wrong inference costs minutes rather than a child locked
 * out of new wishes until a restart. After the window the next wish takes the ordinary path, which
 * re-derives the answer from scratch.
 *
 * <p>Pure and Minecraft-free: the clock is an argument and the inputs are pushed in by the three
 * places that observe them ({@code SafetyGrader}, {@code HealthService}'s sweeper,
 * {@code WishQueue}). {@link #global()} is the process's one instance.
 */
public final class ModdyTired {

    /** Why Moddy is tired. The wire name is what the log and the {@code MODDY-ALERT} line say. */
    public enum Cause {
        GRADER_UNREACHABLE("grader_unreachable"),
        AGENT_UNRESPONSIVE("agent_unresponsive"),
        OVER_QUOTA("over_quota"),
        SPEND_CAP("spend_cap");

        private final String wire;

        Cause(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /**
     * Tired, why, since when, and — when the cause KNOWS its end — until when ({@code untilMs},
     * epoch ms; 0 when it does not). Only {@link Cause#SPEND_CAP} knows: the day's budget refills
     * at the UTC day boundary, which is what lets the child be told when to come back.
     * {@code detail} is for the operator, never for the child.
     */
    public record Tired(Cause cause, String detail, long sinceMs, long untilMs) {

        public Tired(Cause cause, String detail, long sinceMs) {
            this(cause, detail, sinceMs, 0L);
        }
    }

    /** A change of state worth telling the operator about. */
    public record Transition(boolean entered, Cause cause, String detail, long forMs) {

        /**
         * One line; an entry is a {@code MODDY-ALERT}, which an alert watcher greps —
         * except {@link Cause#SPEND_CAP}'s. The operator's ONE page for a tripped cap is sent by
         * the server's spend guard, which has the numbers (today's $ against the cap, and
         * where to raise it); a MODDY-ALERT here would be the same news paged twice.
         */
        public String line() {
            if (entered && cause == Cause.SPEND_CAP) {
                return "moddy tired: " + cause.wire() + " — " + detail
                        + " (paged by the server's spend guard, not by this line)";
            }
            return entered
                    ? "MODDY-ALERT moddy tired: " + cause.wire() + " — " + detail
                    : "moddy awake again: " + cause.wire() + " cleared after " + (forMs / 1000L) + "s";
        }
    }

    /**
     * How long one unanswered grade keeps Moddy tired with nothing newer to go on. Ten minutes:
     * long enough that an outage is not re-discovered by every child in turn, short enough that one
     * stray timeout on an idle evening does not refuse the next morning's first wish.
     */
    public static final long GRADER_WINDOW_MS = 10 * 60_000L;

    /** The same for an unclaimed wish. Longer, because an agent outage is slower to come back. */
    public static final long AGENT_WINDOW_MS = 15 * 60_000L;

    /** {@code -Dmoddingfromamod.tiredInTests=true} — arms the degraded mode inside a gate run. */
    public static final String ARM_PROPERTY =
            com.moddingfromamod.ModdingFromAModMod.MODID + ".tiredInTests";

    /**
     * <b>Always on in a real run; off in a gate run unless a test arms it.</b>
     *
     * <p>The precedent is the sweeper ({@code HealthService.sweepEnabled}) and the reason is the
     * same measured one: a gate has no agent and — on a GitHub runner — no {@code claude} CLI, so
     * every grade there is unreachable and every wish is unclaimed. Armed, the first test to submit
     * would put Moddy to bed and every later test's wish would come back "tired", failing as
     * something else. {@code ModdyTiredGameTest} arms it for its own batches and drives the real
     * paths; everything else keeps measuring what it measured before.
     */
    public static boolean armed() {
        return !com.moddingfromamod.RunContext.isTestRun()
                || "true".equals(System.getProperty(ARM_PROPERTY));
    }

    private static final ModdyTired GLOBAL = new ModdyTired();

    public static ModdyTired global() {
        return GLOBAL;
    }

    /** A fresh, private instance — for a test, which must not share the process's one. */
    public ModdyTired() {
    }

    private long graderOkAtMs;
    private long graderFailAtMs;
    private String graderWhy = "";

    private long neverClaimedAtMs;
    private String agentWhy = "";
    private long parkedAtMs;

    private boolean overQuota;
    private long quotaSinceMs;
    private String quotaWhy = "";

    private boolean spendCapped;
    private long spendSinceMs;
    private long spendUntilMs;
    private String spendWhy = "";

    private Cause announced;
    private long announcedAtMs;

    // ---- inputs ----

    /** The grader answered (whatever it said). */
    public synchronized void graderAnswered(long atMs) {
        graderOkAtMs = Math.max(graderOkAtMs, atMs);
    }

    /** The grader was asked and nothing came back. */
    public synchronized void graderFailed(long atMs, String why) {
        if (atMs >= graderFailAtMs) {
            graderFailAtMs = atMs;
            graderWhy = why == null ? "" : why;
        }
    }

    /** A wish reached the claim ceiling while a worker was free to take it. */
    public synchronized void wishNeverClaimed(long atMs, String why) {
        if (atMs >= neverClaimedAtMs) {
            neverClaimedAtMs = atMs;
            agentWhy = why == null ? "" : why;
        }
    }

    /** A worker parked on {@code await_wish} — a model turn happened, so the agent is answering. */
    public synchronized void workerParked(long atMs) {
        parkedAtMs = Math.max(parkedAtMs, atMs);
    }

    /** The seam: Moddy's own spend ceiling has been reached ({@code true}) or lifted. */
    public synchronized void overQuota(boolean on, String why, long atMs) {
        if (on && !overQuota) {
            quotaSinceMs = atMs;
        }
        overQuota = on;
        quotaWhy = on ? (why == null ? "" : why) : "";
    }

    /**
     * The seam: this world's daily spend cap is reached ({@code true}, until {@code untilMs} —
     * the next UTC midnight) or lifted (the cap raised, or no fresh reading says it is reached).
     */
    public synchronized void spendCapped(boolean on, String why, long untilMs, long atMs) {
        if (on && !spendCapped) {
            spendSinceMs = atMs;
        }
        spendCapped = on;
        spendUntilMs = on ? untilMs : 0L;
        spendWhy = on ? (why == null ? "" : why) : "";
    }

    // ---- the answer ----

    /**
     * Is Moddy tired right now, and why? The most persistent cause wins when several hold.
     *
     * @param graderEnforced whether the grader's answer is ACTED on ({@code child_safety=on}). In
     *                       shadow nothing is enforced, so an unreachable grader tires nobody; in
     *                       {@code off} there is no grader to be unreachable.
     */
    public synchronized Optional<Tired> current(long nowMs, boolean graderEnforced) {
        if (overQuota) {
            return Optional.of(new Tired(Cause.OVER_QUOTA, quotaWhy, quotaSinceMs));
        }
        // NEVER STUCK: the cap is a day's, and it ends at the day boundary whether or not anything
        // reads the spend file again (a stopped timer must not keep Moddy tired into tomorrow).
        if (spendCapped && nowMs < spendUntilMs) {
            return Optional.of(new Tired(Cause.SPEND_CAP, spendWhy, spendSinceMs, spendUntilMs));
        }
        if (neverClaimedAtMs > 0 && neverClaimedAtMs > parkedAtMs
                && nowMs - neverClaimedAtMs < AGENT_WINDOW_MS) {
            return Optional.of(new Tired(Cause.AGENT_UNRESPONSIVE, agentWhy, neverClaimedAtMs));
        }
        if (graderEnforced && graderFailAtMs > 0 && graderFailAtMs > graderOkAtMs
                && nowMs - graderFailAtMs < GRADER_WINDOW_MS) {
            return Optional.of(new Tired(Cause.GRADER_UNREACHABLE, graderWhy, graderFailAtMs));
        }
        return Optional.empty();
    }

    /**
     * What changed since the last call, for the one {@code MODDY-ALERT} per episode. A change of
     * CAUSE while still tired is a new entry (the operator's fix differs), never a silent one.
     */
    public synchronized Optional<Transition> transition(long nowMs, boolean graderEnforced) {
        Optional<Tired> now = current(nowMs, graderEnforced);
        Cause cause = now.map(Tired::cause).orElse(null);
        if (cause == announced) {
            return Optional.empty();
        }
        Optional<Transition> out;
        if (cause == null) {
            out = Optional.of(new Transition(false, announced, "", nowMs - announcedAtMs));
        } else {
            out = Optional.of(new Transition(true, cause, now.get().detail(), 0L));
        }
        announced = cause;
        announcedAtMs = nowMs;
        return out;
    }

    /** Forget everything. Tests only, and the server stopping. */
    public synchronized void reset() {
        graderOkAtMs = 0;
        graderFailAtMs = 0;
        graderWhy = "";
        neverClaimedAtMs = 0;
        agentWhy = "";
        parkedAtMs = 0;
        overQuota = false;
        quotaSinceMs = 0;
        quotaWhy = "";
        spendCapped = false;
        spendSinceMs = 0;
        spendUntilMs = 0;
        spendWhy = "";
        announced = null;
        announcedAtMs = 0;
    }
}
