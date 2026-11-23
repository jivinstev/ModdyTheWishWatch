package com.moddingfromamod.marshal;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>The first sign a child is about to wish, remembered until he does</b> (2026-10-10).
 *
 * <p>The pool is now stopped five minutes after anyone last touched Moddy, because children wish in
 * bursts and a parked worker on Muse keeps no warm cache between them. What keeps that from costing
 * a child a wait is that the pool is started on INTENT — the watch's panel opening, the Wish-now
 * anvil opening, every keystroke in it — so its ~9 s boot runs while he chooses and types. This is
 * where that bet is MEASURED: each signal is noted against the player, and the wish he then submits
 * takes the first one, so the action log can say how far ahead of the wish the pool was asked to
 * start, and an operator's report whether that was ever not enough.
 *
 * <p>Deliberately not a signal: switching to the watch in the hotbar (owner: intent is opening it).
 *
 * <p>Pure and Minecraft-free (the clock is a parameter), so the arithmetic is unit-tested and the
 * game only says who and when. Thread-safe: noted from the server thread, taken there too.
 */
public final class WishIntent {

    /** A signal older than this is a different visit to the watch, not this wish's run-up. */
    public static final long FORGET_MS = 10 * 60_000L;

    private static final WishIntent GLOBAL = new WishIntent();

    /** The first signal of the current run-up, and the newest one. */
    record Seen(String first, long firstMs, String kinds, long lastMs) {
    }

    /** What a submitted wish took: the first signal, how long before the wish, and every kind seen. */
    public record Taken(String first, long leadMs, String kinds) {
    }

    private final Map<UUID, Seen> byPlayer = new ConcurrentHashMap<>();

    public static WishIntent global() {
        return GLOBAL;
    }

    /** {@code kind}: watch | anvil | key. */
    public void note(UUID player, String kind, long nowMs) {
        if (player == null || kind == null) {
            return;
        }
        byPlayer.compute(player, (p, s) -> {
            if (s == null || nowMs - s.lastMs() > FORGET_MS) {
                return new Seen(kind, nowMs, kind, nowMs);
            }
            String kinds = ("," + s.kinds() + ",").contains("," + kind + ",") ? s.kinds() : s.kinds() + "," + kind;
            return new Seen(s.first(), s.firstMs(), kinds, nowMs);
        });
    }

    /** At a wish: the run-up, cleared, so the next wish measures its own. Empty when there was none. */
    public Optional<Taken> take(UUID player, long nowMs) {
        if (player == null) {
            return Optional.empty();
        }
        Seen s = byPlayer.remove(player);
        if (s == null || nowMs - s.lastMs() > FORGET_MS) {
            return Optional.empty();
        }
        return Optional.of(new Taken(s.first(), Math.max(0, nowMs - s.firstMs()), s.kinds()));
    }

    /** A player who left is not about to wish. */
    public void forget(UUID player) {
        if (player != null) {
            byPlayer.remove(player);
        }
    }
}
