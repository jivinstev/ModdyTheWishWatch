package com.moddingfromamod.mixin;

import java.util.Collection;

import com.moddingfromamod.datapack.NoiseMeter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.commands.PlaySoundCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** a throttled wish datapack's sounds are dropped ({@link NoiseMeter}). */
@Mixin(PlaySoundCommand.class)
public abstract class PlaySoundNoiseMixin {

    @Inject(method = "playSound", at = @At("HEAD"), cancellable = true)
    private static void moddingfromamod$noise(CommandSourceStack source, Collection<ServerPlayer> targets,
                                              ResourceLocation sound, SoundSource category, Vec3 pos,
                                              float volume, float pitch, float minVolume,
                                              CallbackInfoReturnable<Integer> cir) {
        if (!NoiseMeter.allowSound(source)) {
            cir.setReturnValue(0);
        }
    }
}
