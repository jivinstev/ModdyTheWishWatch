package com.moddingfromamod.event;

import com.moddingfromamod.compat.Teleports;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * <b>Tell him it is coming, and where to look</b> (E1).
 *
 * <p>An event is announced with a big title in the middle of the screen and a subtitle naming the
 * direction and distance, and a sound played AT the player so it is loud wherever he is. The
 * direction is computed per player, because only a player can be told which way to run.
 */
public final class EventAnnounce {

    private EventAnnounce() {
    }

    /** The subtitle's second half: "90 blocks north-east", or "right here". */
    public static String where(ServerPlayer player, BlockPos at) {
        double dx = at.getX() + 0.5 - player.getX();
        double dz = at.getZ() + 0.5 - player.getZ();
        int dist = (int) Math.round(Math.hypot(dx, dz));
        if (dist < 4) {
            return "right here";
        }
        return dist + " blocks " + EventAim.compass(dx, dz);
    }

    /** The title, the subtitle ("{@code line} — 90 blocks north-east") and a sound. */
    public static void tell(ServerPlayer player, String title, String line, BlockPos at,
                            SoundEvent sound) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        String[] fit = TitleFit.fit(title);
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(fit[0])));
        String rest = fit[1].isEmpty() ? line : line == null || line.isBlank() ? fit[1] : fit[1] + " " + line;
        String sub = rest == null || rest.isBlank() ? where(player, at) : rest + " — " + where(player, at);
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(sub)));
        if (sound != null) {
            com.moddingfromamod.mv.NotifySound.play(player,sound, SoundSource.MASTER, 1.0F, 1.0F);
        }
    }

    /**
     * Turn his camera toward {@code at} without moving him. A server-side {@code setYRot} never
     * reaches a client, which owns its own camera; only a teleport carries a rotation.
     */
    public static void look(ServerPlayer player, BlockPos at) {
        double dx = at.getX() + 0.5 - player.getX();
        double dy = at.getY() + 0.5 - player.getEyeY();
        double dz = at.getZ() + 0.5 - player.getZ();
        Teleports.player(player, player.level(), player.getX(), player.getY(), player.getZ(),
                EventAim.yaw(dx, dz), EventAim.pitch(dx, dy, dz));
    }
}
