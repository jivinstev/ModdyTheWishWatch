package com.moddingfromamod.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * <b>Lets {@code DisplayRig.wear} set an item display's item by its one field</b>, instead
 * of a whole-entity {@code saveWithoutId} → {@code load} round trip — the same move
 * {@link DisplayLightInvoker} made for the light.
 *
 * <p>The round trip cost a full NBT encode and decode of the display (12% of a 40-creature load,
 * and every hurt flash repaints every display of the creature), and re-set every synced field on
 * the way. {@code private} on {@code Display.ItemDisplay} in 1.21.8 (checked with {@code javap}).
 */
@Mixin(Display.ItemDisplay.class)
public interface ItemDisplayItemInvoker {

    @Invoker("setItemStack")
    void moddingfromamod$setItemStack(ItemStack stack);

    @Invoker("getItemStack")
    ItemStack moddingfromamod$getItemStack();
}
