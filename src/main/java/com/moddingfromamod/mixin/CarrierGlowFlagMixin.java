package com.moddingfromamod.mixin;

import com.moddingfromamod.vanilla.DisplayRig;

import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>An atom-rig carrier — or one of its hit proxies — never raises its SHARED glowing
 * flag</b>.
 *
 * <p>The carrier is invisible, but {@code LivingEntityRenderer.getRenderType} draws an invisible
 * mob that "appears glowing" as {@code RenderType.outline(texture)} — so a glowing carrier puts its
 * own silhouette (a horse at one end of a sea serpent) in the outline, and the rig's blocks get
 * none. The client decides that from shared flag 6 alone ({@code Entity.isCurrentlyGlowing} is
 * {@code getSharedFlag(6)} on the client), and the server writes flag 6 in exactly two places, read
 * from the 1.21.4 source: {@code Entity.setGlowingTag} and {@code LivingEntity.updateGlowingStatus}
 * (from {@code tickEffects}, whenever the effects changed). Both go through {@code setSharedFlag},
 * so this is the one place that sees every write — including the ones made between ticks by a
 * command, which a per-tick clear would let reach the client for a tick.
 *
 * <p>ONLY the synced flag is suppressed. {@code hasGlowingTag()}, the GLOWING effect, the saved
 * {@code Glowing} boolean and the server's {@code isCurrentlyGlowing()} are untouched, so they stay
 * the source of truth that {@code MobStates.glowOf}, an undo, a save and
 * {@code DisplayRig.mirrorGlow} all read.
 */
@Mixin(Entity.class)
public abstract class CarrierGlowFlagMixin {

    @Shadow
    protected abstract void setSharedFlag(int flag, boolean value);

    @Inject(method = "setSharedFlag", at = @At("HEAD"), cancellable = true)
    private void moddingfromamod$carrierNeverOutlines(int flag, boolean value, CallbackInfo ci) {
        // Flag first: setSharedFlag runs for fire every tick on every entity, and this must cost
        // one int compare there.
        // A hit proxy is the same invisible mob in a different place, and a glowing one
        // would outline a cow over the creature's head.
        if (flag == 6 && value && (((Entity) (Object) this).getTags().contains(DisplayRig.RIG_TAG)
                || ((Entity) (Object) this).getTags().contains(
                        com.moddingfromamod.vanilla.HitProxies.PROXY_TAG))) {
            ci.cancel();
            setSharedFlag(6, false);   // re-enters with value=false, which passes straight through
        }
    }
}
