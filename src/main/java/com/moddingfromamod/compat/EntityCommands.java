package com.moddingfromamod.compat;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * <b>A command source positioned at, and running as, an entity</b> (1.21.4 half).
 *
 * <p>1.21.2 removed {@code Entity.createCommandSourceStack()}; {@code ServerPlayer} keeps one, and every
 * other entity has {@code createCommandSourceStackForNameResolution(ServerLevel)}. The replacement is
 * NOT the same source: it answers {@code CommandSource.NULL} rather than the entity, and permission 0
 * rather than the entity's own level. Both differences are inert for our callers, and that is checked
 * rather than assumed: every call site sets {@code withPermission(2)} itself, and a non-player
 * entity's {@code sendSystemMessage} was a no-op, so a NULL output sink loses nothing. A caller that
 * relied on the entity's permission level must not use this.
 */
public final class EntityCommands {
    private EntityCommands() {
    }

    public static CommandSourceStack source(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            return player.createCommandSourceStack();
        }
        return entity.createCommandSourceStackForNameResolution((ServerLevel) entity.level());
    }
}
