package com.moddingfromamod.film;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.moddingfromamod.wish.ServedFanfare;
import com.moddingfromamod.wish.WishService;
import com.moddingfromamod.wish.WishSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>{@code /moddyop wishstage serve <recipe> <x y z> [quick]}: serve a known wish at an exact spot, now</b>
 * (Moddy Media).
 *
 * <p>A film's timeline lands each wish on a word of the narration, and some shots stand several pillars
 * at once (the teaser's three in a row, then a ring of eight). A child's served wishes rise where he aims
 * and queue one at a time; a staged one rises at the spot the film chose on its set and does not wait.
 * Everything else is the ordinary served path a watch button takes ({@link WishService#submit} with the
 * recipe named): the same book entry, fanfare, reveal and commands. Run it AS the player who wishes
 * ({@code execute as <player> run moddyop wishstage serve …}); an item flies to him.
 *
 * <p>Replies with the game tick it was staged on, for the take's event log.
 */
public final class WishStage {

    private WishStage() {
    }

    /** {@code /moddyop wishstage …}. Added by {@code OpsCommands}. */
    public static LiteralArgumentBuilder<CommandSourceStack> opBranch() {
        return Commands.literal("wishstage")
                .then(Commands.literal("serve")
                        .then(Commands.argument("recipe", StringArgumentType.word())
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> serve(ctx, false))
                                        .then(Commands.literal("quick").executes(ctx -> serve(ctx, true))))))
                // A wish made before (in the lab), replayed as a served wish at a spot: `/moddy try`, staged.
                .then(Commands.literal("try")
                        .then(Commands.argument("wish", StringArgumentType.word())
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> tryAt(ctx, false))
                                        .then(Commands.literal("quick").executes(ctx -> tryAt(ctx, true))))))
                // A made thing's replay command (what `/moddy try` runs: wishmob spawn, wishbuild, wishdatapack give,
                // moddyevent stage), served as his wish at a spot. For makes `try` cannot find (Builder makes keep no
                // handover memory).
                .then(Commands.literal("cmd")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("command", StringArgumentType.greedyString())
                                        .executes(WishStage::cmdAt))))
                // The Apprentice (no model): what it offers for some words, and making offer n at a spot.
                .then(Commands.literal("picks")
                        .then(Commands.argument("words", StringArgumentType.greedyString()).executes(WishStage::picks)))
                .then(Commands.literal("apprentice")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("n", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                        .then(Commands.argument("words", StringArgumentType.greedyString())
                                                .executes(WishStage::apprentice)))));
    }

    private static int tryAt(CommandContext<CommandSourceStack> ctx, boolean quick)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String wish = StringArgumentType.getString(ctx, "wish");
        BlockPos at = BlockPosArgument.getBlockPos(ctx, "pos");
        long tick = player.level().getGameTime();
        ServedFanfare.stageNext(player.getUUID(), at, quick);
        var result = com.moddingfromamod.wish.WishTry.tryIt(player, wish);
        ServedFanfare.clearStage(player.getUUID());
        String line = "wishstage try " + wish + " at " + at.getX() + " " + at.getY() + " " + at.getZ()
                + " on game tick " + tick + ": " + result;
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return result == com.moddingfromamod.wish.WishTry.Result.GRANTED ? 1 : 0;
    }

    private static int cmdAt(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockPos at = BlockPosArgument.getBlockPos(ctx, "pos");
        String command = StringArgumentType.getString(ctx, "command");
        long tick = player.level().getGameTime();
        ServedFanfare.stageNext(player.getUUID(), at, false);
        // A staged scene fires many wishes on one tick (the teaser's ring of eight); the player's burst
        // budget (5, then one per 6 s) is for a child at the keyboard, not an op directing a take, and
        // it silently left three of eight pillars empty. Staging is op-only, so it starts each wish
        // with a full budget.
        WishService.forget(player);
        var outcome = WishService.submitReplay(player, command,
                com.moddingfromamod.wish.CommandPlan.of(java.util.List.of(command), ""));
        ServedFanfare.clearStage(player.getUUID());
        String line = "wishstage cmd at " + at.getX() + " " + at.getY() + " " + at.getZ() + " on game tick " + tick
                + ": " + outcome + " (" + command + ")";
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return outcome == WishService.Outcome.FULFILLING ? 1 : 0;
    }

    private static int picks(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String words = StringArgumentType.getString(ctx, "words");
        var offers = com.moddingfromamod.wish.ApprenticeService.picksFor(player, words);
        StringBuilder sb = new StringBuilder("picks " + offers.size() + ":");
        for (int i = 0; i < offers.size(); i++) {
            sb.append(" [").append(i).append("] ").append(com.moddingfromamod.wishbuilder.Apprentice.key(offers.get(i)));
        }
        String line = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return offers.size();
    }

    private static int apprentice(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockPos at = BlockPosArgument.getBlockPos(ctx, "pos");
        int n = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "n");
        String words = StringArgumentType.getString(ctx, "words");
        long tick = player.level().getGameTime();
        com.moddingfromamod.wish.WishAimer.stageNext(player, at);
        boolean made = com.moddingfromamod.wish.ApprenticeService.makePick(player, words, n);
        com.moddingfromamod.wish.WishAimer.clearStage(player.getUUID());
        String line = "wishstage apprentice [" + n + "] at " + at.getX() + " " + at.getY() + " " + at.getZ()
                + " on game tick " + tick + ": " + (made ? "MAKING" : "REFUSED");
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return made ? 1 : 0;
    }

    private static int serve(CommandContext<CommandSourceStack> ctx, boolean quick)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String recipe = StringArgumentType.getString(ctx, "recipe");
        BlockPos at = BlockPosArgument.getBlockPos(ctx, "pos");
        long tick = player.level().getGameTime();
        ServedFanfare.stageNext(player.getUUID(), at, quick);
        WishService.Outcome outcome = WishService.submit(player, recipe.replace('_', ' '), WishSource.PRESET, recipe);
        ServedFanfare.clearStage(player.getUUID());   // refused before it was served: never leak to his next wish
        String line = "wishstage " + recipe + " at " + at.getX() + " " + at.getY() + " " + at.getZ()
                + " on game tick " + tick + ": " + outcome;
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return outcome == WishService.Outcome.FULFILLING ? 1 : 0;
    }
}
