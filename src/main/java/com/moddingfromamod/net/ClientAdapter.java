package com.moddingfromamod.net;

import com.google.gson.JsonElement;
import com.moddingfromamod.marshal.WatchPanel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * A hook for a client that is NOT Java's (epic): how the server ADAPTS what it already sends a Java player
 * so that client draws the same thing. On our own servers the private Bedrock plug-in implements it (Bedrock players
 * through Geyser); the public mod has none, and then every player is a Java player ({@link PlayerClient}).
 *
 * <p><b>Adapt-only</b> (D14): an adapter changes how something is SHOWN, never what exists. It registers no
 * commands, items, entities, wish or MCP tools, saved data or settings; its network channels carry only what the
 * other client reports about itself or needs in order to draw. Register it with
 * {@code Plugins.add(ClientAdapter.class, impl)} from a {@link com.moddingfromamod.plugin.ModdyPlugin}.
 *
 * <p><b>An internal seam, not a documented extension point</b>: it exists for our private Bedrock module
 * (D17: Bedrock stays private), and its shape follows what that module needs. It may change without notice.
 */
public interface ClientAdapter {

    /** Is this player on the client this adapter is for? Null: no. */
    boolean adapts(ServerPlayer player);

    /** The chest grid this player's client draws a 54-slot chest as. */
    WatchPanel.Grid grid(ServerPlayer player);

    /**
     * The button-chest layout word {@link ButtonChest#title} carries in its marker ({@code grid}, {@code list},
     * {@code off}) for this player; null: the server's own.
     */
    String chestLayout(ServerPlayer player);

    /** A dialog's JSON as this player's client should get it (the same dialog; only how it draws may differ). */
    JsonElement dialog(ServerPlayer player, JsonElement json);

    /** Register the adapter's own network channels, from the mod's payload registration. */
    void registerPayloads(PayloadRegistrar registrar);
}
