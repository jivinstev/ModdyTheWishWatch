package com.moddingfromamod.event.design;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.event.EventDirector;
import com.moddingfromamod.event.StagedEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * <b>{@code /moddyevent}: the one way to make a kept event happen</b> (E4).
 *
 * <p>It exists so that everything else can be a COMMAND: a world rule ("when the boss dies") runs
 * {@code moddyevent stage fort_blast} as the boss, at the boss; an item runs it as the child; the
 * schedule and the agent call the same code underneath. Op-only (level 2), which is what a world
 * rule's commands and a datapack function run at.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class EventCommand {

    private EventCommand() {
    }

    private static final SuggestionProvider<CommandSourceStack> DESIGNS = (ctx, b) ->
            SharedSuggestionProvider.suggest(EventDesigns.all(ctx.getSource().getServer()).stream()
                    .map(EventDesign::name).toList(), b);

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("moddyevent")
                .requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("define")
                        .then(Commands.argument("json", StringArgumentType.greedyString())
                                .executes(EventCommand::define)))
                .then(Commands.literal("stage")
                        .then(Commands.argument("design", StringArgumentType.word()).suggests(DESIGNS)
                                .executes(ctx -> stage(ctx, null))
                                .then(Commands.literal("at")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> stage(ctx,
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))))
                .then(Commands.literal("every")
                        .then(Commands.argument("days", IntegerArgumentType.integer(DayBank.MIN_DAYS, DayBank.MAX_DAYS))
                                .then(Commands.argument("design", StringArgumentType.word()).suggests(DESIGNS)
                                        .executes(EventCommand::every))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("design", StringArgumentType.word()).suggests(DESIGNS)
                                .executes(ctx -> {
                                    boolean had = EventDesigns.stop(ctx.getSource().getServer(),
                                            StringArgumentType.getString(ctx, "design"));
                                    ctx.getSource().sendSuccess(() -> Component.literal(had
                                            ? "It will not happen again by itself." : "Nothing was scheduled."), false);
                                    return had ? 1 : 0;
                                })))
                .then(Commands.literal("undo")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> {
                                    boolean ok = EventDirector.undo(ctx.getSource().getServer(),
                                            StringArgumentType.getString(ctx, "id"));
                                    ctx.getSource().sendSuccess(() -> Component.literal(ok
                                            ? "Putting everything back." : "There is nothing to undo by that name."), false);
                                    return ok ? 1 : 0;
                                })))
                .then(Commands.literal("list").executes(EventCommand::list)));
    }

    private static int define(CommandContext<CommandSourceStack> ctx) {
        EventDesign.Parsed p = EventDesign.parse(StringArgumentType.getString(ctx, "json"));
        if (!p.ok()) {
            ctx.getSource().sendFailure(Component.literal("Not kept: " + String.join("; ", p.errors())));
            return 0;
        }
        String why = EventDesigns.put(ctx.getSource().getServer(), p.design());
        if (why != null) {
            ctx.getSource().sendFailure(Component.literal(why));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Kept " + p.design().name() + ": "
                + p.design().describe()), false);
        return 1;
    }

    private static int stage(CommandContext<CommandSourceStack> ctx, BlockPos at) {
        CommandSourceStack src = ctx.getSource();
        EventDesign d = EventDesigns.get(src.getServer(), StringArgumentType.getString(ctx, "design"));
        if (d == null) {
            src.sendFailure(Component.literal("There is no event design by that name."));
            return 0;
        }
        ServerLevel level = src.getLevel();
        Entity who = src.getEntity();
        EventBuild.Result r;
        if (at == null && who instanceof ServerPlayer p) {
            r = EventBuild.nearPlayer(p, d, p.getGameProfile().name());
        } else {
            BlockPos spot = at != null ? at : BlockPos.containing(src.getPosition());
            ServerPlayer viewer = nearestPlayer(level, spot);
            float yaw = who != null ? who.getYRot() : viewer != null ? viewer.getYRot() : 0f;
            r = EventBuild.stage(level, d, EventBuild.ground(level, spot.getX(), spot.getZ()), yaw, viewer,
                    viewer != null ? viewer.getGameProfile().name() : "", 0);
        }
        if (!r.ok()) {
            src.sendFailure(Component.literal("It did not happen: " + r.error()));
            return 0;
        }
        String id = r.id();
        src.sendSuccess(() -> Component.literal("Here it comes (" + id + ")."), false);
        return 1;
    }

    private static int every(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        int days = IntegerArgumentType.getInteger(ctx, "days");
        String owner = src.getEntity() instanceof ServerPlayer p ? p.getGameProfile().name() : "";
        String why = EventDesigns.every(src.getServer(), StringArgumentType.getString(ctx, "design"), days, owner);
        if (why != null) {
            src.sendFailure(Component.literal(why));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("It will happen every " + days + (days == 1 ? " day." : " days.")),
                false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        StringBuilder b = new StringBuilder();
        for (EventDesign d : EventDesigns.all(src.getServer())) {
            b.append("\n  ").append(d.name()).append(": ").append(d.describe());
        }
        for (EventDesigns.Schedule s : EventDesigns.schedules(src.getServer())) {
            b.append("\n  every ").append(s.days()).append(" days: ").append(s.design());
        }
        for (StagedEvent e : EventDirector.all(src.getServer())) {
            b.append("\n  happening: ").append(e.id()).append(" (").append(e.phase()).append(')');
        }
        String text = b.length() == 0 ? "No events." : "Events:" + b;
        src.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static ServerPlayer nearestPlayer(ServerLevel level, BlockPos at) {
        ServerPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            double d = p.distanceToSqr(at.getX(), at.getY(), at.getZ());
            if (d < bestD) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }
}
