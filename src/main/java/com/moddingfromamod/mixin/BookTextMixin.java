package com.moddingfromamod.mixin;

import java.util.List;

import com.moddingfromamod.chat.ChatGuard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * step 4: a book's pages (and a signed book's title) pass {@link ChatGuard#placed}. Both private
 * methods vanilla calls once the client's edit has been filtered; refused, the book is left as it was.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class BookTextMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "updateBookContents", at = @At("HEAD"), cancellable = true)
    private void moddingfromamod$sweepBook(List<FilteredText> pages, int slot, CallbackInfo ci) {
        if (!ChatGuard.placed(player, "book", join(null, pages))) {
            ci.cancel();
        }
    }

    @Inject(method = "signBook", at = @At("HEAD"), cancellable = true)
    private void moddingfromamod$sweepSignedBook(FilteredText title, List<FilteredText> pages, int slot,
                                                 CallbackInfo ci) {
        if (!ChatGuard.placed(player, "book", join(title, pages))) {
            ci.cancel();
        }
    }

    private static String join(FilteredText title, List<FilteredText> pages) {
        StringBuilder b = new StringBuilder(title == null ? "" : title.raw() + " ");
        if (pages != null) {
            for (FilteredText p : pages) {
                b.append(p.raw()).append(' ');
            }
        }
        return b.toString();
    }
}
