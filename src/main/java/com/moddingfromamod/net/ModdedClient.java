package com.moddingfromamod.net;

import java.util.List;
import java.util.Optional;

import com.moddingfromamod.plugin.Plugins;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * What the core asks about a MODDED client, and sends it: through the {@link ClientChannels} a plug-in
 * registered. With none (the public single-player mod, D17) there are no channels: every probe is false, every
 * factory null, every sync a no-op — exactly what a stock client got before, since every send skipped a player who
 * could not receive it. The core never names a payload class; it asks here.
 */
public final class ModdedClient {

    /** The modded client's channels, by what they are for. */
    public enum Channel { SPECIES_SYNC, PRESET_SYNC, WISH_ITEM_SYNC, PHOTO, CAPTURE_MODE, ASK_WHICH, WATCH_SYNC,
        CLIP, SPIN, FILM_PATH }

    /** Five numbers a pose; 64 poses is a 6 s clip at ~10 fps, far past any planned take. */
    public static final int MAX_SPIN_POSES = 64;

    /** The most creations the watch's list carries. */
    public static final int MAX_WATCH_CREATIONS = 64;

    private ModdedClient() {
    }

    private static Optional<ClientChannels> hook() {
        return Plugins.shared().first(ClientChannels.class);
    }

    /** Every modded-client channel. Nothing without a plug-in. */
    public static void registerPayloads(PayloadRegistrar registrar) {
        for (ClientChannels c : Plugins.shared().all(ClientChannels.class)) {
            c.registerPayloads(registrar);
        }
    }

    /** The registered type of a channel; null without a plug-in. */
    public static CustomPacketPayload.Type<?> type(Channel channel) {
        return hook().map(h -> h.type(channel)).orElse(null);
    }

    /** Can this player be sent this channel? False without a plug-in, for a stock client, and for a mock with none. */
    public static boolean can(ServerPlayer player, Channel channel) {
        CustomPacketPayload.Type<?> t = type(channel);
        return t != null && Send.canReceive(player, t);
    }

    /**
     * <b>Does this connection have our jar?</b> — the ONE probe for "modded" ({@link Send#hasOurJar}). Asked of the
     * species channel, the oldest and most central thing a modded client receives (it must be a channel that
     * is registered, or every modded client reads as stock).
     */
    public static boolean hasOurJar(ServerPlayer player) {
        return can(player, Channel.SPECIES_SYNC);
    }

    // ── The syncs ──────────────────────────────────────────────────────────────────────────

    /** Every species to one player. */
    public static void sendSpecies(ServerPlayer player) {
        if (can(player, Channel.SPECIES_SYNC)) hook().ifPresent(h -> Send.to(player, h.species()));
    }

    /** Every species to everyone who can take it. */
    public static void broadcastSpecies() {
        broadcastSpecies(net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer());
    }

    public static void broadcastSpecies(MinecraftServer server) {
        if (server != null) hook().ifPresent(h -> Send.all(server, h.species()));
    }

    /** One child's buttons, built for him. */
    public static void sendPresets(ServerPlayer player) {
        if (can(player, Channel.PRESET_SYNC)) {
            hook().ifPresent(h -> Send.to(player, h.presetsFor(player.getUUID().toString())));
        }
    }

    /** Everyone's buttons, each child's own, built only for who can get them. */
    public static void broadcastPresets(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendPresets(p);
        }
    }

    /** Every wish item to one player. */
    public static void sendWishItems(ServerPlayer player) {
        if (can(player, Channel.WISH_ITEM_SYNC)) hook().ifPresent(h -> Send.to(player, h.wishItems()));
    }

    /** The wish-item sync as a payload (null without a plug-in): a test's handle on the real one. */
    public static CustomPacketPayload wishItems() {
        return hook().map(ClientChannels::wishItems).orElse(null);
    }

    /** Every wish item to everyone who can take it. */
    public static void broadcastWishItems(MinecraftServer server) {
        if (server != null) hook().ifPresent(h -> Send.all(server, h.wishItems()));
    }

    // ── Payloads the core sends itself (null without a plug-in; Send skips a null) ──────────

    /** A single ordinary photograph: normal settle, HUD restored straight after. */
    public static CustomPacketPayload photo(String label) {
        return photo(label, 3, false);
    }

    /** A photograph from wherever the camera already is. */
    public static CustomPacketPayload photo(String label, int settleTicks, boolean holdHud) {
        return hook().map(h -> h.photo(label, settleTicks, holdHud, false, 0, 0, 0, 0, 0, false, false)).orElse(null);
    }

    /** A photograph from EXACTLY here (the client snaps to the pose immediately before the shutter). */
    public static CustomPacketPayload photoAt(String label, int settleTicks, boolean holdHud,
                                              double x, double y, double z, float yaw, float pitch,
                                              boolean quietEffects) {
        return hook().map(h -> h.photo(label, settleTicks, holdHud, true, x, y, z, yaw, pitch, quietEffects, false))
                .orElse(null);
    }

    /** A photograph that must contain the thing in his HAND. */
    public static CustomPacketPayload photoHeld(String label, int settleTicks, boolean holdHud,
                                                double x, double y, double z, float yaw, float pitch,
                                                boolean quietEffects) {
        return hook().map(h -> h.photo(label, settleTicks, holdHud, true, x, y, z, yaw, pitch, quietEffects, true))
                .orElse(null);
    }

    public static CustomPacketPayload spin(String label, List<Double> poses, int settleFrames, int gapMs, int width,
                                           int paceMs, int readyMs, List<Integer> ready) {
        return hook().map(h -> h.spin(label, poses, settleFrames, gapMs, width, paceMs, readyMs, ready)).orElse(null);
    }

    public static CustomPacketPayload clip(String label, int frames, int gapMs, int width) {
        return hook().map(h -> h.clip(label, frames, gapMs, width)).orElse(null);
    }

    public static CustomPacketPayload askWhich(List<String> ids) {
        return hook().map(h -> h.askWhich(ids)).orElse(null);
    }

    public static CustomPacketPayload captureMode(boolean active) {
        return hook().map(h -> h.captureMode(active)).orElse(null);
    }

    public static CustomPacketPayload watch(List<String> happening, List<String> creations) {
        return hook().map(h -> h.watch(happening, creations)).orElse(null);
    }

    public static CustomPacketPayload filmPath(FilmPathHandover handover) {
        return handover == null ? null : hook().map(h -> h.filmPath(handover)).orElse(null);
    }
}
