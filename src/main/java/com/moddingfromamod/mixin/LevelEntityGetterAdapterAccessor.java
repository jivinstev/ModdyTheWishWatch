package com.moddingfromamod.mixin;

import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The lookup an {@code @e} with no position walks ({@code SelectorIndexMixin}). Private in 1.21.8. */
@Mixin(LevelEntityGetterAdapter.class)
public interface LevelEntityGetterAdapterAccessor {

    @Accessor("visibleEntities")
    EntityLookup<?> moddingfromamod$visibleEntities();
}
