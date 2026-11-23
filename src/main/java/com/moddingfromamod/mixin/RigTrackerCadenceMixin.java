package com.moddingfromamod.mixin;

import com.moddingfromamod.vanilla.RigCadence;
import com.moddingfromamod.vanilla.RigEaseState;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * a rig atom's MOVE is sent on the rig's beat ({@link RigCadence#send}), not every tick. Off the beat,
 * {@code sendChanges} is skipped for that display: it has no passengers and is no item frame, so what is skipped is
 * the move (its delta codec keeps the last SENT position, so the next move carries the whole way). If its data changed
 * (a pose, a glow) that data alone goes out now, so no atom ever takes a move off the beat: one position schedule per
 * body. Every other entity is untouched. Checked against 1.21.8's {@code ServerEntity}.
 */
@Mixin(ServerEntity.class)
public abstract class RigTrackerCadenceMixin {

    @Shadow
    @Final
    private Entity entity;

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    private void sendDirtyEntityData() {
        throw new AssertionError("shadowed");
    }

    @Inject(method = "sendChanges", at = @At("HEAD"), cancellable = true)
    private void moddingfromamod$rigBeat(CallbackInfo ci) {
        if (!(entity instanceof Display) || !RigCadence.isRigAtom(entity.getTags())) {
            return;
        }
        boolean dirty = entity.getEntityData().isDirty();
        RigCadence.Send send = RigCadence.send(level.getGameTime(), dirty, RigCadence.MOVE_INTERVAL);
        if (dirty && send != RigCadence.Send.HOLD && entity instanceof RigEaseState ease) {
            moddingfromamod$keepEase(ease, level.getGameTime());
        }
        switch (send) {
            case ALL -> {
                // the move goes out as vanilla sends it (RigMoveRotationMixin adds its rotation)
            }
            case DATA_ONLY -> {
                sendDirtyEntityData();
                ci.cancel();
            }
            case HOLD -> ci.cancel();
        }
    }

    /**
     * Its data goes out now. A new ease starting with it is remembered (when it ends); a look change (light, tint, item,
     * shadow) going out with no new ease while one is running re-arms that ease for the ticks it has left, so the client
     * restarts it from where it draws the part, aimed at the same pose, due at the same tick: no jump (RigCadence.REARM).
     */
    @Unique
    private static void moddingfromamod$keepEase(RigEaseState ease, long now) {
        if (ease.moddingfromamod$easeStarted()) {
            ease.moddingfromamod$setEaseEnd(now + ease.moddingfromamod$easeDuration());
        } else if (ease.moddingfromamod$lookChanged() && RigCadence.REARM) {
            int left = RigCadence.remaining(ease.moddingfromamod$easeEnd(), now);
            if (left > 0) {
                ease.moddingfromamod$rearm(left);
                ease.moddingfromamod$setEaseEnd(now + left);
            }
        }
        ease.moddingfromamod$clearEaseFlags();
    }
}
