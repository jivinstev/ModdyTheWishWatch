package com.moddingfromamod.world;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * <b>Owners that are worlds, not children</b> (#178). A world module's own makes (a world's champion designs,
 * {@code BuilderMake.forWorld}) belong to a fixed id per module. A wish owned by one has NO child behind it: nobody is
 * told its plan, no preview stands beside anybody, nothing is placed at "the nearest child" for it ({@code ActingChild}
 * answers nobody), and its pillar is the world's colour.
 *
 * <p>The id is marked in its own bits, so it is told from a player's with nothing remembered: a player's id is a
 * version 4 (online) or version 3 (offline) UUID, and a world's is version 8 (RFC 9562's custom version), the rest
 * the name-based hash of the module id. So it still reads as a world's after a restart, for a design kept from before.
 * Pure: no Minecraft.
 */
public final class WorldOwners {

    private static final int VERSION = 8;

    private WorldOwners() {
    }

    /** The owner a world module's own makes belong to: one fixed id per module, never a player's. */
    public static UUID of(String moduleId) {
        UUID hashed = UUID.nameUUIDFromBytes(("moddy-world:" + (moduleId == null ? "" : moduleId))
                .getBytes(StandardCharsets.UTF_8));
        long msb = (hashed.getMostSignificantBits() & ~0xF000L) | ((long) VERSION << 12);
        return new UUID(msb, hashed.getLeastSignificantBits());   // the variant bits stay RFC 4122's
    }

    /** Is {@code id} a world's, not a child's? */
    public static boolean is(UUID id) {
        return id != null && id.version() == VERSION && id.variant() == 2;
    }
}
