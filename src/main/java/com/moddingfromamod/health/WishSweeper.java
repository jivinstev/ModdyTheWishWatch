package com.moddingfromamod.health;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentSession;
import com.moddingfromamod.agent.WishQueue;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishFeedback;
import com.moddingfromamod.wish.WishProgress;
import com.moddingfromamod.wish.WishStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The wish sweeper</b>: every fifteen seconds, settle any wish that has run out of time
 * ({@link WishTimeoutPolicy}) and say when Moddy falls asleep or wakes ({@link ModdyTired}).
 *
 * <p>Public: it was half of a private health service, but a single-player game needs a
 * stuck wish settled too. Who DRIVES it depends on what is plugged in: a server's {@link HealthHook} plug-in runs it
 * from its own health thread (so its order is what it always was: sweep, its quota and spend evidence, the
 * announcement); with no hook, this class runs it on its own plain daemon thread. Never both.
 *
 * <p>A plain daemon thread, not a {@code ScheduledExecutorService}, for the reason CLAUDE.md gives: a scheduled task
 * that dies takes its reason into a {@code Future} nobody reads.
 */
public final class WishSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** How often the public sweeper runs: a server health thread's fifteen seconds. */
    public static final long EVERY_MS = 15_000L;

    private static volatile Thread thread;
    private static volatile boolean running;

    private WishSweeper() {
    }

    /** Does the public sweeper drive itself here? Only when no plug-in's health thread drives it. */
    public static boolean drivesItself() {
        return com.moddingfromamod.plugin.Plugins.shared().all(HealthHook.class).isEmpty();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (drivesItself()) {
            start(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        stop();
        // Neither may outlive the server it was set on (the reasons are in their own javadocs).
        MaintenanceState.global().resetForTest();
        ModdyTired.global().reset();
    }

    static synchronized void start(MinecraftServer server) {
        if (thread != null && thread.isAlive()) {
            return;
        }
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    server.execute(() -> {
                        try {
                            if (sweepEnabled()) {
                                sweep(server);
                            }
                        } catch (RuntimeException e) {
                            LOG.warn("wish timeout sweep failed: {}", e.toString());
                        }
                    });
                } catch (RuntimeException e) {
                    return;   // the server is stopping
                }
                try {
                    Thread.sleep(EVERY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "mfam-wish-sweeper");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    static synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * <b>Does the background sweeper run?</b> Always in play; in a GATE, only when a timeout was asked for: Gate B
     * tests submit wishes into the one global {@link Marshal} and abandon them on purpose, and a sweeper ticking
     * through the suite would settle them inside somebody else's measurement. Setting either timeout property turns
     * it back on, which is what the GameTest does.
     */
    public static boolean sweepEnabled() {
        if (!com.moddingfromamod.RunContext.isTestRun()) {
            return true;
        }
        return System.getProperty(WishTimeoutPolicy.CLAIM_PROPERTY) != null
                || System.getProperty(WishTimeoutPolicy.RUN_PROPERTY) != null;
    }

    /**
     * Settle every wish that has run out of time. <b>Server thread only.</b>
     *
     * <p>It goes through exactly the path a normal completion takes — {@link WishBook#complete},
     * {@link Marshal#finish}, {@link WishProgress#clearWishBar} — because the bar, the panel and
     * the journal are three views of the same fact and a fourth way of ending a wish is how they
     * come apart. {@code FinishWishTool} is the other caller of that sequence and this mirrors it.
     */
    /** The {@code MODDY-ALERT} line a timed-out wish writes. An alert watcher greps it. */
    public static String timeoutAlert(String shortId, WishTimeoutPolicy.Expiry expiry) {
        return "MODDY-ALERT wish timed out: " + shortId + " " + expiry.line();
    }

    private static volatile String lastAlert = "";

    /** The last {@code MODDY-ALERT} this class emitted, for a gate to assert on. */
    public static String lastAlertForTest() {
        return lastAlert;
    }

    /**
     * The last sweep at which every worker was busy. Sampled every 15 s, so a pool that
     * was full for less than one sweep can be missed — which errs towards the old behaviour, a
     * timeout, and never towards a wish waiting for ever.
     */
    private static volatile long lastSaturatedMs;

    public static void sweep(MinecraftServer server) {
        sweep(server, System.currentTimeMillis());
    }

    /**
     * The sweep at a given moment. The clock is a parameter so a GameTest can sweep "a second and a
     * half from now" at once instead of waiting for it: a GameTest server ticks as fast as it can,
     * and on 1.21.4 a lone batch burned a 6000-tick budget before 1.5 real seconds had passed. The
     * timing itself is asserted in {@code WishTimeoutPolicyTest}, which can sleep.
     */
    public static void sweep(MinecraftServer server, long now) {
        // a worker that died of the ACCOUNT (a spent subscription quota, a failed login) gives
        // its wish back to the queue BEFORE the timeouts below can judge it. A no-op unless the server
        // runs the Muse subscription path (MUSE_SHIM_STATE unset = today's behaviour exactly).
        requeueAccountDeaths(server);
        WishTimeoutPolicy policy = WishTimeoutPolicy.fromProperties();
        Marshal marshal = Marshal.global();
        // a worker that has picked a wish up and not bound it yet is busy too.
        if (WishTimeoutPolicy.saturated(AgentSession.workerCount(),
                marshal.working().size() + marshal.pickingUp().size())) {
            lastSaturatedMs = now;
        }
        List<WishTimeoutPolicy.Candidate> live = new ArrayList<>();
        for (WishRun r : marshal.live()) {
            live.add(WishTimeoutPolicy.Candidate.of(r));
        }
        for (WishTimeoutPolicy.Expiry e : policy.sweep(live, now, lastSaturatedMs)) {
            if (e.reason() == WishTimeoutPolicy.Reason.NO_PROGRESS) {
                // the worker holding it has gone quiet -- wedged, or a process that no longer
                // exists. Recycle it (stop it, and let the pool start a fresh one) BEFORE the settle,
                // so nothing it does afterwards lands on a wish the child has been told is over. The
                // settle below is the ordinary TIMED_OUT path: refund, bar down, the child told.
                WishRun held = marshal.run(e.wishId());
                String worker = held == null ? null : held.workerId() != null ? held.workerId() : held.pickedUpBy();
                com.moddingfromamod.agent.AgentSession.recycleWorker(worker,
                        "wish " + e.wishId().toString().substring(0, 8) + " " + e.line());
            }
            if (e.reason() == WishTimeoutPolicy.Reason.NEVER_CLAIMED) {
                // Nobody took it WHILE A WORKER WAS FREE — the evidence that puts Moddy to bed
                // on, so the next child is told at once rather than after his own two minutes.
                ModdyTired.global().wishNeverClaimed(now, e.line());
            }
            settle(server, e);
        }
        // A server's plug-in reads its own evidence here (the quota windows, the spend cap), before the
        // announcement below, so a cause they set is announced in the same sweep. Nothing in the public mod.
        for (HealthHook h : com.moddingfromamod.plugin.Plugins.shared().all(HealthHook.class)) {
            try {
                h.afterSweep(now);
            } catch (RuntimeException e) {
                LOG.warn("health hook failed after the wish sweep: {}", e.toString());
            }
        }
        announceTiredness(now);
    }

    /**
     * Put back every wish whose worker the Muse shim saw die of a spent quota or a failed login
     *. The pure decision is {@link com.moddingfromamod.marshal.DeadWorkerRequeue}; this is
     * the Minecraft half, in the order a re-opened wish already takes ({@code WishCancel.reaim}):
     * the book back to {@code PENDING} (not a final status, so nothing is refunded or settled, and
     * the wish bank does not charge the same wish twice), the dead slot recycled, the wish offered
     * to the real workers' queue. The child's bar stays up: as far as he can tell, it is still
     * being granted — which is true.
     */
    public static void requeueAccountDeaths(MinecraftServer server) {
        Optional<Path> dir = com.moddingfromamod.agent.MuseShimState.dir();
        // the public fallback chain records its own deaths (a worker whose window ran out), in memory.
        if (dir.isEmpty() && !com.moddingfromamod.agent.ModelFallbackRuntime.anyDeath()) {
            return;
        }
        var roster = AgentSession.roster();
        var back = com.moddingfromamod.marshal.DeadWorkerRequeue.sweep(Marshal.global(),
                roster::nameOfSession,
                worker -> dir.flatMap(d -> com.moddingfromamod.agent.MuseShimState.death(d, worker))
                        .map(d -> new com.moddingfromamod.marshal.DeadWorkerRequeue.Death(
                                d.reason(), d.atMs()))
                        .or(() -> com.moddingfromamod.agent.ModelFallbackRuntime.death(worker)));
        for (var r : back) {
            dir.ifPresent(d -> com.moddingfromamod.agent.MuseShimState.consumeDeath(d, r.worker()));
            com.moddingfromamod.agent.ModelFallbackRuntime.consumeDeath(r.worker());
            String shortId = r.run().wishId().toString().substring(0, 8);
            String why = "its worker " + r.worker() + " died (" + r.reason() + ") -- re-queued for the "
                    + "next worker (#1572, #2536)";
            LOG.warn("wish {}: {}", shortId, why);
            AgentSession.recycleWorker(r.mcpId(), "wish " + shortId + " " + why);
            WishBook book = WishBook.get(server);
            // Its note (the submit-time `result`) goes with it: the next worker reads it as the
            // first one did.
            String note = book.byId(r.run().wishId()).map(com.moddingfromamod.wish.Wish::result)
                    .orElse("");
            book.complete(r.run().wishId(), WishStatus.PENDING, note).ifPresent(w -> putBack(server, w));
        }
    }

    /**
     * Put a wish back in line, as a fresh one would be routed (#81): to the workers' queue while the Magician answers
     * its player, ended kindly otherwise ({@link com.moddingfromamod.wish.MagicianDoor#turnBack}). Public: the
     * sweeper's own step, driven by a GameTest without killing a real worker.
     */
    public static void putBack(MinecraftServer server, com.moddingfromamod.wish.Wish w) {
        if (com.moddingfromamod.wish.MagicianDoor.answersNow(server, w.playerId())) {
            WishQueue.global().offer(w);
        } else {
            com.moddingfromamod.wish.MagicianDoor.turnBack(server, w, "the sweeper");
        }
    }

    /** A test seam: pretend the pool was last full at this moment (0 to forget). */
    public static void lastSaturatedForTest(long atMs) {
        lastSaturatedMs = atMs;
    }

    private static volatile String lastTiredLine = "";

    /** The last tired/awake line this class logged, for a gate to assert on. */
    public static String lastTiredLineForTest() {
        return lastTiredLine;
    }

    /**
     * Tell the operator when Moddy falls asleep or wakes. ONE {@code MODDY-ALERT} per episode
     * — {@link ModdyTired#transition} only answers on a change — so a whole evening's outage is one
     * page, not one per refused wish. Called from the health loop and from every refusal, so the
     * first child refused and the operator hear about it in the same second.
     */
    public static void announceTiredness(long nowMs) {
        if (!ModdyTired.armed()) {
            return;
        }
        ModdyTired.global().transition(nowMs,
                com.moddingfromamod.safety.ChildSafetyConfig.enforcing()).ifPresent(t -> {
                    lastTiredLine = t.line();
                    if (t.entered()) {
                        LOG.warn(t.line());
                    } else {
                        LOG.info(t.line());
                    }
                });
    }

    private static void settle(MinecraftServer server, WishTimeoutPolicy.Expiry expiry) {
        UUID id = expiry.wishId();
        WishBook book = WishBook.get(server);
        var wish = book.byId(id);
        String shortId = wish.map(com.moddingfromamod.wish.Wish::shortId)
                .orElse(id.toString().substring(0, 8));
        book.complete(id, WishStatus.TIMED_OUT, expiry.line());
        Marshal.global().finish(id);
        WishProgress.clearWishBar(id);
        wish.ifPresent(w -> {
            ServerPlayer player = server.getPlayerList().getPlayer(w.playerId());
            if (player != null) {
                WishFeedback.fellAsleep(player);
            }
            com.moddingfromamod.agent.ActionLog.wish(w.text(), "TIMED_OUT",
                    w.source() == null ? "" : w.source().name(), shortId);
        });
        // ONE LINE PER WISH, and it is the wish id that makes it joinable to everything else the
        // harvest bundle carries. Not deduped, unlike the agent-failure alert: each of these is a
        // different child's wish, and suppressing the second would hide the case that matters
        // most — the whole queue timing out at once.
        //
        // Built once and remembered, so a gate can assert on the very string that was logged
        // rather than on a second copy of the format — the shape that let TrainFraming's 90.0 be
        // right at the definition and wrong at both call sites (docs/CAMERA.md).
        String alert = timeoutAlert(shortId, expiry);
        lastAlert = alert;
        LOG.warn(alert);
    }
}
