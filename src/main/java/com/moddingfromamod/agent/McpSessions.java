package com.moddingfromamod.agent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP sessions: a UUID minted at {@code initialize} and required on every later call.
 *
 * <p>Idle sessions expire. When a client presents an unknown or expired id the protocol answers
 * <b>404</b>, which the spec defines as "re-initialize" — so a client that was asleep reconnects by
 * itself instead of getting stuck.
 *
 * <p>Thread-safe: HTTP handler threads touch this directly (it holds no game state).
 */
public final class McpSessions {

    /** How long a session may sit idle before it's dropped. */
    public static final long DEFAULT_TTL_MS = 60L * 60L * 1000L;

    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final long ttlMs;

    public McpSessions() {
        this(DEFAULT_TTL_MS);
    }

    public McpSessions(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public String create(long nowMs) {
        sweep(nowMs);
        String id = UUID.randomUUID().toString();
        lastSeen.put(id, nowMs);
        return id;
    }

    /** Mark a session alive. False if unknown or expired (→ the caller answers 404). */
    public boolean touch(String id, long nowMs) {
        if (id == null) {
            return false;
        }
        Long seen = lastSeen.get(id);
        if (seen == null) {
            return false;
        }
        if (nowMs - seen > ttlMs) {
            lastSeen.remove(id);
            return false;
        }
        lastSeen.put(id, nowMs);
        return true;
    }

    public void end(String id) {
        if (id != null) {
            lastSeen.remove(id);
        }
    }

    public int size() {
        return lastSeen.size();
    }

    private void sweep(long nowMs) {
        lastSeen.entrySet().removeIf(e -> nowMs - e.getValue() > ttlMs);
    }
}
