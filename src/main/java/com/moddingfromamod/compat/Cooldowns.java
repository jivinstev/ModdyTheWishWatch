package com.moddingfromamod.compat;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Item cooldowns (1.21.4 half): keyed by the STACK (its cooldown group) since 1.21.2. */
public final class Cooldowns {
    private Cooldowns() {
    }

    public static void add(Player player, ItemStack stack, int ticks) {
        player.getCooldowns().addCooldown(stack, ticks);
    }

    public static boolean active(Player player, ItemStack stack) {
        return player.getCooldowns().isOnCooldown(stack);
    }
}
