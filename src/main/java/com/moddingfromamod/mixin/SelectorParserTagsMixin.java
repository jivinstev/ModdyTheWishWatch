package com.moddingfromamod.mixin;

import com.mojang.brigadier.StringReader;
import com.moddingfromamod.selector.SelectorTags;
import com.moddingfromamod.selector.TaggedSelector;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>A parsed selector remembers its own text and the positive tags it requires</b> — the
 * text the parser just consumed, read by {@link SelectorTags}. See that class for why the text and
 * not vanilla's option lambda.
 */
@Mixin(EntitySelectorParser.class)
public abstract class SelectorParserTagsMixin {

    @Shadow
    @Final
    private StringReader reader;

    @Unique
    private int moddingfromamod$start = -1;

    @Inject(method = "parse", at = @At("HEAD"))
    private void moddingfromamod$mark(CallbackInfoReturnable<EntitySelector> cir) {
        moddingfromamod$start = reader.getCursor();
    }

    @Inject(method = "parse", at = @At("RETURN"))
    private void moddingfromamod$tag(CallbackInfoReturnable<EntitySelector> cir) {
        EntitySelector sel = cir.getReturnValue();
        int start = moddingfromamod$start;
        moddingfromamod$start = -1;
        if (!(sel instanceof TaggedSelector t) || start < 0 || start > reader.getCursor()) {
            return;
        }
        String text = reader.getString().substring(start, reader.getCursor());
        t.moddingfromamod$setTags(SelectorTags.positive(text), text);
    }
}
