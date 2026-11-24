package com.moddingfromamod.mixin;

import com.moddingfromamod.vanilla.RigMoveRotation;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * a rig atom's move always carries its rotation ({@link RigMoveRotation}). Before {@code sendChanges} decides
 * between a position-only and a position-and-rotation packet, a rig display that moved has its last-sent yaw marked
 * stale, so vanilla's own branch sends {@code PosRot}. A display that did not move is untouched (a still creature costs
 * nothing extra). Every other entity is untouched. Checked against 1.21.8's {@code ServerEntity}.
 */
@Mixin(ServerEntity.class)
public abstract class RigMoveRotationMixin {

    @Shadow
    @Final
    private Entity entity;

    @Shadow
    @Final
    private VecDeltaCodec positionCodec;

    @Shadow
    private byte lastSentYRot;

    @Inject(method = "sendChanges", at = @At("HEAD"))
    private void moddingfromamod$rotationWithMove(CallbackInfo ci) {
        if (RigMoveRotation.ON && entity instanceof Display
                && RigMoveRotation.moved(positionCodec.delta(entity.trackingPosition()).lengthSqr())
                && RigMoveRotation.isRigAtom(entity.entityTags())) {
            lastSentYRot = RigMoveRotation.staleYaw(Mth.packDegrees(entity.getYRot()));
        }
    }
}
