package com.moddingfromamod.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ItemCombinerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The anvil's player, for {@link AnvilNameMixin} (the field lives on the superclass). */
@Mixin(ItemCombinerMenu.class)
public interface ItemCombinerMenuAccessor {

    @Accessor("player")
    Player moddingfromamod$player();
}
