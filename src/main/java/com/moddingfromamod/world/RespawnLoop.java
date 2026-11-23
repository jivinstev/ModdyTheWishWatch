package com.moddingfromamod.world;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Is he dying in a loop on his own base?</b> The owner's island was covered in lava; every
 * respawn put him back in it and he died again before he could open the watch. {@link #died} is told of
 * each death ON HIS OWN BASE and answers true ONCE when there have been {@link #DEATHS} inside
 * {@link #WINDOW_MS} — the moment to offer him a way out in chat, which works without the watch. It
 * answers true again only after another full window, so a long struggle is offered help, not spammed.
 *
 * <p>Minecraft-free, so the counting is a Gate A test; {@code RespawnGuard} is the game-facing half.
 */
public final class RespawnLoop {

    /** Deaths inside the window that make a loop. */
    public static final int DEATHS = 3;
    /** The window, in milliseconds of real time (a death screen is real time, not ticks). */
    public static final long WINDOW_MS = 120_000L;

    private final Map<UUID, Deque<Long>> deaths = new HashMap<>();
    private final Map<UUID, Long> offeredAt = new HashMap<>();

    /** He died on his own base at {@code nowMs}. True when this is the moment to offer him a way out. */
    public synchronized boolean died(UUID id, long nowMs) {
        if (id == null) {
            return false;
        }
        Deque<Long> d = deaths.computeIfAbsent(id, k -> new ArrayDeque<>());
        d.addLast(nowMs);
        while (!d.isEmpty() && nowMs - d.peekFirst() >= WINDOW_MS) {
            d.removeFirst();
        }
        if (d.size() < DEATHS) {
            return false;
        }
        Long last = offeredAt.get(id);
        if (last != null && nowMs - last < WINDOW_MS) {
            return false;
        }
        offeredAt.put(id, nowMs);
        return true;
    }

    /** He left, or he was rescued: start counting afresh. */
    public synchronized void forget(UUID id) {
        deaths.remove(id);
        offeredAt.remove(id);
    }
}
