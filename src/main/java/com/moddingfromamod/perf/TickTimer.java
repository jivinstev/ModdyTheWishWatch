package com.moddingfromamod.perf;

import com.moddingfromamod.ModdingFromAModMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>How long the server's ticks take while somebody is playing</b> — one line a minute in the
 * server log ({@code [tick] …}, logger {@code mfam-tick}), and nothing while the world is empty.
 *
 * <p>Why: a player felt a live world "jittery … like server ticks were slow every second or so"
 * (2026-10-30), and production had no instrument that could say yes or no. Vanilla only complains
 * past 2 s behind ("Can't keep up"); a 100 ms hitch every second never reaches the log. Measured
 * from the start of the tick event to its end, which covers everything the server does in a tick.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class TickTimer {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-tick");

    /** A minute of ticks. */
    static final int WINDOW = 1200;

    private static final TickStats STATS = new TickStats(WINDOW);
    private static volatile long startNs;
    /** True between a tick's start and end, while anyone is on: the only time the sampler looks. */
    private static volatile boolean inTick;
    private static volatile Thread serverThread;

    /** A tick still running after this long is sampled... */
    static final long SAMPLE_AFTER_MS = 40;
    /** ...every this often, at most this many times a tick. */
    static final long SAMPLE_EVERY_MS = 10;
    static final int SAMPLES_PER_TICK = 8;

    private static final TickSampler SAMPLES = new TickSampler();
    private static volatile long sampledTickStart;
    private static int sampledThisTick;
    private static Thread sampler;

    private TickTimer() {
    }

    @SubscribeEvent
    public static void onTickStart(ServerTickEvent.Pre event) {
        startNs = System.nanoTime();
        if (event.getServer().getPlayerList().getPlayerCount() > 0) {
            serverThread = event.getServer().getRunningThread();
            startSampler();
            inTick = true;
        }
    }

    /**
     * <b>The sampler</b> (2026-10-31): the [tick] line said a slow tick came every 10 ticks on a
     * live world and could not say why. A daemon thread that, while a tick has run past
     * {@value #SAMPLE_AFTER_MS} ms, takes the server thread's stack every {@value #SAMPLE_EVERY_MS} ms
     * and counts WHERE it was ({@link TickSampler#where}). Costs nothing while ticks are quick.
     */
    private static synchronized void startSampler() {
        if (sampler != null) {
            return;
        }
        sampler = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(SAMPLE_EVERY_MS);
                    sampleOnce();
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    // never let the sampler die quietly or take anything with it; keep sampling
                    LOG.debug("tick sampler: {}", t.toString());
                }
            }
        }, "mfam-tick-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    static void sampleOnce() {
        Thread t = serverThread;
        long start = startNs;
        if (!inTick || t == null || (System.nanoTime() - start) / 1_000_000 < SAMPLE_AFTER_MS) {
            return;
        }
        if (sampledTickStart != start) {
            sampledTickStart = start;
            sampledThisTick = 0;
        }
        if (sampledThisTick++ >= SAMPLES_PER_TICK) {
            return;
        }
        String pack = com.moddingfromamod.datapack.NoiseMeter.runningPack();
        StackTraceElement[] st = t.getStackTrace();
        if (inTick && startNs == start) {               // still the same slow tick
            SAMPLES.add(TickSampler.where(st, pack));
        }
    }

    @SubscribeEvent
    public static void onTickEnd(ServerTickEvent.Post event) {
        inTick = false;
        int players = event.getServer().getPlayerList().getPlayerCount();
        if (players == 0 || startNs == 0) {
            STATS.reset();
            SAMPLES.reset();
            return;
        }
        ChunkPaceLog.tick(event.getServer());   // a player's [chunks] line 15 s after joining
        if (!STATS.add((System.nanoTime() - startNs) / 1_000_000.0)) {
            if (STATS.over() > 0) {
                String where = SAMPLES.top(6);
                LOG.warn("[tick] last {} ticks with {} player(s) on: {}{}", STATS.count(), players, STATS.summary(),
                        where.isEmpty() ? "" : " — slow ticks were in: " + where);
            } else {
                LOG.info("[tick] last {} ticks with {} player(s) on: {}", STATS.count(), players, STATS.summary());
            }
            // what the @e[tag=…] index did over the same window (its own line, so nothing
            // that reads [tick] lines changes)
            LOG.info("[selectors] {}", com.moddingfromamod.selector.SelectorIndex.window());
            // and how fast each player's game took chunks, and whether the device or the
            // connection held it back (ChunkPace) -- the question a slow join could not answer
            ChunkPaceLog.window(event.getServer(), STATS.count() / 20.0);
            STATS.reset();
            SAMPLES.reset();
        }
    }
}
