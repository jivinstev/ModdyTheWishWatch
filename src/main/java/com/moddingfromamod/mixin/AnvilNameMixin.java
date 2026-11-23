package com.moddingfromamod.mixin;

import com.moddingfromamod.chat.ChatGuard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AnvilMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * step 4: an anvil NAME passes {@link ChatGuard#placed} — a renamed name tag puts words over a
 * mob for anyone near it to read. (Moddy's wish box was once an anvil and was exempted here; since D2,
 *, it is a dialog, so every anvil this sees is a real rename.) Refused, the name is not taken
 * (vanilla's own "false"), and he is told why.
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilNameMixin {

    @Inject(method = "setItemName", at = @At("HEAD"), cancellable = true)
    private void moddingfromamod$sweepName(String name, CallbackInfoReturnable<Boolean> cir) {
        Object self = this;
        if (((ItemCombinerMenuAccessor) self).moddingfromamod$player() instanceof ServerPlayer sp
                && !ChatGuard.placed(sp, "name", name)) {
            cir.setReturnValue(false);
        }
    }
}
