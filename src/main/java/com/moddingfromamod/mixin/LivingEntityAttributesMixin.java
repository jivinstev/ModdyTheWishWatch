package com.moddingfromamod.mixin;

import net.minecraft.world.entity.EntityType;
import com.moddingfromamod.wish.AttributeMapBody;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>A body's attribute map knows its body</b> (#174), so a change a wish makes to it ({@code AttributeTouchMixin})
 * reaches {@code wish/LiveFitWatch} as that body at once, and nothing has to scan every entity to find it. Set once,
 * at the end of the constructor that made the map; it changes nothing else.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityAttributesMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void moddingfromamod$ownMap(EntityType<? extends LivingEntity> type, Level level, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.getAttributes() instanceof AttributeMapBody body) {
            body.moddingfromamod$setBody(self);
        }
    }
}
