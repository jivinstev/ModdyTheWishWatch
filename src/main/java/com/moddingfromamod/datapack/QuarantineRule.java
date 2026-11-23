package com.moddingfromamod.datapack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>When a wish datapack is eating the server, and which one</b> — Minecraft-free, so the whole
 * decision is a Gate A test against made-up tick streams.
 *
 * <p>A wish's datapack runs on its own, every tick or on an event, long after the wish. An honest mistake in
 * one (bh-g03: an {@code on_hit} that called a baby every time somebody nearby was hurt, while the babies kept
 * hurting him) grew until ONE tick took 60 s and the watchdog killed the server. A linter per pattern is
 * whack-a-mole; this watches what every pack actually COSTS, whoever wrote it, and names the one to pause.
 *
 * <h2>The breach — sustained, never one spike</h2>
 * Over the last {@link #WINDOW_MS} of wall time, at least {@link #MIN_SLOW_TICKS} ticks took longer than
 * {@link #SLOW_TICK_MS}, and those slow ticks fill at least {@link #SLOW_SHARE} of the window. Wall time,
 * not a tick count: a runaway slows the very ticks a count would wait for (100 ticks at 1 s is 100 s, past the
 * watchdog). Three slow ticks, not one: a single long tick (a big summon at a reveal, a chunk burst — the
 * real servers log "Can't keep up" bursts of up to 11.7 s) is one tick followed by fast catch-up ticks, and never
 * trips it.
 *
 * <p><b>The numbers are measured</b> (2026-11-18, nine days of two live servers' {@code [tick]} lines, 97
 * one-minute windows with players on): the worst minute's MEAN was 38 ms and its p95 102 ms; single ticks
 * reached 1.6 s. So 250 ms is 2.5× the worst healthy p95, and "three-quarters of five seconds spent in ticks
 * over 250 ms" is a server running at under four ticks a second for five seconds, which no healthy minute
 * came near.
 *
 * <h2>The blame — which pack, or none</h2>
 * Every tick records each wish pack's own function time, and how many entities it spawned in the last
 * {@link #RECENT_SPAWN_MS} are still alive. A pack's blame for a tick is its function time plus its share of
 * the tick's OTHER time in proportion to the live entities it recently spawned (the bh-g03 stack named no pack
 * at all — the time was a crowd of carriers colliding, in vanilla's own code). The pack with the most blame
 * over the window is quarantined only when it carries at least {@link #BLAME_SHARE} of the window's tick time
 * AND at least {@link #LEAD}× the next pack's: a slow server with no wish pack behind it (chunk generation, a
 * big build) quarantines NOTHING, which is the false positive that matters — pausing an innocent pack.
 * Entities spawned long ago are not blamed: a pack that made fifty fish last week is not this minute's flood.
 *
 * <h2>The hard stop — one function, one tick</h2>
 * A sustained window cannot see a recursive {@code function} that never returns: it is ONE tick, and the
 * watchdog kills the server at 60 s. So one outermost function call of a wish pack running past
 * {@link #HARD_STOP_MS} is stopped where it stands (the command quota is spent, as vanilla's own
 * {@code maxCommandChainLength} would), and {@link #HARD_STOPS} of them inside {@link #HARD_STOP_WINDOW_MS}
 * quarantine the pack. One stop alone does not: a one-off heavy function is cut short once and keeps its place.
 */
public final class QuarantineRule {

    /** A tick slower than this is a slow tick. */
    public static final double SLOW_TICK_MS = 250;
    /** The sustained window, in wall time. */
    public static final long WINDOW_MS = 5_000;
    /** At least this many slow ticks inside it. */
    public static final int MIN_SLOW_TICKS = 3;
    /** ...filling at least this share of it. */
    public static final double SLOW_SHARE = 0.75;
    /** The blamed pack carries at least this share of the window's tick time... */
    public static final double BLAME_SHARE = 0.25;
    /** ...and at least this many times the next pack's blame. */
    public static final double LEAD = 2.0;
    /** Entities a pack spawned longer ago than this are not its doing any more. */
    public static final long RECENT_SPAWN_MS = 60_000;
    /** One outermost function call of a wish pack may run this long. */
    public static final long HARD_STOP_MS = 2_000;
    /** This many hard stops... */
    public static final int HARD_STOPS = 2;
    /** ...inside this long quarantine the pack. */
    public static final long HARD_STOP_WINDOW_MS = 60_000;

    /**
     * One finished tick.
     *
     * @param endMs     when it ended (wall clock)
     * @param ms        how long it took
     * @param fnMs      each wish pack's own function time in it
     * @param spawned   each wish pack's recently spawned, still-alive entity count at its end
     * @param entities  every entity loaded at its end (0 = not counted: no entity blame this tick)
     */
    public record Tick(long endMs, double ms, Map<String, Double> fnMs, Map<String, Integer> spawned, int entities) {
    }

    /** What the window says: breached or not, and who to blame (null = nobody, or no breach). */
    public record Verdict(boolean breach, String pack, double share, Map<String, Double> blame, String why) {
        static Verdict calm(String why) {
            return new Verdict(false, null, 0, Map.of(), why);
        }
    }

    private final Deque<Tick> window = new ArrayDeque<>();
    private final Map<String, Deque<Long>> hardStops = new HashMap<>();

    /** Add a finished tick and say what the window now holds. */
    public Verdict add(Tick t) {
        window.addLast(t);
        while (!window.isEmpty() && window.peekFirst().endMs() < t.endMs() - WINDOW_MS - 120_000) {
            window.removeFirst();               // keep a little history past the window, never unbounded
        }
        return judge(t.endMs());
    }

    /** The verdict over the window ending at {@code nowMs}. */
    public Verdict judge(long nowMs) {
        long from = nowMs - WINDOW_MS;
        double slowMs = 0;
        int slow = 0;
        double total = 0;
        Map<String, Double> blame = new LinkedHashMap<>();
        for (Tick t : window) {
            if (t.endMs() <= from) {
                continue;
            }
            // A tick that started before the window counts only for its part inside it.
            double inside = Math.min(t.ms(), t.endMs() - from);
            total += inside;
            if (t.ms() > SLOW_TICK_MS) {
                slow++;
                slowMs += inside;
            }
            double scale = t.ms() <= 0 ? 0 : inside / t.ms();
            double fnSum = 0;
            for (double v : t.fnMs().values()) {
                fnSum += v;
            }
            double other = Math.max(0, t.ms() - fnSum);
            for (var e : t.fnMs().entrySet()) {
                blame.merge(e.getKey(), e.getValue() * scale, Double::sum);
            }
            if (t.entities() > 0) {
                for (var e : t.spawned().entrySet()) {
                    double part = other * Math.min(1.0, e.getValue() / (double) t.entities());
                    blame.merge(e.getKey(), part * scale, Double::sum);
                }
            }
        }
        if (slow < MIN_SLOW_TICKS || slowMs < SLOW_SHARE * WINDOW_MS) {
            return Verdict.calm(slow + " slow tick(s), " + (int) slowMs + " ms of the last " + WINDOW_MS + " ms");
        }
        String top = null;
        double best = 0;
        double second = 0;
        for (var e : blame.entrySet()) {
            if (e.getValue() > best) {
                second = best;
                best = e.getValue();
                top = e.getKey();
            } else if (e.getValue() > second) {
                second = e.getValue();
            }
        }
        double share = total <= 0 ? 0 : best / total;
        if (top == null || share < BLAME_SHARE || best < LEAD * second) {
            return new Verdict(true, null, share, blame, "the server is slow (" + slow + " ticks over "
                    + (int) SLOW_TICK_MS + " ms) and no wish datapack carries it"
                    + (top == null ? "" : " — the most is " + top + " at " + pct(share)));
        }
        return new Verdict(true, top, share, blame, "the server spent " + (int) slowMs + " ms of the last "
                + WINDOW_MS + " ms in " + slow + " ticks over " + (int) SLOW_TICK_MS + " ms, and " + top
                + " carries " + pct(share) + " of it");
    }

    /**
     * A wish pack's function was hard-stopped at {@code nowMs}. True when this is the stop that quarantines
     * it ({@link #HARD_STOPS} inside {@link #HARD_STOP_WINDOW_MS}).
     */
    public boolean hardStop(String pack, long nowMs) {
        Deque<Long> d = hardStops.computeIfAbsent(pack, k -> new ArrayDeque<>());
        d.addLast(nowMs);
        while (!d.isEmpty() && d.peekFirst() < nowMs - HARD_STOP_WINDOW_MS) {
            d.removeFirst();
        }
        return d.size() >= HARD_STOPS;
    }

    /** Forget a pack (it was quarantined, or restored): its old cost is not the next decision's. */
    public void forget(String pack) {
        hardStops.remove(pack);
        List<Tick> kept = new ArrayList<>();
        for (Tick t : window) {
            Map<String, Double> fn = new HashMap<>(t.fnMs());
            Map<String, Integer> sp = new HashMap<>(t.spawned());
            fn.remove(pack);
            sp.remove(pack);
            kept.add(new Tick(t.endMs(), t.ms(), fn, sp, t.entities()));
        }
        window.clear();
        window.addAll(kept);
    }

    /** Start over (a world closed, or a test). */
    public void reset() {
        window.clear();
        hardStops.clear();
    }

    // ── after a crash: which packs to boot disabled ────────────────────────────────────────────────

    /** A pack the world has, with when its zip was last written. */
    public record Installed(String pack, long modifiedMs) {
    }

    /** How long before a crash a pack's install, or the meter's last suspicion, still points at it. */
    public static final long CRASH_WINDOW_MS = 10 * 60_000L;

    /**
     * <b>The packs to boot disabled after a watchdog crash</b> (option 2, the backstop). The meter's own
     * suspect, written while ticks were slowing ({@code suspectAtMs} within {@link #CRASH_WINDOW_MS} before
     * the crash), is the best evidence and is used alone. Without one, every wish pack installed or changed in
     * that window before the crash. Never a pack written AFTER the crash (that is a later wish), and nothing at
     * all when neither points anywhere — a crash with no wish pack behind it disables nothing.
     */
    public static List<String> crashSuspects(long crashMs, String suspect, long suspectAtMs,
                                             Collection<Installed> installed) {
        List<String> out = new ArrayList<>();
        if (suspect != null && !suspect.isBlank() && suspectAtMs <= crashMs + 5_000
                && suspectAtMs >= crashMs - CRASH_WINDOW_MS) {
            out.add(suspect);
            return out;
        }
        for (Installed i : installed) {
            if (i.modifiedMs() <= crashMs && i.modifiedMs() >= crashMs - CRASH_WINDOW_MS && !out.contains(i.pack())) {
                out.add(i.pack());
            }
        }
        return out;
    }

    private static String pct(double share) {
        return Math.round(share * 100) + "%";
    }
}
