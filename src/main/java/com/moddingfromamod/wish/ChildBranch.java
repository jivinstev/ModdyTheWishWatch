package com.moddingfromamod.wish;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;

/**
 * <b>A {@code /moddy} branch a plug-in adds</b> (epic), the way {@code BuilderMenus.childBranch()}
 * hands one to {@link WishCommands}: a player's verb is always a branch of {@code /moddy}, never a top-level
 * command. An islands plug-in, for example, adds {@code /moddy island new [confirm]}. With none registered
 * {@code /moddy} has only the core's branches.
 *
 * <p><b>A documented extension point</b>: {@code docs/EXTENDING.md} → "ChildBranch".
 */
public interface ChildBranch {

    /** A fresh branch, built each time the commands are registered. */
    LiteralArgumentBuilder<CommandSourceStack> branch();
}
