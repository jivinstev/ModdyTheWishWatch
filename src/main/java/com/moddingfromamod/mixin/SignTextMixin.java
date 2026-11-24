package com.moddingfromamod.mixin;

import java.util.List;

import com.moddingfromamod.chat.ChatGuard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * step 4: a sign's words pass {@link ChatGuard#placed} on a world whose {@code people.placedText}
 * is {@code sweep}. NeoForge has no sign-edit event to cancel (ChatGuard's old "not covered" note), so
 * this is the one place a player's sign text arrives on the server. Refused, the sign keeps what it had,
 * and the client is sent the sign as it really is so his screen does not show words nobody else sees.
 */
@Mixin(SignBlockEntity.class)
public abstract class SignTextMixin {

    @Inject(method = "updateSignText", at = @At("HEAD"), cancellable = true)
    private void moddingfromamod$sweepSign(Player player, net.minecraft.world.level.block.entity.SignTextSlot slot,
                                          List<FilteredText> lines, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer sp) || lines == null) {
            return;
        }
        StringBuilder words = new StringBuilder();
        for (FilteredText l : lines) {
            words.append(l.raw()).append(' ');
        }
        if (!ChatGuard.placed(sp, "sign", words.toString())) {
            ci.cancel();
            SignBlockEntity self = (SignBlockEntity) (Object) this;
            if (self.getLevel() != null) {
                self.getLevel().sendBlockUpdated(self.getBlockPos(), self.getBlockState(), self.getBlockState(), 3);
            }
        }
    }
}
