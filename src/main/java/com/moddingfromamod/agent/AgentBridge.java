package com.moddingfromamod.agent;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * <b>The one place a non-game thread is allowed to touch the game.</b>
 *
 * <p>MCP tools run on HTTP handler threads. Reading or mutating world/entity state from there
 * corrupts things in ways that surface later as impossible bugs, so every tool body goes through
 * {@link #onServer} — which hops onto the server thread, waits with a finite timeout, and hands the
 * value back.
 *
 * <p>Note what is deliberately <em>not</em> tied to the world here: the HTTP server itself outlives
 * any single world (see {@link McpHttpServer}). Only this reference to the running server comes and
 * goes, so when the player is at the title screen tools return a polite "no world open" instead of
 * the agent's connection dying.
 */
public final class AgentBridge {

    /** Default cap on how long a tool may wait for the game thread. */
    public static final long DEFAULT_TIMEOUT_MS = 5_000L;

    /**
     * How much longer a tool may wait for the game thread <b>during a test run</b> —.
     *
     * <p>Every budget here is chosen for real play, where a child types one wish at a time and the
     * server is otherwise idle, and where the whole point of a finite wait is that nobody is left
     * staring at a frozen watch. The gate suite is the opposite: train assembly, contraption
     * ticking and the machines corpus all run on the server thread, deliberately and at once. A
     * call that returns in milliseconds in play then queues behind them and trips a timeout that
     * has nothing to do with the thing under test — {@code modifyMobGlowsInAColour} failing on a
     * 10s budget while, two seconds either side, a train assembled and another train drove.
     *
     * <p>So the budget is stretched where the load is manufactured, and only there. This is not the
     * product being slow; it is a test-only assumption about how quickly the server answers, and
     * fixing it at the source beats raising one caller's number and waiting for the next caller to
     * hit it.
     *
     * <p>Stretched, not removed. A genuine hang must still fail rather than hang the suite, and the
     * timeout message says which budget it was, so an extended one that still expires reads as the
     * real problem it is instead of looking like ordinary load.
     */
    static final int TEST_RUN_BUDGET_FACTOR = 6;

    /**
     * The shortest wait any test-run caller gets, however small a budget it asked for.
     *
     * <p>This is a hang backstop, not a responsiveness assertion — see {@link #budgetFor}. Five
     * minutes is far beyond any legitimate server-thread queue (the worst measured was a 258s
     * batch, and a caller waits behind only part of that) and still finite, so a real deadlock
     * fails the run instead of stalling it forever.
     */
    static final long TEST_RUN_BUDGET_FLOOR_MS = 300_000L;

    private static volatile MinecraftServer server;

    private AgentBridge() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        server = null;
    }

    /** The running server, or null when no world is open. */
    public static MinecraftServer serverOrNull() {
        return server;
    }

    public static boolean worldIsOpen() {
        return server != null;
    }

    /** Raised when the game can't service a tool right now. Callers turn this into a tool error. */
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    /** Run {@code work} on the server thread and return its value. */
    public static <T> T onServer(Supplier<T> work) {
        return onServer(work, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Run {@code work} on the server thread and return its value.
     *
     * @throws Unavailable if no world is open, the server is too busy, or the work failed
     */
    public static <T> T onServer(Supplier<T> work, long timeoutMs) {
        MinecraftServer s = server;
        if (s == null) {
            throw new Unavailable("No world is open yet — ask the player to load their world.");
        }
        if (s.isSameThread()) {
            // Submitting from the server thread and then blocking on the result deadlocks the game.
            // Fail loudly: this is always a programming error, never a runtime condition.
            throw new IllegalStateException(
                    "AgentBridge.onServer() called from the server thread — that would deadlock");
        }
        long budgetMs = budgetFor(timeoutMs);
        // CARRY THE WORKER'S IDENTITY ACROSS THE HOP.
        //
        // AgentContext is a ThreadLocal, bound on the MCP dispatch thread. Everything below runs on
        // the SERVER thread, where it is not bound — so any tool asking "which wish am I granting?"
        // from inside here got the fallback answer instead: "the single live run, or null if there
        // is more than one". At one slot that fallback was always right, which is why nobody
        // noticed. At three slots it returns NULL exactly when the question matters, and the tools
        // that ask silently do nothing: plan_wish sets no plan, so the wish shows no progress bar,
        // which is how Steve found it — Zippy was being built with nothing on screen.
        //
        // Re-bound here rather than in each tool because a tool that forgets is a tool that fails
        // only under concurrency, and only sometimes. BOTH bindings: the slot name too, or every
        // wish the Muse subscription served settled stamped with the requested model (see carried).
        Supplier<T> bound = AgentContext.carried(work);
        try {
            return s.submit(bound::get).get(budgetMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // Deliberately NOT "try again in a moment". A timeout here means the game thread is
            // busy, most often driving a datapack reload — the submitted work is still QUEUED and
            // will usually run. Inviting a retry made the agent queue the same expensive work over
            // and over (see ReloadDatapacksTool for the incident this wording caused).
            throw new Unavailable("The game is busy — it did not answer within "
                    + (budgetMs / 1000) + "s"
                    // Say WHICH budget expired. An extended budget that still runs out is a real
                    // hang, and it must not read as ordinary test-suite load.
                    + (budgetMs == timeoutMs ? "" : " (the extended test-run budget)")
                    + ". Your request may still be running. Do NOT repeat it; "
                    + "do something else, or check the result another way.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted while waiting for the game.");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new Unavailable("That didn't work in the game: " + cause);
        }
    }

    /**
     * The wait a caller actually gets: what it asked for in play, longer under test.
     *
     * <p>Separate and package-visible so the rule can be asserted directly. A policy that only
     * exists inside a {@code try} block can only be tested by manufacturing a timeout, which is
     * exactly the flaky, load-dependent thing being fixed.
     */
    static long budgetFor(long requestedMs) {
        if (!com.moddingfromamod.RunContext.isTestRun()) {
            return requestedMs;
        }
        // A FLOOR, not just a multiple —, which is coming back exactly as an earlier issue's own test
        // warned it would ("too small a factor and the flake simply returns at a higher load").
        //
        // stretched the caller's wait 6x. That still scales off a number chosen for an IDLE
        // game, so it still loses a race the moment the suite gets busier, and 5s x 6 = 30s is not
        // remotely enough: DatapackReloader's own doc says ONE reload occupies the server thread
        // for 10-30s, and Gate B measured 109 of them queued inside a single 50-test batch.
        //
        // Under test this stops being an assertion about responsiveness and becomes a HANG
        // BACKSTOP. Nothing here is judging whether the game answered quickly — the GameTest's own
        // timeoutTicks is the deadline that means something. So the budget is set to outlast any
        // legitimate queue, while a genuine deadlock ends the run rather than stalling forever.
        return Math.max(requestedMs * TEST_RUN_BUDGET_FACTOR, TEST_RUN_BUDGET_FLOOR_MS);
    }

    /** Convenience for work with no return value. */
    public static void onServerRun(Runnable work) {
        onServer(() -> {
            work.run();
            return Boolean.TRUE;
        });
    }
}
