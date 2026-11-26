package com.moddingfromamod.world;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Which region each living wish creature was last fitted in</b> (#182): the pure half of {@link RegionWatch}. A
 * creature is fitted by the region it stands in, so the moment it crosses into or out of one it must be fitted again.
 * Pure: no Minecraft. Thread-safe.
 */
public final class RegionCrossings {

    /** The region id of "no region": the world's own limits. */
    public static final String NONE = "";

    private final Map<UUID, String> last = new HashMap<>();

    /** It was just fitted in {@code region} ({@link #NONE} for none). */
    public synchronized void fitted(UUID creature, String region) {
        last.put(creature, region == null ? NONE : region);
    }

    /**
     * It stands in {@code region} now: true when that differs from where it was last fitted (and that is remembered).
     * A creature never seen is not moved: it is fitted when it spawns or loads, which records it.
     */
    public synchronized boolean moved(UUID creature, String region) {
        String now = region == null ? NONE : region;
        String was = last.get(creature);
        if (was == null || was.equals(now)) {
            return false;
        }
        last.put(creature, now);
        return true;
    }

    /** Is it being watched? */
    public synchronized boolean watches(UUID creature) {
        return last.containsKey(creature);
    }

    /** Every creature watched now (a copy). */
    public synchronized List<UUID> watched() {
        return List.copyOf(last.keySet());
    }

    /** It is gone (died, unloaded): it is fitted again, and so watched again, when it loads. */
    public synchronized void forget(UUID creature) {
        last.remove(creature);
    }

    /** Everything it has seen (a server stopping, the last region going away). */
    public synchronized void clear() {
        last.clear();
    }

    public synchronized int size() {
        return last.size();
    }
}
