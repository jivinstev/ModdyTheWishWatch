package com.moddingfromamod.mixin;

import net.minecraft.world.entity.ambient.Bat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>A bat he RIDES does not flutter off on its own</b> (2026-10-11). A flying creature's carrier is
 * a bat, and a bat's own AI picks a random spot every second or so and nudges its speed towards it
 * — so a ridden one he had let go of sank 0.63 blocks in 2 s, and a climb wandered. While a player
 * rides it ({@code RideSeat.riderOf}) its AI step is skipped and {@code PetBehaviours.steer} alone
 * moves it. Only the AI step: its travel, which applies that speed, still runs.
 *
 * <p>The handler takes no target arguments, so the same mixin matches 1.21.4's
 * {@code customServerAiStep(ServerLevel)} and 1.21.1's {@code customServerAiStep()}.
 *
 * <p><b>And a carrier bat never ROOSTS.</b> From the 1.21.8 bytecode: {@code Bat}'s
 * constructor calls {@code setResting(true)} on the server, and {@code Bat.tick} pins a resting bat
 * with {@code setPosRaw(x, floor(y) + 1 - bbHeight, z)} — top against the block above, as a bat
 * hangs from a ceiling, and {@code setPosRaw} skips collision. Only {@code customServerAiStep}
 * clears it, and the inject above skips exactly that step for a driven, held or ridden carrier
 * (and a mob with no AI never runs it). A one-block bat pinned that way barely moves; a carrier
 * scaled to a plane (1.9 tall) or a balloon (6.7) is pulled 2 and 6 blocks INTO THE GROUND, its eye
 * reads sky light 0, {@code DisplayRig.followLight} writes that to every display, and the creature
 * is drawn black and sunk (measured in the starter studio: all six rideable fliers at y = ground −
 * 2…6, eye light 0/0, beside a walker at sky 15). A rig's carrier only carries; it has no use for a
 * roost, so it is never let rest, whatever set it.
 */
@Mixin(Bat.class)
public abstract class BatRideMixin {

    @Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true)
    private void moddingfromamod$stillWhileRidden(CallbackInfo ci) {
        // and while it waits for its rider or comes back inside its leash, both of which
        // the bat's own random flight would undo a tick later.
        Bat self = (Bat) (Object) this;
        if (com.moddingfromamod.mob.RideSeat.riderOf(self) != null
                || com.moddingfromamod.mob.PetBehaviours.holding(self)
                || com.moddingfromamod.vanilla.CreatureLife.drives(self)) {
            ci.cancel();
        }
    }

    /** Born resting, or loaded resting: a carrier is woken before the pin in {@code tick} runs. */
    @Inject(method = "tick", at = @At("HEAD"))
    private void moddingfromamod$carrierNeverRoosts(CallbackInfo ci) {
        Bat self = (Bat) (Object) this;
        if (!self.level().isClientSide() && self.isResting() && carrier(self)) {
            self.setResting(false);
        }
    }

    /** ...and is never put back to rest by its own AI. */
    @Inject(method = "setResting", at = @At("HEAD"), cancellable = true)
    private void moddingfromamod$carrierStaysAwake(boolean resting, CallbackInfo ci) {
        if (resting && carrier((Bat) (Object) this)) {
            ci.cancel();
        }
    }

    private static boolean carrier(Bat bat) {
        return bat.entityTags().contains(com.moddingfromamod.vanilla.DisplayRig.RIG_TAG);
    }
}
