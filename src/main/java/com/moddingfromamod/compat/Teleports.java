package com.moddingfromamod.compat;

import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>Put a player somewhere, possibly in another dimension</b> (1.21.4 half).
 *
 * <p>1.21.2 removed {@code ServerPlayer.teleportTo(level, x, y, z, yaw, pitch)}; the survivor takes a
 * relative-movement set and a {@code setCamera} flag. 1.21.1's six-argument body was: point the camera
 * back at the player, dismount, then teleport or change dimension. The eight-argument form does the
 * camera when asked and not the dismount, so the dismount is done here: a child hopped by a wish while
 * riding must arrive on foot, exactly as before.
 */
public final class Teleports {
    private Teleports() {
    }

    public static void player(ServerPlayer player, ServerLevel level, double x, double y, double z,
                              float yaw, float pitch) {
        player.stopRiding();
        player.teleportTo(level, x, y, z, Set.of(), yaw, pitch, true);
    }
}
