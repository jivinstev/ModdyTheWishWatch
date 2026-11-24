package com.moddingfromamod.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.FlyingAnimal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>A rig carrier keeps its walk animation on the SERVER</b>. Until 1.21.4 every living entity
 * ran {@code calculateEntityAnimation} on both sides; 1.21.5 moved it behind {@code isClientSide()}, so
 * a server-side {@code walkAnimation} is always zero. The atom rig is animated FROM THE SERVER and picks
 * its walk clip and phase from the carrier's {@code walkAnimation} (RigAnimator, VanillaRigAnimator), so
 * on 1.21.8 every sculpted creature slid about in its idle pose — caught by the vanillawalk film, whose
 * cow moved two blocks with a walk speed of 0.000. This restores exactly what vanilla still does on the
 * client, for rig carriers only (tagged), at the same point in the tick.
 */
@Mixin(LivingEntity.class)
public abstract class RigWalkAnimationMixin {

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void moddingfromamod$serverWalkAnimation(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.level().isClientSide()
                && self.entityTags().contains(com.moddingfromamod.vanilla.DisplayRig.RIG_TAG)) {
            self.calculateEntityAnimation(self instanceof FlyingAnimal);
        }
    }
}
