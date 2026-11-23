package com.moddingfromamod.wish.reveal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.PlayerKind;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>The pillar and the reveals are never sent to a camera client</b>.
 *
 * <p>Until this, every pillar in the world was TAKEN DOWN whenever any camera was shooting
 * ({@code WishCapture.anyShooting()}, global: any camera, any wish, any player). On a server with a
 * separate {@code Moddy-Camera} client the camera films during {@code finish_building}, so the
 * child watched his pillar vanish for 4–13 s right after "it's here!", come back, and only then
 * burst — it looked finished, then suddenly it wasn't (2026-10-01, on a live server; nine windows
 * matched to the second). The pillar is not in the photograph because the camera is never TOLD
 * about it — the same per-connection packet veil {@code vanilla/RigVeilService} uses to keep a
 * half-built creature from a stock child, the other way round. Nothing about the entity changes, so
 * every child keeps seeing it.
 *
 * <p>Two moments send the remove: right after the spawn ({@link #hideNow}, called from
 * {@code Fx.add} — the add was flushed synchronously inside {@code addFreshEntity}, so the remove
 * follows it in the same tick and the camera never renders a frame of it), and at the end of every
 * tick for everything alive, because a camera that teleports back into range is re-paired by vanilla
 * inside the tick. It is never lifted: a camera has no reason ever to see a wish's effects.
 *
 * <p>When the child's own eyes are the camera (no camera client), there is no other connection to
 * hide it from, so the pillar is still taken down — {@link #hiddenAt} — but only near the child
 * being photographed, never every pillar in the world.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class FxVeil {

    /** Every wish fx display alive. Server thread. */
    static final Set<Entity> LIVE = ConcurrentHashMap.newKeySet();

    /** How near a child being photographed a pillar must be to be taken down for the shot. */
    static final double OWN_EYES_REACH = 256;

    /** Removes sent to camera connections (Gate B). */
    private static volatile long sent;

    private FxVeil() {
    }

    /** The connections that are never told about a wish's effects: the mod's own camera clients. */
    static List<ServerPlayer> cameras(MinecraftServer server) {
        List<ServerPlayer> out = new ArrayList<>();
        if (server == null) {
            return out;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!PlayerKind.isChild(p.getUUID())) {
                out.add(p);
            }
        }
        return out;
    }

    /** A display was just spawned: un-tell every camera in its level, in the same tick. */
    static void hideNow(ServerLevel level, Entity e) {
        if (level == null || e == null) {
            return;
        }
        LIVE.add(e);
        for (ServerPlayer p : cameras(level.getServer())) {
            if (p.level() == level) {
                p.connection.send(new ClientboundRemoveEntitiesPacket(e.getId()));
                sent++;
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        sweep(event.getServer());
    }

    /** Answer any re-pairing before the next frame. Public for Gate B. */
    public static void sweep(MinecraftServer server) {
        LIVE.removeIf(Entity::isRemoved);
        if (LIVE.isEmpty()) {
            return;
        }
        List<ServerPlayer> cams = cameras(server);
        for (ServerPlayer p : cams) {
            ServerLevel level = p.level();
            int[] ids = LIVE.stream().filter(e -> e.level() == level).mapToInt(Entity::getId).toArray();
            if (ids.length > 0) {
                p.connection.send(new ClientboundRemoveEntitiesPacket(ids));
                sent++;
            }
        }
    }

    /** How many removes have been sent to cameras since the server started (Gate B). */
    public static long sent() {
        return sent;
    }

    /**
     * Must a pillar or reveal at {@code (x, z)} in {@code level} be TAKEN DOWN now? Only when a
     * child near it is lending his own eyes to the photograph ({@code WishCapture.ownEyesShooting}).
     */
    static boolean hiddenAt(List<ServerPlayer> eyes, ServerLevel level, double x, double z) {
        for (ServerPlayer p : eyes) {
            if (p.level() == level) {
                double dx = p.getX() - x, dz = p.getZ() - z;
                if (dx * dx + dz * dz <= OWN_EYES_REACH * OWN_EYES_REACH) {
                    return true;
                }
            }
        }
        return false;
    }
}
