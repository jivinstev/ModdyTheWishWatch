package com.moddingfromamod.mixin;

import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * <b>Lets {@code DisplayRig} set a display's light override by its one field</b>, instead
 * of a whole-entity {@code saveWithoutId} → {@code load} round trip.
 *
 * <p>The round trip re-sets every synced field, and an {@code ItemStack} has no {@code equals}, so
 * {@code SynchedEntityData} calls the item changed every time: each light change would re-send
 * every display's item stack as well (the cost {@code DisplayRig.mirrorGlow} already measured at
 * ~54 B a display). The setter writes one packed int, and writing an unchanged value dirties
 * nothing. {@code private} on {@code Display} in 1.21.4 and 1.21.1 alike (same name in both).
 */
@Mixin(Display.class)
public interface DisplayLightInvoker {

    @Invoker("setBrightnessOverride")
    void moddingfromamod$setBrightnessOverride(Brightness brightness);

    /** The override as packed light, {@code -1} for none — what a test reads back. */
    @Invoker("getPackedBrightnessOverride")
    int moddingfromamod$packedBrightnessOverride();
}
