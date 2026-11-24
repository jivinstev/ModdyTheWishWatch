package com.moddingfromamod.compat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * Thrown projectiles (1.21.4 half): 1.21.2 made a throwable carry its stack explicitly where
 * 1.21.1 resolved {@code getDefaultItem()} -- a plain snowball in both cases.
 */
public final class Projectiles {
    private Projectiles() {
    }

    public static Snowball snowball(Level level, LivingEntity owner) {
        return new Snowball(level, owner, new ItemStack(Items.SNOWBALL));
    }
}
