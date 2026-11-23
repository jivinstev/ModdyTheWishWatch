package com.moddingfromamod.world;

import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The running half of {@link StandalonePacks}: once a minute, push back the lifetime of every player who is
 * on his own base, and take away what has run out — a datapack is switched off (its zip kept, exactly as
 * {@code remove_datapack} does), a rule removed — telling him if he is on. {@link #reclaimed} takes every one of a
 * player's when his base is reclaimed.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class StandalonePackService {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-standalone");

    private StandalonePackService() {
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 1200 != 600) {
            return;
        }
        tick(event.getServer());
    }

    /** One pass: renew, then take away what is due. Public for a GameTest. */
    public static void tick(MinecraftServer server) {
        StandalonePacks.Lifetime l = StandalonePacks.of(WorldRulesService.loadedOrNull());
        Bases bases = WorldModuleHost.liveBases();
        if (l == null || bases == null) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Base b = bases.baseOf(p.getUUID());
            if (b != null && b.holds(p.level().dimension().location().toString(), p.getX(), p.getZ())) {
                StandalonePacks.renew(p.getUUID(), l);
            }
        }
        for (StandalonePacks.Entry e : StandalonePacks.dueNow()) {
            takeAway(server, e, StandalonePacks.endWords(e));
        }
    }

    /** His base was reclaimed: every standalone pack and rule of his goes, whatever its end. */
    public static int reclaimed(MinecraftServer server, UUID owner) {
        int n = 0;
        for (StandalonePacks.Entry e : StandalonePacks.of(owner.toString())) {
            takeAway(server, e, null);
            n++;
        }
        return n;
    }

    private static void takeAway(MinecraftServer server, StandalonePacks.Entry e, String words) {
        try {
            if (e.kind().equals("rule")) {
                com.moddingfromamod.rules.WorldRules.remove(server, e.id());
            } else {
                com.moddingfromamod.datapack.DatapackReloader.disableAndReload(server, e.id());
            }
        } catch (RuntimeException ex) {
            LOG.warn("[standalone] could not take away {} {}: {}", e.kind(), e.id(), ex.toString());
        }
        StandalonePacks.ended(e);
        LOG.info("[standalone] {} {} of {} ended ({})", e.kind(), e.id(), e.playerId(), words == null ? "base reclaimed"
                : "lifetime over");
        if (words != null) {
            try {
                ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(e.playerId()));
                if (p != null) {
                    p.sendSystemMessage(Component.literal("✨ " + words).withStyle(ChatFormatting.AQUA));
                }
            } catch (IllegalArgumentException ignored) {
                // not a player id: nobody to tell
            }
        }
    }
}
