package com.moddingfromamod.readymade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The per-wish notes queue</b>: what a nudge found, waiting for the worker's next tool
 * reply. an earlier issue's release note is the pattern — the hand-over cannot carry an answer that lands
 * later, so the answer rides the first reply after it does.
 *
 * <p>{@link #expect} marks a nudge in flight. Nothing waits on it except the two tools the issue
 * names ({@code author_datapack}, {@code cbscript_help}) — the moment a worker stops looking for a
 * ready-made thing and starts writing its own — and they wait at most {@link #PARK_MS}.
 */
public final class WishNotes {

    /** The issue's ceiling on a plan-time answer, for the two tools that wait for one. */
    public static final long PARK_MS = 5_000L;

    private static final WishNotes GLOBAL = new WishNotes();

    public static WishNotes global() {
        return GLOBAL;
    }

    public static WishNotes forTest() {
        return new WishNotes();
    }

    private final Map<String, Integer> pending = new HashMap<>();
    private final Map<String, List<String>> notes = new HashMap<>();

    /** A nudge for this wish has started. */
    public synchronized void expect(String wishId) {
        if (wishId != null) {
            pending.merge(wishId, 1, Integer::sum);
        }
    }

    /** A nudge has landed; an empty note is a {@code none} and adds nothing. */
    public synchronized void deliver(String wishId, String note) {
        if (wishId == null) {
            return;
        }
        pending.computeIfPresent(wishId, (k, v) -> v <= 1 ? null : v - 1);
        if (note != null && !note.isBlank()) {
            notes.computeIfAbsent(wishId, k -> new ArrayList<>()).add(note);
        }
        notifyAll();
    }

    public synchronized boolean pending(String wishId) {
        return wishId != null && pending.containsKey(wishId);
    }

    /** Wait up to {@code ms} for every nudge on this wish to land. True when none is left. */
    public synchronized boolean await(String wishId, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (pending(wishId)) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) {
                return false;
            }
            wait(left);
        }
        return true;
    }

    /** Every note waiting for this wish, joined, and cleared. Empty when none. */
    public synchronized String drain(String wishId) {
        List<String> n = wishId == null ? null : notes.remove(wishId);
        return n == null ? "" : String.join("\n\n", n);
    }

    /** The wish is over: nothing more will be read for it. */
    public synchronized void forget(String wishId) {
        if (wishId != null) {
            pending.remove(wishId);
            notes.remove(wishId);
            notifyAll();
        }
    }
}
