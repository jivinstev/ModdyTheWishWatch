package com.moddingfromamod.mixin;

import java.util.List;

import com.moddingfromamod.vanilla.RigBundle;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * the entity tracker's pass ({@code ChunkMap.tick}) is where a rig's packets are held ({@link RigBundle}); when
 * it ends, each viewer gets what its rigs sent as one bundle, which a client applies on one tick. Checked against
 * 1.21.8's {@code ChunkMap}.
 */
@Mixin(ChunkMap.class)
public abstract class RigBundleTickMixin {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void moddingfromamod$holdRigPackets(CallbackInfo ci) {
        RigBundle.begin(RigBundleTickMixin::moddingfromamod$send);
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void moddingfromamod$sendRigPackets(CallbackInfo ci) {
        RigBundle.end(RigBundleTickMixin::moddingfromamod$send);
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static void moddingfromamod$send(Object viewer, List<Object> packets) {
        ServerPlayerConnection c = (ServerPlayerConnection) viewer;
        if (packets.size() == 1) {
            c.send((Packet<?>) packets.get(0));
            return;
        }
        c.send(new ClientboundBundlePacket((Iterable<Packet<? super ClientGamePacketListener>>) (List<?>) packets));
    }
}
