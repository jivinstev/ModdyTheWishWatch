package com.moddingfromamod.world;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The running world's rules, hot-reloaded</b> — the process-wide holder around one
 * {@link WorldRulesReloader} and the daemon thread that polls it.
 *
 * <p>The file is {@code <gameDir>/moddingfromamod/world-rules.json} (the same override folder the
 * lessons and the safety lexicon use), or wherever {@code -Dmoddingfromamod.world.rules} points.
 * A server's tools may replace the file at any time, and the change reaches a running world within
 * a poll and needs no restart.
 *
 * <p>A PLAIN DAEMON THREAD with its own try/catch, not a scheduled executor: CLAUDE.md's rule for
 * anything whose job is to notice something when nothing else will (a task that dies inside an
 * executor takes its reason into a Future nobody reads).
 */
public final class WorldRulesService {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/world-rules");

    public static final String FILE_PROPERTY = "moddingfromamod.world.rules";
    public static final String POLL_PROPERTY = "moddingfromamod.world.rules.pollMs";
    static final long DEFAULT_POLL_MS = 5_000L;

    private static volatile WorldRulesReloader reloader;
    private static volatile Thread poller;
    private static volatile WorldRules testRules;

    private WorldRulesService() {
    }

    /** Where this world's rules file is read from. */
    public static Path defaultFile() {
        String p = System.getProperty(FILE_PROPERTY);
        if (p != null && !p.isBlank()) {
            return Path.of(p.strip());
        }
        try {
            return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("moddingfromamod").resolve("world-rules.json");
        } catch (RuntimeException | LinkageError e) {
            return Path.of("moddingfromamod", "world-rules.json");
        }
    }

    /** Start watching {@code file}; {@code fallback} until a good file is read. Replaces any watcher. */
    public static synchronized void start(Path file, String worldId, WorldRules fallback) {
        stop();
        WorldRulesReloader r = new WorldRulesReloader(file, worldId, fallback, LOGGER::warn, LOGGER::info);
        r.poll();
        reloader = r;
        long every = pollMs();
        Thread t = new Thread(() -> {
            while (reloader == r && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(every);
                    r.poll();
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    LOGGER.debug("world rules poll: {}", e.toString());
                }
            }
        }, "mfam-world-rules");
        t.setDaemon(true);
        poller = t;
        t.start();
        LOGGER.info("world rules: {} ({})", r.current(), r.fromFile() ? "from " + file : "no file at " + file
                + " — " + (fallback == WorldRules.CLOSED ? "a world with bases, so everything the reach rule refused stays refused"
                : "nothing is denied"));
    }

    /** Stop watching. */
    public static synchronized void stop() {
        Thread t = poller;
        poller = null;
        reloader = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private static long pollMs() {
        try {
            return Math.max(250L, Long.parseLong(System.getProperty(POLL_PROPERTY, String.valueOf(DEFAULT_POLL_MS))));
        } catch (NumberFormatException e) {
            return DEFAULT_POLL_MS;
        }
    }

    /** The rules in force: a test's, else the file's, else the fallback, else {@link WorldRules#ALLOW_ALL}. */
    public static WorldRules current() {
        WorldRules t = testRules;
        if (t != null) {
            return t;
        }
        WorldRulesReloader r = reloader;
        return r == null ? WorldRules.ALLOW_ALL : r.current();
    }

    /**
     * The rules a FILE (or a test) set, or null when none was read — what the reach backstop asks,
     * so that "no file" stays {@link WorldRules#CLOSED} there on a world with bases.
     */
    public static WorldRules loadedOrNull() {
        WorldRules t = testRules;
        if (t != null) {
            return t;
        }
        WorldRulesReloader r = reloader;
        return r != null && r.fromFile() ? r.current() : null;
    }

    /** The watcher, for a status line and a test; null when none runs. */
    public static WorldRulesReloader reloader() {
        return reloader;
    }

    /** Pin the rules for a test (null restores). Use a batch of its own: statics are shared. */
    public static void forTest(WorldRules rules) {
        testRules = rules;
    }
}
