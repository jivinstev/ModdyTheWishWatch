package com.moddingfromamod.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * <b>Hurt an entity, server-side, and say whether it took the hit</b> (1.21.4 half).
 * 1.21.2 made {@code hurt} a final void that dispatches by side; the boolean moved to
 * {@code hurtServer}. Callers are GameTests on the server.
 */
public final class Damage {
    private Damage() {
    }

    public static boolean hurt(LivingEntity entity, DamageSource source, float amount) {
        return entity.hurtServer((ServerLevel) entity.level(), source, amount);
    }
}
