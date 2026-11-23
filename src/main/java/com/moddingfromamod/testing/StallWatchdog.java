package com.moddingfromamod.testing;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.seam.GameTestStall;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>A Gate B run whose Server thread stops ticking ENDS, by name, with every thread's stack</b>.
 *
 * <p>Six Gate B runs in ten days never finished (2026-11-11 → 11-20): four nightly regression builds, killed at their
 * 80-minute bound or ending with no result line and nothing kept, and two PR gates that sat silent for 77 and 82
 * minutes. Each was the Server thread blocked: a test's {@code placeNewPlayer} waiting for chunks a GameTest server
 * never has time to load, and once a light worker dead of an OutOfMemoryError with the Server thread waiting on
 * its chunk (the 11-18 nightly). A GameTest's {@code timeoutTicks} cannot fire without ticks, and Minecraft's own
 * watchdog runs only on a dedicated server.
 *
 * <p>This is that watchdog for a GameTest server: a daemon thread that, once the Server thread has not finished a tick
 * for {@code -Dmoddingfromamod.stallSeconds} (default 900: past {@code ToolMatrix}'s own 10-minute wall limit, the
 * longest honest single tick in the suite), logs {@code GATE B STALL:} with the test whose tick it is stuck in
 * ({@link GameTestStall}), dumps every thread with the Server thread first, and halts the JVM. {@code tools/gate-b.sh}
 * prints those lines under its NO RESULT verdict. A plain daemon thread with its own try/catch, never a
 * {@code ScheduledExecutorService}: a scheduled task that dies takes its reason into a Future nobody reads (CLAUDE.md).
 */
public final class StallWatchdog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID + "/stall");

    /** The exit code a stalled run halts with; gate-b.sh reads the log line, not this. */
    static final int EXIT = 71;

    private static volatile boolean watching;

    private StallWatchdog() {
    }

    /** Only on a GameTest server (the namespaces are set by runGameTestServer alone). */
    static void register() {
        if (System.getProperty("neoforge.enabledGameTestNamespaces", "").isBlank()) {
            return;
        }
        long limit = Long.getLong(ModdingFromAModMod.MODID + ".stallSeconds", 900L) * 1_000_000_000L;
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> GameTestStall.tickEnded());
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> watching = false);
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> start(limit));
    }

    private static void start(long limitNanos) {
        GameTestStall.tickEnded();
        watching = true;
        Thread t = new Thread(() -> {
            while (watching) {
                try {
                    Thread.sleep(5_000L);
                    String stalled = GameTestStall.stalledNow(limitNanos);
                    if (stalled != null && watching) {
                        report(stalled);
                        Runtime.getRuntime().halt(EXIT);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t2) {
                    LOGGER.error("the Gate B stall watchdog itself failed; it is no longer watching", t2);
                    return;
                }
            }
        }, "moddingfromamod-stall-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** To stdout as well as the log: the JVM halts next, so nothing buffered may be the only copy. */
    private static void report(String stalled) {
        StringBuilder out = new StringBuilder("GATE B STALL: ").append(stalled).append('\n');
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        all.entrySet().stream()
                .sorted((a, b) -> Boolean.compare(!"Server thread".equals(a.getKey().getName()),
                        !"Server thread".equals(b.getKey().getName())))
                .forEach(e -> {
                    out.append("GATE B STALL: \"").append(e.getKey().getName()).append("\" ")
                            .append(e.getKey().getState()).append('\n');
                    for (StackTraceElement f : e.getValue()) {
                        out.append("GATE B STALL:     at ").append(f).append('\n');
                    }
                });
        out.append("GATE B STALL: the run is halted (exit ").append(EXIT).append(") so it fails now, not at an outer timeout\n");
        LOGGER.error(out.toString());
        System.out.print(out);
        System.out.flush();
        System.err.flush();
    }
}
