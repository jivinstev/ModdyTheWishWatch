package com.moddingfromamod.marshal;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A Wish Builder make is LOCAL ONLY: no model and no web call, ever</b>.
 *
 * <p>A plain Builder make, an Apprentice make, a typed re-wish change and the free change are all one thing: a
 * {@code BuilderMake}, whose tool calls are lowered from reviewed picks ({@link WishRun#builderMade}). So is the
 * ANSWER before a make: the Apprentice reading typed words into a page of picks ({@code ApprenticeService.offer}, and
 * its sentence-model callback) and the re-wish reader ({@code Rewish.tryAnswer}) run inside {@link #during} too, and
 * only the hand-off to the Magician steps {@link #outside}. "Local only" is
 * the Apprentice's whole promise. It was broken once without anybody noticing: {@code sculpt_creature} asked Muse
 * Ember who the creature was (2–3 s, blocking the make) and started a web research agent on the make's own name.
 *
 * <p>So every place the mod reaches a model or the web asks {@link #refuse} first: the helper route
 * ({@code HelperRoute.first}, which every helper job's model calls go through), {@code MuseExec}, and the web fetchers.
 * During a make the call is REFUSED (the caller gets its usual "no answer"), logged as a warning and recorded in the
 * action log as {@code kind: local_only}, so a regression is loud in production as well as red in the gates:
 * {@code LocalOnlyGameTest} makes every kind through the real tools and requires zero refusals, and
 * {@code LocalOnlyCoverageTest} fails any class that opens a network or model call without either asking here or saying
 * why it can never run inside a make.
 *
 * <p>A make is recognised two ways, both explicit: the thread is inside {@link #during} (BuilderMake runs its tool loop
 * there), or the tool call is bound to a worker session whose run is a Builder make. Never the marshal's "the one run
 * in flight" fallback, which would misread an unbound helper thread of somebody else's wish.
 */
public final class LocalOnly {

    private static final Logger LOGGER = LoggerFactory.getLogger("mfam-local-only");

    private static final ThreadLocal<Boolean> MAKING = new ThreadLocal<>();

    /** Every refusal since the server started; a test reads the difference across its own make. */
    private static final AtomicLong REFUSED = new AtomicLong();

    private LocalOnly() {
    }

    /** Run {@code body} as part of a Builder make on this thread. Always restores the previous state. */
    public static <T> T during(Supplier<T> body) {
        Boolean previous = MAKING.get();
        MAKING.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                MAKING.remove();
            } else {
                MAKING.set(previous);
            }
        }
    }

    /** {@link #during} for a body with no answer. */
    public static void run(Runnable body) {
        during(() -> {
            body.run();
            return null;
        });
    }

    /**
     * Run {@code body} OUTSIDE the guard, on purpose: the one way a local answer hands words on to something that may
     * call a model (the Apprentice, not sure, passing them to the Magician). Restores the state after.
     */
    public static void outside(Runnable body) {
        Boolean previous = MAKING.get();
        MAKING.remove();
        try {
            body.run();
        } finally {
            if (previous != null) {
                MAKING.set(previous);
            }
        }
    }

    /** Is this thread working on a Builder make right now? */
    public static boolean active() {
        if (Boolean.TRUE.equals(MAKING.get())) {
            return true;
        }
        String worker = com.moddingfromamod.agent.AgentContext.workerId();
        if (worker.isEmpty()) {
            return false;
        }
        Marshal marshal = Marshal.global();
        WishRun run = marshal == null ? null : marshal.runForWorker(worker);
        return run != null && run.builderMade();
    }

    /**
     * Asked before any model or web call. True means DO NOT make it: this is a Builder make, which is local only. The
     * refusal is logged and recorded once per call; the caller answers as it does when no model answered.
     *
     * @param what what was about to be called, in a few words ("helper model call: character lookup")
     */
    public static boolean refuse(String what) {
        if (!active()) {
            return false;
        }
        REFUSED.incrementAndGet();
        LOGGER.warn("local only: refused {} during a Wish Builder make or an Apprentice answer — they never call a "
                + "model or the web (#2789)", what);
        com.moddingfromamod.agent.ActionLog.localOnly(what);
        return true;
    }

    /** How many calls have been refused since the server started. */
    public static long refusedSoFar() {
        return REFUSED.get();
    }
}
