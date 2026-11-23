package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;

import java.util.Set;

/**
 * <b>Eaten or drunk faster (part 1).</b> When he starts to eat or drink a thing
 * carrying {@code eat_speed}, the use is shortened on the server to its share of the real one: a
 * loaf that takes 1.6 s takes 0.4 s at 4. The food, its hunger and saturation, and any
 * {@code on_consume} wish are the real item's own; only the waiting is shorter. One mechanism on
 * both targets, where the component routes differ ({@code consumable} on 1.21.4, {@code food} on
 * 1.21.1, and no component at all for a 1.21.1 potion).
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class EatSpeed {

    /**
     * E1's label, and deliberately NOT {@code eat_speed}: that is the cake-fork row (syn-4-040), and
     * a cake is a block he eats FROM — part 2. Claiming it here would unlock a wish this
     * does not grant.
     */
    public static final Set<String> PARTS = Set.of("eat_speed_held");

    private EatSpeed() {
    }

    @SubscribeEvent
    public static void onStart(LivingEntityUseItemEvent.Start event) {
        int n = speed(event.getItem());
        if (n > 1) {
            event.setDuration(Math.max(1, event.getDuration() / n));
        }
    }

    /** How many times faster, or 1. */
    public static int speed(ItemStack stack) {
        CustomData data = stack == null ? null : stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return 1;
        }
        int n = data.copyTag().getIntOr(com.moddingfromamod.datapack.ItemProperties.EAT_SPEED_DATA, 0);
        return n >= 2 && n <= 8 ? n : 1;
    }
}
