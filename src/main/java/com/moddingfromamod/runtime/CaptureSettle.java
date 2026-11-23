package com.moddingfromamod.runtime;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * <b>Wait for a borrowed body to be handed back, then read the world it is actually standing in.</b>
 *
 * <p>Pure and Minecraft-free; {@code WishCapture.awaitSettled} is the four-line wiring that supplies
 * the real clock, the real session map and the real thread check.
 *
 * <h2>The question this answers, and the wrong answer it replaces</h2>
 *
 * <p>A capture session outlives the {@code photograph} call that opened it — by
 * {@link CaptureTimings#IDLE_EXIT_MS} normally and {@link CaptureTimings#MAX_SESSION_MS} at worst —
 * so a tool that runs straight afterwards can read a child who is standing on a photography seat
 * rather than at home. Every positional answer is then about the seat: his position, the block under
 * him, the biome, and above all <i>what is around him</i>.
 *
 * <p>The tempting fix is to answer from the remembered origin instead. It is worse than it looks,
 * and the reason is that the two kinds of world read fail DIFFERENTLY at a place nobody is standing:
 * {@code ServerLevel.getBlockState} force-loads the chunk and answers correctly, while
 * {@code getEntitiesOfClass} reads only loaded entity sections and returns an <b>empty list</b>. So
 * a report built that way describes the ground and the biome at home perfectly and says "nothing is
 * around him" with a farm and six mobs standing there — and empty reads as a fact. Worse, the case
 * where a child is the viewpoint at all is the case where there is no camera, which is normally
 * singleplayer: he is the only player, so once he is warped to the seat there is nothing keeping his
 * home loaded.
 *
 * <p><b>Waiting removes the question instead of answering it.</b> Afterwards he really is at home,
 * his own chunk ticket really is there, and the entities really do exist — so the tool goes on doing
 * exactly what it always did, and the only thing that varies by capture state is <i>when</i> it does
 * it. The answer never varies, which is what keeps this out of §0.5's way.
 *
 * <h2>🔴 It must not run on the server thread, and that is a guard rather than a note</h2>
 *
 * <p>The session ends on the server tick. Waiting for it FROM the server thread stops the tick, so
 * the thing being waited for can never happen. It would not even present as a hang: the wait sits
 * inside {@code AgentBridge.onServer}, which has its own finite budget, so it would surface as a
 * bridge timeout — an error naming the transport, for a deadlock caused by the caller. Hence
 * {@link Verdict#WRONG_THREAD}: loud, immediate, and named after the actual mistake.
 */
public final class CaptureSettle {

    private CaptureSettle() {
    }

    /** What happened, in enough detail that the caller can say something true about it. */
    public enum Verdict {
        /** Nobody was holding him. The overwhelmingly common case, and it costs nothing. */
        ALREADY_FREE,
        /** He was held, and was handed back within the budget. */
        SETTLED,
        /** Still held after {@link CaptureTimings#settleWaitMs()} — the watchdog did not fire. */
        TIMED_OUT,
        /** The wait was interrupted; treat it as unknown rather than as settled. */
        INTERRUPTED,
        /** 🔴 Called from the server thread, where waiting can only ever deadlock. */
        WRONG_THREAD;

        /** True when the body is known to be the child's own again. */
        public boolean settled() {
            return this == ALREADY_FREE || this == SETTLED;
        }
    }

    /** Somewhere to sleep that a test can replace with a clock nudge. */
    @FunctionalInterface
    public interface Sleeper {
        /** @return false if interrupted */
        boolean sleep(long ms);
    }

    /** How often to look while waiting. Far finer than the ticker, so the wait ends promptly. */
    public static final long POLL_MS = 50;

    /**
     * <b>What to tell the agent when the wait did not succeed</b> — empty when it did.
     *
     * <p>Pure so the branch is gated somewhere. Driving it for real needs a body wedged past
     * {@link CaptureTimings#settleWaitMs()}, i.e. a watchdog that failed — which is not a state any
     * gate can honestly manufacture without disabling the watchdog it is meant to be testing. So
     * the WORDS are tested here and the wiring is one call, rather than the whole thing living
     * inline in a tool where nothing exercises it at all.
     *
     * <p>Its job is to stop a fault reading as an answer. The report it prefixes is deliberately
     * still about the LIVE body — complete and self-consistent, because that is the one place whose
     * chunks are loaded and whose entities exist — so the only thing that can mislead is the
     * unstated assumption that the position is his own. This states it.
     *
     * @param home where he really lives, or null if that is not known either
     */
    public static String notice(Verdict verdict, String home) {
        if (verdict == null || verdict.settled()) {
            return "";
        }
        return "(!) He is being held still for a photograph and was not released in time ("
                + verdict + " after " + CaptureTimings.settleWaitMs() + " ms). EVERYTHING BELOW "
                + "describes where the photograph is being taken from, not where he lives"
                + (home == null || home.isBlank() ? "" : "; his own spot is " + home)
                + ". Do not build here on the strength of it.\n\n";
    }

    /**
     * The decision.
     *
     * @param stillHeld     is the body still borrowed RIGHT NOW
     * @param onServerThread are we on the thread that would have to run the release
     * @param clock          milliseconds, monotonic
     * @param sleeper        how to wait between looks
     * @param budgetMs       how long to wait in total — {@link CaptureTimings#settleWaitMs()}
     */
    public static Verdict await(BooleanSupplier stillHeld, BooleanSupplier onServerThread,
                                LongSupplier clock, Sleeper sleeper, long budgetMs) {
        // CHECK BEFORE THE THREAD, so the common case cannot be turned into an error by a caller
        // that happens to be on the server thread and has nothing to wait for anyway. This ordering
        // is deliberate: it makes the guard fire on the mistake that matters (waiting) rather than
        // on merely asking (which is free and correct from anywhere).
        if (!stillHeld.getAsBoolean()) {
            return Verdict.ALREADY_FREE;
        }
        if (onServerThread.getAsBoolean()) {
            return Verdict.WRONG_THREAD;
        }
        long deadline = clock.getAsLong() + budgetMs;
        while (clock.getAsLong() < deadline) {
            if (!sleeper.sleep(POLL_MS)) {
                return Verdict.INTERRUPTED;
            }
            if (!stillHeld.getAsBoolean()) {
                return Verdict.SETTLED;
            }
        }
        return stillHeld.getAsBoolean() ? Verdict.TIMED_OUT : Verdict.SETTLED;
    }
}
