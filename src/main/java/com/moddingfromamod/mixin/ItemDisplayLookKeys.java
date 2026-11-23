package com.moddingfromamod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code ItemDisplay}'s item and item-context keys, which rebuild a client's render state as the light and the
 * transform do ({@code ItemDisplay.onSyncedDataUpdated}). Private in 1.21.8. Read by {@link RigDisplayEaseMixin}.
 */
@Mixin(Display.ItemDisplay.class)
public interface ItemDisplayLookKeys {

    @Accessor("DATA_ITEM_STACK_ID")
    static EntityDataAccessor<?> moddingfromamod$itemKey() {
        throw new AssertionError("mixin accessor");
    }

    @Accessor("DATA_ITEM_DISPLAY_ID")
    static EntityDataAccessor<?> moddingfromamod$contextKey() {
        throw new AssertionError("mixin accessor");
    }
}
