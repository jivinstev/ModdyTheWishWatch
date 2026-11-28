package com.moddingfromamod.wishbuilder;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>The Builder's page facts are worked out before anyone opens the Builder</b>. The walk ({@link BuilderPageFacts})
 * used to start lazily at the first Builder page, which then showed its tiles locked ("Getting this page ready…") for
 * seconds on a slow machine. Here every tree in play (the server's own allowlist and each online player's) is asked for
 * the moment a world answers makes, whenever that happens: at start, when a world module or the first region comes
 * live, or when a player whose allowlist is a new tree joins. Once a second, while a world answers, one cheap check per
 * tree (already known: two map reads); with none answering, nothing at all and the worker is never started. Never
 * blocks the server thread (the work is the worker's) and never runs inside a make. Registered on
 * {@code NeoForge.EVENT_BUS}.
 */
public final class BuilderPageWarm {

    /** How often the trees in play are looked at, in ticks. */
    static final int EVERY_TICKS = 20;

    private BuilderPageWarm() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % EVERY_TICKS == 0) {
            warm(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && p.level().getServer() != null) {
            warm(p.level().getServer());
        }
    }

    /** Ask for every tree in play now, if a world answers makes. @return how many trees were newly asked for. */
    public static int warm(MinecraftServer server) {
        if (!com.moddingfromamod.wish.MakeGate.anyWorld()) {
            return 0;
        }
        Set<BuilderAllowlist> trees = new LinkedHashSet<>();
        trees.add(BuilderSettings.current().allow());
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            trees.add(BuilderSettings.forPlayer(p.getUUID()).allow());
        }
        return BuilderPageFacts.warm(true, trees);
    }
}
