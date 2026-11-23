package com.moddingfromamod.seam;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>One player's world settings, for a test that shares its server.</b>
 *
 * <p>A GameTest batch may run every case's bot in ONE server at once. A case about a Wish Crafter, or about a
 * world whose Builder is on, cannot switch the server's own settings ({@code crafters.open}, {@code builder.enabled}):
 * every other bot's case would see them too (the watch cases count the watch's buttons). So a case marks ITS bot,
 * and the paths that decide "is he a Crafter" ({@code CrafterService.role}) and "is the Builder on for him"
 * ({@code BuilderSettings.forPlayer}) read the mark first.
 *
 * <p>Nothing that ships sets a mark: only a GameTest does, and the batch clears a bot's marks before each case it
 * takes. Empty — every real server — it changes nothing.
 */
public final class PlayerSeams {
    private static final Set<UUID> CRAFTERS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> BUILDER_ON = ConcurrentHashMap.newKeySet();

    private PlayerSeams() {
    }

    /** He is a Wish Crafter, as if the server's Crafters door were open and he were not on its list. */
    public static void crafter(UUID id) {
        CRAFTERS.add(id);
    }

    /** The Builder is on for him, as if the server's {@code builder.enabled} were true. */
    public static void builderOn(UUID id) {
        BUILDER_ON.add(id);
    }

    public static boolean isCrafter(UUID id) {
        return id != null && !CRAFTERS.isEmpty() && CRAFTERS.contains(id);
    }

    public static boolean hasBuilderOn(UUID id) {
        return id != null && !BUILDER_ON.isEmpty() && BUILDER_ON.contains(id);
    }

    /** Every mark on him, gone. */
    public static void clear(UUID id) {
        if (id != null) {
            CRAFTERS.remove(id);
            BUILDER_ON.remove(id);
        }
    }
}
