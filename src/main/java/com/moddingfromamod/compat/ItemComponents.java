package com.moddingfromamod.compat;

import java.util.LinkedHashSet;
import java.util.SequencedSet;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * <b>Item components whose tooltip line used to be a flag on the component</b>. 1.21.5 moved
 * "show in tooltip" out of {@code DyedItemColor}, {@code ItemAttributeModifiers} and friends into one
 * {@code TooltipDisplay} component, and {@code HIDE_TOOLTIP} became its {@code hideTooltip}.
 */
public final class ItemComponents {

    private ItemComponents() {
    }

    /** What {@code set(DYED_COLOR, new DyedItemColor(rgb, false))} did: the colour, with no "Dyed" line. */
    public static void dyeHidden(ItemStack s, int rgb) {
        s.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb));
        hideLine(s, DataComponents.DYED_COLOR);
    }

    /** Hides one component's tooltip line, keeping whatever else is already hidden. */
    public static void hideLine(ItemStack s, DataComponentType<?> type) {
        TooltipDisplay now = s.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT);
        SequencedSet<DataComponentType<?>> hidden = new LinkedHashSet<>(now.hiddenComponents());
        hidden.add(type);
        s.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(now.hideTooltip(), hidden));
    }

    /** What {@code set(HIDE_TOOLTIP, Unit.INSTANCE)} did: no tooltip at all. */
    public static void hideTooltip(ItemStack s) {
        TooltipDisplay now = s.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT);
        s.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, now.hiddenComponents()));
    }
}
