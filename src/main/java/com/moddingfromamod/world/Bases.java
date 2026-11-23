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

    // ── a server's own upkeep: what a house asks of the running world's bases without naming the level ──

    /** Where a service player stands on its own base, and whether the base was put back to bare first. */
    record ServiceStand(int x, int y, int z, boolean reset) {
    }

    /**
     * Give a SERVICE player (a server's own monitoring player, never a child) a base of its own on this world, so a
     * wish it makes may change something at all. Called once the world's module has started. Default: this
     * world keeps no bases for service players, and nothing happens.
     */
    default void reserveService(UUID service) {
    }

    /**
     * Ready a service player's base for a wish it is about to make: keep its chunks loaded, put it back to bare,
     * and say where it stands. Null when it has no base built here yet (the default: the service player then wishes
     * from the world's spawn, as on a world with no bases).
     */
    default ServiceStand readyService(UUID service) {
        return null;
    }

    /** Keep a service player's base loaded while its wish runs (nobody else is near it). Default: nothing to hold. */
    default void holdService(UUID service) {
    }

    /**
     * An operator's tool: retire ONE player's base now, by its owner id (an orphaned base nobody can reach).
     * Returns {@code "rested"}, {@code "absent"}, or a line starting {@code "refused"}. Default: refused, because
     * this world's bases cannot be rested.
     */
    default String rest(UUID owner, long nowMs) {
        return "refused: this world's " + noun() + "s cannot be rested";
    }
}
