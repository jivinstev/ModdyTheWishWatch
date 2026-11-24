package com.moddingfromamod.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;

/**
 * <b>A player's respawn point</b>. 1.21.5 folded {@code getRespawnPosition / Dimension / Angle /
 * isRespawnForced} into one {@code RespawnConfig} (null = none) and {@code setRespawnPosition} takes it.
 * Each method here is what the old one returned, so callers read as they did.
 */
public final class Respawn {

    private Respawn() {
    }

    public static BlockPos pos(ServerPlayer p) {
        ServerPlayer.RespawnConfig c = p.getRespawnConfig();
        return c == null ? null : c.respawnData().pos();
    }

    /** The old default: the overworld when no point is set. */
    public static ResourceKey<Level> dimension(ServerPlayer p) {
        ServerPlayer.RespawnConfig c = p.getRespawnConfig();
        return c == null ? Level.OVERWORLD : c.respawnData().dimension();
    }

    public static float angle(ServerPlayer p) {
        ServerPlayer.RespawnConfig c = p.getRespawnConfig();
        return c == null ? 0f : c.respawnData().yaw();
    }

    public static boolean forced(ServerPlayer p) {
        ServerPlayer.RespawnConfig c = p.getRespawnConfig();
        return c != null && c.forced();
    }

    /** {@code setRespawnPosition(dim, pos, angle, forced, sendMessage)}; a null pos clears it. */
    public static void set(ServerPlayer p, ResourceKey<Level> dim, BlockPos pos, float angle, boolean forced,
            boolean sendMessage) {
        p.setRespawnPosition(pos == null ? null
                : new ServerPlayer.RespawnConfig(
                        new LevelData.RespawnData(GlobalPos.of(dim, pos), angle, 0f), forced),
                sendMessage);
    }
}
