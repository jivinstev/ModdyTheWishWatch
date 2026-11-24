package com.moddingfromamod.mixin;

import java.util.Set;

import com.moddingfromamod.vanilla.RigBundle;
import com.moddingfromamod.vanilla.RigCadence;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * while the tracker's pass is held ({@link RigBundle#holding}), a rig atom's broadcast is held for the players
 * that see it now, and sent with the rest of its rig when the pass ends ({@code RigBundleTickMixin}). Every other
 * entity, and any broadcast outside the pass, goes out as vanilla sends it. Checked against 1.21.10's
 * {@code ChunkMap.TrackedEntity}: {@code sendToTrackingPlayers} (1.21.8's {@code broadcast}) is what
 * {@code ServerEntity} and {@code sendToTrackingPlayersAndSelf} (was {@code broadcastAndSend}) send through.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class RigBundleBroadcastMixin {

    @Shadow
    @Final
    Entity entity;

    @Shadow
    @Final
    Set<ServerPlayerConnection> seenBy;

    @Inject(method = "sendToTrackingPlayers(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void moddingfromamod$holdRigPacket(Packet<?> packet, CallbackInfo ci) {
        if (RigBundle.holding() && entity instanceof Display && RigCadence.isRigAtom(entity.getTags())) {
            RigBundle.hold(seenBy, packet);
            ci.cancel();
        }
    }
}
