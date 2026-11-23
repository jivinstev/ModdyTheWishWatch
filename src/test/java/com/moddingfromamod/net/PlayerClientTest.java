package com.moddingfromamod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.plugin.Plugins;
import org.junit.jupiter.api.Test;

/**
 * With no client plug-in, every player is a Java player: the public mod's whole contract for the seam. The
 * Bedrock plug-in's side, on real players, is the private WatchHomeBedrockGameTest.
 */
class PlayerClientTest {

    @Test
    void withNoPluginEveryAnswerIsJavas() {
        Plugins none = new Plugins();
        assertFalse(PlayerClient.isBedrock(none, null));
        assertEquals(WatchPanel.Grid.JAVA, PlayerClient.grid(none, null));
        assertEquals("grid", PlayerClient.chestLayout(none, null), "the marker's layout word it always carried by default");
        JsonElement d = JsonParser.parseString("{\"title\":{\"text\":\"🔍 Find\"}}");
        assertSame(d, PlayerClient.dialog(none, null, d), "a Java player is sent the dialog exactly as it is");
    }

    @Test
    void aNullPlayerIsNeverAnotherClient() {
        Plugins p = new Plugins();
        p.add(ClientAdapter.class, new ClientAdapter() {
            @Override public boolean adapts(net.minecraft.server.level.ServerPlayer player) { return true; }
            @Override public WatchPanel.Grid grid(net.minecraft.server.level.ServerPlayer player) { return WatchPanel.Grid.POCKET; }
            @Override public String chestLayout(net.minecraft.server.level.ServerPlayer player) { return "list"; }
            @Override public JsonElement dialog(net.minecraft.server.level.ServerPlayer player, JsonElement json) { return json; }
            @Override public void registerPayloads(net.neoforged.neoforge.network.registration.PayloadRegistrar r) { }
        });
        assertFalse(PlayerClient.isBedrock(p, null));
        assertEquals("list", PlayerClient.chestLayout(p, null), "the adapter answers the layout once it is registered");
        assertEquals(WatchPanel.Grid.POCKET, PlayerClient.grid(p, null));
    }
}
