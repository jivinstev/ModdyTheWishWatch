package com.moddingfromamod.mixin;

import com.moddingfromamod.vanilla.HitProxies;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>A projectile may hit a rig's hit proxy exactly when it may hit the carrier</b>.
 *
 * <p>A proxy is an invisible mob standing over part of a creature, and everything that lands on
 * it is forwarded to the carrier ({@code HitProxies}). Without this, the rules a projectile applies
 * to its TARGET would be applied to the cow instead: {@code canHitEntity} refuses the projectile's
 * own shooter, which is how a creature's fire breath — born inside its own head, where a proxy
 * stands — does not strike the creature that breathed it. Asked about the carrier, the same method
 * gives the same answer for the proxy, including an arrow's piercing list.
 *
 * <p>And a ball a wished creature threw may hit only what that creature may hurt
 * ({@code RigAbilities.mayThrowAt}).
 *
 * <p>Server only: a proxy's carrier is looked up in the server level, and a client never decides
 * a hit.
 */
@Mixin(Projectile.class)
public abstract class ProxyProjectileMixin {

    @Shadow
    protected abstract boolean canHitEntity(Entity target);

    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void moddingfromamod$asIfTheCarrier(Entity target,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (target.level().isClientSide()) {
            return;
        }
        // A ball a wished creature THREW touches only what that creature may hurt (chunk 2).
        // Here and not in ProjectileImpactEvent: cancelling that event does not stop a THROWN
        // projectile's hit in NeoForge 21.4 (ThrowableProjectile.tick calls hitTargetOrDeflectSelf
        // whatever it returned — only an arrow honours it; read from the bytecode), so a friendly
        // creature's ball still landed on the child. Refused here, it simply flies on through.
        Projectile self = (Projectile) (Object) this;
        if (self.getTags().contains(com.moddingfromamod.vanilla.RigAbilities.THROW_TAG)
                && !com.moddingfromamod.vanilla.RigAbilities.mayThrowAt(self, target)) {
            cir.setReturnValue(false);
            return;
        }
        if (!HitProxies.isProxy(target)) {
            return;
        }
        Entity carrier = HitProxies.carrierOf(target);
        cir.setReturnValue(carrier != null && canHitEntity(carrier));
    }
}
