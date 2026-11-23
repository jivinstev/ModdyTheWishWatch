package com.moddingfromamod.mixin;

import java.util.Set;

import com.moddingfromamod.selector.ObservedTagSet;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>Every entity's tag set reports its changes</b>: {@code Entity.tags} is replaced by an
 * {@link ObservedTagSet} as the entity is constructed, so the {@code @e[tag=…]} index hears about
 * every change however it is made — {@code addTag}, a load, a direct {@code getTags().add}. The set
 * delegates to a {@code HashSet}, so iteration order (and a saved {@code Tags} list) is unchanged.
 */
@Mixin(Entity.class)
public abstract class EntityTagSetMixin {

    @Shadow
    @Final
    @Mutable
    private Set<String> tags;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void moddingfromamod$observeTags(CallbackInfo ci) {
        if (!(tags instanceof ObservedTagSet)) {
            ObservedTagSet s = new ObservedTagSet();
            s.addAll(tags);   // nothing has been added by here in vanilla; copied in case
            tags = s;
        }
    }
}
