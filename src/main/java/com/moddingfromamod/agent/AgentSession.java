package com.moddingfromamod.agent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.safety.ChildSafetyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The wish-granting agent, <b>owned by the mod and living exactly as long as it does</b>.
 *
 * <p>Previously a person started a daemon by hand and it outlived every game. That was wrong in
 * three ways at once, and binding the session to the mod fixes all three for free:
 * <ul>
 *   <li><b>Context reset.</b> A fresh session per run means a restarted game gets a clean-slate
 *       agent, with no reset mechanism to build or remember to trigger.</li>
 *   <li><b>Multiple instances.</b> Each game binds its <i>own</i> ephemeral MCP port and hands that
 *       port to its <i>own</i> agent, so two Minecrafts get two isolated agents instead of fighting
 *       over one fixed port.</li>
 *   <li><b>No stray daemon.</b> Nothing keeps running — and burning tokens — after the game closes.</li>
 * </ul>
 *
 * <p>The agent is started once a world is actually open and stopped when the server stops. Start-up
 * costs ~40 s before the first tool call (measured), which is why it begins at world load rather
 * than on the first wish: by the time a child has found the watch, the agent is already parked on
 * {@code await_wish} and answers in milliseconds.
 *
 * <p>⚠ <b>Except after twenty quiet minutes</b> (step 4, 2026-09-24): with
 * nobody touching Moddy the pool is stopped even while children play, and {@link #wake} starts it
 * again the moment a child opens the watch — so the boot overlaps him reading the panel, and a wish
 * that still has to wait for it says so once ({@code WishFeedback.wakingUp}).
 *
 * <h2>Deliberately off during tests</h2>
 * Gates must never spawn a real agent: it would cost tokens, act autonomously on a test world, and
 * make runs non-deterministic. {@link #shouldRun()} refuses unless this is a genuine play session.
 *
 * <h2>Orphan guard</h2>
 * A child process outliving a {@code kill -9}'d game would sit burning tokens forever. Java's
 * {@code destroy()} covers a clean exit; for the unclean one the agent is told to stop when its
 * endpoint stops answering, so the game vanishing is itself the shutdown signal.
 */
public final class AgentSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** {@code -Dmoddingfromamod.agent=off} to play without an agent; {@code =on} to force it. */
    private static final String ENABLE_PROPERTY = ModdingFromAModMod.MODID + ".agent";

    /**
     * {@code -Dmoddingfromamod.model=sonnet} pins the wish-granting model. Unset (the normal case)
     * leaves the choice to the CLI, so a real game follows whatever the machine is configured for.
     */
    private static final String MODEL_PROPERTY = ModdingFromAModMod.MODID + ".model";

    /**
     * {@code -Dmoddingfromamod.effort=low} pins the reasoning effort. Unset leaves it to the CLI,
     * which for Opus 5 means its default of {@code high}.
     *
     * <p>Effort is fixed when the session is CREATED — there is no way to change it on a running
     * agent — so this is the only place it can be set, and a change means a fresh-memory agent.
     *
     * <p>⚠ An unrecognised value does not fail: the CLI warns and silently falls back to the
     * default. So a typo here does not produce a broken run, it produces a run that measures the
     * default while claiming to measure something else. {@link #VALID_EFFORTS} rejects it here
     * instead, and every arm verifies the applied effort from the session transcript afterwards.
     */
    private static final String EFFORT_PROPERTY = ModdingFromAModMod.MODID + ".effort";

    private static final List<String> VALID_EFFORTS =
            List.of("low", "medium", "high", "xhigh", "max");

    /**
     * <b>What a child's game actually runs</b>, unless a property says otherwise.
     *
     * <p>Chosen from measurement rather than taste ({@code docs/MODEL_EVAL.md}). Over the
     * seven named creatures, against Opus 5 at its default {@code high}:
     *
     * <ul>
     *   <li><b>696s against 1704s</b> — 59% less waiting, faster on every single creature;</li>
     *   <li><b>zero</b> CBScript compile failures, the same as high effort (Sonnet 5 had two);</li>
     *   <li>14 sculpt passes against 15 — no extra redos;</li>
     *   <li><b>16/16</b> effect selections and 7/10 item selections, matching high effort exactly;</li>
     *   <li>it never once claimed {@code DONE} it had not established — the honesty regression that
     *       ruled Sonnet out does not appear here.</li>
     * </ul>
     *
     * <p>The cost, stated so it is not forgotten: it verifies far less (3 calls against 10). The
     * child is likelier to receive an ability that silently does not work — and to be TOLD it might
     * not be right. That is an argument for an objective oracle, not against low effort.
     *
     * <p><b>The pair is what was validated, so the pair is what ships.</b> Opus at low effort is
     * measured; Sonnet at low effort is not, and would be what a bare {@code effort=low} produced on
     * a machine whose CLI default model was something else.
     *
     * <p>Override either with {@code -Dmoddingfromamod.model=…} / {@code .effort=…}, or pass
     * {@code default} to hand the choice back to the CLI.
     */
    private static final String DEFAULT_MODEL = "opus";

    private static final String DEFAULT_EFFORT = "low";

    /** The model to grant wishes as: the property, else the shipped default, else the CLI's. */
    public static String resolvedModel() {
        // The agent.model knob wins over the boot property. Read at each worker launch.
        // the host's wish-model pick (MagicianWiring applies it only where no override locks the model).
        java.util.Optional<String> picked = com.moddingfromamod.config.MagicianWiring.mainSpec();
        if (picked.isPresent()) {
            return picked.get();
        }
        String explicit = com.moddingfromamod.config.ModdySettings.get("agent.model")
                .orElse(System.getProperty(MODEL_PROPERTY, "")).trim();
        if (explicit.equalsIgnoreCase("default")) {
            return "";
        }
        return explicit.isEmpty() ? DEFAULT_MODEL : explicit;
    }

    /**
     * The reasoning effort: the property, else the shipped default, else the CLI's.
     *
     * <p>An unrecognised value falls back to the shipped default rather than being passed on. The
     * CLI itself would merely warn and use ITS default, which turns a typo into a session that
     * silently runs at a different effort than every artefact claims.
     */
    public static String resolvedEffort() {
        String explicit = com.moddingfromamod.config.ModdySettings.get("agent.effort")
                .orElse(System.getProperty(EFFORT_PROPERTY, "")).trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (explicit.equals("default")) {
            return "";
        }
        if (explicit.isEmpty()) {
            return DEFAULT_EFFORT;
        }
        if (!VALID_EFFORTS.contains(explicit)) {
            LOGGER.warn("ignoring -D{}={}: not one of {}. Falling back to the shipped default '{}'.",
                    EFFORT_PROPERTY, explicit, VALID_EFFORTS, DEFAULT_EFFORT);
            return DEFAULT_EFFORT;
        }
        return explicit;
    }

    /**
     * The launcher processes, one per worker.
     *
     * <p>A LIST because the pool is however many wishes may be granted at once. At the default of
     * one slot this holds exactly one and every behaviour is identical to before — raising it is a
     * flag, not a redesign, which is the point: the scheduler, the leases and the per-wish context
     * were all built and gated first, so this is the last and smallest piece.
     */
    private static final java.util.Map<String, Process> processes = new java.util.LinkedHashMap<>();

    /**
     * Which worker is which: slot name ↔ CLI session id ↔ MCP session id. Keyed by the same
     * slot names as {@link #processes}. See {@link com.moddingfromamod.marshal.WorkerRoster}.
     */
    private static final com.moddingfromamod.marshal.WorkerRoster ROSTER =
            new com.moddingfromamod.marshal.WorkerRoster();

    /** The pool's roster, for the MCP server to record who is calling. */
    public static com.moddingfromamod.marshal.WorkerRoster roster() {
        return ROSTER;
    }

    /**
     * Bumped by {@link #stop()}. The staggered launcher checks it before each worker, so a pool
     * stopped mid-stagger does not go on filling itself in the background.
     */
    private static volatile int poolGeneration;

    /**
     * How long the pool took to become usable — measured, because the claim that start-up is paid
     * before a child reaches his watch was made when there was one worker and never re-checked with
     * four. See {@link com.moddingfromamod.marshal.ColdStart}.
     */
    private static final com.moddingfromamod.marshal.ColdStart COLD_START =
            new com.moddingfromamod.marshal.ColdStart();

    public static com.moddingfromamod.marshal.ColdStart coldStart() {
        return COLD_START;
    }
    private static Path configFile;

    /**
     * The CLI's own id for the background session, parsed from its output.
     *
     * <p>Load-bearing. {@code --bg} <b>detaches</b>: the process we launch prints
     * {@code backgrounded · <id> · <name>} and exits within seconds, so {@code destroy()} kills
     * nothing. Without this id the agent would outlive the game — the stray daemon this whole class
     * exists to prevent. Measured, not assumed: a probe confirmed the launcher was dead 12s after
     * start while the agent ran on.
     */
    private static final List<String> sessionIds =
            java.util.Collections.synchronizedList(new ArrayList<>());

    /**
     * <b>How each worker was STARTED, keyed by its id in {@link #sessionIds}</b>: the program path and the CLI
     * config folder of its launch. Every stop reads these and never {@link ClaudeCli#resolve()} (null once the
     * Magician is off or no Claude model is picked) nor {@link #workerCliDir()} (worked out from the CURRENT wish
     * model). See {@link WorkerStopper}.
     */
    private static final java.util.Map<String, WorkerStopper.Launch> LAUNCHES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The {@code claude daemon run} processes this game started (see {@link WorkerStopper}, "The daemon"). */
    private static final java.util.Map<Long, WorkerStopper.Daemon> MODDY_DAEMONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Pool providers: which program runs the wish workers. */
    static final String PROVIDER_CLAUDE = "claude-code";
    static final String PROVIDER_MUSE = "muse-code";

    /**
     * The provider the running pool was started for, or null with no pool. A pool started for one provider
     * is stopped when the picks move to the other ({@link #stopIfProviderChanged()}), and {@link #start} replaces it
     * rather than returning early.
     */
    private static volatile String poolProvider;

    /** The provider the picks choose now. A seam so a unit test can switch it without settings. */
    static volatile java.util.function.Supplier<String> currentProvider = () ->
            com.moddingfromamod.config.MagicianWiring.museMain().isPresent() ? PROVIDER_MUSE : PROVIDER_CLAUDE;

    private AgentSession() {
    }

    /**
     * Is this a real play session?
     *
     * <p>Anything test-shaped is refused. The GameTest server and the automated client test both
     * set recognisable properties, and an explicit {@code =off} always wins so a grown-up can play
     * without an agent running.
     */
    public static boolean shouldRun() {
        String explicit = System.getProperty(ENABLE_PROPERTY, "");
        if (explicit.equalsIgnoreCase("off") || explicit.equalsIgnoreCase("false")) {
            return false;
        }
        if (explicit.equalsIgnoreCase("on") || explicit.equalsIgnoreCase("true")) {
            return true;
        }
        // Magician off (the public default): no worker at all.
        if (!com.moddingfromamod.config.Magician.enabled() && !com.moddingfromamod.config.Magician.checking()) {
            return false;
        }
        // Never spend tokens in the gates, and never let a second autonomous actor fight the
        // client test for control of the world.
        return !com.moddingfromamod.RunContext.isTestRun();
    }

    /** Start the agent for this run, if it should run at all. Safe to call twice. */
    public static synchronized void start(String claudeCli) {
        String provider = currentProvider.get();
        if (!processes.isEmpty()) {
            if (provider.equals(poolProvider)) {
                return;
            }
            // a pool started for the other provider (Claude workers, Muse picks now) is not "up": replace it.
            LOGGER.info("the wish pool was started for {} and the picks now run {}: stopping it before starting again",
                    poolProvider, provider);
            stop();
        }
        // Asked to run, so no longer parked: if this start fails with a child online, /healthz
        // says 503 again, which is the truth.
        parked = false;
        if (!shouldRun()) {
            LOGGER.info("wish agent not started (Magician mode is off, a test run, or disabled with -D{}=off)",
                    ENABLE_PROPERTY);
            return;
        }
        int port = McpHttpServer.boundPort();
        if (port <= 0) {
            // NOTED as well as logged: a WARN line on the console is invisible to a
            // watchdog, and in these cases no CLI was ever started, so there is no
            // agent*.log for the classifier to read either. See health/AgentWarnings.
            String why = "wish agent not started: the MCP endpoint isn't listening";
            LOGGER.warn(why);
            com.moddingfromamod.health.AgentWarnings.note(why);
            return;
        }
        // the host's picks run the wish model on Muse Code; then the binary is `muse`, whatever was passed.
        boolean muse = com.moddingfromamod.config.MagicianWiring.museMain().isPresent();
        if (muse) {
            claudeCli = MuseExec.binary();
        }
        if (claudeCli == null || claudeCli.isBlank()) {
            String why = muse ? "wish agent not started: could not find Muse Code (install it, then `muse login`)"
                    : "wish agent not started: could not find the Claude CLI (set -D"
                    + ModdingFromAModMod.MODID + ".cli=/path/to/claude)";
            LOGGER.warn(why);
            com.moddingfromamod.health.AgentWarnings.note(why);
            return;
        }
        int cap = com.moddingfromamod.marshal.WishSlots.slots();
        COLD_START.poolStarting(System.currentTimeMillis());
        try {
            // A previous boot's complaint is not this boot's state: cleared here so a pool that
            // was restarted after the login was fixed stops reporting `not_logged_in` in
            // health.json. Anything that fails below notes itself again immediately.
            com.moddingfromamod.health.AgentWarnings.clear();
            configFile = writeMcpConfig(port);
            if (!muse) {
                // Claude Code only: Muse Code workers are this JVM's own children, never a --bg daemon.
                trustWorkspace(configFile.getParent());
                reapFinishedAgents(claudeCli);
            }
            // ONE worker, not the whole pool. The rest are started only when a wish actually takes
            // the last free one -- see startSupervisor and PoolPlan. Worker 1 goes up inline so
            // `processes` is non-empty the moment this returns and the re-entry guard above holds.
            poolProvider = provider;
            startOne(claudeCli, port, cap);
            lastSpawnMs = System.currentTimeMillis();
            lastPlayersSeenMs = lastSpawnMs;   // a pool that just booted has not been abandoned
            lastActivityMs = lastSpawnMs;      // ...and has not been ignored either
            // Only if a worker really launched: a pool whose first launch failed must stay
            // wakeable, or the next watch click would find "up" and never try again.
            poolUp = !processes.isEmpty();
            deafStreak = 0;
            // EVERY pool is supervised, including a one-slot pool. It used to start only
            // when cap > 1, which meant a one-worker pool was never reaped when the world emptied
            // and a deaf lone worker — the worst case, a pool with nobody listening at all — was
            // never replaced. The supervisor's top-up is a no-op at cap 1 (no room), so the only
            // things it adds there are exactly those two.
            startSupervisor(claudeCli, port, cap, poolGeneration);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not start the wish agents: {}", e.toString());
            com.moddingfromamod.health.AgentWarnings.note(
                    "could not start the wish agents: " + e);
        }
    }

    /** Consecutive supervisor ticks with a worker unaccounted for. See PoolPlan.DEAF_TICKS. */
    private static int deafStreak;

    /**
     * When somebody was last online, so an empty world can be reaped (step 2).
     *
     * <p>Set at {@link #start} rather than at zero: a pool that has just booted has not been
     * abandoned, and starting this at the epoch would make the very first supervisor tick of every
     * world reap the worker it had just launched.
     */
    private static volatile long lastPlayersSeenMs;

    /**
     * When anybody last TOUCHED Moddy — a join, a watch right-click, a wish submitted, or a worker
     * holding a wish (step 4). The second reap rule reads it: twenty quiet minutes with
     * children online stops the pool, and the next touch wakes it.
     *
     * <p>Set at {@link #start} for the same reason {@link #lastPlayersSeenMs} is: a pool that has
     * just booted has not been ignored.
     */
    private static volatile long lastActivityMs;

    /**
     * True from the moment {@link #reapIfNobodyIsPlaying()} stops an idle pool until the next
     * {@link #start}. A PARKED pool is not a broken one: it wakes on the next watch right-click or
     * wish, so {@code /healthz} must not call the server down for it. It did (2026-09-26): the
     * pool parked with two children online and idle, /healthz answered 503 for twelve minutes,
     * and an external health check paged that "children cannot reach Moddy" while they played.
     */
    private static volatile boolean parked;

    /** @see #parked */
    public static boolean isParked() {
        return parked;
    }

    /**
     * Whether a pool is up, read WITHOUT the class monitor. {@link #isRunning()} is
     * {@code synchronized}, and the monitor is held across process launches — seconds — so the
     * server thread asking "will this wish wait for a boot?" must not queue behind it. Written only
     * by {@link #start} and {@link #stop}, both under the monitor.
     */
    private static volatile boolean poolUp;

    /** A wake in flight — see {@link #wake}. */
    private static final java.util.concurrent.atomic.AtomicBoolean WAKING =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * <b>A child showed INTENT to wish</b> (2026-10-10): the watch's panel opened, the
     * Wish-now anvil opened, or he typed in it. Starts the pool if it was stopped — so its boot runs
     * while he chooses and types — and remembers the signal, so the wish he submits can say how far
     * ahead of it the pool was asked ({@link com.moddingfromamod.marshal.WishIntent}). Switching to
     * the watch in the hotbar is deliberately NOT intent. Cheap enough for every keystroke: a
     * volatile read when the pool is up.
     *
     * @param kind watch | anvil | key
     */
    public static void intent(java.util.UUID player, String kind) {
        com.moddingfromamod.marshal.WishIntent.global().note(player, kind, System.currentTimeMillis());
        wake(switch (kind) {
            case "anvil" -> "the Wish-now anvil";
            case "key" -> "typing a wish";
            default -> "the watch";
        });
    }

    /**
     * Somebody touched Moddy: the activity clock the online reap rule reads (step 4).
     *
     * <p>Lock-free and cheap: a volatile write, safe from the server thread and the MCP handler
     * threads alike.
     */
    public static void touch() {
        lastActivityMs = System.currentTimeMillis();
    }

    /** Milliseconds since anybody touched Moddy, for the readers ({@code HealthService}). */
    public static long msSinceActivity() {
        return System.currentTimeMillis() - lastActivityMs;
    }

    /**
     * <b>WAKE: the first sign a child is about to wish</b> (step 4, option C).
     *
     * <p>Touches the clock and, if the pool was reaped, starts it — off the server thread, because
     * {@link #start} holds the class monitor while it launches a CLI. Called on a watch right-click
     * (both routes: {@code VanillaCarrierAbilities} for a stock client, {@code WishWatchItem} for a
     * modded one) so the ~10–13 s boot overlaps the child reading the panel or typing, and on every
     * submitted wish as the backstop for {@code /wish} typed in chat, which no earlier signal
     * precedes.
     *
     * <p>A no-op wherever the agent may not run ({@link #shouldRun()}): the gates never launch a
     * real worker, and neither does this.
     */
    public static void wake(String why) {
        touch();
        if (!wakeWanted.getAsBoolean()) {
            return;
        }
        // ONE waker at a time (2026-10-10): every keystroke in the Wish-now anvil wakes the
        // pool now, and a child types a dozen characters inside one ~9 s boot. Without this each one
        // made a thread that queued on start()'s monitor only to find the pool up. It is also what
        // keeps two joins at once from racing two starts — and start() is synchronized and
        // idempotent behind it, so even a waker that slipped past would find the pool up.
        if (!WAKING.compareAndSet(false, true)) {
            return;
        }
        // A PLAIN DAEMON THREAD with its own try/catch, never an executor (CLAUDE.md: a scheduled task
        // that dies takes its reason into a Future nobody reads). Throwable, not RuntimeException: an
        // Error here (a linkage error, an OOM in a launch) would otherwise end the thread with nothing
        // said, and the pool would simply never come up. Whatever happens, WAKING is cleared, so the
        // next join, watch click or wish tries again.
        Thread t = new Thread(() -> {
            try {
                LOGGER.info("starting the wish pool off the server thread ({})", why);
                wakeStart.run();
            } catch (Throwable e) {
                LOGGER.warn("could not start the wish pool ({}): {} — the next join or wish tries again",
                        why, e.toString());
                com.moddingfromamod.health.AgentWarnings.note("could not start the wish agents: " + e);
            } finally {
                WAKING.set(false);
            }
        }, "wish-pool-wake");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Whether a wake has anything to do: the pool is down and may run here. A seam so a test
     * can drive {@link #wake} where the agent never runs — the gates' {@link #shouldRun()} is false.
     */
    static volatile java.util.function.BooleanSupplier wakeWanted = () -> !poolUp && shouldRun();

    /**
     * What a wake runs on its own thread: {@link #start} with the resolved CLI. A seam so a
     * test can hand it a slow fake and measure that the server thread never waits for it.
     * {@link ClaudeCli#resolve()} is inside it on purpose: it probes the file system and is part of
     * the cost a join used to pay on the server thread.
     */
    static volatile Runnable wakeStart = () -> start(ClaudeCli.resolve());

    /** True while a wake's thread is starting the pool: read by tests, never by the game. */
    static boolean waking() {
        return WAKING.get();
    }

    /**
     * Wake for a SUBMITTED wish, and say whether it will have to wait for a boot.
     *
     * <p>The answer is decided BEFORE waking, from the pool as the wish found it: not running, or
     * launched within the boot grace with nobody listening yet ({@code IdlePolicy.wakeSaysSo}).
     * False wherever the agent does not run, so a gate's wishes never carry the line. (The wake
     * itself also happens in {@code WishQueue.offer}, the door every agent-bound wish uses; this
     * one only exists to answer the question first.)
     */
    public static boolean wakeForWish() {
        boolean says = shouldRun() && com.moddingfromamod.marshal.IdlePolicy.wakeSaysSo(
                poolUp, System.currentTimeMillis() - lastSpawnMs,
                WishQueue.global().waitingWorkers());
        wake("a wish");
        return says;
    }

    /**
     * How many players are online — the signal {@link IdlePolicy} reaps on.
     *
     * <p>A field rather than a direct call so the reap path can be driven where it RUNS rather than
     * only where it is defined. That distinction is this repo's most expensive recurring lesson:
     * an earlier issue's melter gate and an earlier issue's note rule were both green for months while the code they
     * guarded had never once executed in a real session. {@code AgentReapGameTest} substitutes this
     * and drives {@link #reapIfNobodyIsPlaying()} itself.
     */
    static volatile java.util.function.IntSupplier playerCount = AgentSession::livePlayerCount;

    /**
     * How many workers are holding a wish — the safety condition that stops a reap mid-grant.
     *
     * <p>⚠ A field for the same reason as {@link #playerCount}, and the A/B that proved it was
     * needed is worth recording: with this read straight off {@code Marshal.global()}, the reap
     * gate's assertions passed <b>whether or not the code under them worked</b>. GameTests in one
     * batch run concurrently and share every static, so a neighbouring test holding a live wish
     * makes {@code working()} non-empty, which makes {@code shouldReap} false, which makes an
     * {@code assertFalse} pass for entirely the wrong reason. Deliberately breaking the presence
     * clock produced a green run — the test was vacuous and would have shipped that way.
     *
     * <p>Substituting it also buys the thing that could not be tested at all before: that a wish in
     * flight really does veto a reap.
     */
    static volatile java.util.function.IntSupplier workersHoldingAWish =
            () -> com.moddingfromamod.marshal.Marshal.global().working().size()
                    + com.moddingfromamod.marshal.Marshal.global().pickingUp().size();   //

    /**
     * ⚠ Returns <b>-1</b>, not 0, when there is no server to ask.
     *
     * <p>0 means "the world is empty", which is a reason to reap. "I do not know" is not, and the
     * two failure modes are not symmetric — an unreaped pool costs money, a wrongly reaped one
     * costs a child their wish. {@link IdlePolicy#shouldReap} tests {@code != 0} precisely so this
     * lands on the money side.
     */
    static int livePlayerCount() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return -1;
        }
        try {
            // CHILDREN. A camera never leaves, so counting it kept the pool from ever reaping.
            return com.moddingfromamod.wish.CameraPresence.childrenOnline(server);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Stop the whole pool if nobody has been online for long enough (step 2).
     *
     * <p>The WHOLE pool, deliberately. Reaping a chosen worker needs a map from the marshal's
     * {@code workerId} (an MCP session id) to {@link #sessionIds()} (CLI session ids), and those are
     * different namespaces that nothing correlates — names that plumbing as the blocker for
     * everything else it wants. Stopping all of them needs no such map, and an empty world is
     * exactly the case where "all of them" is the right answer anyway.
     *
     * <p>{@link #stop()} is reused rather than reimplemented: it bumps the generation, stops every
     * session by id through {@code claude stop} — never {@code kill}, which measured makes the
     * daemon restart the agent ~60 s later from its transcript — and clears the process list. The
     * generation bump is also what ends the supervisor that called this.
     *
     * @return whether the pool was stopped, so the caller can stand down
     */
    static synchronized boolean reapIfNobodyIsPlaying() {
        if (!wouldReapNow()) {
            return false;
        }
        if (processes.isEmpty()) {
            return false;   // nothing to stop; do not log about it every three seconds
        }
        int players = playerCount.getAsInt();
        if (players > 0) {
            LOGGER.info("{} player(s) online but nobody has touched Moddy for {} minutes and no wish "
                    + "is in flight — stopping the wish pool (#364 step 4). It wakes on the next "
                    + "watch right-click or wish.", players,
                    (System.currentTimeMillis() - lastActivityMs) / 60_000L);
        } else {
            LOGGER.info("nobody has been online for {} minutes and no wish is in flight — stopping "
                    + "the wish pool (#364). It starts again when somebody joins, behind the "
                    + "loading screen.", (System.currentTimeMillis() - lastPlayersSeenMs) / 60_000L);
        }
        stop();
        parked = true;
        return true;
    }

    /**
     * <b>Stop the pool because the server is about to sleep</b> — {@code /moddyop agent reap},
     * run by a server's own sleep tooling after the last look and before it commits.
     *
     * <p>An idle pool never holds a server awake: the sleep clock starts when the last
     * active player leaves, and the pool is reaped HERE rather than waited for (its grace used to
     * run in SERIES with the sleep timer, ~26 + 30 minutes after every session). A worker granting a
     * wish is never taken away: that refuses, and the sleep is called off, exactly as a wish in
     * flight always blocked it.
     *
     * <p>The stop itself ({@link #stop()}: one {@code claude stop} per session, up to 10 s each) runs
     * on a daemon thread, because this is called on the server thread through RCON; the caller confirms
     * with {@code /moddyop health} that {@code agent.workers} reached 0.
     *
     * @return one line for RCON: {@code reaping N worker(s)…}, {@code nothing to reap…} or {@code refused: …}
     */
    public static String reapForSleep() {
        int busy = workersHoldingAWish.getAsInt();
        if (busy != 0) {
            return "refused: " + busy + " worker(s) are granting a wish";
        }
        int n;
        synchronized (AgentSession.class) {
            n = processes.size();
        }
        if (n == 0) {
            return "nothing to reap: the pool is already stopped";
        }
        Thread t = new Thread(() -> {
            try {
                stop();
                LOGGER.info("stopped the wish pool ({} worker(s)) because the server is going to sleep; "
                        + "the next join starts it again", n);
            } catch (RuntimeException e) {
                LOGGER.warn("could not stop the wish pool for a sleep: {}", e.toString());
            }
        }, "wish-pool-reap-for-sleep");
        t.setDaemon(true);
        t.start();
        return "reaping " + n + " worker(s) for a sleep";
    }

    /**
     * The DECISION, split from the act of stopping so a gate can drive it.
     *
     * <p>A gate cannot launch a real pool — {@code shouldRun()} is false under tests, and rightly
     * so — which would leave the interesting branch of {@link #reapIfNobodyIsPlaying()} unreachable
     * and the whole rule tested only in {@code IdlePolicyTest}, where it is DEFINED. This repo's
     * most expensive recurring lesson is that such a rule is not tested where it RUNS: an earlier issue's
     * melter gate and an earlier issue's note rule were both green for months over code that had never once
     * executed. So the decision is a method, and {@code AgentReapGameTest} drives this one against
     * the real {@link com.moddingfromamod.marshal.Marshal} with a substituted player count.
     *
     * <p>It also keeps the presence clock, which is why it is not a pure function: seeing anybody
     * — including the {@code -1} "there is no server to ask" answer — is what resets the absence.
     */
    static synchronized boolean wouldReapNow() {
        int players = playerCount.getAsInt();
        int busy = workersHoldingAWish.getAsInt();
        long now = System.currentTimeMillis();
        if (busy != 0) {
            lastActivityMs = now;      // a grant in progress is activity for as long as it runs
        }
        if (players != 0) {
            lastPlayersSeenMs = now;   // includes the -1 "don't know" case, which must not reap
            // step 4: children online, nobody touching Moddy. -1 answers false there too.
            return com.moddingfromamod.marshal.IdlePolicy.shouldReapWhileOnline(
                    players, now - lastActivityMs, busy);
        }
        return com.moddingfromamod.marshal.IdlePolicy.shouldReap(players, now - lastPlayersSeenMs,
                busy);
    }

    /**
     * <b>The same question {@link #wouldReapNow()} answers, asked by a READER</b> — the metrics
     * scrape and {@code HealthSnapshot} (HO-6).
     *
     * <p>⚠ Two differences from {@code wouldReapNow}, and both are the reason this exists rather
     * than being a call to it.
     *
     * <ol>
     *   <li><b>It does not keep the clock.</b> {@code wouldReapNow} resets {@code
     *       lastPlayersSeenMs} as a side effect, which is right for the supervisor that runs on a
     *       fixed tick and wrong for a scrape that lands whenever Prometheus feels like it: the
     *       absence a child's disconnection started would be measured from the last SCRAPE
     *       instead.</li>
     *   <li><b>It takes no lock.</b> {@code wouldReapNow} is {@code synchronized} on this class,
     *       and the pool holds that monitor across process launches — seconds at a time. A
     *       {@code /metrics} scrape that can block for seconds is a {@code /metrics} that stops
     *       answering exactly when something is wrong, which is the one moment it is for. All
     *       three fields it reads are {@code volatile}, so the read is sound without it.</li>
     * </ol>
     *
     * <p>The cost of being lock-free is that the answer can be a scrape old. That is the right
     * trade for a gauge: {@code desiredWorkers} is a LEVEL the runner converges on, so a stale
     * read self-corrects on the next poll — the property {@code PoolPlan}'s javadoc is built on.
     */
    public static boolean reapWanted() {
        int players = playerCount.getAsInt();
        int busy = workersHoldingAWish.getAsInt();
        long now = System.currentTimeMillis();
        if (players != 0) {
            // Reads the activity clock and never writes it, for the reason above.
            return com.moddingfromamod.marshal.IdlePolicy.shouldReapWhileOnline(
                    players, now - lastActivityMs, busy);
        }
        return com.moddingfromamod.marshal.IdlePolicy.shouldReap(
                players, now - lastPlayersSeenMs, busy);
    }

    /** Pretend the world emptied this long ago. Test seam for the reap gate; nothing else sets it. */
    static synchronized void lastPlayersSeenForTest(long ms) {
        lastPlayersSeenMs = ms;
    }

    /** Pretend nobody has touched Moddy since {@code ms}. Test seam for the online reap gate. */
    static synchronized void lastActivityForTest(long ms) {
        lastActivityMs = ms;
    }

    /** When the pool last launched a worker, so a booting one is not mistaken for a deaf one. */
    private static volatile long lastSpawnMs;

    /**
     * When a worker was last launched, for whoever needs to know a wait was a BOOT (HO-14).
     *
     * <p>Lock-free on a volatile read, for {@code reapWanted()}'s reason: this is read from
     * {@code WishQueue.await}, on the path a child is waiting on, and blocking there for the pool's
     * monitor — which is held across process launches — would make the measurement cost more than
     * the thing it measures.
     */
    public static long lastSpawnMs() {
        return lastSpawnMs;
    }

    /** How often the supervisor asks whether the pool still has a free worker. */
    static final long SUPERVISE_EVERY_MS = 3_000L;

    /**
     * Keep exactly one worker free, adding another the moment a wish takes the last spare.
     *
     * <p>A daemon that POLLS rather than a callback fired from the marshal, deliberately: the
     * assignment happens on the wish-submission path, and CLAUDE.md's rule is that nothing on that
     * path may block. Launching a process there would put a fork+exec between a child pressing the
     * button and the agent hearing them. Three seconds of latency on the *replacement* worker costs
     * nobody anything, because the wish that triggered it already has a worker.
     *
     * <p>A plain daemon thread rather than a {@code ScheduledExecutorService} for the reason
     * CLAUDE.md records: a scheduled task whose exception lands in a {@code Future} nobody reads
     * fails silently and completely.
     */
    private static void startSupervisor(String claudeCli, int port, int bootCap, int generation) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(SUPERVISE_EVERY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    synchronized (AgentSession.class) {
                        if (generation != poolGeneration) {
                            return; // pool stopped; stop supervising it
                        }
                        // BEFORE the top-up, or an empty world would spend a boot replacing a
                        // deaf worker one tick before deciding it wanted no workers at all.
                        if (reapIfNobodyIsPlaying()) {
                            return;
                        }
                        // NAMED deafness first: a slot that has not been heard listening or
                        // working for DEAF_STOP_MS is stopped, so the top-up below replaces it
                        // rather than counting it as capacity forever.
                        replaceDeafWorkers(System.currentTimeMillis());
                        // An EXTRA worker (beyond the one spare) idle past the grace is
                        // stopped — at most one per tick, never the last free one, so the top-up
                        // below has nothing to answer it with.
                        stopIdleExtra(System.currentTimeMillis());
                        // LIVE, not the value captured at start: the pool.slots knob may move
                        // while the pool runs, and a raised cap must be usable at the next wish.
                        int cap = com.moddingfromamod.marshal.WishSlots.slots();
                        int running = processes.size();
                        // a worker that picked a wish up and is deciding its gates or waiting for its
                        // brief is busy, not deaf: counted here, it starts no spare and is never stopped as deaf.
                        int busy = com.moddingfromamod.marshal.Marshal.global().working().size()
                                + com.moddingfromamod.marshal.Marshal.global().pickingUp().size();
                        int parked = WishQueue.global().waitingWorkers();
                        long now = System.currentTimeMillis();

                        // A worker neither working nor listening is either mid-stride between two
                        // await_wish calls (a second or two) or deaf (forever). Only the
                        // second deserves a replacement, so the deficit has to persist.
                        boolean settled =
                                now - lastSpawnMs > com.moddingfromamod.marshal.PoolPlan.BOOT_GRACE_MS;
                        int unaccounted = settled
                                ? com.moddingfromamod.marshal.PoolPlan.deafCount(running, busy, parked)
                                : 0;
                        deafStreak = unaccounted > 0 ? deafStreak + 1 : 0;
                        int deaf = deafStreak >= com.moddingfromamod.marshal.PoolPlan.DEAF_TICKS
                                ? unaccounted : 0;
                        // Logged, never PAGED: ~9 s between two await_wish calls is a slow
                        // model turn as often as a deaf worker — measured on the rig, Muse took 8.9 s
                        // on a healthy worker that had just granted a wish, and this line paged the
                        // owner. What it buys is capacity (a spare listener), not a diagnosis; the
                        // NAMED check below is the one that knows, and the one that alerts.
                        if (deaf > 0 && deafStreak == com.moddingfromamod.marshal.PoolPlan.DEAF_TICKS) {
                            LOGGER.info("{} wish worker(s) neither working nor listening for ~{} s — "
                                    + "not counted as a listener until heard again (#352); "
                                    + "starting a spare if the pool has room", deaf,
                                    com.moddingfromamod.marshal.PoolPlan.DEAF_TICKS * SUPERVISE_EVERY_MS / 1000);
                        }

                        // A deaf worker is discounted as a listener but still occupies a slot, so
                        // a persistently deaf pool costs a bounded number of boots, not a stream.
                        int want = com.moddingfromamod.marshal.PoolPlan
                                .spawnsNeeded(running - deaf, busy, running, cap);
                        for (int i = 0; i < want; i++) {
                            LOGGER.info("no wish worker is free to listen (running {}, busy {}, "
                                    + "parked {}, presumed deaf {}) — starting worker {} of at most "
                                    + "{}", running, busy, parked, deaf, running + 1 + i, cap);
                            startOne(claudeCli, port, cap);
                            lastSpawnMs = System.currentTimeMillis();
                            deafStreak = 0;
                        }
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("wish pool supervisor: {}", e.toString());
                }
            }
        }, "wish-agent-supervisor");
        t.setDaemon(true);
        t.start();
    }


    /**
     * <h2>⚠ FORKING worker 1's conversation into the rest was tried and does NOT work — measured,
     * twice, do not re-try it </h2>
     *
     * <p>It is the obvious idea and a good one: the CLI has {@code --resume <id> --fork-session},
     * so workers 2..N could inherit worker 1's finished boot instead of each doing their own. In a
     * bounded {@code -p} harness it looked spectacular — an added worker fell from write 34,257
     * over three turns to write 886 over one, a 93–95% cut.
     *
     * <p>In the production shape ({@code --bg}, real prompt, real tool list, per-worker {@code -n}
     * name, and an {@code await_wish} that actually BLOCKS) it evaporates. Two runs of
     * {@code tools/fork-arm.py --workers 3}:
     *
     * <pre>
     *   run 1   independent $0.655   forked $0.912    (forking 39% WORSE)
     *   run 2   independent $0.687   forked $0.645    (forking 6% better)
     * </pre>
     *
     * The independent arm is tight across runs; the forked arm is not, because a resumed
     * conversation sometimes matches nothing at all — run 1's worker 2 wrote 45,087 tokens with
     * <b>zero</b> cache reads, having inherited a whole boot it then could not reuse. Averaged,
     * forking is slightly worse and much less predictable, and it would buy that with a permanent
     * inherited history (cause 3) and roughly triple the RSS per worker.
     *
     * <p><b>The measurement that IS worth keeping</b>, from the same runs: a worker goes from
     * launch to parked on {@code await_wish} in <b>4.4–8.1 s</b> (12 workers, both arms), and costs
     * about <b>$0.21</b> to boot. Booting a worker is therefore cheap and quick enough to do on
     * demand — which is the argument for starting fewer of them at world open rather than for
     * making the boot cleverer.
     */

    /** Memoised {@code --help} text, so the pool probes the CLI once rather than once per worker. */
    private static volatile String cliHelp;

    /**
     * The CLI's own {@code --help}, used to ask whether it knows a flag before we pass it.
     *
     * <p>Asking the binary beats parsing its version number: it stays correct if the flag is
     * renamed or backported. A failure returns empty, which reads as "does not support it" — the
     * safe direction, because the alternative is a worker that refuses to start at all.
     */
    private static String cliHelpText(String claudeCli) {
        String cached = cliHelp;
        if (cached != null) {
            return cached;
        }
        String help = "";
        try {
            Process p = new ProcessBuilder(claudeCli, "--help")
                    .redirectErrorStream(true)
                    .redirectInput(new File("/dev/null"))
                    .start();
            byte[] out = p.getInputStream().readAllBytes();
            if (p.waitFor(20, TimeUnit.SECONDS)) {
                help = new String(out, StandardCharsets.UTF_8);
            } else {
                p.destroyForcibly();
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("could not read the CLI's --help: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cliHelp = help;
        return help;
    }

    /**
     * One parked worker.
     *
     * <p>Each gets its own NAME so it is distinguishable in {@code claude agents} and in the app —
     * without that, a grown-up watching two agents work sees two identical rows and cannot tell
     * which is granting what. They are distinguished to the MOD by their MCP session id, which the
     * CLI establishes on its own, so nothing here has to allocate one.
     *
     * <h2>The name was suspected of splitting the prompt cache. It does not. MEASURED — do not
     * re-try this </h2>
     *
     * <p>It is a reasonable suspicion and it survives a long way. Under {@code --bg} the name is not
     * merely a display label: the CLI writes it into the conversation as {@code agent-name} and
     * {@code custom-title} records, and those are the FIRST records in the session — ahead of the
     * prompt and every attachment. A prefix is hashed in order, so a per-worker name sitting at the
     * front looks exactly like something that would invalidate everything behind it. Diffing two
     * workers' transcripts record by record, it was also the <b>only</b> difference before the first
     * assistant turn: same 31,233-char prompt, same tool deltas, same skill listing, same token
     * reminder.
     *
     * <p>It was tried anyway, and it changed nothing. Two real pool boots on a warm machine, one
     * with a name per worker and one with a single shared name, first-turn totals over three
     * workers:
     *
     * <pre>
     *   wish-granter-1/2/3 (per worker)   write 58,959   read 73,098
     *   wish-granter       (shared)       write 58,960   read 73,098
     * </pre>
     *
     * One token apart. The name is simply not part of what the cache is keyed on, so the pool keeps
     * the per-worker name and the legibility that comes with it.
     *
     * <p><b>What the numbers actually say</b>, and it is the useful part: each worker reads a shared
     * 24,366-token head and writes a 19,653-token tail. The head is genuinely shared — across
     * workers, and across pool boots within the TTL. The tail is written per conversation and no
     * amount of aligning the workers changes it: it is still written when every record before the
     * first turn is byte-identical, and when worker 1's response completed a full ten seconds before
     * workers 2 and 3 asked. Treat that tail as the price of a worker (~$0.21 at list), not as a bug
     * to be tuned away.
     */
    /**
     * The CLI refuses to start in an untrusted directory, and every worker runs in the pool's own
     * — so on a fresh machine every worker died at launch and read as DEAF. See WorkspaceTrust.
     */
    private static void trustWorkspace(Path dir) {
        // a redirected worker reads ITS OWN config dir, so the record goes there too.
        Path own = workerCliDir();
        if (own != null) {
            try {
                com.moddingfromamod.worker.WorkspaceTrust.ensure(own.resolve(".claude.json"), dir);
            } catch (IOException e) {
                LOGGER.warn("could not record {} as trusted in {}: {}", dir, own, e.toString());
            }
        }
        try {
            var out = com.moddingfromamod.worker.WorkspaceTrust.ensure(
                    com.moddingfromamod.worker.WorkspaceTrust.configPath(), dir);
            if (out == com.moddingfromamod.worker.WorkspaceTrust.Outcome.WRITTEN) {
                LOGGER.info("trusted the wish workers' directory {} in {} — without it the CLI "
                        + "refuses to start there", dir, com.moddingfromamod.worker.WorkspaceTrust.configPath());
            } else if (out == com.moddingfromamod.worker.WorkspaceTrust.Outcome.UNREADABLE) {
                LOGGER.warn("{} is not JSON the CLI wrote, so it was left alone — if every wish worker "
                        + "logs 'Workspace not trusted', run `claude` once in {} and accept the prompt",
                        com.moddingfromamod.worker.WorkspaceTrust.configPath(), dir);
            }
        } catch (IOException e) {
            LOGGER.warn("could not record {} as trusted for the CLI: {} — workers may refuse to start",
                    dir, e.toString());
        }
    }

    private static void startOne(String claudeCli, int port, int workers) {
        // The lowest free slot, so a replacement takes the name of the worker it replaced and
        // `claude agents` never shows wish-granter-7 in a three-slot pool.
        String name = ROSTER.nextName(AGENT_NAME, workers);
        int worker = processes.size() + 1;
        // the host's chain (the pick, then magician.fallback) decides THIS launch's model; a worker already
        // granting a wish keeps its own. Absent when an override locks the model (its launcher decides).
        java.util.Optional<ModelFallback.Entry> entry = java.util.Optional.empty();
        var chain = com.moddingfromamod.config.MagicianWiring.mainChain();
        if (chain.isPresent()) {
            ModelFallback.Decision d = ModelFallbackRuntime.decide(ModelFallbackRuntime.Chain.WISH, chain.get());
            if (d.exhausted()) {
                String why = "wish agent " + worker + " not started: every model in the chain is unavailable ("
                        + String.join("; ", d.passed().stream().map(ModelFallback.Passed::why).toList()) + ")";
                LOGGER.warn(why);
                com.moddingfromamod.health.AgentWarnings.note(why);
                return;
            }
            entry = d.entry();
            ModelFallbackRuntime.launched(name, entry.get());
            if (d.index() > 0) {
                LOGGER.info("wish agent {}: on fallback {} of the chain, {} ({})", worker, d.index(),
                        entry.get().key(), String.join("; ", d.passed().stream().map(ModelFallback.Passed::why)
                                .toList()));
            }
            if (entry.get().harness() == com.moddingfromamod.config.SupportedModels.Harness.MUSE_CODE) {
                startOneMuse(name, worker, workers, port, entry.get().paid(), entry.get().model().workerSpec());
                return;
            }
            String cli = ClaudeCli.resolve();
            if (cli != null) {
                claudeCli = cli;   // the chain moved from Muse Code to Claude Code: the pool's binary was muse
            }
        } else {
            java.util.Optional<com.moddingfromamod.config.SupportedModels.Mode> muse =
                    com.moddingfromamod.config.MagicianWiring.museMain();
            if (muse.isPresent()) {
                startOneMuse(name, worker, workers, port,
                        muse.get() == com.moddingfromamod.config.SupportedModels.Mode.API, resolvedModel());
                return;
            }
        }
        // smoke: the Claude path can be chosen AFTER the pool started on Muse Code (the host switched the
        // Magician to Claude, or the chain fell through to a Claude model), and start() trusted the workers'
        // directory only on a pool that STARTED on Claude. Every worker then logged "Workspace not trusted" and the
        // wish was never claimed until a restart. So it is ensured at every Claude launch (a no-op once recorded).
        if (configFile != null) {
            trustWorkspace(configFile.getParent());
        }
        try {
            // ⚠ ONE BUILDER, TWO CALLERS (HO-6). This argv is a security boundary —
            // `--permission-mode dontAsk` with an --allowed-tools list of MCP tools only is what
            // stops the agent reaching for Bash, Write or WebFetch. The worker container has to
            // build the same line, and two copies of a permission set is exactly the shape this
            // repo's rule is about: a defect found in one copy is a defect CLASS found. So the
            // argv lives in WorkerArgv, which is Minecraft-free precisely so a worker can use it.
            WishModel.Resolved endpoint = WishModel.resolve(entry.map(e -> e.model().workerSpec())
                    .orElseGet(AgentSession::resolvedModel), System::getenv);
            String model = endpoint.model();
            String effort = resolvedEffort();
            // SHARE ONE CACHED PREFIX ACROSS THE POOL. Conditional because an unknown
            // option is a hard error on this CLI — passing it blind would mean every wish fails on
            // an older one. See PromptCache for why an identical prompt was not already enough.
            String cliForHelp = claudeCli;
            List<String> cacheFlags = PromptCache.flags(() -> cliHelpText(cliForHelp));
            // allowedTools() is the AUTHORITY and is computed live from the registry here; the
            // generated resource WorkerArgv reads is a copy of this, and a Gate A test regenerates
            // and compares it so the two cannot drift.
            // ITS OWN CONFIG, naming it. The header rides on every MCP request, which is
            // what lets the server say which slot an MCP session belongs to — see WorkerRoster.
            Path config = writeWorkerConfig(port, name);
            if (++boots == DEAF_BOOT) {
                Files.writeString(config, Files.readString(config, StandardCharsets.UTF_8)
                        .replace(AgentToken.token(), "soak-deliberately-wrong"), StandardCharsets.UTF_8);
                LOGGER.warn("SOAK: wish agent {} (boot {}) is DELIBERATELY DEAF — its token is wrong",
                        worker, boots);
            }
            List<String> cmd = new ArrayList<>(com.moddingfromamod.worker.WorkerArgv.build(
                    claudeCli, name, prompt(), config.toString(),
                    allowedTools(), model, effort, cacheFlags));
            // its first turn waits until this endpoint has sent it its tools (writeToolsWait).
            cmd.add("--settings");
            cmd.add(writeToolsWait(config.getParent(), name).toString());
            LOGGER.info("wish agent {} of {} granting wishes as {}/{} at effort '{}'{} (cache flags: {})",
                    worker, workers, endpoint.provider(),
                    model.isEmpty() ? "<cli default>" : model,
                    effort.isEmpty() ? "<cli default>" : effort,
                    endpoint.redirects() ? " via " + endpoint.baseUrl() : "",
                    // Logged because a CONDITIONAL flag that silently never applies is
                    // indistinguishable from one that applies and does nothing. See PromptCache.
                    cacheFlags.isEmpty() ? "none — this CLI does not advertise them" : cacheFlags);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // NOT THIS SESSION'S IDENTITY. A worker launched from inside another Claude
            // session inherits its session id, remote-session wiring and messaging socket, which
            // is two agents claiming one identity. Scrubbed before anything below adds its own.
            com.moddingfromamod.worker.WorkerEnv.scrub(pb.environment());
            // Every wish tool up front, on every model.
            com.moddingfromamod.worker.ToolExposure.applyTo(pb.environment());
            // The redirect lands on THIS subprocess and nothing else — not the session that
            // started the game, not a nested CLI call, not any other tool on the machine.
            WishModel.applyTo(pb.environment(), endpoint);
            // subscription mode means the sign-in, never a key that happens to be set.
            if (entry.isPresent()) {
                com.moddingfromamod.config.MagicianWiring.applyEntry(pb.environment(), entry.get());
            } else {
                com.moddingfromamod.config.MagicianWiring.applyMain(pb.environment());
            }
            // its own CLI config dir, and so its own --bg daemon. Set AFTER the redirect,
            // which clears every CLAUDE* variable. Null for a worker on this machine's sign-in.
            // kept with the launch, so the stop uses THIS folder whatever the wish model is by then.
            Path cliDir = workerCliDir();
            WorkerCliConfig.applyTo(pb.environment(), cliDir);
            // Wonder-first or Spark-only, per world (the agent.wishModel knob). Read at THIS
            // launch, so a flip reaches the next worker and never one already granting a wish.
            // Only tools/muse-cli-shim.py reads it; any other CLI ignores the variable.
            String arm = WishModelArm.resolved();
            WishModelArm.applyTo(pb.environment(), arm);
            // which program runs Spark when the arm is the API-key fallback (the
            // agent.sparkHarness knob, claude-cli by default). Read at THIS launch, like the arm.
            String harness = SparkHarness.resolved();
            SparkHarness.applyTo(pb.environment(), harness);
            LOGGER.info("wish agent {}: agent.wishModel={} agent.sparkHarness={}", worker, arm, harness);
            // Pin the cache TTL and the CLI version. Identical for every worker on purpose: an
            // environment that differed between them is one more way to split the prefix.
            // The TTL is withheld from a REDIRECTED worker: a foreign endpoint rejects
            // `cache_control.ttl: 1h` outright and the wish then grants nothing, silently.
            PromptCache.applyTo(pb.environment(), !endpoint.redirects());
            // A clean working directory: started in the game's cwd the agent reads whatever
            // CLAUDE.md happens to be there and invents a mission from it (observed in the probe).
            pb.directory(configFile.getParent().toFile());
            // Explicitly nothing on stdin. Inheriting the JVM's stdin makes the CLI wait for input
            // that will never come — it logs "no stdin data received in 3s" and stalls that long
            // before starting (observed in the first live run).
            pb.redirectInput(new File("/dev/null"));
            pb.redirectErrorStream(true);
            // Its OWN log. One shared file interleaves two agents' output line by line, which is
            // unreadable exactly when something has gone wrong and reading it is the only recourse.
            Path log = configFile.resolveSibling(name.equals(AGENT_NAME) ? "agent.log"
                    : "agent-" + name.substring(AGENT_NAME.length() + 1) + ".log");
            // APPENDED, so a replaced worker's log is kept beside its replacement's — the deaf
            // one's last words are the evidence asks for. The id is read from the new part.
            long offset = Files.isRegularFile(log) ? Files.size(log) : 0L;
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            ROSTER.booted(name, System.currentTimeMillis());
            CLAUDE_LAUNCHES.put(log, offset);
            // the daemons alive just before this launch, so one it brings up is known to be Moddy's.
            java.util.Set<String> daemonsBefore = WorkerStopper.daemonsNow();
            Process launcher = pb.start();
            processes.put(name, launcher);
            captureSessionId(log, offset, name,
                    new WorkerStopper.Launch(claudeCli, cliDir, false, launcher), daemonsBefore);
            LOGGER.info("wish agent '{}' ({} of {}) starting against port {} — watch it with `claude agents`",
                    name, worker, workers, port);
        } catch (IOException e) {
            LOGGER.warn("could not start wish agent {}: {}", worker, e.toString());
            com.moddingfromamod.health.AgentWarnings.note(
                    "could not start wish agent " + worker + ": " + e);
        }
    }

    /**
     * One wish worker on <b>Muse Code</b>: the host's picks chose a Muse model. {@link MuseWorker} builds and
     * starts it; it is this JVM's own child, so its id goes straight into the pool's lists (no {@code backgrounded}
     * line to read) and every stop path ends it by destroying the process ({@link #stopOne} never asks a CLI).
     */
    private static void startOneMuse(String name, int worker, int workers, int port, boolean apiKey,
                                     String modelSpec) {
        String bin = MuseExec.binary();
        if (bin == null) {
            String why = "could not start wish agent " + worker + ": Muse Code (`muse`) is not installed";
            LOGGER.warn(why);
            com.moddingfromamod.health.AgentWarnings.note(why);
            return;
        }
        try {
            Path log = configFile.resolveSibling(name.equals(AGENT_NAME) ? "agent.log"
                    : "agent-" + name.substring(AGENT_NAME.length() + 1) + ".log");
            ROSTER.booted(name, System.currentTimeMillis());
            long offset = Files.isRegularFile(log) ? Files.size(log) : 0L;
            var started = MuseWorker.start(bin, configFile.getParent(), log, name, prompt(), port,
                    AgentToken.token(), modelSpec, resolvedEffort(), apiKey, MCP_CLIENT_TIMEOUT_MS);
            processes.put(name, started.getKey());
            String id = started.getValue().id();
            LAUNCHES.put(id, new WorkerStopper.Launch(bin, null, true, started.getKey()));
            synchronized (sessionIds) {
                sessionIds.add(id);
            }
            ROSTER.cliId(name, id);
            Process p = started.getKey();
            p.onExit().thenRun(() -> {
                synchronized (sessionIds) {
                    sessionIds.remove(id);   // the roster keeps the slot, so the deaf rule replaces it
                }
                LAUNCHES.remove(id);
                // what did it die of? A spent window, a rate limit or a failing sign-in skips its entry, so
                // the replacement launches on the next one, and the wish it held goes back to the queue.
                ModelFallbackRuntime.workerEnded(name, logSince(log, offset));
            });
            LOGGER.info("wish agent '{}' ({} of {}) on Muse Code against port {}", name, worker, workers, port);
        } catch (IOException e) {
            LOGGER.warn("could not start wish agent {} on Muse Code: {}", worker, e.toString());
            com.moddingfromamod.health.AgentWarnings.note("could not start wish agent " + worker
                    + " on Muse Code: " + e.getMessage());
        }
    }

    /** The Claude CLI a Claude Code worker would run, or null (the fallback chain's program check). */
    static String claudeBinary() {
        return ClaudeCli.resolve();
    }

    /**
     * The binary the wish workers run: Muse Code when the host's picks chose a Muse model, else the Claude
     * CLI. Null when this machine has neither the one it needs.
     */
    public static String workerBinary() {
        return com.moddingfromamod.config.MagicianWiring.museMain().isPresent() ? MuseExec.binary()
                : ClaudeCli.resolve();
    }

    /**
     * Stop the agent. Called on server stop and from a JVM shutdown hook.
     *
     * <p>Killing the launcher is <b>not</b> enough — it detached and exited long ago. The session
     * has to be stopped by id through the CLI, which is why {@link #sessionId} is captured at all.
     */
    public static synchronized void stop() {
        poolGeneration++;   // any stagger still pending belongs to a pool that no longer exists
        poolUp = false;
        poolProvider = null;
        List<Process> launchers = new ArrayList<>(processes.values());
        ROSTER.clear();
        List<String> ids;
        synchronized (sessionIds) {
            ids = new ArrayList<>(sessionIds);
            sessionIds.clear();
        }
        processes.clear();
        // the daemons Moddy started, with their children listed NOW — a spare pty host is re-parented to init
        // once its daemon exits, and would no longer be found under it.
        List<WorkerStopper.Daemon> daemons = new ArrayList<>();
        for (WorkerStopper.Daemon d : MODDY_DAEMONS.values()) {
            daemons.add(WorkerStopper.withChildrenNow(d));
        }
        MODDY_DAEMONS.clear();
        // The WHOLE pool is going, so each launcher's whole tree goes with it (a Muse worker and its children; a
        // Claude launcher has usually exited already, and anything under it is something it started).
        for (Process p : launchers) {
            if (p != null) {
                WorkerStopper.killTree(p.toHandle());
            }
        }
        // EVERY one of them, and one failing must not strand the rest — a stray daemon per worker
        // is exactly what this whole mechanism exists to prevent.
        for (String id : ids) {
            stopOne(id);
        }
        LAUNCHES.clear();
        for (WorkerStopper.Daemon d : daemons) {
            try {
                WorkerStopper.stopDaemon(d, n -> ReapRule.isOurs(n, AGENT_NAME));
            } catch (RuntimeException e) {
                LOGGER.warn("could not stop the claude daemon {}: {}", d.handle().pid(), e.toString());
            }
        }
    }

    /**
     * <b>The picks moved to another provider: stop the pool started for the old one</b>. Called after every
     * pick change ({@code ModdySettings.change}, so the Magician setup's {@code changed()}, a preset and
     * {@code /moddy setting} all reach it). Cheap on the calling (server) thread: two volatile reads, and the stop —
     * which takes the class monitor and waits for CLIs — runs on its own daemon thread.
     */
    public static void stopIfProviderChanged() {
        String running = poolProvider;
        if (running == null || running.equals(currentProvider.get())) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                stopIfProviderChangedNow();
            } catch (Throwable e) {
                LOGGER.warn("could not stop the wish pool after a provider change: {}", e.toString());
            }
        }, "wish-pool-switch");
        t.setDaemon(true);
        t.start();
    }

    /** {@link #stopIfProviderChanged()}'s work, synchronously: true when a pool was stopped. */
    static synchronized boolean stopIfProviderChangedNow() {
        String running = poolProvider;
        String now = currentProvider.get();
        if (running == null || running.equals(now) || (processes.isEmpty() && workerCount() == 0)) {
            return false;
        }
        LOGGER.info("the picks moved the wish workers from {} to {}: stopping every {} worker", running, now, running);
        stop();
        return true;
    }

    private static void stopOne(String id) {
        if (id == null) {
            return;
        }
        // with what STARTED it, never ClaudeCli.resolve() or workerCliDir() — see WorkerStopper.
        WorkerStopper.stop(id, LAUNCHES.remove(id));
    }

    /**
     * A TEST's stand-in worker, put in the pool as a real launch would be: {@code id} is its session id,
     * {@code launch} how it was "started", {@code provider} the provider its pool runs. Never called by the game.
     */
    static synchronized void adoptForTest(String name, String id, WorkerStopper.Launch launch, String provider) {
        processes.put(name, launch.process());
        LAUNCHES.put(id, launch);
        synchronized (sessionIds) {
            sessionIds.add(id);
        }
        ROSTER.booted(name, System.currentTimeMillis());
        ROSTER.cliId(name, id);
        poolProvider = provider;
    }

    /** A TEST's stand-in daemon "Moddy started". Never called by the game. */
    static void adoptDaemonForTest(WorkerStopper.Daemon d) {
        MODDY_DAEMONS.put(d.handle().pid(), d);
    }

    /** The first background session's id, once the CLI has reported it. Null until then. */
    public static String sessionId() {
        synchronized (sessionIds) {
            return sessionIds.isEmpty() ? null : sessionIds.get(0);
        }
    }

    /** Every parked worker's session id. */
    public static List<String> sessionIds() {
        synchronized (sessionIds) {
            return new ArrayList<>(sessionIds);
        }
    }

    /** How many worker launchers the pool is holding — what the soak samples. */
    static synchronized int runningWorkers() {
        return processes.size();
    }

    /** How many deaf workers the supervisor has stopped and replaced since this JVM started. */
    static int deafReplaced() {
        return deafReplaced;
    }

    private static volatile int deafReplaced;

    /**
     * SOAK ONLY: the Nth worker this JVM launches gets a config with a WRONG bearer token,
     * so it boots, never reaches {@code await_wish}, and is — from the pool's side — exactly an earlier issue's
     * deaf worker. 0, the default, injects nothing; only {@code tools/soak-pool.sh} sets it.
     */
    private static final int DEAF_BOOT = Integer.getInteger(ModdingFromAModMod.MODID + ".soakdeafboot", 0);

    private static int boots;

    /** How many workers are parked right now. */
    public static int workerCount() {
        synchronized (sessionIds) {
            return sessionIds.size();
        }
    }

    /**
     * <b>Will a wish submitted now reach an agent?</b> Running now, or one the wish itself wakes.
     *
     * <p>Not the same question as {@link #isRunning()}, and asking that one instead is: a
     * pool that is merely STOPPED — reaped after twenty quiet minutes, or not yet booted on a
     * dedicated server nobody has joined — is woken by the very wish being routed
     * ({@code WishQueue.offer}). So the offline matcher's "no agent" lane fired for wishes an
     * agent was about to hear, and a keyword grabbed them: a wish for fast boats on ice became a
     * speed effect on the child. Only a server that cannot launch one at all (an agentless server,
     * a gate, no CLI) is offline.
     */
    public static boolean canAnswer() {
        Boolean forced = answerableForTest;
        if (forced != null) {
            return forced;
        }
        return isRunning() || (shouldRun() && workerBinary() != null);
    }

    /** The {@link #canAnswer()} seam for a gate, which can launch no pool; {@code null} restores it. */
    public static void answerableForTest(Boolean answerable) {
        answerableForTest = answerable;
    }

    private static volatile Boolean answerableForTest;

    public static synchronized boolean isRunning() {
        synchronized (sessionIds) {
            if (!sessionIds.isEmpty()) {
                return true;
            }
        }
        for (Process p : processes.values()) {
            if (p != null && p.isAlive()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read the session id out of the CLI's output.
     *
     * <p>On a daemon thread: {@link #start} runs on the server thread during world load, and
     * waiting there for a subprocess to print would stall the game.
     */
    private static final java.util.regex.Pattern BACKGROUNDED =
            java.util.regex.Pattern.compile("backgrounded\\s*\\W\\s*(\\w{6,})");

    /**
     * The session id the CLI printed AFTER byte {@code offset} of an appended log, or null.
     *
     * <p>⚠ Sliced as BYTES, then decoded. The first version sliced the decoded string at the byte
     * offset under a comment claiming the two agree because "the banner is ASCII up to the id". It
     * is not: the CLI prints {@code backgrounded · <id>}, and {@code ·} is two bytes. Every earlier
     * banner in the file moved the character index further behind the byte offset, so the slice
     * began PAST the new id, the id was never read, and the worker could not be stopped — found by
     * the pool soak on the second run into one log, when two of three workers leaked.
     */
    static String sessionIdAfter(byte[] log, long offset) {
        int from = (int) Math.max(0, Math.min(offset, log.length));
        String fresh = new String(log, from, log.length - from, StandardCharsets.UTF_8);
        var m = BACKGROUNDED.matcher(fresh);
        return m.find() ? m.group(1) : null;
    }

    /** Each Claude Code worker log, and where its newest launch's output starts (for {@link #workerTrustProblem}). */
    private static final java.util.Map<Path, Long> CLAUDE_LAUNCHES = new java.util.concurrent.ConcurrentHashMap<>();

    /** What the Claude CLI prints when it refuses to start in a directory nobody trusted. */
    static final String NOT_TRUSTED = "Workspace not trusted";

    /**
     * Why the Claude Code workers could not take a wish, when it is the workspace-trust refusal (smoke): a
     * plain sentence, or null. Read from each worker's NEWEST launch only, so an old refusal fixed since is not
     * reported again. For the Magician check's failure line, which otherwise said only "never claimed".
     */
    public static String workerTrustProblem() {
        Path dir = configFile == null ? null : configFile.getParent();
        for (var e : CLAUDE_LAUNCHES.entrySet()) {
            String said = trustRefusal(logSince(e.getKey(), e.getValue()));
            if (said != null) {
                return "the Claude Code workers refused to start because their directory "
                        + (dir == null ? "" : dir + " ") + "is not trusted (\"" + said + "\")";
            }
        }
        return null;
    }

    /** The trust refusal's line in a worker's output, trimmed, or null. Pure. */
    static String trustRefusal(String logText) {
        if (logText == null) {
            return null;
        }
        for (String line : logText.split("\\R")) {
            int i = line.indexOf(NOT_TRUSTED);
            if (i >= 0) {
                String l = line.substring(i).strip();
                return l.length() > 160 ? l.substring(0, 160) : l;
            }
        }
        return null;
    }

    /** What a worker wrote to its log after {@code offset} (the last 64 KiB of it), or "" when it cannot be read. */
    static String logSince(Path log, long offset) {
        try {
            if (!Files.isRegularFile(log)) {
                return "";
            }
            byte[] all = Files.readAllBytes(log);
            int from = (int) Math.max(Math.min(offset, all.length), all.length - 65_536L);
            return new String(all, from, all.length - from, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * Record any {@code claude daemon run} that came up across this launch and was spawned from the pool's folder:
     * Moddy started it, so a whole-pool stop may stop it (see {@link WorkerStopper}, "The daemon"). One that was
     * already running — the player's — is in {@code before} and never recorded.
     */
    private static void noteDaemons(java.util.Set<String> before, WorkerStopper.Launch launch) {
        Path cfg = configFile;
        if (before == null || cfg == null) {
            return;
        }
        try {
            for (ProcessHandle h : WorkerStopper.startedSince(before, cfg.getParent())) {
                if (MODDY_DAEMONS.putIfAbsent(h.pid(),
                        new WorkerStopper.Daemon(h, launch.program(), launch.configDir(), List.of())) == null) {
                    LOGGER.info("the claude daemon {} was started by Moddy's wish worker; it is stopped with the pool "
                            + "unless somebody else's session is on it", h.pid());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debug("could not look for the claude daemon: {}", e.toString());
        }
    }

    private static void captureSessionId(Path log, long offset, String name, WorkerStopper.Launch launch,
                                         java.util.Set<String> daemonsBefore) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 40; i++) {
                try {
                    Thread.sleep(500);
                    if (Files.isRegularFile(log)) {
                        String id = sessionIdAfter(Files.readAllBytes(log), offset);
                        if (id != null) {
                            // how it was started, BEFORE the id is stoppable, so no stop finds it unrecorded.
                            LAUNCHES.put(id, launch);
                            noteDaemons(daemonsBefore, launch);
                            // Each worker watches its OWN log, so ids cannot be attributed to the
                            // wrong one; the list is what stop() walks.
                            synchronized (sessionIds) {
                                if (!sessionIds.contains(id)) {
                                    sessionIds.add(id);
                                }
                            }
                            ROSTER.cliId(name, id);
                            LOGGER.info("wish agent session {} is live — `claude attach {}` to watch",
                                    id, id);
                            return;
                        }
                    }
                } catch (IOException ignored) {
                    // keep trying; the file may not exist yet
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            LOGGER.warn("could not read a wish agent's session id from {} — it may not stop "
                    + "cleanly", log.getFileName());
            noteDaemons(daemonsBefore, launch);   // a daemon it brought up is still Moddy's to stop
            // a Claude worker that never came up may have been refused by its account (not signed in, a bad
            // key, a usage limit): that skips its chain entry, so the next launch tries the next one.
            ModelFallbackRuntime.workerEnded(name, logSince(log, offset));
        }, "wish-agent-id");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Stop finished wish-granters left over from previous games.
     *
     * <p>A detached session that has completed lingers in {@code claude agents} forever, and within
     * a few sessions the live one is impossible to pick out of the list.
     */
    /**
     * This server's redirected workers' own CLI config dir, prepared, or null when the workers run
     * on this machine's own sign-in (or the dir could not be made — then they share the default
     * daemon, which is the behaviour, logged).
     */
    public static Path workerCliDir() {
        Path dir = WorkerCliConfig.dirFor(com.moddingfromamod.runtime.RuntimeConfig.home(),
                WishModel.resolve(resolvedModel(), System::getenv));
        if (dir == null) {
            return null;
        }
        try {
            WorkerCliConfig.prepare(dir, WorkerCliConfig.defaultDir());
            return dir;
        } catch (IOException e) {
            LOGGER.warn("could not prepare the redirected workers' CLI config {} ({}); they share "
                    + "the default --bg daemon, so another session's sign-in can reach them (#1679)",
                    dir, e.toString());
            return null;
        }
    }

    private static void reapFinishedAgents(String cli) {
        // The default daemon (workers on this machine's sign-in, and any redirected worker started
        //) and, for a redirect, the worker's own.
        reapFinishedAgents(cli, null);
        Path own = workerCliDir();
        if (own != null) {
            reapFinishedAgents(cli, own);
        }
    }

    private static void reapFinishedAgents(String cli, Path configDir) {
        try {
            ProcessBuilder listPb = new ProcessBuilder(cli, "agents", "--json");
            WorkerCliConfig.applyTo(listPb.environment(), configDir);
            Process list = listPb
                    .redirectInput(new File("/dev/null")).start();
            String out = new String(list.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            list.waitFor(10, TimeUnit.SECONDS);
            var arr = com.google.gson.JsonParser.parseString(out);
            if (!arr.isJsonArray()) {
                return;
            }
            for (var el : arr.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                var o = el.getAsJsonObject();
                boolean ours = o.has("name")
                        && ReapRule.isOurs(o.get("name").getAsString(), AGENT_NAME);
                String state = o.has("state") ? o.get("state").getAsString() : null;
                String cwd = o.has("cwd") ? o.get("cwd").getAsString() : null;
                // A blocked session is only ours to stop once its client is gone; see ReapRule.
                boolean stop = ReapRule.shouldStop(state, clientAlive(cwd));
                if (ours && stop && o.has("id")) {
                    ProcessBuilder stopPb = new ProcessBuilder(cli, "stop", o.get("id").getAsString());
                    WorkerCliConfig.applyTo(stopPb.environment(), configDir);
                    stopPb.redirectInput(new File("/dev/null"))
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                }
            }
        } catch (Exception ignored) {
            // Tidying up is never worth failing a world load over.
        }
    }

    /**
     * Is the Minecraft client this session was granting wishes for still running?
     *
     * <p>Asked by connecting to its MCP port. Anything other than a refusal is treated as ALIVE —
     * an unreadable cwd, an unparseable port, a socket error we did not expect. The failure modes
     * are not symmetric: leaving a dead session registered costs some memory and an untidy list,
     * while stopping a live granter takes a running agent away from a child mid-wish.
     */
    private static boolean clientAlive(String cwd) {
        var port = ReapRule.portOf(cwd);
        if (port.isEmpty()) {
            return true;
        }
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port.getAsInt()), 250);
            return true;
        } catch (java.net.ConnectException refused) {
            return false;
        } catch (Exception other) {
            return true;
        }
    }

    /** Name the session appears under in `claude agents` and the Claude app. */
    private static final String AGENT_NAME = "wish-granter";

    /**
     * The CLI's per-call abort, set on our server in the mcp.json.
     *
     * <p>Must stay comfortably ABOVE {@code AwaitWishTool.DEFAULT_TIMEOUT_MS} so the game is always
     * the side that ends a wait. If these ever cross, the client aborts mid-call and the worker
     * takes the confusing path instead of the clean one.
     */
    static final long MCP_CLIENT_TIMEOUT_MS = 900_000L;

    private static Path writeMcpConfig(int port) throws IOException {
        Path dir = AgentToken.tokenFile().getParent().resolve("session-" + port);
        Files.createDirectories(dir);
        // ⚠ `timeout` is what lets a worker PARK instead of polling, and it is measured, not
        // guessed: without it the CLI aborts any tool call at 60 s, so await_wish could never hold
        // longer than that however this mod configured itself. 900 s here against AwaitWishTool's
        // 540 s hold, so OUR server always returns first and the client never aborts. See
        // AwaitWishTool.DEFAULT_TIMEOUT_MS for the measurement and the cost it saves.
        String json = "{\"mcpServers\":{\"wishwatch\":{\"type\":\"http\","
                + "\"url\":\"http://127.0.0.1:" + port + McpHttpServer.PATH + "\","
                + "\"timeout\":" + MCP_CLIENT_TIMEOUT_MS + ","
                + "\"headers\":{\"Authorization\":\"Bearer " + AgentToken.token() + "\"}}}}";
        Path file = dir.resolve("mcp.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    /** The header a worker's own config sends on every request, naming its slot. */
    public static final String WORKER_HEADER = "X-Moddy-Worker";

    /**
     * One worker's config: the pool's, plus {@link #WORKER_HEADER}. A file per worker is what
     * turns "a worker is deaf" into "wish-granter-2 is deaf" — with one shared file every worker
     * was indistinguishable to the server.
     *
     * <p>⚠ Nothing in it reaches the model: headers go to our MCP server only, and the tool list
     * the server returns is identical for every worker, so the shared cached prefix is
     * unchanged by it. The soak checks that rather than trusting it: every worker's first turn
     * should still READ the shared head.
     */
    private static Path writeWorkerConfig(int port, String name) throws IOException {
        String json = "{\"mcpServers\":{\"wishwatch\":{\"type\":\"http\","
                + "\"url\":\"http://127.0.0.1:" + port + McpHttpServer.PATH + "\","
                + "\"timeout\":" + MCP_CLIENT_TIMEOUT_MS + ","
                + "\"headers\":{\"Authorization\":\"Bearer " + AgentToken.token() + "\","
                + "\"" + WORKER_HEADER + "\":\"" + name + "\"}}}}";
        Path file = configFile.resolveSibling("mcp-" + name + ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    // ── the first turn waits for the tools ─────────────────────────────────────────────────

    /**
     * <b>Why a worker's first turn waits for its tool list</b>. The CLI fixes the tools of a turn
     * when the prompt is submitted, and with every wish tool up front ({@code ENABLE_TOOL_SEARCH=false})
     * there is no ToolSearch to wait for a server that is still listing. A {@code --bg} worker's
     * prompt is submitted ~0.3 s after it starts connecting; our tool list is ~205 KB, and while a freshly
     * booted server is busy the list lands after that — measured: worker 1's tools 23 ms late on a fresh
     * machine, then in a stub with our real tool list, every time on a cold CLI daemon. Such a worker says
     * "no tools are available", ends its only turn, and never speaks again; the issue's first wish went
     * unclaimed for 123 s through four of them. Neither the CLI's startup wait, a blocking connect, an
     * {@code alwaysLoad} server nor a Stop hook (the tools are fixed for the whole turn) changed that; a
     * hook BEFORE the prompt did, every time.
     *
     * <p>So each worker carries a {@code UserPromptSubmit} hook that waits for a marker this endpoint
     * touches when it sends THAT worker its tool list ({@link #toolsListed}), then half a second for the
     * CLI to read it — at most {@value #TOOLS_WAIT_DECISECONDS} tenths of a second, so a hook can never
     * hold a worker for long. It prints nothing: a {@code UserPromptSubmit} hook's output would be added
     * to the prompt.
     */
    static final int TOOLS_WAIT_DECISECONDS = 100;

    static final String WAIT_SCRIPT = "#!/bin/sh\n"
            + "# #1957: hold a wish worker's first turn until its wish tools are listed (see AgentSession).\n"
            + "m=\"$1\"; i=0\n"
            + "while [ ! -f \"$m\" ] && [ \"$i\" -lt " + TOOLS_WAIT_DECISECONDS + " ]; do sleep 0.1; i=$((i+1)); done\n"
            + "sleep 0.5\n"
            + "exit 0\n";

    /** The marker {@link #toolsListed} touches for this slot, beside the worker's own config. */
    static Path toolsListedMarker(Path dir, String name) {
        return dir.resolve("tools-listed-" + name);
    }

    /**
     * The worker's own {@code --settings} file: the {@code UserPromptSubmit} hook that waits for its tools.
     * The marker of a slot's previous incarnation is removed first, or a replacement would not wait.
     */
    static Path writeToolsWait(Path dir, String name) throws IOException {
        Path script = dir.resolve("wait-for-tools.sh");
        Files.writeString(script, WAIT_SCRIPT, StandardCharsets.UTF_8);
        Path marker = toolsListedMarker(dir, name);
        Files.deleteIfExists(marker);
        com.google.gson.JsonObject hook = new com.google.gson.JsonObject();
        hook.addProperty("type", "command");
        hook.addProperty("command", "/bin/sh " + quote(script) + " " + quote(marker));
        com.google.gson.JsonArray inner = new com.google.gson.JsonArray();
        inner.add(hook);
        com.google.gson.JsonObject matcher = new com.google.gson.JsonObject();
        matcher.add("hooks", inner);
        com.google.gson.JsonArray onSubmit = new com.google.gson.JsonArray();
        onSubmit.add(matcher);
        com.google.gson.JsonObject hooks = new com.google.gson.JsonObject();
        hooks.add("UserPromptSubmit", onSubmit);
        com.google.gson.JsonObject settings = new com.google.gson.JsonObject();
        settings.add("hooks", hooks);
        Path file = dir.resolve("settings-" + name + ".json");
        Files.writeString(file, settings.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static String quote(Path p) {
        return "'" + p.toString().replace("'", "'\\''") + "'";
    }

    /**
     * This endpoint just sent {@code worker} its tool list: let its first turn go. Called from the
     * MCP handler; a failure only means that worker's hook waits out its bound.
     */
    public static void toolsListed(String worker) {
        Path dir = configFile == null ? null : configFile.getParent();
        if (dir == null || worker == null || worker.isBlank() || !ROSTER_NAME.matcher(worker).matches()) {
            return;
        }
        try {
            Files.writeString(toolsListedMarker(dir, worker), "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.debug("could not mark {}'s tools as listed: {}", worker, e.toString());
        }
    }

    /** Only a slot name the pool could have made, so a header never names a path. */
    private static final java.util.regex.Pattern ROSTER_NAME = java.util.regex.Pattern.compile("[a-z0-9-]{1,64}");

    /**
     * Stop every slot the roster calls deaf, so the supervisor's top-up replaces it.
     *
     * <p>Through {@code claude stop}, never {@code kill} (a killed session is restarted by
     * the daemon from its transcript ~60 s later). A deaf worker holds no wish — it was neither
     * parked nor working for {@link com.moddingfromamod.marshal.PoolPlan#DEAF_STOP_MS} — but the
     * marshal is told anyway, so if that is ever wrong the wish goes back to the front of the queue
     * rather than being lost with the worker.
     */
    private static void replaceDeafWorkers(long nowMs) {
        var marshal = com.moddingfromamod.marshal.Marshal.global();
        java.util.Set<String> accounted = new java.util.HashSet<>(WishQueue.global().parkedWorkers());
        for (var run : marshal.live()) {
            if (run.workerId() != null) {
                accounted.add(run.workerId());
            }
            if (run.pickedUpBy() != null) {
                accounted.add(run.pickedUpBy());   //: deciding its gates or waiting for its brief
            }
        }
        for (var slot : ROSTER.snapshot()) {
            if (slot.mcpId() != null && marshal.windingDown(slot.mcpId()) != null) {
                accounted.add(slot.mcpId());
            }
        }
        for (String name : ROSTER.deaf(accounted, nowMs)) {
            var slot = ROSTER.forget(name);
            Process launcher = processes.remove(name);
            if (launcher != null && launcher.isAlive()) {
                launcher.destroy();
            }
            if (slot.mcpId() != null) {
                marshal.workerDied(slot.mcpId());
            }
            deafReplaced++;
            boolean fromBirth = slot.listedAtMs() > 0 && !slot.calledTool();
            String why = fromBirth
                    ? name + " was sent its tools " + (nowMs - slot.listedAtMs()) / 1000 + "s ago and has "
                            + "never called one — its first turn was built before the tools arrived "
                            + "(#1957) — stopping it and starting a replacement"
                    : name + " booted " + (nowMs - slot.bootedAtMs()) / 1000 + "s ago and has not "
                    + "listened for " + com.moddingfromamod.marshal.PoolPlan.deafStopMs() / 1000
                    + "s (" + (slot.lastHeardMs() == 0 ? "never heard from at all"
                            : "last heard " + (nowMs - slot.lastHeardMs()) / 1000 + "s ago")
                    + ") — stopping it and starting a replacement (#352)";
            LOGGER.warn("deaf wish worker: {}", why);
            com.moddingfromamod.health.AgentWarnings.note("deaf wish worker: " + why);
            if (slot.cliId() == null) {
                LOGGER.warn("deaf wish worker {} never printed a CLI session id, so it cannot be "
                        + "stopped by id — it holds no wish, but may linger in `claude agents`", name);
                continue;
            }
            synchronized (sessionIds) {
                sessionIds.remove(slot.cliId());
            }
            stopOne(slot.cliId());
        }
    }

    /** How many wedged workers the no-progress watchdog has recycled since this JVM started. */
    static int wedgedRecycled() {
        return wedgedRecycled;
    }

    private static volatile int wedgedRecycled;

    /**
     * <b>Stop the worker holding a wish that has made no progress</b> -- the no-progress
     * watchdog's hand ({@code WishTimeoutPolicy.Reason.NO_PROGRESS}). The wish itself is settled by
     * the caller through the ordinary timeout path; this only makes sure the worker that went quiet
     * is not left holding a slot. Stopped exactly the way a deaf worker is
     * ({@link #replaceDeafWorkers}): forgotten by the roster -- so any await it sends afterwards is
     * refused as a stale incarnation ({@code WorkerRoster.mayTake}) -- its launcher dropped, its CLI
     * session id removed, then {@code claude stop} by id. The supervisor's next tick starts a
     * replacement if the pool is short of a spare.
     *
     * <p>On its own daemon thread: the caller is the health sweep, and the stop takes this class's
     * monitor (which the supervisor holds across launches) and then waits up to 10 s for the CLI.
     * A session no slot owns -- one the pool already stopped -- has nothing left to stop.
     *
     * @param mcpId the MCP session id the wish was assigned under, or null
     */
    public static void recycleWorker(String mcpId, String why) {
        stopWorkerOf(mcpId, why, true);
    }

    /**
     * <b>Stop the worker holding this MCP session ON PURPOSE</b> — the pickup ratchet took
     * its wish back, or a safety layer flagged its wish: its context holds words no later wish
     * should sit beside, so it is stopped and the pool starts a fresh one. {@link #recycleWorker}'s
     * mechanics exactly, but logged as the decision it is, not as a wedged worker (no operator warning).
     */
    public static void retireWorker(String mcpId, String why) {
        stopWorkerOf(mcpId, why, false);
    }

    private static void stopWorkerOf(String mcpId, String why, boolean wedged) {
        if (mcpId == null || mcpId.isBlank()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                String name = ROSTER.nameOfSession(mcpId);
                if (name == null) {
                    LOGGER.warn("wedged wish worker: {} -- its session {} belongs to no live worker "
                            + "(already stopped), nothing to recycle (#1548)", why, mcpId);
                    return;
                }
                String cliId;
                synchronized (AgentSession.class) {
                    var slot = ROSTER.forget(name);
                    Process launcher = processes.remove(name);
                    if (launcher != null && launcher.isAlive()) {
                        launcher.destroy();
                    }
                    cliId = slot == null ? null : slot.cliId();
                    if (cliId != null) {
                        synchronized (sessionIds) {
                            sessionIds.remove(cliId);
                        }
                    }
                }
                if (wedged) {
                    wedgedRecycled++;
                    String line = name + ": " + why + " -- stopping it; the pool starts a fresh one (#1548)";
                    LOGGER.warn("wedged wish worker: {}", line);
                    com.moddingfromamod.health.AgentWarnings.note("wedged wish worker: " + line);
                } else {
                    LOGGER.info("retiring wish worker {}: {} -- the pool starts a fresh one", name, why);
                }
                if (cliId != null) {
                    stopOne(cliId);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("could not stop worker {}: {}", mcpId, e.toString());
            }
        }, wedged ? "wish-worker-recycle" : "wish-worker-retire");
        t.setDaemon(true);
        t.start();
    }

    /** How many idle EXTRA workers the supervisor has stopped since this JVM started. */
    static int extrasStopped() {
        return extrasStopped;
    }

    private static volatile int extrasStopped;

    /**
     * <b>Shrink the pool by one idle extra worker, if it has one</b> (2026-10-08).
     *
     * <p>The pool grows the moment a wish takes the last free worker, and before this it only ever
     * shrank all at once, when the whole pool was reaped — so a burst of three wishes left three or
     * four workers parked, polling every ~110 s, until twenty quiet minutes had passed. Measured on
     * a live server: 79% of the model spend was that standby. Now a worker beyond the one spare that
     * has sat parked for {@link com.moddingfromamod.marshal.PoolPlan#extraIdleStopMs()} is stopped.
     * The decision is {@link com.moddingfromamod.marshal.PoolPlan#shrinkCandidate} (pure, unit
     * tested); {@link #shrinkStep} gathers its inputs and retires the worker from the queue; this
     * stops the process.
     *
     * <p>Stopped exactly the way a deaf worker is ({@link #replaceDeafWorkers}): forgotten by the
     * roster, its launcher dropped, its CLI session id removed from {@link #sessionIds} so
     * {@link #stop()} does not stop it twice, then {@code claude stop} by id — on a daemon thread,
     * because that waits up to 10 s and the supervisor holds this class's monitor, which the
     * wish-submission path also takes ({@link #isRunning()}).
     *
     * <p>Spend accounting needs nothing: {@code spend.idle} is per (worker, hour) from the worker's
     * own transcript, so a stopped worker's hours simply end, and a relaunch into the same slot is
     * counted as a new launch in the boot split, as a deaf replacement already is.
     */
    private static void stopIdleExtra(long nowMs) {
        String name = shrinkStep(ROSTER, WishQueue.global(),
                com.moddingfromamod.marshal.Marshal.global(), nowMs,
                com.moddingfromamod.marshal.PoolPlan.extraIdleStopMs());
        if (name == null) {
            return;
        }
        var slot = ROSTER.forget(name);
        Process launcher = processes.remove(name);
        if (launcher != null && launcher.isAlive()) {
            launcher.destroy();
        }
        extrasStopped++;
        String cliId = slot == null ? null : slot.cliId();
        if (cliId != null) {
            synchronized (sessionIds) {
                sessionIds.remove(cliId);
            }
        }
        LOGGER.info("wish pool shrinking: stopping idle extra worker {} (parked {} s with no wish; "
                        + "{} worker(s) left, the spare stays), pool.extraIdleStopSeconds={}",
                name, slot == null ? -1 : (nowMs - slot.idleSinceMs()) / 1000, processes.size(),
                com.moddingfromamod.marshal.PoolPlan.extraIdleStopMs() / 1000);
        if (cliId == null) {
            return;   // shrinkCandidate only names a stoppable worker; belt and braces
        }
        Thread t = new Thread(() -> {
            try {
                stopOne(cliId);
            } catch (RuntimeException e) {
                LOGGER.warn("could not stop idle extra worker {}: {}", name, e.toString());
            }
        }, "wish-pool-shrink");
        t.setDaemon(true);
        t.start();
    }

    /**
     * <b>The shrink DECISION where it runs</b>, split from the act of stopping so a gate can drive it
     * against a real queue with real parked awaits (a gate never launches a real worker).
     *
     * <p>Keeps the roster's idle clocks, asks {@link com.moddingfromamod.marshal.PoolPlan#shrinkCandidate},
     * and RETIRES the chosen worker from the queue ({@link WishQueue#retireIfParked}) before naming
     * it — so its still-blocked {@code await_wish} can never take a wish once the process is gone.
     * If the worker took a wish between the census and the retire, the retire refuses and nothing is
     * stopped this tick.
     *
     * @return the slot to stop (already retired from {@code queue}), or {@code null}
     */
    static String shrinkStep(com.moddingfromamod.marshal.WorkerRoster roster, WishQueue queue,
                             com.moddingfromamod.marshal.Marshal marshal, long nowMs, long graceMs) {
        java.util.Set<String> busy = new java.util.HashSet<>();
        for (var run : marshal.live()) {
            if (run.workerId() != null) {
                busy.add(run.workerId());
            }
            if (run.pickedUpBy() != null) {
                busy.add(run.pickedUpBy());   //: deciding its gates or waiting for its brief, not idle
            }
        }
        List<com.moddingfromamod.marshal.WorkerRoster.Slot> slots = roster.snapshot();
        for (var slot : slots) {
            if (slot.mcpId() != null && marshal.windingDown(slot.mcpId()) != null) {
                busy.add(slot.mcpId());
            }
        }
        roster.idleClock(busy, nowMs);
        java.util.Set<String> parkedNow = queue.parkedWorkers();
        List<com.moddingfromamod.marshal.PoolPlan.Worker> view = new ArrayList<>();
        for (var slot : slots) {
            String mcp = slot.mcpId();
            view.add(new com.moddingfromamod.marshal.PoolPlan.Worker(slot.name(), slot.bootedAtMs(),
                    slot.idleSinceMs(), mcp != null && parkedNow.contains(mcp),
                    mcp != null && busy.contains(mcp), mcp != null && slot.cliId() != null));
        }
        String name = com.moddingfromamod.marshal.PoolPlan.shrinkCandidate(view, nowMs, graceMs);
        if (name == null) {
            return null;
        }
        for (var slot : slots) {
            if (slot.name().equals(name)) {
                return queue.retireIfParked(slot.mcpId()) ? name : null;
            }
        }
        return null;
    }

    /**
     * The pre-approved tool list handed to the CLI.
     *
     * <p><b>Derived from the registry on purpose.</b> With {@code --permission-mode dontAsk}, a tool
     * missing from this list isn't prompted for, it's silently DENIED — so a capability added to
     * {@link WishAgent#buildTools()} but forgotten here would be invisible to the agent and look
     * like the feature simply doesn't work. Public so a test can assert the two can't drift.
     */
    public static String allowedTools() {
        StringBuilder sb = new StringBuilder();
        // THE FULL SET: this is a PERMISSION list (and the committed worker copy of it,
        // agent/allowed-tools.txt), and leaving a served tool out denies it silently.
        for (McpTool tool : WishAgent.buildTools().all()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append("mcp__wishwatch__").append(tool.name());
        }
        return sb.toString();
    }

    /**
     * The standing brief.
     *
     * <p>Same content as the retired watcher's nudge, with one structural change: it is now a
     * <b>loop</b> rather than a one-shot instruction, because this session persists for the whole
     * game rather than being spawned per wish.
     *
     * <p>Public so a test can assert the brief still names the tools it is supposed to route to. A
     * tool the agent is <i>allowed</i> to call but never <i>told</i> about is very nearly as
     * invisible as one it is denied — which is exactly how sculpt_creature could have shipped
     * registered, permitted, and never used.
     */
    public static String prompt() {
        // ⚠ MOVED, not copied (HO-6). The worker container builds the same command line and needs
        // the same prompt, and it cannot load THIS class — AgentSession imports Minecraft, and
        // dragging the game onto a worker's classpath to read a string is not a trade worth
        // making. The text lives in WorkerPrompt, which is Minecraft-free; moving it rather than
        // generating a copy means there is nothing that can drift.
        return com.moddingfromamod.worker.WorkerPrompt.text();
    }

    /**
     * <b>The child-safety section of the system prompt — layer L3, best effort, no round trip.</b>
     *
     * <p>{@code docs/CHILD_SAFETY_PLAN.md} §3. It states the four axes of §1 in the agent's own
     * terms, gives the allowed-on-purpose column equal weight, and names three habits: redirect
     * rather than refuse, answer real distress kindly and still grant what you can, and treat a
     * {@code plan_wish} refusal that names a stage as an instruction to re-plan that stage.
     *
     * <p><b>Empty when {@link ChildSafetyConfig.Mode#OFF}</b>, which is the A/B control arm. It
     * returns its own surrounding spaces so that an OFF prompt is byte-identical to the prompt
     * before this section existed: {@code prompt()} joins its elements with a single space, this
     * call replaces a {@code ""} paragraph separator, and {@code " " + text + " "} reproduces the
     * two-space break the rest of the prompt uses. See the comment at the call site.
     *
     * <p>Public so a test can pin the text without reading the whole 33 KB prompt, and so the
     * on/off arms can be compared without booting an agent.
     *
     * @return the section with its paragraph padding, or {@code ""} in the control arm
     */
    public static String childSafetySection() {
        // Moved with the prompt it is spliced into (HO-6) — see WorkerPrompt. ChildSafetyConfig
        // imports no Minecraft, so the whole brief is buildable without the game.
        return com.moddingfromamod.worker.WorkerPrompt.childSafetySection();
    }
}
