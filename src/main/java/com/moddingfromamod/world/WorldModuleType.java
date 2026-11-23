package com.moddingfromamod.world;

/**
 * <b>A kind of {@link WorldModule} a plug-in offers</b> (epic). The core names no module: a private
 * plug-in registers one of these with {@code Plugins.shared().add(WorldModuleType.class, …)}, and
 * {@link WorldModuleHost} starts the one the world's manifest names. With none registered every world runs with
 * no module, which is the public single-player mod.
 *
 * <p><b>A documented extension point</b>: {@code docs/EXTENDING.md} → "WorldModuleType".
 */
public interface WorldModuleType {

    /** The id a manifest names it by ({@code "module": "islands"}). */
    String id();

    /** A fresh module, started by {@link WorldModuleHost} when the server is up. */
    WorldModule create();

    /**
     * True when this module should run on a world that names NO module (a server whose launch
     * config predates the {@code module} line but has this module's feature switched on). Read at server start.
     */
    default boolean impliedWhenUnnamed() {
        return false;
    }

    /**
     * True when THIS world was made as one of this module's worlds: a level jar's world type, its built-in
     * packs in the world's own data configuration, or a marker the module wrote in the world's saved data. Asked at
     * server start, after a JVM property naming a module (which always wins, so a house is unchanged) and before
     * {@link #impliedWhenUnnamed()}. Per world, so one instance can hold a level's world and a plain one side by side.
     */
    default boolean claims(net.minecraft.server.MinecraftServer server) {
        return false;
    }
}
