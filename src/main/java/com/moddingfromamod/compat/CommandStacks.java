package com.moddingfromamod.compat;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A command source. 26.3 dropped the constructor that took a text name, a display name AND an
 * entity: a source with an entity is now named after that entity (as {@code execute as} names it),
 * and only a source with no entity takes a fixed name.
 */
public final class CommandStacks {
    private CommandStacks() {
    }

    public static CommandSourceStack of(CommandSource source, Vec3 pos, Vec2 rot, @Nullable ServerLevel level,
                                        PermissionSet permissions, Component name, @Nullable MinecraftServer server,
                                        @Nullable Entity entity) {
        return entity == null
                ? new CommandSourceStack(source, pos, rot, level, permissions, name, server)
                : new CommandSourceStack(source, pos, rot, level, permissions, server, entity);
    }
}
