package com.moddingfromamod.vanilla;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.marshal.Clock;

/**
 * <b>A token bucket per caller</b> — the policy behind the pack host's 429 (plan §5).
 *
 * <p>Minecraft-free and with the clock handed in, so the one thing that is actually hard to get
 * right — refilling over time — is asserted in a unit test that never sleeps. The HTTP side is
 * three lines over {@link #allow(String)}.
 *
 * <h2>What it is for</h2>
 *
 * <p>{@code PackHost} is the one thing this deployment puts on the public internet, and it is
 * unauthenticated by design (it serves content-addressed resource packs, which are public to anyone
 * the game server admits). So the risk is not disclosure, it is a stranger asking for the same
 * 45 KB zip a thousand times a second on a home broadband connection. A bucket answers that without
 * knowing anything about who is asking.
 *
 * <h2>Three decisions worth knowing</h2>
 *
 * <ul>
 *   <li><b>The burst IS the per-minute rate.</b> A child's client fetches one pack per wish, so a
 *       full bucket is a minute of headroom for a household; splitting rate from burst would be one
 *       more number to tune with no evidence behind either.</li>
 *   <li><b>A rate of zero means NO LIMIT, not "refuse everything".</b> An operator typing
 *       {@code -Dmoddingfromamod.packhost.rpm=0} means "turn this off"; a limiter that read it as
 *       "block every request" would take the pack host down on a plausible typo, and every icon on
 *       every stock client with it.</li>
 *   <li><b>Idle buckets are evicted.</b> A map keyed by remote address with no eviction is a slow
 *       memory leak addressable by any stranger — which is the same attacker this class exists
 *       for.</li>
 * </ul>
 */
public final class RateLimiter {

    /** Buckets full and untouched for this long are forgotten. */
    static final long IDLE_EVICT_MS = 10 * 60_000L;

    /** Sweep once the map has grown past this; cheap, and only ever on a new key. */
    static final int SWEEP_AT = 256;

    private final int perMinute;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute, Clock clock) {
        this.perMinute = Math.max(0, perMinute);
        this.clock = clock;
    }

    /** The configured ceiling; 0 means the limiter is off. */
    public int perMinute() {
        return perMinute;
    }

    /**
     * Take a token for {@code key}, refilling first.
     *
     * @param key whatever identifies a caller — the pack host passes the remote ADDRESS without its
     *            port, because a port changes per connection and would give every request its own
     *            fresh bucket, which is a limiter that limits nothing
     * @return true when the request may proceed; false means answer 429
     */
    public boolean allow(String key) {
        if (perMinute <= 0) {
            return true;
        }
        String id = key == null ? "" : key;
        if (buckets.size() >= SWEEP_AT) {
            evictIdle();
        }
        Bucket b = buckets.computeIfAbsent(id, k -> new Bucket(perMinute, clock.nowMs()));
        return b.take(perMinute, clock.nowMs());
    }

    /** Whole tokens left for {@code key}, refilled to now. For tests and for a status line. */
    public int remaining(String key) {
        if (perMinute <= 0) {
            return Integer.MAX_VALUE;
        }
        Bucket b = buckets.get(key == null ? "" : key);
        if (b == null) {
            return perMinute;
        }
        synchronized (b) {
            b.refill(perMinute, clock.nowMs());
            return (int) Math.floor(b.tokens);
        }
    }

    /** How many callers are being tracked. */
    public int trackedKeys() {
        return buckets.size();
    }

    private void evictIdle() {
        long now = clock.nowMs();
        for (Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
                it.hasNext(); ) {
            Bucket b = it.next().getValue();
            synchronized (b) {
                if (now - b.lastMs < IDLE_EVICT_MS) {
                    continue;
                }
                // REFILL BEFORE JUDGING. A bucket is "full" if it WOULD be full now, not if it was
                // full when it was last touched — the untouched ones are exactly the ones this
                // sweep is for, and without the refill it evicts nothing at all (measured: 258 of
                // 258 buckets survived a ten-minute idle).
                b.refill(perMinute, now);
                // And only a full one is forgotten: dropping a bucket that is still empty would
                // hand its owner a fresh full one, which is the limit being lifted for whoever is
                // hammering hardest.
                if (b.tokens >= perMinute) {
                    it.remove();
                }
            }
        }
    }

    /** One caller's tokens. Guarded by its own monitor; several handler threads share a key. */
    private static final class Bucket {
        private double tokens;
        private long lastMs;

        Bucket(int capacity, long nowMs) {
            this.tokens = capacity;
            this.lastMs = nowMs;
        }

        synchronized boolean take(int capacity, long nowMs) {
            refill(capacity, nowMs);
            if (tokens >= 1.0d) {
                tokens -= 1.0d;
                return true;
            }
            return false;
        }

        void refill(int capacity, long nowMs) {
            long elapsed = nowMs - lastMs;
            if (elapsed <= 0) {
                // A clock that went backwards (NTP, a test) must not mint tokens, and must not
                // strand the bucket either: stand still.
                lastMs = nowMs;
                return;
            }
            tokens = Math.min(capacity, tokens + (elapsed * capacity) / 60_000.0d);
            lastMs = nowMs;
        }
    }
}
