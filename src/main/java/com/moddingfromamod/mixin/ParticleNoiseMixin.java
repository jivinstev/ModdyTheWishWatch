package com.moddingfromamod.mixin;

import java.util.Collection;

import com.moddingfromamod.datapack.NoiseMeter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.commands.ParticleCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** a throttled wish datapack's particle bursts are dropped ({@link NoiseMeter}). */
@Mixin(ParticleCommand.class)
public abstract class ParticleNoiseMixin {

    @Inject(method = "sendParticles", at = @At("HEAD"), cancellable = true)
    private static void moddingfromamod$noise(CommandSourceStack source, ParticleOptions particle, Vec3 pos,
                                              Vec3 delta, float speed, int count, boolean force,
                                              Collection<ServerPlayer> viewers,
                                              CallbackInfoReturnable<Integer> cir) {
        if (!NoiseMeter.allowParticles(source, count)) {
            cir.setReturnValue(0);
        }
    }
}
