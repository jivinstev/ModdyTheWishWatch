package com.moddingfromamod.store;

import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

/**
 * <b>A plug-in's per-player stores, moved when a player's id changes</b>. A server that re-keys a player
 * (the same person arriving under a new id) moves every file in his shelf and the world's stores itself; a store a
 * plug-in keeps (a level's bases, its economy) answers here, so the server moves it without naming the plug-in.
 *
 * <p>Read with {@code Plugins.shared().all(PlayerRekey.class)}, every implementation asked in turn. With nothing
 * registered nothing extra is moved: the public single-player mod never re-keys anyone.
 *
 * <p>Server thread. <b>An internal seam</b>: {@code docs/EXTENDING.md} → "Internal seams". The re-key is our
 * own front door's (identity); this may change without notice.
 */
public interface PlayerRekey {

    /**
     * Files in the world folder this store moves ITSELF ({@link #rekey}), by name, so the server's generic file move
     * leaves them alone. Empty when the store is not running on this world.
     */
    default Set<String> ownFiles() {
        return Set.of();
    }

    /** Does this id own anything in this store? */
    default boolean holdsAnythingOf(UUID old) {
        return false;
    }

    /** Move {@code old}'s records to {@code key}, in memory and on disk. The names of the files that changed. */
    default Set<String> rekey(UUID old, UUID key) {
        return Set.of();
    }

    /** Re-read from disk, after the server's own move rewrote files this store reads. */
    default void reload(MinecraftServer server) {
    }
}
