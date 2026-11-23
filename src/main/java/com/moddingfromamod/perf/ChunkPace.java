package com.moddingfromamod.perf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>How fast each player's game takes chunks, and why</b> — the {@code [chunks]} line (2026-11-09) —
 * pure: player ids are strings, the server feeds it numbers.
 *
 * <p>A player joined a dedicated server on a phone and the scenery filled "INCREDIBLY slowly" while the
 * server's own ticks were idle (12 ms of a 50 ms budget). Minecraft paces chunk sending to the CLIENT,
 * so that left two suspects the logs could not tell apart: the device and the connection. Vanilla
 * already measures both, and this only writes them down:
 *
 * <ul>
 *   <li><b>The rate the client asks for</b> ({@code ServerboundChunkBatchReceivedPacket}). The client
 *       computes it from ITS OWN time per chunk — {@code ChunkBatchSizeCalculator}: 7 ms ÷ the
 *       average nanoseconds a chunk cost it (1.21.8 bytecode). So {@code 7 / rate} is the device's
 *       milliseconds per chunk, and a low rate is a slow DEVICE, whatever the network.</li>
 *   <li><b>Batches sent and not yet acknowledged</b> ({@code PlayerChunkSender.unacknowledgedBatches},
 *       capped at {@value #MAX_UNACKED}). The server stops sending at the cap until an
 *       acknowledgement comes back, so chunks WAITING while the server is held at the cap for a third
 *       or more of at least a second of waiting, with a healthy rate, is a slow CONNECTION. Merely touching the cap is not:
 *       a join's first burst does that on loopback ({@link #MIN_WAIT_TICKS}).</li>
 * </ul>
 */
public final class ChunkPace {

    /** {@code PlayerChunkSender.MAX_UNACKNOWLEDGED_BATCHES} on 1.21.8. */
    public static final int MAX_UNACKED = 10;
    /** The client's budget behind its rate: {@code ChunkBatchSizeCalculator}'s 7,000,000 ns. */
    public static final double CLIENT_BUDGET_MS = 7.0;
    /** Below this rate (over 7 ms a chunk) a device is the limit; a desktop asks 9+ (vanilla's start). */
    public static final float SLOW_DEVICE_RATE = 1.0f;
    /** The first line, this long after a join: the fill a player noticed. */
    public static final int FIRST_LINE_TICKS = 300;

    private static final class Stats {
        long joinedTick = -1;
        boolean firstLogged;
        final List<Float> rates = new ArrayList<>();
        int sent;
        int maxPending;
        int maxUnacked;
        int waitingTicks;
        int heldTicks;
    }

    private final Map<String, Stats> players = new HashMap<>();

    public synchronized void joined(String id, long tick) {
        Stats s = new Stats();
        s.joinedTick = tick;
        players.put(id, s);
    }

    public synchronized void left(String id) {
        players.remove(id);
    }

    /** The client acknowledged a batch and asked for this rate. */
    public synchronized void ack(String id, float desiredChunksPerTick) {
        stats(id).rates.add(desiredChunksPerTick);
    }

    /** The server sent {@code n} chunks; after it, {@code pending} wait and {@code unacked} batches are out. */
    public synchronized void sent(String id, int n, int pending, int unacked) {
        Stats s = stats(id);
        s.sent += Math.max(0, n);
        s.maxPending = Math.max(s.maxPending, pending);
        s.maxUnacked = Math.max(s.maxUnacked, unacked);
        if (pending > 0) {
            s.waitingTicks++;
            if (unacked >= MAX_UNACKED) {
                s.heldTicks++;
            }
        }
    }

    /** Players whose first line is due at {@code tick} (once each, {@link #FIRST_LINE_TICKS} after joining). */
    public synchronized List<String> firstDue(long tick) {
        List<String> due = new ArrayList<>();
        players.forEach((id, s) -> {
            if (!s.firstLogged && s.joinedTick >= 0 && tick - s.joinedTick >= FIRST_LINE_TICKS) {
                s.firstLogged = true;
                due.add(id);
            }
        });
        return due;
    }

    /** The line for one player over the window just ended, and a fresh window. */
    public synchronized String line(String id, String name, String window, double seconds, int pendingNow) {
        Stats s = stats(id);
        String out = format(name, window, seconds, s.rates, s.sent, Math.max(s.maxPending, pendingNow), s.maxUnacked,
                s.waitingTicks, s.heldTicks);
        Stats fresh = new Stats();
        fresh.joinedTick = s.joinedTick;
        fresh.firstLogged = true;   // a minute line that came first already covered the join
        players.put(id, fresh);
        return out;
    }

    private Stats stats(String id) {
        return players.computeIfAbsent(id, k -> new Stats());
    }

    static String format(String name, String window, double seconds, List<Float> rates, int sent,
                         int maxPending, int maxUnacked, int waitingTicks, int heldTicks) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" (").append(window).append("): ");
        float median = Float.NaN;
        if (rates.isEmpty()) {
            sb.append("the client acknowledged no chunk batch");
        } else {
            List<Float> sorted = new ArrayList<>(rates);
            sorted.sort(Float::compare);
            median = sorted.get(sorted.size() / 2);
            sb.append(String.format(Locale.ROOT, "client asks %.2f chunks/tick (min %.2f, max %.2f, %d acks) = ~%.1f ms of its own time per chunk",
                    median, sorted.get(0), sorted.get(sorted.size() - 1), sorted.size(),
                    median > 0 ? CLIENT_BUDGET_MS / median : Double.POSITIVE_INFINITY));
        }
        sb.append(String.format(Locale.ROOT, "; server sent %d chunks (%.1f/s), up to %d waiting to send; chunks waited %d tick(s), "
                        + "held at the %d-batch acknowledgement cap for %d of them",
                sent, seconds > 0 ? sent / seconds : 0.0, maxPending, waitingTicks, MAX_UNACKED, heldTicks));
        sb.append(" -> ").append(reading(median, waitingTicks, heldTicks));
        return sb.toString();
    }

    /**
     * At least this many ticks of waiting (one second) before the connection is blamed — and then only
     * when the server was HELD at the acknowledgement cap for a third of them or more. Measured
     * 2026-11-09 with a stock 1.21.8 client joining a lite server: on loopback a join's burst TOUCHED
     * the cap (10/10) and was held there 0 of 22 waiting ticks; through a relay throttled to 15 KB/s
     * with 150 ms latency it was held 37 of 78. A slow link is one that keeps the server waiting there.
     */
    static final int MIN_WAIT_TICKS = 20;

    /** What the numbers say: the device, the connection, or nobody. */
    static String reading(float medianRate, int waitingTicks, int heldTicks) {
        if (waitingTicks == 0) {
            return "nothing was waiting: every chunk in view had been sent";
        }
        boolean slowDevice = !Float.isNaN(medianRate) && medianRate < SLOW_DEVICE_RATE;
        boolean slowLink = waitingTicks >= MIN_WAIT_TICKS && heldTicks * 3 >= waitingTicks;
        if (slowDevice && slowLink) {
            return "the DEVICE is slow to build chunks AND its acknowledgements lag (connection)";
        }
        if (slowDevice) {
            return "the client's DEVICE is the limit (it takes over 7 ms per chunk)";
        }
        if (slowLink) {
            return "the CONNECTION is the limit (batches sit unacknowledged at the cap)";
        }
        return "the client kept up; chunks went as fast as it asked";
    }
}
