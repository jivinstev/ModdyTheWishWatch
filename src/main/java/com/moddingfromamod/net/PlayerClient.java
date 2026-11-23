package com.moddingfromamod.net;

import java.util.Optional;

import com.google.gson.JsonElement;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.plugin.Plugins;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Which client a player is on, as the core asks it: through the {@link ClientAdapter} a plug-in registered,
 * and with none, every player is a Java player — the plain single-player mod. The core never names the Bedrock code;
 * it asks here.
 *
 * <p>Each question has a twin that takes the {@link Plugins} to read, so a test can ask a registry with no plug-in in
 * it (or one it filled) about a real player.
 */
public final class PlayerClient {

    /** The button-chest layout with no adapter: the one the marker has always carried by default. */
    public static final String DEFAULT_LAYOUT = "grid";

    private PlayerClient() {
    }

    private static Optional<ClientAdapter> adapter(Plugins plugins) {
        return plugins.first(ClientAdapter.class);
    }

    /** Is this player on a Bedrock client? Always false without the private Bedrock plug-in. */
    public static boolean isBedrock(ServerPlayer player) {
        return isBedrock(Plugins.shared(), player);
    }

    public static boolean isBedrock(Plugins plugins, ServerPlayer player) {
        return player != null && adapter(plugins).map(a -> a.adapts(player)).orElse(false);
    }

    /** The chest grid this player's client draws. Java: 9 wide. */
    public static WatchPanel.Grid grid(ServerPlayer player) {
        return grid(Plugins.shared(), player);
    }

    public static WatchPanel.Grid grid(Plugins plugins, ServerPlayer player) {
        return adapter(plugins).map(a -> a.grid(player)).orElse(WatchPanel.Grid.JAVA);
    }

    /** The button-chest layout word for this player (null: the server's). */
    public static String chestLayout(ServerPlayer player) {
        return chestLayout(Plugins.shared(), player);
    }

    public static String chestLayout(Plugins plugins, ServerPlayer player) {
        return adapter(plugins).map(a -> a.chestLayout(player)).orElse(DEFAULT_LAYOUT);
    }

    /** The dialog JSON this player is sent. Java: exactly the dialog. */
    public static JsonElement dialog(ServerPlayer player, JsonElement json) {
        return dialog(Plugins.shared(), player, json);
    }

    public static JsonElement dialog(Plugins plugins, ServerPlayer player, JsonElement json) {
        return adapter(plugins).map(a -> a.dialog(player, json)).orElse(json);
    }

    /** Every adapter's own channels. Nothing without a plug-in. */
    public static void registerPayloads(PayloadRegistrar registrar) {
        for (ClientAdapter a : Plugins.shared().all(ClientAdapter.class)) {
            a.registerPayloads(registrar);
        }
    }
}
