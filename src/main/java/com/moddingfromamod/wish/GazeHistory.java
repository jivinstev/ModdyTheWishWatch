package com.moddingfromamod.wish;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>Where each player looked over the last few ticks</b> (#114), for {@link AimConfidence#steady}: a press after a
 * steady gaze is a look at something, a press in the middle of a turn is not. Two floats per tick per player, the last
 * {@link #KEPT} ticks, in memory only (nothing stored), forgotten when he leaves. Server thread.
 */
@EventBusSubscriber
public final class GazeHistory {

    /** How many ticks are kept. */
    static final int KEPT = AimConfidence.STEADY_TICKS + 2;

    private static final Map<UUID, ArrayDeque<AimConfidence.Look>> LOOKS = new ConcurrentHashMap<>();

    private GazeHistory() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            record(p.getUUID(), p.getYRot(), p.getXRot());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LOOKS.remove(event.getEntity().getUUID());
    }

    static void record(UUID who, float yaw, float pitch) {
        ArrayDeque<AimConfidence.Look> q = LOOKS.computeIfAbsent(who, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new AimConfidence.Look(yaw, pitch));
            while (q.size() > KEPT) {
                q.removeFirst();
            }
        }
    }

    /** His last looks, oldest first, ending with where he looks NOW (so a turn this very tick is seen). */
    public static List<AimConfidence.Look> recent(ServerPlayer p) {
        ArrayDeque<AimConfidence.Look> q = LOOKS.get(p.getUUID());
        List<AimConfidence.Look> out = new java.util.ArrayList<>();
        if (q != null) {
            synchronized (q) {
                out.addAll(q);
            }
        }
        out.add(new AimConfidence.Look(p.getYRot(), p.getXRot()));
        return out;
    }
}
