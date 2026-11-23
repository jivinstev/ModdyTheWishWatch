package com.moddingfromamod.mixin;

import com.moddingfromamod.datapack.PackCostMeter;
import net.minecraft.commands.execution.ExecutionContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * a wish datapack's function that will not end is stopped INSIDE its tick. Every command a function runs
 * costs one of the context's quota ({@code incrementCost}); when {@link PackCostMeter#overBudget} says the
 * running wish pack's outermost call has run past its hard stop, the quota is spent, and vanilla's own loop
 * ({@code runCommandQueue}: "Command execution stopped due to limit") ends the chain where it stands. A sustained
 * window could never see this — a recursion that never returns is ONE tick, and the watchdog ends the server at
 * 60 s. Only a wish pack's function is ever stopped: with no wish-pack function running, the meter says no.
 */
@Mixin(ExecutionContext.class)
public abstract class ExecutionContextHardStopMixin {

    @Shadow
    private int commandQuota;

    @Inject(method = "incrementCost", at = @At("TAIL"))
    private void moddingfromamod$hardStop(CallbackInfo ci) {
        if (commandQuota > 0 && PackCostMeter.overBudget()) {
            commandQuota = 0;
        }
    }
}
