package com.moddingfromamod.store;

import java.nio.file.Path;

/**
 * The Minecraft-side half of {@link ArtifactTimes}: the clock, and where the backfill's two files
 * are for the world being loaded.
 */
public final class ShelfTimes {

    private ShelfTimes() {
    }

    /** What wishes made since this server started, by {@code kind:id} — the live half. */
    private static final java.util.Map<String, Long> LIVE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Path seededFor;
    private static volatile TimesBackfill seed = TimesBackfill.EMPTY;

    /** A granted wish made this (from {@code ActionLog.made}): newest of its kind from now on. */
    public static void made(String kind, String id, long when) {
        if (kind != null && id != null && !id.isBlank()) {
            LIVE.merge(kind.trim().toLowerCase(java.util.Locale.ROOT) + ":"
                    + id.trim().toLowerCase(java.util.Locale.ROOT), when, Math::max);
        }
    }

    /**
     * When a wish last made this, for the kinds that are not shelved and so carry no time of
     * their own — machines and trains. The records are read ONCE per world and then kept current
     * by {@link #made}: the watch asks every half second, and the action log changes with every
     * tool call.
     */
    public static long madeAt(String kind, String id) {
        Path w = com.moddingfromamod.runtime.WorldPaths.wishesDir();
        if (!java.util.Objects.equals(w, seededFor)) {
            seed = backfill(w);
            seededFor = w;
        }
        Long live = LIVE.get((kind == null ? "" : kind.trim().toLowerCase(java.util.Locale.ROOT))
                + ":" + (id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT)));
        return Math.max(live == null ? 0L : live, seed.timesOf(kind, id).recency());
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    /** The backfill for the world whose {@code wishes/} folder is {@code wishesDir} (may be null). */
    public static TimesBackfill backfill(Path wishesDir) {
        try {
            return TimesBackfill.forFiles(com.moddingfromamod.agent.ActionLog.path(),
                    wishesDir == null ? null : wishesDir.resolve("journal.jsonl"));
        } catch (RuntimeException e) {
            return TimesBackfill.EMPTY;
        }
    }
}
