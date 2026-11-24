package com.moddingfromamod.mv;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * A sound only this player hears, at their position: what {@code ServerPlayer.playNotifySound} did before Minecraft
 * 1.21.11 removed it (the same packet, sent to this player's connection alone).
 */
public final class NotifySound {
    private NotifySound() {
    }

    public static void play(ServerPlayer player, SoundEvent sound, SoundSource source, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), source,
                player.getX(), player.getY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
    }
}
