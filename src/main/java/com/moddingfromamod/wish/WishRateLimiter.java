package com.moddingfromamod.wish;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps an excited 7-year-old from flooding the wish pipeline: a per-player token bucket plus a
 * short "you just asked that" dedupe window.
 *
 * <p>This is not just politeness. Once wishes can escalate to an AI agent, every submission costs
 * real time and money, so the throttle is a budget control as much as a server-load control.
 *
 * <p>Pure: the clock is passed in rather than read, so tests are deterministic and don't sleep.
 */
public final class WishRateLimiter {

    private final int capacity;
    private final long refillIntervalMs;
    private final long dedupeWindowMs;
    private final Map<String, Bucket> buckets = new HashMap<>();

    /**
     * @param capacity         wishes allowed in a burst
     * @param refillIntervalMs how often one token is restored
     * @param dedupeWindowMs   how long an identical wish is ignored
     */
    public WishRateLimiter(int capacity, long refillIntervalMs, long dedupeWindowMs) {
        this.capacity = Math.max(1, capacity);
        this.refillIntervalMs = Math.max(1, refillIntervalMs);
        this.dedupeWindowMs = Math.max(0, dedupeWindowMs);
    }

    /** Sensible defaults: 5 in a burst, one back every 6s, identical wishes ignored for 3s. */
    public static WishRateLimiter defaults() {
        return new WishRateLimiter(5, 6_000L, 3_000L);
    }

    public enum Decision {
        /** Go ahead. */
        ALLOW,
        /** Same wish, moments ago — silently ignore so a double-click isn't two wishes. */
        DUPLICATE,
        /** Out of tokens — tell them Moddy needs a breather. */
        RATE_LIMITED
    }

    /**
     * Decide whether to accept a wish. Consumes a token only when the answer is {@link Decision#ALLOW},
     * so a rejected wish never costs the player their budget.
     */
    public synchronized Decision check(String playerKey, String dedupeKey, long nowMs) {
        Bucket b = buckets.computeIfAbsent(playerKey == null ? "" : playerKey,
                k -> new Bucket(capacity, nowMs));
        b.refill(nowMs, capacity, refillIntervalMs);

        if (dedupeKey != null && !dedupeKey.isEmpty()
                && dedupeKey.equals(b.lastKey) && nowMs - b.lastAtMs < dedupeWindowMs) {
            return Decision.DUPLICATE;
        }
        if (b.tokens <= 0) {
            return Decision.RATE_LIMITED;
        }
        b.tokens--;
        b.lastKey = dedupeKey;
        b.lastAtMs = nowMs;
        return Decision.ALLOW;
    }

    /** Tokens currently available (for "/moddy status" and tests). */
    public synchronized int tokensFor(String playerKey, long nowMs) {
        Bucket b = buckets.get(playerKey == null ? "" : playerKey);
        if (b == null) {
            return capacity;
        }
        b.refill(nowMs, capacity, refillIntervalMs);
        return b.tokens;
    }

    /** Drop a player's state (on logout) so the map can't grow forever. */
    public synchronized void forget(String playerKey) {
        buckets.remove(playerKey == null ? "" : playerKey);
    }

    private static final class Bucket {
        int tokens;
        long lastRefillMs;
        String lastKey = "";
        long lastAtMs = Long.MIN_VALUE / 4; // far enough back that the first wish never dedupes

        Bucket(int tokens, long nowMs) {
            this.tokens = tokens;
            this.lastRefillMs = nowMs;
        }

        void refill(long nowMs, int capacity, long intervalMs) {
            if (nowMs <= lastRefillMs) {
                return; // clock went backwards; don't hand out free tokens
            }
            long elapsed = nowMs - lastRefillMs;
            long gained = elapsed / intervalMs;
            if (gained > 0) {
                tokens = (int) Math.min(capacity, tokens + gained);
                lastRefillMs += gained * intervalMs;
            }
        }
    }
}
