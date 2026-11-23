package com.moddingfromamod.perf;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The {@code [chunks]} line</b>: one per player 15 s after joining (the fill), then one each
 * minute beside {@code [tick]} ({@link TickTimer}). The Minecraft half of {@link ChunkPace}; fed by
 * {@code mixin/ChunkBatchAckMixin} (the rate the client asks for) and {@code
 * mixin/PlayerChunkSenderMixin} (what was sent, what waits, what is unacknowledged).
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class ChunkPaceLog {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-tick");

    static final ChunkPace PACE = new ChunkPace();

    /** What a player's chunk sender can say right now ({@code PlayerChunkSenderMixin}). */
    public interface Readable {
        int moddingfromamod$pending();

        int moddingfromamod$unacked();
    }

    private ChunkPaceLog() {
    }

    public static void ack(ServerPlayer p, float desired) {
        PACE.ack(p.getStringUUID(), desired);
    }

    public static void sent(ServerPlayer p, int n, int pending, int unacked) {
        PACE.sent(p.getStringUUID(), n, pending, unacked);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            PACE.joined(p.getStringUUID(), p.getServer() == null ? 0 : p.getServer().getTickCount());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PACE.left(event.getEntity().getStringUUID());
    }

    /** Every tick with somebody on: anyone 15 s into their visit gets the first line. */
    static void tick(MinecraftServer server) {
        for (String id : PACE.firstDue(server.getTickCount())) {
            ServerPlayer p = server.getPlayerList().getPlayer(java.util.UUID.fromString(id));
            if (p != null && metered(p)) {
                LOG.info("[chunks] {}", PACE.line(id, p.getGameProfile().getName(), "first 15 s",
                        ChunkPace.FIRST_LINE_TICKS / 20.0, pending(p)));
            }
        }
    }

    /** At the end of {@link TickTimer}'s minute: everybody on. */
    static void window(MinecraftServer server, double seconds) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!metered(p)) {
                continue;
            }
            LOG.info("[chunks] {}", PACE.line(p.getStringUUID(), p.getGameProfile().getName(), "last minute",
                    seconds, pending(p)));
        }
    }

    /** Whether this player's chunk sender is metered: the meters are a server's own mixins, which the single-player
     *  mod does not carry. Without them every count reads zero, so no line at all. */
    private static boolean metered(ServerPlayer p) {
        return p.connection != null && p.connection.chunkSender instanceof Readable;
    }

    private static int pending(ServerPlayer p) {
        return p.connection != null && p.connection.chunkSender instanceof Readable r ? r.moddingfromamod$pending() : 0;
    }
}
