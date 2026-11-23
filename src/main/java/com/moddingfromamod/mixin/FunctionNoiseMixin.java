package com.moddingfromamod.mixin;

import com.moddingfromamod.datapack.NoiseMeter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.server.ServerFunctionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * which datapack function is running, for {@link NoiseMeter} — every tick-tag and scheduled
 * function passes {@code execute}, and a function it calls runs inside it, so the outermost one is
 * whose noise it is.
 */
@Mixin(ServerFunctionManager.class)
public abstract class FunctionNoiseMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void moddingfromamod$noiseTick(CallbackInfo ci) {
        NoiseMeter.tickStart();
    }

    @Inject(method = "execute", at = @At("HEAD"))
    private void moddingfromamod$noiseEnter(CommandFunction<CommandSourceStack> function, CommandSourceStack source,
                                           CallbackInfo ci) {
        NoiseMeter.enter(function.id().getNamespace());
    }

    @Inject(method = "execute", at = @At("RETURN"))
    private void moddingfromamod$noiseExit(CommandFunction<CommandSourceStack> function, CommandSourceStack source,
                                          CallbackInfo ci) {
        NoiseMeter.exit();
    }
}
