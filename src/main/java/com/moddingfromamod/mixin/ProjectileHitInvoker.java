package com.moddingfromamod.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * <b>Lets {@code HitProxies} land a projectile on a rig's carrier</b>. A projectile that
 * reaches an invisible proxy has its impact cancelled and re-applied to the carrier, and the method
 * every projectile applies an impact through, {@code Projectile.hitTargetOrDeflectSelf}, is
 * {@code protected} (1.21.4 and 1.21.1 alike). Calling it rather than re-implementing it keeps
 * every projectile's own rules — an arrow's damage and piercing, a snowball's knockback, a
 * fishing hook's catch, a deflection off a shield.
 */
@Mixin(Projectile.class)
public interface ProjectileHitInvoker {

    @Invoker("hitTargetOrDeflectSelf")
    ProjectileDeflection moddingfromamod$hitTargetOrDeflectSelf(HitResult hit);
}
