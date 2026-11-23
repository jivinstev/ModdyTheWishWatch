package com.moddingfromamod.agent;

import java.util.function.Supplier;

import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;

/**
 * Which worker is calling — bound for the duration of one tool call, then unbound.
 *
 * <p>This is the seam that lets everything else stop being a process-wide global. Today the mod can
 * only ask "what is the current wish?" because there is exactly one; with more than one in flight
 * that question has no answer unless you also say <b>who is asking</b>. An MCP session is one parked
 * agent, so the session id is the worker id, and the marshal already knows which wish each worker
 * holds.
 *
 * <p>A {@link ThreadLocal} rather than an argument threaded through every tool: {@code McpProtocol}
 * dispatches on the HTTP handler thread and the tool bodies are an open set (24 of them today), so
 * one binding at the dispatch site covers every tool now and in future — the same argument that put
 * the action log and the narrator there.
 *
 * <p>It also removes a recorded test problem: <i>GameTests in the same batch run interleaved on
 * the one server thread</i>,
 * so anything asserting on a singleton's statics fails against another test rather than against the
 * code. A component handed its own run observes only its own work.
 */
public final class AgentContext {

    private static final ThreadLocal<String> SESSION = new ThreadLocal<>();

    private AgentContext() {
    }

    /** Run {@code body} with this session bound. Always unbinds, even if the body throws. */
    public static <T> T with(String sessionId, Supplier<T> body) {
        String previous = SESSION.get();
        SESSION.set(sessionId);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                SESSION.remove();
            } else {
                SESSION.set(previous);
            }
        }
    }

    /** The calling worker's id, or "" when nothing is bound (a direct call, or a unit test). */
    public static String workerId() {
        String s = SESSION.get();
        return s == null ? "" : s;
    }

    /** Bind explicitly — for a test, or for a call that is not coming through the HTTP dispatch. */
    public static void bind(String sessionId) {
        SESSION.set(sessionId);
    }

    public static void unbind() {
        SESSION.remove();
    }

    /**
     * <b>Which pool slot is calling</b> — the {@code X-Moddy-Worker} header, bound for one
     * whole MCP request rather than one tool call, because the tool-call record is written after
     * the call returns.
     *
     * <p>It is the join key between the action log and the CLI's transcripts: the CLI writes the
     * same name ({@code -n <name>}) into its transcript as an {@code agent-name} record, so
     * {@code tools/wish-cost.py} can charge each model turn to the wish its own worker held. The
     * MCP session id cannot do that job — it changes on a re-initialise, and the transcript never
     * sees it — and a pinned transcript id cannot either: {@code --bg} ignores
     * {@code --session-id}.
     */
    private static final ThreadLocal<String> WORKER_NAME = new ThreadLocal<>();

    /** A slot name is ours and short; anything else in the header is dropped, never logged. */
    private static final java.util.regex.Pattern SAFE_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public static void bindWorkerName(String name) {
        if (name != null && SAFE_NAME.matcher(name).matches()) {
            WORKER_NAME.set(name);
        } else {
            WORKER_NAME.remove();
        }
    }

    public static void unbindWorkerName() {
        WORKER_NAME.remove();
    }

    /** The calling slot's name, or "" when the request carried none (a gate, a stock client). */
    public static String workerName() {
        String s = WORKER_NAME.get();
        return s == null ? "" : s;
    }

    /**
     * <b>{@code work}, carrying BOTH of the calling thread's bindings to whichever thread runs it</b>
     * — the session and the slot name — restoring that thread's own bindings afterwards.
     *
     * <p>The thread hop used to carry the session only. Everything keyed on the slot name read ""
     * on the server thread: {@code MuseShimState.servedSpecForCurrentWorker()} fell back to the
     * requested model, so every wish the Muse subscription served settled stamped "Spark" in prod
     * (20 of 20 settled records since 2026-10-15 had no workerName, while the shim logged
     * {@code arm=subscription model=muse-wonder-1.0} 29 times), and the provenance stamp, the
     * action log's {@code workerName} and the spend attribution read the same blank. Captured at
     * the call, so the value is the caller's even if the runner is a pooled thread.
     */
    public static <T> Supplier<T> carried(Supplier<T> work) {
        String session = SESSION.get();
        String slot = WORKER_NAME.get();
        if (session == null && slot == null) {
            return work;
        }
        return () -> {
            String prevSession = SESSION.get();
            String prevSlot = WORKER_NAME.get();
            restore(SESSION, session);
            restore(WORKER_NAME, slot);
            try {
                return work.get();
            } finally {
                restore(SESSION, prevSession);
                restore(WORKER_NAME, prevSlot);
            }
        };
    }

    private static void restore(ThreadLocal<String> local, String value) {
        if (value == null) {
            local.remove();
        } else {
            local.set(value);
        }
    }

    /**
     * The wish this worker is granting right now, or null.
     *
     * <p>Falls back to <b>the single live run</b> when nothing is bound — or the bound session has no
     * history with the marshal (the CLI re-initialised its MCP session mid-wish) — and there is
     * exactly one, so
     * every existing call site keeps working unchanged at one slot. That fallback is deliberately
     * limited to the unambiguous case: with two in flight and no binding, the honest answer is "I do
     * not know", and guessing is precisely the silent mis-attribution this class exists to stop.
     */
    public static WishRun currentRun() {
        return currentRun(Marshal.global());
    }

    /**
     * The UUID of the child whose wish this worker is granting, as a string, or {@code ""}.
     *
     * <p>For the log sites that are bound to a run but never handed a player: a plan grade, a text
     * sweep, a datapack compile. The empty string is the honest answer for a direct call, a gate,
     * or two runs in flight with nothing bound — {@link #currentRun()} already refuses to guess in
     * that last case, and this inherits the refusal rather than re-deciding it.
     *
     * <p>Never throws. It is only ever used to decorate a log record, and a logging helper that
     * could take a wish down with it would be a worse bug than the missing field.
     */
    public static String currentPlayerId() {
        try {
            WishRun run = currentRun();
            return run == null || run.playerId() == null ? "" : run.playerId().toString();
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    /**
     * <b>The calling worker had a wish, and it has ENDED</b> — finished, or stopped by the child — while the worker
     * is still running a tool for it. {@link #currentRun()} answers null for such a worker, and so does a
     * caller with no wish at all; this tells the two apart, so {@link ActingChild} does not read "no wish" as "act
     * for whoever joined first". Measured 2026-11-10: a Builder creature wish was stopped while its
     * {@code sculpt_creature} was mid-call, and the creature was conjured in front of ANOTHER child five blocks away.
     */
    public static boolean ownWishEnded() {
        return ownWishEnded(Marshal.global());
    }

    /** Seam for tests: the same question against a marshal handed in. */
    public static boolean ownWishEnded(Marshal marshal) {
        String worker = workerId();
        return marshal != null && !worker.isEmpty() && marshal.runForWorker(worker) == null
                && marshal.hasAssigned(worker);
    }

    /** Seam for tests: the same lookup against a marshal handed in. */
    public static WishRun currentRun(Marshal marshal) {
        if (marshal == null) {
            return null;
        }
        String worker = workerId();
        if (!worker.isEmpty()) {
            WishRun run = marshal.runForWorker(worker);
            if (run != null) {
                return run;
            }
            // THIS WORKER'S OWN WISH HAS ENDED -- finished, or stopped. It is still calling
            // tools, and the single-live-run fallback below would hand it whichever OTHER wish is
            // the only one in flight: another child's plan, his bar, his wish finished by a
            // stranger. CancelGate refuses the child-visible tools for such a worker at the MCP
            // layer, but every other tool resolves its run here. A worker the marshal has ever
            // given a wish can always be placed, and "no wish now" is the answer.
            if (marshal.hasAssigned(worker)) {
                return null;
            }
        }
        var working = marshal.working();
        return working.size() == 1 ? working.get(0) : null;
    }
}
