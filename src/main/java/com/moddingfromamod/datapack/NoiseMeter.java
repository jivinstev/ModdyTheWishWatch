package com.moddingfromamod.datapack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A wish datapack that drones is turned down, not off</b> — the runtime backstop to
 * {@link NoiseLint}. Every sound and every particle burst of at least {@value #BURST} a wish
 * datapack's functions emit is counted against that pack ({@link NoiseWindow}); a pack loud on half
 * of the last 30 seconds' ticks keeps working but makes one loud emission a second until it calms
 * down. Small particles (auras, trails) are never touched, and neither is anything a pack does that
 * is not a sound or a burst — its gameplay stays exactly as granted.
 *
 * <p>Why at runtime as well as at compile time: a counter that is true on every tick reads like a
 * timer. The {@code impulse} template at {@code PERIOD_TICKS=1} (the old T-Rex's proximity
 * knockback) telegraphed 60 particles and a sound 20 times a second with nobody near it, and no
 * reading of the source says so. This sees what actually happens, whoever wrote it.
 *
 * <p>Attribution: {@code FunctionNoiseMixin} marks which function is running (every tick and
 * scheduled function passes {@code ServerFunctionManager.execute}); the particle and playsound
 * commands ask {@link #allow} on their way out. Only a WISH datapack's namespace is metered (a pack
 * {@link DatapackStore} holds) — the world's own packs (SkyBlock's) are not ours to quieten.
 * Server thread only.
 */
public final class NoiseMeter {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-noise");

    /** A particle command this big is a burst, not an ambient look. */
    public static final int BURST = 60;

    private static final Deque<String> RUNNING = new ArrayDeque<>();
    /** The outermost running function's namespace, readable from another thread (TickTimer's sampler). */
    private static volatile String running;
    private static final Map<String, NoiseWindow> WINDOWS = new HashMap<>();
    private static final Map<String, Boolean> IS_WISH = new HashMap<>();
    private static final Map<String, Integer> DROPPED = new HashMap<>();
    private static long wishCheckedAt = Long.MIN_VALUE;

    /** For a test: meter this namespace whether or not the store holds it. */
    private static final java.util.Set<String> FORCED = new java.util.HashSet<>();

    private NoiseMeter() {
    }

    /** A function starts (outermost first). */
    public static void enter(String namespace) {
        RUNNING.push(namespace == null ? "" : namespace);
        running = RUNNING.peekLast();
        PackCostMeter.enter(namespace);     //: what each wish pack costs
    }

    /** ...and ends. */
    public static void exit() {
        RUNNING.poll();
        running = RUNNING.peekLast();
        PackCostMeter.exit();
    }

    /** May this particle command go out? Always, unless it is a burst from a throttled wish pack. */
    public static boolean allowParticles(CommandSourceStack source, int count) {
        return count < BURST || allow(source);
    }

    /** May this playsound go out? */
    public static boolean allowSound(CommandSourceStack source) {
        return allow(source);
    }

    private static boolean allow(CommandSourceStack source) {
        String pack = RUNNING.peekLast();               // the OUTERMOST function: whose tick it is
        if (pack == null || pack.isEmpty() || source == null || source.getServer() == null) {
            return true;                                 // a player's command, not a datapack's
        }
        long tick = source.getServer().getTickCount();
        if (!isWishPack(pack, tick)) {
            return true;
        }
        NoiseWindow w = WINDOWS.computeIfAbsent(pack, k -> new NoiseWindow());
        boolean was = w.throttled();
        boolean ok = w.emit(tick);
        if (!ok) {
            DROPPED.merge(pack, 1, Integer::sum);
        }
        if (w.throttled() != was) {
            if (w.throttled()) {
                LOG.warn("[noise] wish datapack {} was loud on {} of the last {} ticks -> its sounds and "
                        + "bursts are capped to one a second (#1725)", pack, w.loudTicks(), NoiseWindow.WINDOW);
            } else {
                LOG.info("[noise] wish datapack {} is quiet again ({} loud ticks of {}) -> uncapped", pack,
                        w.loudTicks(), NoiseWindow.WINDOW);
            }
        }
        return ok;
    }

    /** Whether a namespace is a wish datapack, re-read from the store at most once a second. */
    private static boolean isWishPack(String ns, long tick) {
        if (FORCED.contains(ns)) {
            return true;
        }
        if ("minecraft".equals(ns)) {
            return false;
        }
        if (tick - wishCheckedAt >= 20 || tick < wishCheckedAt) {
            IS_WISH.clear();
            wishCheckedAt = tick;
        }
        return IS_WISH.computeIfAbsent(ns, DatapackStore::exists);
    }

    /** Is this pack being turned down right now? */
    public static boolean throttled(String pack) {
        NoiseWindow w = WINDOWS.get(pack);
        return w != null && w.throttled();
    }

    /** Which datapack's function the server thread is running right now, or null. Any thread. */
    public static String runningPack() {
        return running;
    }

    /** How many loud emissions this pack has had dropped since it was first metered. */
    public static int dropped(String pack) {
        return DROPPED.getOrDefault(pack, 0);
    }

    /** A new tick: anything left on the stack by a function that threw is not running any more. */
    public static void tickStart() {
        RUNNING.clear();
        running = null;
        PackCostMeter.tickStart();
    }

    /** Tests: meter {@code ns} as a wish pack and start it from nothing. */
    public static void forTest(String ns) {
        FORCED.add(ns);
        WINDOWS.remove(ns);
        DROPPED.remove(ns);
    }

    public static void forgetTest(String ns) {
        FORCED.remove(ns);
        WINDOWS.remove(ns);
        DROPPED.remove(ns);
    }
}
