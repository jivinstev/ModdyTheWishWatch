package com.moddingfromamod.marshal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which progress bar belongs to which wish, and to which child.
 *
 * <p><b>The bookkeeping that was wrong, separated from the drawing.</b> The progress layer kept its
 * bars in a map keyed by PLAYER while wishes became parallel, which was three bugs wearing one
 * symptom — Steve made three wishes at once and saw a single bar showing the first. Underneath:
 * a second wish could not open a bar at all, a third overwrote the second's plan, and the first wish
 * to finish closed the shared bar while two were still building, which reads exactly like everything
 * having stopped.
 *
 * <p>All three are questions about a map, and none of them needs Minecraft. Keeping them here means
 * they are answered in microseconds by unit tests rather than by a three-minute GameTest — and, as
 * it turned out, by one that could not be written at all: asserting this on a real server needs a
 * mock player in the level, and Create broadcasts {@code sync_rail_graph} to the player list, which
 * crashes the server on a player that has no connection. The rule is not about players; it is about
 * keys.
 */
public final class BarBook {

    /** wish → who it belongs to and what to call it. Ordered, so bars stack in wish order. */
    private final Map<UUID, Entry> open = new LinkedHashMap<>();

    /** @param name what the bar is called, so three of them are tellable apart */
    public record Entry(UUID playerId, String name) {
    }

    /**
     * Start a bar for this wish, or leave the existing one alone.
     *
     * <p>Returns whether a NEW bar was opened, so the caller knows whether to build the Minecraft
     * side. Re-opening an existing wish is a no-op rather than a reset — a bar that restarts its own
     * animation every time something touches it looks like it is stuck.
     */
    public boolean open(UUID wishId, UUID playerId, String name) {
        if (wishId == null || playerId == null || open.containsKey(wishId)) {
            return false;
        }
        open.put(wishId, new Entry(playerId, name == null ? "" : name.strip()));
        return true;
    }

    /** Rename one, e.g. once the resolver works out that "make it bigger" meant his ice dragon. */
    public void rename(UUID wishId, String name) {
        Entry e = open.get(wishId);
        if (e != null && name != null && !name.isBlank()) {
            open.put(wishId, new Entry(e.playerId(), name.strip()));
        }
    }

    public String nameOf(UUID wishId) {
        Entry e = open.get(wishId);
        return e == null ? "" : e.name();
    }

    /** Close ONE wish's bar. Not the child's — that is the bug this class exists to prevent. */
    public boolean close(UUID wishId) {
        return wishId != null && open.remove(wishId) != null;
    }

    /** Every bar this child has, for logging out and for "stop everything". */
    public List<UUID> forPlayer(UUID playerId) {
        return open.entrySet().stream()
                .filter(e -> e.getValue().playerId().equals(playerId))
                .map(Map.Entry::getKey)
                .toList();
    }

    public int countFor(UUID playerId) {
        return forPlayer(playerId).size();
    }

    public boolean isOpen(UUID wishId) {
        return wishId != null && open.containsKey(wishId);
    }
}
