package com.moddingfromamod.wish;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>Talks to the child while the model cannot.</b> — the runtime half of
 * {@link ToolNarration}.
 *
 * <p>Wrapped around the ONE dispatch site in {@code McpProtocol}, so every tool is covered now and
 * in future — the same argument as the action log that already lives there. Two jobs:
 *
 * <ul>
 *   <li>a {@code status} the agent attached to the work is shown <b>the moment the call arrives</b>,
 *       before the work starts. That is a whole {@code set_status} round trip — about 4 seconds —
 *       removed from every authoring turn, with the agent's own wording kept.</li>
 *   <li>while the call is in flight, honest generic lines. The model is mid-inference during a 40
 *       second {@code author_datapack} and physically cannot speak; only the mod can.</li>
 * </ul>
 *
 * <p><b>Never claims progress it cannot observe.</b> The mod knows the tool and the elapsed time,
 * so the lines say what is happening and how long it has been going — never a percentage.
 *
 * <p>One daemon thread, shared. A narrator that outlives its call would leave a stale line on
 * screen, which is precisely the 52-second-stale-status bug it exists to remove, so
 * {@link #stop} is unconditional in a finally block.
 */
public final class ToolNarrator {

    /**
     * How often to reconsider what to say. Cheap: it is a table lookup, not an inference.
     */
    private static final long TICK_MS = 1000;

    /** When the AGENT last set the status itself, so the mod knows when it may fill in. */
    private static final AtomicReference<Long> AGENT_SPOKE_AT =
            new AtomicReference<>(0L);

    private ToolNarrator() {
    }

    /**
     * Called by {@code set_status} (and the ride-along) so the narrator knows to stay quiet.
     *
     * <p>Stamped on the calling wish's own run when there is one. Shared, one agent saying
     * something would silence the OTHER wish's narrator — and this narrator exists precisely to
     * speak during the window where a model cannot.
     */
    public static void agentSpoke() {
        long now = System.currentTimeMillis();
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        if (run != null) {
            run.agentSpoke(now);
        }
        AGENT_SPOKE_AT.set(now);
    }

    /**
     * A running narration; stop it when the tool returns.
     *
     * <p>A plain flag and a plain thread, NOT a ScheduledExecutorService. The executor version
     * worked in a unit test and its task <b>never ran once</b> inside the game — no tick, no
     * exception, nothing — and a scheduled task that dies takes its reason into a Future nobody
     * reads. For a component whose entire job is to speak when nothing else can, a silent failure
     * mode is the wrong trade for the tidier API.
     */
    public record Handle(AtomicBoolean running) {

        public void stop() {
            if (running != null) {
                running.set(false);
            }
        }
    }

    private static final Handle NOTHING = new Handle(null);

    /**
     * Show the agent's own line now, then narrate until the returned handle is stopped.
     *
     * @param tool the tool being called
     * @param args its arguments — read only for an optional {@code status}
     */
    public static Handle begin(String tool, JsonObject args) {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        java.util.function.Supplier<Long> spokeAt =
                run == null ? AGENT_SPOKE_AT::get : run::agentSpokeAtMs;
        // ⚠ THE OWNER IS CAPTURED HERE AND NOWHERE ELSE, and that is the whole subtlety of this
        // fix. Everything below runs on the narrator's own daemon thread, where AgentContext — a
        // ThreadLocal — is unbound; asking currentRun() from the sink would fall back to "the
        // single live run", which is null exactly when two children are wishing at once. So a
        // narrator fixed at the sink would narrate to NOBODY with two children, which is a
        // different bug wearing the same green run. This method is the last place on the dispatch
        // thread, so the answer is taken here and carried in.
        java.util.UUID owner = run == null ? null : run.playerId();
        if (run != null) {
            // A tool call is a real event: the bar's "still working" lines wait for real quiet.
            WishProgress.event(run.wishId());
        }
        return start(tool, args, spokeAt, (doing, phase) -> show(owner, doing, phase));
    }

    /**
     * The same thing with its two dependencies handed in — used by the gate.
     *
     * <p><b>Not a convenience.</b> The first version of this was tested through the singleton's own
     * statics, and the tests failed against each other rather than against the code: GameTests in
     * the same batch run interleaved, so one test's "the agent just spoke" and another's "forget
     * what was said" landed inside a third's measurement. Every failure read as a broken feature.
     * Passing the clock and the sink in means a test observes only its own narration.
     *
     * @param agentSpokeAt when the agent last set the status itself
     * @param sink         where a chosen line goes
     */
    static Handle start(String tool, JsonObject args,
                        java.util.function.Supplier<Long> agentSpokeAt,
                        java.util.function.BiConsumer<String, String> sink) {
        String said = ToolNarration.rideAlong(
                args != null && args.has("status") && args.get("status").isJsonPrimitive()
                        ? args.get("status").getAsString() : null);
        if (said != null) {
            // THE FREE HALF. The agent's own words, in the same turn as the work.
            sink.accept(said, "building");
            agentSpoke();
        }
        if (!ToolNarration.narratable(tool)) {
            return NOTHING;
        }
        long started = System.currentTimeMillis();
        // Remember the last line so an unchanged one is not re-sent every second; the status bar
        // would otherwise re-animate on a line that has not changed, which reads as a stutter.
        AtomicReference<String> last = new AtomicReference<>(said);
        AtomicBoolean running = new AtomicBoolean(true);
        Thread t = new Thread(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(TICK_MS);
                    long now = System.currentTimeMillis();
                    ToolNarration.Line line = ToolNarration.lineFor(tool, now - started,
                            now - agentSpokeAt.get());
                    if (running.get() && line != null && !line.doing().equals(last.get())) {
                        last.set(line.doing());
                        sink.accept(line.doing(), line.phase());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable ignored) {
                    // Narration must never be able to fail a wish, and one bad line must not end
                    // the narration -- a quiet screen is exactly the bug this exists to fix.
                }
            }
        }, "mfam-narrator");
        t.setDaemon(true);
        t.start();
        return new Handle(running);
    }

    /**
     * Show a line to the child whose wish this is — see {@link #begin} for why the owner is a
     * parameter rather than something looked up here.
     *
     * @param owner whose wish is being narrated, or null when the run could not be identified, in
     *              which case nobody is told (D2: telling everybody is the defect, not the fallback)
     */
    private static void show(java.util.UUID owner, String doing, String phase) {
        try {
            AgentBridge.onServerRun(() -> {
                var server = AgentBridge.serverOrNull();
                if (server == null) {
                    return;
                }
                for (java.util.UUID id : com.moddingfromamod.marshal.WishAudience.forNarration(
                        owner, server.getPlayerList().getPlayers().stream()
                                .map(ServerPlayer::getUUID).toList())) {
                    ServerPlayer p = server.getPlayerList().getPlayer(id);
                    if (p != null) {
                        WishProgress.setStatus(p, doing, phase);
                    }
                }
            });
        } catch (Exception ignored) {
            // No server, or shutting down. Saying nothing is the correct outcome.
        }
    }
}
