package com.moddingfromamod.world;

import java.util.UUID;

/**
 * <b>Whose base is this spot, and where is this player's base</b> (locked 2026-09-29).
 *
 * <p>The general concept a Survival world's rules are written against. A private world module's provider is
 * its islands ({@code isle.IslandService}); a future world can supply land claims or plots instead,
 * and nothing that reads this interface changes. The reach rule ({@link BaseReach}), the
 * respawn-home rule ({@link WorldModuleHost}) and an operator's tools speak only to this — never to an island
 * grid — which is the whole point of lifting the island code behind it.
 *
 * <p>Minecraft-free, so every rule written against it is a Gate A test; the game-facing provider
 * answers {@link #positionOf} from the live player list.
 */
public interface Bases {

    /** The overworld's dimension id, which is where every base of T17 is. */
    String OVERWORLD = "minecraft:overworld";

    /** A position with its dimension. */
    record Pos(String dimension, double x, double y, double z) {
    }

    /** What a base is called on this world, for the words a refusal uses: "island", "plot". */
    String noun();

    /** This player's base, or null (none yet — or a camera, which never gets one). */
    Base baseOf(UUID player);

    /** Whose base is this column? The owner, or null when it is nobody's (the arrival, the void). */
    UUID ownerAt(String dimension, double x, double z);

    /** Where the player is now, or null when he is not in the world. */
    Pos positionOf(UUID player);

    /**
     * Can he be sent to his base yet? False while it is still being made (an island waiting for its
     * chunks): sending him there would drop him into the void beside nothing. Default true.
     */
    default boolean standsYet(UUID player) {
        return true;
    }
}
