package com.moddingfromamod.safety;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <b>When a held-back message becomes a short break from chat</b>. Pure and clock-handed: the
 * caller passes the time, so Gate A asserts the whole schedule in milliseconds.
 *
 * <p>One held-back message is a mistake and costs nothing more than the message.
 * {@link #STRIKES_TO_MUTE} of them inside {@link #WINDOW_MS} is a pattern, and the sender's chat
 * rests for a while — {@link #MUTE_MS}, longer each time it happens again within a day. A mute never
 * touches anything but CHAT: the player still plays and still wishes, and the lines say so.
 *
 * <p>Memory only, deliberately: a restart forgives, and nothing about a stranger's manners is written
 * to disk beyond the {@code safety} records the sweep already logs (which carry no words).
 */
public final class ChatStrikes {

    /** How far back a held-back message still counts. */
    public static final long WINDOW_MS = 10 * 60_000L;
    /** Held-back messages inside the window that start a break. */
    public static final int STRIKES_TO_MUTE = 3;
    /** The first, second and third-or-later break inside {@link #REPEAT_MS}. */
    static final long[] MUTE_MS = {10 * 60_000L, 30 * 60_000L, 60 * 60_000L};
    /** How long an earlier break makes the next one longer. */
    public static final long REPEAT_MS = 24 * 60 * 60_000L;

    private final Map<UUID, Deque<Long>> strikes = new HashMap<>();
    private final Map<UUID, Deque<Long>> mutes = new HashMap<>();
    private final Map<UUID, Long> mutedUntil = new HashMap<>();

    /** Until when this player's chat rests, or 0 when it does not. */
    public synchronized long mutedUntil(UUID player, long nowMs) {
        Long until = mutedUntil.get(player);
        if (until == null || until <= nowMs) {
            mutedUntil.remove(player);
            return 0L;
        }
        return until;
    }

    /**
     * One held-back message. Returns the end of the break it STARTED, or 0 when it started none —
     * so the caller can say "back in 10 minutes" exactly once, on the message that began it.
     */
    public synchronized long strike(UUID player, long nowMs) {
        Deque<Long> s = strikes.computeIfAbsent(player, k -> new ArrayDeque<>());
        s.addLast(nowMs);
        while (!s.isEmpty() && nowMs - s.peekFirst() > WINDOW_MS) {
            s.removeFirst();
        }
        if (s.size() < STRIKES_TO_MUTE) {
            return 0L;
        }
        s.clear();
        Deque<Long> m = mutes.computeIfAbsent(player, k -> new ArrayDeque<>());
        while (!m.isEmpty() && nowMs - m.peekFirst() > REPEAT_MS) {
            m.removeFirst();
        }
        long length = MUTE_MS[Math.min(m.size(), MUTE_MS.length - 1)];
        m.addLast(nowMs);
        long until = nowMs + length;
        mutedUntil.put(player, until);
        return until;
    }

    /** Whole minutes left, rounded UP (a child is never told "back in 0 minutes"). Pure. */
    public static int minutesLeft(long untilMs, long nowMs) {
        long left = Math.max(0L, untilMs - nowMs);
        return (int) Math.max(1L, (left + 59_999L) / 60_000L);
    }

    /** Forget everybody (a server stop). */
    public synchronized void clear() {
        strikes.clear();
        mutes.clear();
        mutedUntil.clear();
    }
}
