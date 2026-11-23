package com.moddingfromamod.plugin;

/**
 * A piece of Moddy that is not part of the public mod, plugged in at start (epic).
 *
 * <p>The open-source mod is the core. Private code (a server's own world modules, economy and settings)
 * reaches it only through this interface, so the core never names it: a plug-in is listed in
 * {@code META-INF/services/com.moddingfromamod.plugin.ModdyPlugin} and found by {@link java.util.ServiceLoader}.
 * The public repo ships that file empty; the private repo lists its plug-ins. {@code PublicSplitTest} fails any
 * direct reference from public code to private code, so this is the one way in.
 *
 * <p>A plug-in registers implementations of the core's hook interfaces with {@link Plugins#add}; the core asks
 * {@link Plugins#all} or {@link Plugins#first} and behaves as plain single-player Moddy when nothing is registered.
 */
public interface ModdyPlugin {

    /** A short name for the boot log, e.g. {@code "my-server"}. */
    String name();

    /** Register this plug-in's hook implementations. Called once, from the mod's constructor, before any use. */
    void register(Plugins plugins);
}
