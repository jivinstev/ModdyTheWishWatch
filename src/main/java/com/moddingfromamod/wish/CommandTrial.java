package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * <b>Try a template's commands BEFORE it is saved, without changing the world.</b>
 *
 * <p>A template is not a recording. When a wish is granted with a typed tool ({@code modify_mob},
 * say), {@code add_template} cannot save the call that worked — a template is a list of commands —
 * so the agent re-expresses it as commands, and nothing ever ran them. That is how Steve's Great Big
 * Shark was saved as a {@code summon} whose {@code attributes} Minecraft ignores: the first wish
 * worked, and every instant re-wish afterwards was an ordinary shark announced as the big one.
 *
 * <p>Running the commands for real at teach time would summon a second shark next to the first, so
 * this does the next best thing, and it covers the failure that is SILENT:
 * <ul>
 *   <li><b>every command is parsed by Brigadier</b> exactly as {@link WishExecutor#runOne} would —
 *       a command Minecraft cannot read is refused now, not the first time the child presses the
 *       button;</li>
 *   <li><b>a {@code summon} with NBT is loaded into a throwaway entity</b> — the same
 *       {@code EntityType.loadEntityRecursive} call {@code /summon} makes — that is never added to
 *       the world, with {@link WarnCapture} listening. Every WARN Minecraft logs while reading that
 *       NBT ({@code Ignoring unknown attribute}, {@code Ignoring malformed attribute}, …) is part of
 *       the wish it would quietly drop.</li>
 * </ul>
 *
 * <p>Attribute ids are repaired first ({@link WishExecutor#repairAttributeIds}), exactly as at run
 * time, so a name the executor would fix is not reported as a problem.
 */
public final class CommandTrial {

    private CommandTrial() {
    }

    /** Every problem with {@code commands}, one line each naming the command; empty = all clean. */
    public static List<String> problems(MinecraftServer server, ServerLevel level, Vec3 pos,
                                        List<String> commands) {
        List<String> out = new ArrayList<>();
        for (String command : commands) {
            for (String p : problems(server, level, pos, command)) {
                out.add("`" + command + "`: " + p);
            }
        }
        return out;
    }

    /** The problems with one command. Empty when Minecraft would read and apply all of it. */
    public static List<String> problems(MinecraftServer server, ServerLevel level, Vec3 pos,
                                        String raw) {
        String command = WishExecutor.repairAttributeIds(raw == null ? "" : raw.strip()).command();
        CommandSourceStack stack = new CommandSourceStack(CommandSource.NULL, pos, Vec2.ZERO, level,
                net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER, "wish-trial", Component.literal("wish-trial"), server, null);
        ParseResults<CommandSourceStack> parsed =
                server.getCommands().getDispatcher().parse(command, stack);
        if (!parsed.getExceptions().isEmpty()) {
            List<String> out = new ArrayList<>();
            parsed.getExceptions().values().forEach(e ->
                    out.add("Minecraft can't read it — " + e.getMessage()));
            return out;
        }
        if (parsed.getReader().canRead()) {
            return List.of("Minecraft can't read it — it stops understanding at '"
                    + parsed.getReader().getRemaining().trim() + "'");
        }
        if (command.startsWith("wishsummon ")) {
            // Brigadier takes the spec as a greedy string, so parse it the way the command will.
            String[] parts = command.split(" ", 3);
            if (parts.length == 3
                    && com.moddingfromamod.mob.WishSummonCommand.parseSpec(parts[2]) == null) {
                return List.of("the spec after the mob must be modify_mob's JSON, e.g. "
                        + "{\"scale\":4,\"health\":300}");
            }
            return List.of();
        }
        if (!command.startsWith("summon ")) {
            return List.of();
        }
        return trialSummon(level, parsed.getContext().build(command));
    }

    private static List<String> trialSummon(ServerLevel level, CommandContext<CommandSourceStack> ctx) {
        Holder.Reference<EntityType<?>> type;
        CompoundTag nbt;
        try {
            type = ResourceArgument.getSummonableEntityType(ctx, "entity");
        } catch (Exception e) {
            return List.of("Minecraft can't summon that — " + e.getMessage());
        }
        try {
            nbt = CompoundTagArgument.getCompoundTag(ctx, "nbt");
        } catch (IllegalArgumentException noNbt) {
            return List.of();   // a bare summon: nothing in it that could be silently dropped
        }
        CompoundTag tag = nbt.copy();
        tag.putString("id", type.key().identifier().toString());
        List<String> out = new ArrayList<>();
        WarnCapture.Captured<Entity> tried;
        try {
            tried = WarnCapture.during(() -> EntityType.loadEntityRecursive(tag, level, net.minecraft.world.entity.EntitySpawnReason.COMMAND, e -> e));
        } catch (RuntimeException e) {
            return List.of("Minecraft couldn't make this creature from its NBT — " + e);
        }
        if (tried.value() == null) {
            out.add("Minecraft couldn't make this creature from its NBT");
        } else {
            // Never added to the world; discarding just makes that explicit for passengers too.
            tried.value().getSelfAndPassengers().forEach(Entity::discard);
        }
        for (String warning : tried.warnings()) {
            if (!WarnCapture.dropsPartOfCommand(warning)) {
                continue;   // a mod talking while its creature loads is not a dropped instruction
            }
            out.add("Minecraft would summon it but IGNORE part of it — \"" + warning + "\"");
        }
        return out;
    }
}
