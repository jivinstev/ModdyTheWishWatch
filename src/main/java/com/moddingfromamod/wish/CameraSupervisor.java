package com.moddingfromamod.wish;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.RunContext;
import com.moddingfromamod.marshal.PlayerKind;
import com.moddingfromamod.runtime.RuntimeConfig;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Something that launches, watches and stops the camera</b> —, the operational blocker
 * under both.
 *
 * <p>The DECISION is {@link CameraLifecycle}, which is Minecraft-free and unit-tested. This is the
 * hands: it counts children, spawns a process, notices when it dies, and stops it. Split that way
 * because the rule is where the edge cases are and the process is where the platform is.
 *
 * <h2>⚠ Three gates before it does anything at all</h2>
 *
 * <ol>
 *   <li><b>A DEDICATED server only.</b> On a child's own machine the mod set is arbitrary and
 *       unknown, so a camera cannot be guaranteed to render what it is pointed at — and spawning a
 *       second Minecraft on a seven-year-old's laptop is not a thing to do quietly. This is
 *       {@code docs/CAMERA_CLIENT.md} §3's topology decision, enforced rather than described.</li>
 *   <li><b>{@code cameraName} configured.</b> Empty is the singleplayer case and means "no camera",
 *       which is the shipped default.</li>
 * </ol>
 *
 * <p>With either of those false this class is inert, and inert is the shipped state.
 *
 * <h2>⚠ It does not OWN the only way a camera can exist</h2>
 *
 * <p>A third condition gates the SPAWNING half alone: a launcher must be findable
 * ({@link RuntimeConfig#cameraLauncher}). With none, this class has no hands — and that is a
 * WARN rather than an error, because a camera started by something else (a harness, a service
 * manager) works perfectly: {@link #serving} asks the SERVER who is connected, never this class
 * what it spawned. Conflating the two would put a false sentence in the log on every run of
 * {@code tools/wish-paths.sh}, which starts its own camera.
 *
 * <h2>Why a process and not a thread</h2>
 *
 * <p>A camera is a whole Minecraft client — its own GL context, its own resource packs, its own
 * game directory. It cannot live inside the server JVM, and the state separation is the point:
 * §6f measured three processes with three roots and nothing shared, which is what makes the
 * camera's resource reloads cost the child nothing.
 */
public final class CameraSupervisor {

    private CameraSupervisor() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-camera-supervisor");

    /** Only look every this many ticks; the decision is about seconds, not frames. */
    private static final int TICK_INTERVAL = 40;

    private static int tickCounter;
    private static Process camera;
    private static volatile boolean starting;
    private static long startedAtMs;
    private static long idleSinceMs;
    private static final AtomicInteger FAILURES = new AtomicInteger();
    private static volatile boolean gaveUp;
    /** So "there is no launcher here" is one line per server, not one every two seconds. */
    private static volatile boolean noLauncherSaid;
    /** Body one has been seen in the world since it was started ({@link CameraHang#judge}). */
    private static boolean cameraWasJoined;
    /** Bodies being dumped and put down off the server thread; no fresh START while any is. */
    private static final AtomicInteger RECYCLING = new AtomicInteger();

    // ── The EXTRA bodies. Body one is the `camera` above and CameraLifecycle's to run;
    // bodies two and up are CameraPool's, keyed by pool index (1 = "<name>-2"). ──────────────
    private static final java.util.Map<Integer, Process> EXTRAS = new java.util.TreeMap<>();
    private static final java.util.Map<Integer, Long> EXTRA_STARTED_MS = new java.util.HashMap<>();
    /** Extra bodies seen joined since they were started ({@link CameraHang#judge}). */
    private static final java.util.Set<Integer> EXTRA_JOINED = new java.util.HashSet<>();
    private static long waitingSinceMs;
    private static long extraIdleSinceMs;

    /**
     * <b>Is a camera photographing RIGHT NOW?</b> — the question an earlier issue's deferral asks, and it must
     * be the live answer rather than "one is configured".
     *
     * <p>Deliberately asks the SERVER whether a non-child is actually connected, rather than
     * trusting this class's own idea of whether it spawned something. A process that is alive but
     * has not joined, or has been kicked, or crashed without exiting, would all read as "up" from
     * a {@code Process.isAlive()} check — and every one of those is a window where the agent
     * photographs through the child and withholding his pack shows him paper.
     */
    public static boolean serving(MinecraftServer server) {
        if (server == null || starting) {
            return false;
        }
        for (var p : server.getPlayerList().getPlayers()) {
            if (!PlayerKind.isChild(p.getUUID())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a camera is named at all — for a status line, never for the deferral.
     *
     * <p>⚠ Since {@code cameraName} gained a default this is <b>true on every ordinary run</b>, and
     * it is kept rather than deleted because a deployment can still blank it deliberately
     * ({@code -Dmoddingfromamod.cameraName=off}) and because a status line that stops saying it would
     * make that choice invisible. It is NOT what decides whether a camera is up: {@link #serving}
     * is, and it asks the server.
     */
    /**
     * How long the camera now being started has been starting, or 0 when none is (one not yet
     * started is about to be — the photograph that asks has just woken it). Read by
     * {@code CameraWait.budgetMs}.
     */
    public static long startingForMs(long nowMs) {
        return starting && startedAtMs > 0 ? Math.max(0, nowMs - startedAtMs) : 0;
    }

    /** Has the supervisor given up starting a camera (a first photo stops waiting)? */
    public static boolean gaveUp() {
        return gaveUp;
    }

    public static boolean configured() {
        return !RuntimeConfig.cameraName().isEmpty();
    }

    /**
     * <b>How many wishes are waiting for a camera, over every body in the pool</b> — the
     * measurement the pool is sized from ({@link CameraPool#decide}).
     *
     * <p>Summed rather than maxed: two wishes waiting on two different bodies is two children
     * watching a progress bar, and a third body would take one of them out of line.
     */
    public static int poolWaiting(MinecraftServer server) {
        if (server == null) {
            return 0;
        }
        int waiting = 0;
        for (var p : server.getPlayerList().getPlayers()) {
            if (!PlayerKind.isChild(p.getUUID())) {
                waiting += WishCapture.queueDepth(p.getUUID());
            }
        }
        return waiting;
    }

    /** For a gate, and for the contact sheet. */
    public static String status(MinecraftServer server) {
        // The pool's live numbers ride the same line, so the tower's Wish cams card reads
        // them the way the Wish workers card reads the agent's — off health.json, never re-derived.
        //   running  camera bodies in the world now (connected, whoever started them)
        //   busy     of those, the ones a wish holds right now
        //   waiting  wishes queued for a camera, over every body (what grows the pool)
        //   allowed  camera.concurrent in force
        int running = 0;
        int busy = 0;
        if (server != null) {
            for (var p : server.getPlayerList().getPlayers()) {
                if (!PlayerKind.isChild(p.getUUID())) {
                    running++;
                    if (WishCapture.cameraHeldBy(p.getUUID()) != null) {
                        busy++;
                    }
                }
            }
        }
        return "camera{configured=" + configured() + " serving=" + serving(server)
                + " starting=" + starting + " failures=" + FAILURES.get()
                + " gaveUp=" + gaveUp + " running=" + running + " busy=" + busy
                + " waiting=" + poolWaiting(server)
                + " allowed=" + CameraProfile.fromProperties().concurrent() + "}";
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % TICK_INTERVAL != 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        // ⚠ A TEST RUN MUST NOT SPAWN A MINECRAFT CLIENT, and this became load-bearing the day
        // cameraName gained a default. Gate B is a DEDICATED server and its mock players are
        // ordinary children, so `childrenOnline > 0` is true for a few ticks of most tests --
        // which under the old `!configured()` gate meant nothing and now means START. The launcher
        // is auto-discovered by walking up to a `tools/` directory, which a checkout always has,
        // so a gate run would have spawned one client per test with nothing asking it to.
        //
        // A harness that WANTS a camera starts its own (tools/camera-client.sh) and is unaffected:
        // `serving` asks the SERVER who is connected rather than asking this class what it spawned.
        if (server == null || server.isSingleplayer() || RunContext.isTestRun() || !configured()) {
            return;
        }
        long now = System.currentTimeMillis();

        int children = 0;
        boolean cameraJoined = false;
        for (var p : server.getPlayerList().getPlayers()) {
            if (PlayerKind.isChild(p.getUUID())) {
                children++;
            } else {
                cameraJoined = true;
            }
        }
        // On a world whose cameras sleep on VMs, a camera is wanted while a WISH is in
        // flight — not while a child is merely online — so nothing runs at rest (CameraWait).
        boolean wishesMode = CameraWait.demandIsWishes();
        // Not every wish in flight — the ones that need a camera (CameraNeed's
        // state machine: a wish whose kind is not known yet still counts, however long it plans).
        lastDemand = CameraNeed.shared().demand(wishesInFlight(), now);
        int wanting = wishesMode ? lastDemand.wanting() : children;
        if (wanting > 0) {
            idleSinceMs = 0;
        } else if (idleSinceMs == 0) {
            idleSinceMs = now;
        }

        // A process that exited on its own is a FAILURE while children are online -- and the
        // reason exists is that this is otherwise invisible: Viewpoint.acquire quietly falls
        // back to the child, which is correct degradation and looks exactly like success.
        if (camera != null && !camera.isAlive()) {
            LOG.warn("the camera process exited (code {}) -- photographs will fall back to the "
                    + "child's eyes until it is back", camera.exitValue());
            camera = null;
            if (starting) {
                starting = false;
                FAILURES.incrementAndGet();
            }
        }
        // A body that was in the world and LEFT while its launcher lives (`lost connection: Timed out`)
        // is nothing CameraLifecycle can see: not up, not starting, so it would START a second launcher
        // over the first and drop the first one's handle. Dump it and put it down (CameraHang).
        String bodyOne = RuntimeConfig.cameraName();
        boolean bodyOneOnline = false;
        for (var p : server.getPlayerList().getPlayers()) {
            if (!PlayerKind.isChild(p.getUUID()) && p.getGameProfile().getName().equalsIgnoreCase(bodyOne)) {
                bodyOneOnline = true;
            }
        }
        if (camera != null && bodyOneOnline) {
            cameraWasJoined = true;
        }
        if (camera != null && CameraHang.judge(camera.isAlive(), bodyOneOnline, cameraWasJoined, false)
                == CameraHang.Verdict.RECYCLE) {
            recycle(camera, "camera body 1 ('" + bodyOne + "')");
            camera = null;
            starting = false;
            cameraWasJoined = false;
            cameraJoined = false;
            for (var p : server.getPlayerList().getPlayers()) {
                cameraJoined |= !PlayerKind.isChild(p.getUUID());
            }
        }
        if (starting && cameraJoined) {
            starting = false;
            FAILURES.set(0);
            LOG.info("camera joined after {} ms", now - startedAtMs);
        }

        // The drain ("keep it for the wishes still running") counts only wishes that want a camera in
        // wishes mode, or an item wish would hold a VM through its whole grant.
        var state = new CameraLifecycle.State(wanting, wishesMode ? lastDemand.wanting() : WishProgress.inFlight(),
                cameraJoined, starting, idleSinceMs, startedAtMs, FAILURES.get());

        CameraLifecycle.vmBodies(CameraWait.demandIsWishes());
        switch (CameraLifecycle.decide(state, now, CameraWait.lingerMs())) {
            case START -> {
                // Not while a hung body is still being dumped: two clients under one name would
                // fight for it, and the dump takes seconds, not minutes (CameraHang.DUMP_TIMEOUT_MS).
                if (RECYCLING.get() == 0) {
                    start(server, now);
                }
            }
            case STOP -> stop(state.wishesInFlight() > 0);
            case GIVE_UP -> {
                if (!gaveUp) {
                    gaveUp = true;
                    // LOUD, ONCE. A silent absence is the failure being removed: every photograph
                    // from here goes through the child, and nothing else would say so.
                    LOG.error("giving up on the camera after {} consecutive failed starts. Every "
                            + "photograph will now borrow the child's eyes, and because no camera "
                            + "is connected #515's pack deferral stops withholding -- so he pays "
                            + "the ~6.7 s install per sculpt again, which is correct and is the "
                            + "cost of this failure.", FAILURES.get());
                }
            }
            default -> { }
        }
        superviseExtras(server, now);
    }

    /**
     * <b>Grow the pool when a wish has been waiting, shrink it when an extra body idles.</b>
     *
     * <p>The size is read off the queue ({@link #poolWaiting}), never guessed: a second body starts
     * only after a wish has waited {@link CameraPool#GROW_AFTER_MS}, and the growth is logged WITH
     * that depth, so the question "is {@code camera.concurrent} the right number" is answered from
     * the server log rather than from intuition.
     */
    /**
     * The wishes a camera is wanted for right now ({@link CameraNeed#inFlight}). Public for
     * a GameTest that drives it through a real canary submit.
     */
    public static java.util.Set<java.util.UUID> wishesInFlight() {
        java.util.List<java.util.UUID> live = new java.util.ArrayList<>();
        for (var run : com.moddingfromamod.marshal.Marshal.global().live()) {
            live.add(run.wishId());
        }
        return CameraNeed.inFlight(WishProgress.inFlightIds(), live, WishProgress::endedForChild);
    }

    /** The last look's camera demand (CameraNeed) — body one's want, the pool's forecast. */
    private static volatile CameraNeed.Demand lastDemand = CameraNeed.Demand.NONE;

    /** For the health line and a test: what the supervisor last saw. */
    public static CameraNeed.Demand lastDemand() {
        return lastDemand;
    }

    private static void superviseExtras(MinecraftServer server, long now) {
        CameraProfile profile = CameraProfile.fromProperties();
        java.util.List<String> names = CameraPool.names(RuntimeConfig.cameraName(), CameraPool.MAX);
        // Reap extras that died on their own; a body that is gone is not "running".
        EXTRAS.entrySet().removeIf(e -> {
            if (!e.getValue().isAlive()) {
                LOG.warn("camera body {} exited (code {})", e.getKey() + 1, e.getValue().exitValue());
                EXTRA_STARTED_MS.remove(e.getKey());
                EXTRA_JOINED.remove(e.getKey());
                return true;
            }
            return false;
        });
        java.util.Set<String> online = new java.util.HashSet<>();
        for (var p : server.getPlayerList().getPlayers()) {
            if (!PlayerKind.isChild(p.getUUID())) {
                online.add(p.getGameProfile().getName().toLowerCase(java.util.Locale.ROOT));
            }
        }
        boolean extraStarting = false;
        for (var it = EXTRAS.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            boolean joined = e.getKey() < names.size()
                    && online.contains(names.get(e.getKey()).toLowerCase(java.util.Locale.ROOT));
            if (joined) {
                EXTRA_STARTED_MS.remove(e.getKey());
                EXTRA_JOINED.add(e.getKey());
                continue;
            }
            if (CameraHang.judge(e.getValue().isAlive(), false, EXTRA_JOINED.contains(e.getKey()), false)
                    == CameraHang.Verdict.RECYCLE) {
                recycle(e.getValue(), "camera body " + (e.getKey() + 1) + " ('"
                        + (e.getKey() < names.size() ? names.get(e.getKey()) : "?") + "')");
                EXTRA_STARTED_MS.remove(e.getKey());
                EXTRA_JOINED.remove(e.getKey());
                it.remove();
                continue;
            }
            Long since = EXTRA_STARTED_MS.get(e.getKey());
            if (since != null && now - since < CameraLifecycle.startTimeoutMs()) {
                extraStarting = true;
            } else {
                // Alive and never joined (or joined and was since kicked): a body the server
                // cannot see photographs nothing, and counting it as "running" would stop the
                // pool growing. Put it down; the next GROW starts a fresh one.
                LOG.warn("camera body {} is not connected -- stopping it", e.getKey() + 1);
                end(e.getValue(), false);
                EXTRA_STARTED_MS.remove(e.getKey());
                it.remove();
            }
        }
        int waiting = poolWaiting(server);
        if (waiting > 0) {
            if (waitingSinceMs == 0) {
                waitingSinceMs = now;
            }
            extraIdleSinceMs = 0;
        } else {
            waitingSinceMs = 0;
            if (extraIdleSinceMs == 0) {
                extraIdleSinceMs = now;
            }
        }
        int running = (camera != null ? 1 : 0) + EXTRAS.size();
        var state = new CameraPool.State(running, online.size(), starting || extraStarting,
                waiting, waitingSinceMs, extraIdleSinceMs, profile.concurrent(),
                // the forecast only where a body is a camera VM: on a server whose cameras are local
                // Minecraft clients an extra body is gigabytes, so it waits for a queue
                CameraWait.demandIsWishes() ? CameraNeed.bodiesFor(lastDemand, profile.concurrent()) : 0);
        switch (CameraPool.decide(state, now)) {
            case GROW -> {
                int index = 1;
                while (EXTRAS.containsKey(index)) {
                    index++;
                }
                if (index < names.size()) {
                    LOG.info("camera pool: {} wish(es) waiting for {} ms on {} body(ies), {} s of "
                            + "camera time in flight -- starting body {} of at most {}", waiting,
                            waitingSinceMs == 0 ? 0 : now - waitingSinceMs, running,
                            Math.round(lastDemand.seconds()), index + 1, profile.concurrent());
                    startExtra(server, index, names.get(index), now);
                }
            }
            case SHRINK -> {
                if (!EXTRAS.isEmpty()) {
                    int last = ((java.util.TreeMap<Integer, Process>) EXTRAS).lastKey();
                    LOG.info("camera pool: nobody waiting -- stopping body {}", last + 1);
                    end(EXTRAS.remove(last), false);
                    EXTRA_STARTED_MS.remove(last);
                    EXTRA_JOINED.remove(last);
                    extraIdleSinceMs = now;
                }
            }
            default -> { }
        }
    }

    private static void startExtra(MinecraftServer server, int index, String name, long now) {
        Path launcher = RuntimeConfig.cameraLauncher();
        if (launcher == null) {
            return;   // start() already said so, once
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(launcher.toString());
            pb.environment().put("PORT", String.valueOf(server.getPort()));
            pb.environment().put("CAMERA_NAME", name);
            pb.environment().putAll(CameraProfile.fromProperties().launchEnv());
            // Its OWN lane: a client-test.sh SLOT is a signal dir and a working directory, and two
            // bodies sharing one would clear each other's signals and fight over one game dir.
            String lane = ModdingFromAModMod.MODID + "-camera-" + (index + 1);
            pb.environment().put("SLOT", "camera-" + (index + 1));
            pb.environment().put("SIG", "/tmp/" + lane);
            pb.environment().put("LOGS", "/tmp/" + lane + "-logs");
            // client-test.sh caps live clients per machine (default 2) to protect a dev box. On a
            // camera host the pool IS the client population, so the cap is the pool's own.
            pb.environment().put("MFAM_MAX_CLIENTS",
                    String.valueOf(Math.max(2, CameraProfile.fromProperties().concurrent())));
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File("/tmp/" + lane + "-launch.log"));
            // No stdin, cross-platform: close the pipe rather than open /dev/null, which a
            // Windows host does not have (PlatformParityTest holds that debt from growing).
            Process body = pb.start();
            body.getOutputStream().close();
            EXTRAS.put(index, body);
            EXTRA_STARTED_MS.put(index, now);
            EXTRA_JOINED.remove(index);
            LOG.info("starting camera body {} as '{}' on port {}", index + 1, name, server.getPort());
        } catch (Exception e) {
            LOG.error("could not start camera body " + (index + 1), e);
        }
    }

    private static void start(MinecraftServer server, long now) {
        Path launcher = RuntimeConfig.cameraLauncher();
        if (launcher == null) {
            // ⚠ NOT "the deferral is off", and not an ERROR either. Nothing here owns the only way
            // a camera can exist: a harness launches its own, and so would a service manager. The
            // deferral asks CameraSupervisor.serving(), which asks the SERVER who is connected --
            // so an externally-started camera works perfectly and this class simply has no hands.
            // Saying "photographs will use the child's eyes" here would be a false statement in a
            // log on every run of tools/wish-paths.sh, which starts a camera itself.
            if (!noLauncherSaid) {
                noLauncherSaid = true;
                LOG.warn("no camera launcher found (looked for tools/camera-client.sh above {}; "
                        + "override with -D{}.cameraLauncher). A camera started by something else "
                        + "still works -- 'serving' asks the server who is connected, not this "
                        + "class what it spawned.",
                        Path.of("").toAbsolutePath(), ModdingFromAModMod.MODID);
            }
            return;
        }
        try {
            String port = String.valueOf(server.getPort());
            ProcessBuilder pb = new ProcessBuilder(launcher.toString());
            pb.environment().put("PORT", port);
            pb.environment().put("CAMERA_NAME", RuntimeConfig.cameraName());
            // ── HO-14: how big, and how fast ────────────────────────────────────────────────
            // A typical server box has no serious GPU (Q9), so a camera is told 1280x720 at 30 fps
            // rather than whatever the launcher's defaults happen to be. ⚠ A profile that is
            // built and not APPLIED is decoration — this line is the whole reason CameraProfile
            // is not one — and the exit criterion is a PNG that is 1280x720, not a green test.
            CameraProfile profile = CameraProfile.fromProperties();
            pb.environment().putAll(profile.launchEnv());
            LOG.info("camera profile: {}", profile.describe());
            // ── 🔴 THE CAMERA MUST NOT INHERIT THE SERVER'S SIGNAL DIRECTORY ──────────────────
            //
            // A ProcessBuilder inherits this JVM's environment, and a server launched by a rig has
            // SIG set to that rig's own directory. camera-client.sh takes SIG from the environment,
            // and client-test.sh CLEARS its signal directory on start -- so the camera deletes the
            // log the thing that started it is still writing to.
            //
            // Measured: server.log vanished mid-run, every wait on it hung for 21 minutes, and the
            // camera's own "FAILED" line landed in the rig's `result` file where the wish verdict
            // goes -- so a failed camera read as a failed wish. camera-client.sh's own header
            // records the same collision from the other side ("the log lives OUTSIDE the signal dir
            // on purpose ... which deleted a log two-children.sh had just opened there").
            //
            // Set explicitly rather than cleared, so the camera always has its own place to write
            // and a reader always knows where that is.
            pb.environment().put("SIG", "/tmp/" + ModdingFromAModMod.MODID + "-camera");
            pb.environment().put("LOGS", "/tmp/" + ModdingFromAModMod.MODID + "-camera-logs");
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File("/tmp/" + ModdingFromAModMod.MODID + "-camera-launch.log"));
            pb.redirectInput(new File("/dev/null"));
            camera = pb.start();
            starting = true;
            cameraWasJoined = false;
            startedAtMs = now;
            LOG.info("starting a camera on port {} as '{}' (attempt {})",
                    port, RuntimeConfig.cameraName(), FAILURES.get() + 1);
        } catch (Exception e) {
            FAILURES.incrementAndGet();
            LOG.error("could not start the camera", e);
        }
    }

    private static void stop(boolean drainedOut) {
        if (camera == null) {
            return;
        }
        if (drainedOut) {
            // Said out loud because it is the bounded half of "wait for the last wish": a wish
            // that never ends would otherwise hold a Minecraft open for the life of the server.
            LOG.warn("stopping the camera with wishes still in flight -- the drain cap of {} ms "
                    + "was reached, which means a wish is wedged", CameraLifecycle.DRAIN_CAP_MS);
        }
        // in wishes mode the clock is "no wish in flight", not "nobody online" — the old words
        // were logged with the child standing right there.
        LOG.info(CameraWait.demandIsWishes() ? "stopping the camera (no wish in flight for {} ms)"
                        : "stopping the camera (nobody online for {} ms)",
                idleSinceMs == 0 ? 0 : System.currentTimeMillis() - idleSinceMs);
        end(camera, false);
        camera = null;
        starting = false;
        cameraWasJoined = false;
        // The pool lives and dies with body one: its extras exist only to shorten a queue, and
        // with nobody online there is no queue.
        for (Process p : EXTRAS.values()) {
            end(p, false);
        }
        EXTRAS.clear();
        EXTRA_STARTED_MS.clear();
        EXTRA_JOINED.clear();
    }

    /**
     * <b>A body that left the game while its launcher lives</b>: one loud line, then a thread dump
     * of its JVM into {@link CameraHang#LOG} and the stop, on a daemon thread — a hung JVM's attach
     * can take seconds and the server thread must not wait on it. A plain thread with its own
     * catch, not an executor: a task that dies in a Future says nothing (CLAUDE.md).
     */
    private static void recycle(Process body, String who) {
        LOG.error("{} LEFT THE GAME WHILE ITS PROCESS IS STILL ALIVE -- a hung or frozen client (it "
                + "stopped answering keep-alives). Writing what it was doing to {} and stopping it; the "
                + "next look starts a fresh body if a wish wants one", who, CameraHang.LOG.toAbsolutePath());
        RECYCLING.incrementAndGet();
        Thread t = new Thread(() -> {
            try {
                String outcome = CameraHang.recycle(body, who, CameraHang.LOG, CameraSupervisor::endPatiently);
                LOG.warn("{} put down: {}", who, outcome);
            } catch (RuntimeException e) {
                LOG.error("could not dump " + who + "; stopping it anyway", e);
                endPatiently(body);
            } finally {
                RECYCLING.decrementAndGet();
            }
        }, "mfam-camera-recycle");
        t.setDaemon(true);
        t.start();
    }

    /**
     * TERM (so a remote camera launcher releases its lease), and KILL only what a hung JVM ignored. The tree is
     * read BEFORE the TERM: camera-launch.sh's trap exits at once, and a launcher that has exited has
     * no descendants to ask about — its hung client would be re-parented to init and left running.
     */
    static void endPatiently(Process body) {
        endPatiently(body, 15_000);
    }

    static void endPatiently(Process body, long patienceMs) {
        java.util.List<ProcessHandle> tree = new java.util.ArrayList<>(body.descendants().toList());
        tree.add(body.toHandle());
        end(body, false);
        long deadline = System.currentTimeMillis() + patienceMs;
        for (ProcessHandle h : tree) {
            long left = deadline - System.currentTimeMillis();
            try {
                if (left > 0) {
                    h.onExit().get(left, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
                // still alive: forced below
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    /**
     * Stop a camera body AND everything it started.
     *
     * <p>⚠ A launcher is a shell with a Minecraft (and an Xvfb) under it. {@code destroyForcibly}
     * is SIGKILL, which a shell cannot trap, so killing only the launcher ORPHANED the client:
     * measured on a rehearsal server, the camera's JVM (2 GB) was still running, re-parented to
     * init, after the server it served had stopped — and the next start would have been a second
     * {@code Moddy-Camera} fighting the first for the name. {@link ProcessHandle#descendants} is
     * the JDK's own answer on every OS, so nothing here goes looking in the process table.
     */
    static void end(Process p, boolean forcibly) {
        if (p == null) {
            return;
        }
        try {
            p.descendants().forEach(forcibly ? ProcessHandle::destroyForcibly : ProcessHandle::destroy);
        } catch (RuntimeException e) {
            LOG.warn("could not stop a camera's children", e);
        }
        if (forcibly) {
            p.destroyForcibly();
        } else {
            p.destroy();
        }
    }

    /** A camera must never outlive the server it was photographing for. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (camera != null) {
            LOG.info("server stopping -- taking the camera with it");
            end(camera, true);
            camera = null;
        }
        for (Process p : EXTRAS.values()) {
            end(p, true);
        }
        EXTRAS.clear();
        EXTRA_STARTED_MS.clear();
        EXTRA_JOINED.clear();
        cameraWasJoined = false;
        waitingSinceMs = 0;
        extraIdleSinceMs = 0;
        starting = false;
        gaveUp = false;
        noLauncherSaid = false;
        FAILURES.set(0);
        idleSinceMs = 0;
    }
}
