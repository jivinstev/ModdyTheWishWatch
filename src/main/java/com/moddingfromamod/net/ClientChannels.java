package com.moddingfromamod.net;

import java.util.List;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The hook for a MODDED client's own channels (epic): every payload the server sends to, or takes
 * from, a client that runs Moddy's jar — the species, button and item syncs, the wish camera's shutter, spin and
 * clip, the watch's list, the film path, the nicer "which one?" picker — and the channels a proxy in front of the server
 * speaks (the service-client challenge, the player's real version).
 *
 * <p>The public mod is single-player with no client code (D17), so it registers none of them: with no hook, every
 * factory here is absent, {@link ModdedClient} answers null and false, and the server behaves exactly as it does for
 * a stock client today (each send site already skipped a client that cannot receive). On our own servers the
 * private plug-in {@code net/ModdedClientPlugin} implements it, with the payload classes, and our servers and the
 * modded client behave as before. Register it with {@code Plugins.add(ClientChannels.class, impl)}.
 *
 * <p>Each factory builds the payload; the core sends it through {@link Send}, so every send still skips a player
 * who cannot receive it.
 *
 * <p><b>An internal seam, not an extension point</b>: it exists for our private repo, whose modded client is
 * the only thing that could answer these channels, and it may change without notice. Do not build on it.
 */
public interface ClientChannels {

    /** Register every channel, on the mod's one (optional) registrar. */
    void registerPayloads(PayloadRegistrar registrar);

    /** The registered type for a channel, for {@link Send#canReceive}; null if this hook has none for it. */
    CustomPacketPayload.Type<?> type(ModdedClient.Channel channel);

    /** Every species definition, for the client's renderer. */
    CustomPacketPayload species();

    /** One child's buttons (the set is built for him). */
    CustomPacketPayload presetsFor(String viewerId);

    /** Every wish item, for the client-side creative menu. */
    CustomPacketPayload wishItems();

    /** A photograph: from where the camera is, or (with {@code hasPose}) from exactly here. */
    CustomPacketPayload photo(String label, int settleTicks, boolean holdHud, boolean hasPose,
                              double x, double y, double z, float yaw, float pitch,
                              boolean quietEffects, boolean showHand);

    /** A spin take: the whole camera path, one frame per pose. */
    CustomPacketPayload spin(String label, List<Double> poses, int settleFrames, int gapMs, int width,
                             int paceMs, int readyMs, List<Integer> ready);

    /** A clip the client films of itself. */
    CustomPacketPayload clip(String label, int frames, int gapMs, int width);

    /** The picker for "which one did you mean?" (chat already asks; this is the nicer answer). */
    CustomPacketPayload askWhich(List<String> ids);

    /** The capture mode a camera holds while a wish is photographed. */
    CustomPacketPayload captureMode(boolean active);

    /** The watch's list: what is happening and what he made. */
    CustomPacketPayload watch(List<String> happening, List<String> creations);

    /** The smooth film camera's path for a modded viewer to draw. */
    CustomPacketPayload filmPath(FilmPathHandover handover);
}
