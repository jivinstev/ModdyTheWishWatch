package com.moddingfromamod.mixin;

import com.moddingfromamod.mob.SpawnScope;
import com.moddingfromamod.wish.AttributeMapBody;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>A body whose attributes change while a wish runs is held to the world's limits</b> (#174). Every change to an
 * attribute (a base set, a modifier added or removed: an {@code attribute} command, an effect's modifiers, a
 * {@code data merge} of attributes, a summon's NBT) passes through {@code AttributeMap#onAttributeModified}. While a
 * wish's spawn scope is open on this thread ({@link SpawnScope}: a wish command, a replay, a rule, a wish datapack's
 * function), the body the map belongs to ({@link AttributeMapBody}, set by {@code LivingEntityAttributesMixin}) is
 * handed to {@code wish/LiveFitWatch}, which fits it when the command ends. With no scope open (the world's own doing,
 * vanilla's, another mod's, and always on a client thread) nothing happens. Observes only: the change itself goes on
 * exactly as vanilla makes it.
 */
@Mixin(AttributeMap.class)
public abstract class AttributeTouchMixin implements AttributeMapBody {

    @Unique
    private LivingEntity moddingfromamod$body;

    @Override
    public void moddingfromamod$setBody(LivingEntity body) {
        this.moddingfromamod$body = body;
    }

    @Inject(method = "onAttributeModified", at = @At("HEAD"))
    private void moddingfromamod$touched(AttributeInstance instance, CallbackInfo ci) {
        if (moddingfromamod$body != null && SpawnScope.current() != null) {
            com.moddingfromamod.wish.LiveFitWatch.touched(moddingfromamod$body);
        }
    }
}
