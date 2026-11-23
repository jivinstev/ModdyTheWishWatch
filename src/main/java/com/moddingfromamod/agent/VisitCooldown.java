package com.moddingfromamod.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * <b>A short cooldown per visitor and host</b> (the mitigation for "following": one
 * child repeatedly appearing next to another). A visit stamps the pair; the same visitor reaching the
 * same host again inside {@link #WINDOW_MS} is turned away kindly. Another host is a different pair,
 * and the host visiting back is a different pair too.
 *
 * <p>Pure: the clock is handed in ({@code VisitCooldownTest}), and nothing here touches the game.
 */
public final class VisitCooldown {

    /** 60 s: long enough that "follow him around" stops working, short enough for a game of tag. */
    public static final long WINDOW_MS = 60_000L;

    private final LongSupplier clock;
    private final long window;
    private final Map<String, Long> last = new HashMap<>();

    public VisitCooldown(LongSupplier clock, long windowMs) {
        this.clock = clock;
        this.window = windowMs;
    }

    /** The one the server uses. */
    public static final VisitCooldown SHARED = new VisitCooldown(System::currentTimeMillis, WINDOW_MS);

    /**
     * May {@code visitor} visit {@code host} now? If yes the visit is stamped; if no, 0 is never
     * returned — the whole seconds left are.
     */
    public synchronized long tryVisit(UUID visitor, UUID host) {
        long now = clock.getAsLong();
        String key = visitor + ">" + host;
        Long at = last.get(key);
        if (at != null && now - at < window) {
            return Math.max(1, (window - (now - at) + 999) / 1000);
        }
        last.put(key, now);
        if (last.size() > 4096) {
            last.values().removeIf(t -> now - t >= window);
        }
        return 0;
    }

    /** Forget every stamp (a test). */
    public synchronized void clear() {
        last.clear();
    }
}
