package com.moddingfromamod.plugin;

import net.neoforged.bus.api.IEventBus;

/**
 * <b>A plug-in's own event listeners</b>: a dedicated server's operations (its {@code /moddyop} commands, health
 * thread, AFK kick, join timings, the service-client gate) are private, so the core cannot name them. It asks every
 * registered wiring at the two points where it registers its own listeners, so a plug-in's listeners go in at the
 * place in the order they always did. With none (the public single-player mod) nothing is added.
 *
 * <p><b>An internal seam, not a documented extension point</b>: it keeps a private plug-in's listeners out of
 * the mod constructor. Its shape may change with them.
 */
public interface BusWiring {

    /** Called with the mod event bus, among the core's mod-bus listeners. */
    default void modBus(IEventBus modBus) {
    }

    /** Called with the game event bus ({@code NeoForge.EVENT_BUS}), among the core's game-bus handlers. */
    default void gameBus(IEventBus gameBus) {
    }
}
