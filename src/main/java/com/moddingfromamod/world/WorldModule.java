package com.moddingfromamod.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>What a world does that no rules-file parameter can express</b>
 * ("a pluggable world module"). The world's manifest names its module ({@code worlds/isle.json} →
 * {@code "module": "isle"}, rendered as {@code -Dmoddingfromamod.world.module}); the module supplies
 * the world's {@link Bases} provider, its onboarding and anything else only that world does.
 *
 * <p><b>No Java hot-reload</b>, deliberately: a module is code, and reloading code fights
 * NeoForge's registries, mixins and class loaders. A module changes with a release, which restarts
 * each world only when it is empty. What changes live is the rules FILE
 * ({@link WorldRulesService}).
 *
 * <p>Every method is called on the server thread by {@link WorldModuleHost}, which owns the event
 * handlers — so a module is a plain object a GameTest can drive, not a second set of listeners.
 */
public interface WorldModule {

    /** The id the manifest names it by: {@code "islands"}. */
    String id();

    /** The server is up: open this world's state. Returns its bases, or null when it has none. */
    Bases start(MinecraftServer server);

    /** The server stopped. */
    default void stop() {
    }

    /** A player joined (onboarding: an island module hands out an island and moves him onto it). */
    default void onLogin(ServerPlayer player) {
    }

    /** A player left (step 7: a module may record when he was last seen). */
    default void onLogout(ServerPlayer player) {
    }

    /** One server tick. */
    default void onTick() {
    }

    /**
     * Why an EMPTY dedicated server must keep ticking right now, or null to let vanilla's pause-when-empty apply
     * (follow-up). Vanilla 1.21.2+ stops ticking a server nobody has been on for
     * {@code pause-when-empty-seconds}, and with it {@link #onTick}: an island module's idle pre-generation, whose whole
     * point is to run while nobody is on, froze 60 s after the boot. Asked every tick (paused or not) by
     * {@code PauseWhileEmptyMixin} through {@link KeepTicking}, on the server thread, so keep it a field read.
     * Return null again as soon as the work is done, so the server rests. Default: never.
     */
    default String keepTicking() {
        return null;
    }

    /**
     * Extra lines this world adds to a player's help (folded in from the {@code BaseHelp} hook): said
     * after 💡 Help &amp; links ({@code GuidePages.sayLinks}) and after the respawn-loop rescue offer
     * ({@link RespawnGuard#offer}). An island module adds "Want to start over?" for a player with an island. Server
     * thread. Default: none.
     */
    default java.util.List<net.minecraft.network.chat.Component> helpLines(ServerPlayer player) {
        return java.util.List.of();
    }

    /**
     * What a wish costs in THIS world: a level's charge (a wish bank), or null for the plain rules
     * ({@code WishCharge}'s registered one, else free). {@code WishCharge.get()} asks the running module first, so
     * with two level jars in one instance each world is priced by its own level, never by whichever registered first.
     */
    default com.moddingfromamod.wish.WishCharge charge() {
        return null;
    }

    /**
     * What may be made in THIS world, and what waits ({@link WorldMakes}): this level's own, or null for the registered
     * one, else everything allowed. {@code WorldMakes.get()} asks the running module first, as the charge does.
     */
    default WorldMakes makes() {
        return null;
    }

    /**
     * The rules in force until this world's rules file has been read. A world with bases defaults to
     * {@link WorldRules#CLOSED} — never looser than before the file existed.
     */
    default WorldRules fallbackRules(Bases bases) {
        return bases == null ? WorldRules.ALLOW_ALL : WorldRules.CLOSED;
    }
}
