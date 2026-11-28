package com.moddingfromamod.testing.gt;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Which production classes does each GameTest class actually execute?</b> — the mapping run
 * behind targeted Gate B (docs/GATE_B.md, "The coverage map").
 *
 * <p>On only when {@code -Dmoddingfromamod.covmap=<dir>} is set AND the JVM carries the JaCoCo agent
 * ({@code -Pcovagent=<jacocoagent.jar>}, which {@code tools/gate/gate.py run --covmap-tools} passes). Then
 * {@link GameTestAdapter} gives every GameTest CLASS its own environment, so classes run strictly
 * one after another, and {@link #enter} dumps and resets the agent's execution data each time the
 * running class changes: {@code <dir>/__boot__.0.exec} is everything before the first test,
 * {@code <dir>/<Class>.<n>.exec} is what ran while that class's tests did.
 *
 * <p>JaCoCo is reached by reflection: it is a tool on the mapping run's command line, never a
 * dependency of the mod. A normal Gate B run never loads it and keeps its real batches.
 */
public final class CoverageMap {
    private static final Logger LOG = LoggerFactory.getLogger("gt-covmap");
    public static final String PROPERTY = "moddingfromamod.covmap";
    static final String BOOT = "__boot__";
    /**
     * The IDLE window: one synthetic test that only waits. What runs during it (tick handlers, timers,
     * the tail of start-up) runs during every test, so the map counts a file for a test only where the
     * test executed something this window did not.
     */
    static final String IDLE = "__idle__";
    static final String SHUTDOWN = "__shutdown__";
    /**
     * The SWEEP between two classes ({@link #sweep}): the harness discarding what the previous class left, and every
     * handler that fires for it (an entity leaving its level: {@code PetBehaviours#onLeave}, {@code LiveFitWatch#onLeave}).
     * No test of ours runs it, so it is AMBIENT ({@code tools/gate/covmap.py} PSEUDO), never the next class's. Counted
     * as the next class's, those handlers ran in whichever classes happened to follow one that left a mob, and were
     * ambient in a replica only when some boot or shutdown happened to discard one too: about 11 methods in or out of
     * 40-odd classes between two replicas, and the ambient sets 20 apart (27 Nov 2026).
     */
    static final String SWEEP = "__sweep__";
    /**
     * A class's SETTLE window: after its last batch, a synthetic test of its own unit that only waits, so what the class
     * left running (a listener, a make on a worker thread, a periodic poll it started) is counted as the class's, not
     * as whichever class happens to run next. At least SETTLE_TICKS ticks and SETTLE_MS of wall time.
     */
    static final int SETTLE_TICKS = 40;
    static final long SETTLE_MS = 1500;
    /**
     * ...and then until no other thread is running the mod's code (a Builder make on its worker thread, a pack being
     * compiled, a poll it kicked), at most this long. Measured before: a make a class started finished in whichever
     * class ran next, a block of about 105 methods that landed in a different class each run. The longest job a class
     * starts is the Builder's page-facts walk ({@code BuilderPageFacts}, started when a world first answers makes): 6000
     * pages in 99 s under coverage on a 4-core machine. At 15 s it ran on through the next five or six classes, a
     * different number in each run, each held its whole 15 s (a block of 170 to 470 methods in every one: agreement
     * 96% on 27 Nov 2026, against 99.26% before the walk was started ahead of the first page). So the most is
     * well past that walk; a class with nothing running still settles in {@link #SETTLE_MS}.
     */
    static final long SETTLE_MAX_MS = 300_000;

    /**
     * The settle window's tick budget: never the thing that ends it. The wall clock ends it ({@link #settled}); a
     * GameTest server ticks as fast as it can (hundreds a second), and the harness holds a test to at most a minute of
     * wall clock past its ticks ({@code seam/WallClockTimeout.MOST_MS}), so a budget of ticks this large outlasts
     * {@link #SETTLE_MAX_MS} at any rate a server reaches.
     */
    static final int SETTLE_TIMEOUT_TICKS = 2_000_000;

    /**
     * Is the mod's own work done? No thread but the caller (the server thread, which runs the settle window's check)
     * is RUNNABLE with a frame of the mod's code. Threads parked, sleeping or waiting (an idle pool, a poll between
     * rounds) are done; the GameTest harness's own frames do not count.
     */
    static boolean quiet() {
        return busy() == null;
    }

    /** The name of a thread still running the mod's code (see {@link #quiet}), or null when none is. */
    static String busy() {
        Thread self = Thread.currentThread();
        for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            if (t == self || t.getState() != Thread.State.RUNNABLE) {
                continue;
            }
            for (StackTraceElement f : e.getValue()) {
                String c = f.getClassName();
                if (c.startsWith("com.moddingfromamod.") && !c.startsWith("com.moddingfromamod.testing.")) {
                    return t.getName();
                }
            }
        }
        return null;
    }

    /** A settle window that reached {@link #SETTLE_MAX_MS} still busy says so, once: what it gave up on lands next. */
    static void gaveUp(String cls, String thread) {
        LOG.warn("gt-covmap: {}'s settle window reached {} s with {} still running the mod's code; what it does next "
                + "is counted in the next class", cls, SETTLE_MAX_MS / 1000, thread);
    }

    /** Has a settle window that started at {@code startMs} waited enough (pure: the clock and the check handed in)? */
    static boolean settled(long startMs, long nowMs, boolean quiet) {
        long ran = nowMs - startMs;
        return ran >= SETTLE_MS && (quiet || ran >= SETTLE_MAX_MS);
    }
    /** Every synthetic test of a mapping run is named {@code moddingfromamod:covmapidle.*}; none is a test of the tier. */
    public static final String SYNTHETIC_PREFIX = "covmapidle.";
    static final int IDLE_TICKS = 400;
    static final net.minecraft.resources.Identifier IDLE_ID =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("moddingfromamod", "covmapidle.idle");

    /** The settle window's test id for a class (lower case, as every test id is). */
    static net.minecraft.resources.Identifier settleId(String simpleClass) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath("moddingfromamod",
                SYNTHETIC_PREFIX + "settle_" + simpleClass.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The environment (batch) name of one of a class's batches on a mapping run, and of its settle window. Sorted by
     * name ({@code testing/mixin/GameTestBatchOrderMixin}), a class's batches come together ({@code covmap_<class>.b_...}),
     * its settle window right after them ({@code covmap_<class>.s}: {@code b} sorts before {@code s}, and {@code .}
     * before any character a class name can hold, so no class's batches fall between another's, even {@code Foo}'s
     * and {@code Foo_C}'s), and the idle window ({@code covmap_0idle}) before them all.
     */
    static String batchName(String simpleClass, String batch) {
        return "covmap_" + simpleClass + ".b_" + batch;
    }

    static String settleBatch(String simpleClass) {
        return "covmap_" + simpleClass + ".s";
    }

    static final String IDLE_BATCH = "covmap_0idle";

    /** A mapping run's batches in the order of their environment names (stable: a name's chunks keep their order). */
    public static java.util.List<net.minecraft.gametest.framework.GameTestBatch> inOrder(
            java.util.List<net.minecraft.gametest.framework.GameTestBatch> batches) {
        java.util.List<net.minecraft.gametest.framework.GameTestBatch> out = new java.util.ArrayList<>(batches);
        out.sort(java.util.Comparator.comparing((net.minecraft.gametest.framework.GameTestBatch b) -> envName(b))
                .thenComparingInt(net.minecraft.gametest.framework.GameTestBatch::index));
        return out;
    }

    static String envName(net.minecraft.gametest.framework.GameTestBatch b) {
        // the PATH alone: a class of the ci shard (namespace moddingfromamod_ci) keeps its settle window (moddingfromamod)
        return b.environment().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
    }

    private static final Path DIR = dir();
    private static Object agent;
    private static Method getExecutionData;
    private static String current = BOOT;
    private static int segment;

    private CoverageMap() {
    }

    private static Path dir() {
        String raw = System.getProperty(PROPERTY, "");
        return raw.isBlank() ? null : Path.of(raw);
    }

    public static boolean on() {
        return DIR != null;
    }

    /**
     * The unit the map keys a test by: its CLASS — except the Bedrock batch, whose CASES are units of their own
     * ({@code BedrockBatchGameTest#<case>}): its 44 cases cover very different code, and a change should
     * run the cases that reach it, not the whole batch. They share one batch (the bot pool joins once and leaves
     * once), so a Bedrock test STARTS under the batch's setup unit and its case takes a TURN ({@link #takeTurn}):
     * on a mapping run one case runs at a time, and each turn is its own dump.
     */
    public static String unitOf(Method m) {
        String cls = m.getDeclaringClass().getSimpleName();
        return cls.startsWith("BedrockBatch") ? cls + SETUP : cls;
    }

    /** Units the map never selects by: the Bedrock batch's harness between cases. */
    public static final String SETUP = "#__setup__";
    static final String BETWEEN = "#__between__";
    /** A turn held this long is a case that failed without ending it: the next case takes over. */
    static final long TURN_STALE_MS = 180_000;
    private static String turn;
    private static long turnSince;

    /**
     * May the Bedrock case {@code unit} (e.g. {@code BedrockBatchGameTest#watch_panel_grid}) start now? Always on a
     * normal run. On a mapping run: only when no other case holds the turn — then its coverage is its own dump.
     */
    public static synchronized boolean takeTurn(String unit) {
        if (DIR == null || unit.equals(turn)) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (turn != null && now - turnSince < TURN_STALE_MS) {
            return false;
        }
        if (turn != null) {
            LOG.warn("gt-covmap: {} never ended its turn — {} takes it", turn, unit);
        }
        turn = unit;
        turnSince = now;
        enter(unit);
        return true;
    }

    /** The case is over: its dump closes, and what runs until the next case is the harness's. */
    public static synchronized void endTurn(String unit) {
        if (DIR == null || !unit.equals(turn)) {
            return;
        }
        LOG.info("gt-covmap: unit {} ran {} s", unit, String.format(java.util.Locale.ROOT, "%.1f",
                (System.currentTimeMillis() - turnSince) / 1000.0));
        turn = null;
        enter(unit.substring(0, unit.indexOf('#')) + BETWEEN);
    }

    /** A Bedrock test waits for its turn on a mapping run, so it may wait for every case before it. */
    static int timeoutOf(Method m, int ticks) {
        return DIR != null && m.getDeclaringClass().getSimpleName().startsWith("BedrockBatch") ? ticks * 60 : ticks;
    }

    /** A test of {@code cls} is starting. A change of class closes the previous class's data. */
    static synchronized void enter(String cls) {
        if (DIR == null || cls.equals(current)) {
            return;
        }
        dump(current);
        if (sweeps(current, cls) && sweep()) {
            dump(SWEEP);   // the harness's own work: ambient, whichever class comes next
        }
        current = cls;
    }

    /**
     * Is the world swept between {@code from} and {@code to}? Always, but between two units of the Bedrock batch: its
     * cases share the batch's staged board and the pool bots' spots, and a case clears its own plot (BedrockStage.clear).
     * When it is, the sweep is dumped as {@link #SWEEP} between the two, so it is neither's.
     */
    static boolean sweeps(String from, String to) {
        return !(from.startsWith("BedrockBatch") && to.startsWith("BedrockBatch"));
    }

    /**
     * What an earlier class left in the world keeps ticking — a creature's rig, a dropped item — and
     * would be counted as this class's execution (measured: 15-57 files a class, against the same
     * class run alone). So a new class starts with no entity but the players. @return whether it ran (on the server
     * thread, with a server up)
     */
    private static boolean sweep() {
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) {
            return false;
        }
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            java.util.List<net.minecraft.world.entity.Entity> gone = new java.util.ArrayList<>();
            level.getAllEntities().forEach(e -> {
                if (e != null && !(e instanceof net.minecraft.world.entity.player.Player)) {
                    gone.add(e);
                }
            });
            gone.forEach(net.minecraft.world.entity.Entity::discard);
        }
        return true;
    }

    static synchronized void start() {
        if (DIR == null) {
            return;
        }
        try {
            Class<?> rt = Class.forName("org.jacoco.agent.rt.RT", true, ClassLoader.getSystemClassLoader());
            agent = rt.getMethod("getAgent").invoke(null);
            getExecutionData = agent.getClass().getMethod("getExecutionData", boolean.class);
            getExecutionData.setAccessible(true);
            Files.createDirectories(DIR);
        } catch (ReflectiveOperationException | IOException e) {
            // loud: a mapping run without the agent would write an empty map that reads as "nothing depends on anything"
            throw new IllegalStateException("covmap=" + DIR + " but the JaCoCo agent is not on this JVM (-Pcovagent)", e);
        }
        // the server's own stop (saves, closing services) belongs to no test class
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> enter(SHUTDOWN));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (CoverageMap.class) {
                dump(current);
            }
        }, "gt-covmap-last"));
        LOG.info("gt-covmap: writing per-class execution data to {}", DIR);
    }

    private static void dump(String name) {
        try {
            byte[] data = (byte[]) getExecutionData.invoke(agent, true);
            // a class's batches need not run back to back, so it may be entered more than once: numbered
            // segments, which tools/gate/CovmapAnalyze.java unions
            Files.write(DIR.resolve(name + "." + (segment++) + ".exec"), data);
        } catch (ReflectiveOperationException | IOException e) {
            throw new IllegalStateException("covmap: could not write " + name, e);
        }
    }
}
