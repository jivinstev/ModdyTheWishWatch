package com.moddingfromamod.mixin;

import com.moddingfromamod.mob.SpawnScope;

import net.minecraft.server.ServerFunctionManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * a DATAPACK's summons are wished creatures too. A wish datapack runs on its own, every tick
 * or on a schedule, long after the wish that wrote it — so no wish scope is open when its
 * {@code summon} lands, and a "zombies forever" loop would reach the world guard as nobody's doing.
 * Every tick function and every scheduled one runs through these two methods, so the scope opened
 * here ({@link SpawnScope#NO_WISH}: no wave, the world ceiling only) covers them all.
 * {@code ops.WishMobLimit} reads it.
 *
 * <p>It is also where a datapack's {@code /give} is stamped a {@link
 * com.moddingfromamod.item.delivery.DeliveryPlan.Kind#BEHAVIOUR BEHAVIOUR}: a function nothing
 * else scoped is an item's use, a placed machine's output or refund, a creature's ability or a
 * clock — the thing's own doing, which goes to his hotbar, his inventory and his feet, never the
 * Wish Bag, and is never announced. A function run INSIDE a hand-over (a made item's
 * {@code wishdatapack <id> give}, run by the wish) finds that scope open and keeps it.
 */
@Mixin(ServerFunctionManager.class)
public abstract class FunctionSpawnScopeMixin {

    @Unique
    private int moddingfromamod$depth;
    @Unique
    private boolean moddingfromamod$opened;

    @Inject(method = {"tick", "execute"}, at = @At("HEAD"))
    private void moddingfromamod$open(CallbackInfo ci) {
        if (moddingfromamod$depth++ == 0) {
            moddingfromamod$opened = SpawnScope.open(SpawnScope.NO_WISH,
                    com.moddingfromamod.item.delivery.DeliveryPlan.Kind.BEHAVIOUR);
        }
    }

    @Inject(method = {"tick", "execute"}, at = @At("RETURN"))
    private void moddingfromamod$close(CallbackInfo ci) {
        if (--moddingfromamod$depth == 0) {
            SpawnScope.close(moddingfromamod$opened);
            moddingfromamod$opened = false;
        }
    }
}
