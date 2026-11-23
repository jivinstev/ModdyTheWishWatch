package com.moddingfromamod.testing.gt;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Which production classes does each GameTest class actually execute?</b> — the mapping run
 * behind dynamic test selection (docs/GATE_COVERAGE_MAP.md).
 *
 * <p>On only when {@code -Dmoddingfromamod.covmap=<dir>} is set AND the JVM carries the JaCoCo agent
 * ({@code -Pcovagent=<jacocoagent.jar>}, which {@code tools/gate-covmap.sh} passes). Then
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
    static final int IDLE_TICKS = 400;
    static final net.minecraft.resources.ResourceLocation IDLE_ID =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("moddingfromamod", "covmapidle.idle");

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
        // between two units of the Bedrock batch nothing is swept: its cases share the batch's staged board and
        // the pool bots' spots, and a case clears its own plot (BedrockStage.clear)
        boolean withinBedrock = current.startsWith("BedrockBatch") && cls.startsWith("BedrockBatch");
        current = cls;
        if (!withinBedrock) {
            sweep();
        }
    }

    /**
     * What an earlier class left in the world keeps ticking — a creature's rig, a dropped item — and
     * would be counted as this class's execution (measured: 15-57 files a class, against the same
     * class run alone). So a new class starts with no entity but the players.
     */
    private static void sweep() {
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) {
            return;
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
            // segments, which tools/gate-covmap.py unions
            Files.write(DIR.resolve(name + "." + (segment++) + ".exec"), data);
        } catch (ReflectiveOperationException | IOException e) {
            throw new IllegalStateException("covmap: could not write " + name, e);
        }
    }
}
