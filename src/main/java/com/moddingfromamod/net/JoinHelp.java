package com.moddingfromamod.net;

import com.moddingfromamod.runtime.RuntimeConfig;
import com.mojang.logging.LogUtils;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import org.slf4j.Logger;

/**
 * Adds "Trouble joining? moddythewishwatch.com/join" as the server list's second line, under the
 * server's own MOTD. Set at start rather than written into server.properties, because an install
 * never re-renders that file (it is the operator's own) — so a line added there would reach only
 * servers installed after today, and never the one already running.
 */
public final class JoinHelp {

    private static final Logger LOGGER = LogUtils.getLogger();

    private JoinHelp() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (!server.isDedicatedServer()) {
            return;   // singleplayer has no server list
        }
        String before = server.getMotd();
        String after = JoinWords.withHelpLine(before, JoinWords.helpLine(RuntimeConfig.joinHelpUrl()));
        if (!after.equals(before)) {
            server.setMotd(after);
            LOGGER.info("server list: {}", after.replace("\n", " / "));
        }
    }
}
