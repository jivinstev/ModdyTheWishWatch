package com.moddingfromamod.testing.mixin;

import com.moddingfromamod.seam.SoundLedger;

import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * every sound packet on its way to a player, for {@link SoundLedger} — a no-op unless a
 * harness switched the ledger on. The packet, not {@code Level.playSound}, because
 * {@code /playsound} sends straight to the player and never touches the level.
 *
 * <p>Test-only: only a harness turns the ledger on, so this lives in the GameTest config, which every dev
 * run registers and no jar carries.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class SoundSentMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"))
    private void moddingfromamod$ledger(Packet<?> packet, io.netty.channel.ChannelFutureListener listener, CallbackInfo ci) {
        if (SoundLedger.on() && (Object) this instanceof ServerGamePacketListenerImpl game) {
            SoundLedger.sent(game.player, packet);
        }
    }
}
