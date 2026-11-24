package com.moddingfromamod.datapack;

import java.io.IOException;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /wishdatapack <id> [entry]} — the <b>replay command</b> that makes a CBScript datapack wish
 * reusable, exactly as {@code /wishbuild} does for buildings.
 *
 * <p>A learned wish is a list of commands, so a datapack feature could never become a button without
 * one. This is that command: it makes sure the saved datapack {@code <id>} is loaded (idempotent —
 * packs auto-load on world start, so this is usually a no-op safety net), and then runs its
 * declared <b>entry</b> function ({@code <id>:<entry>}) with elevated permission — a give, a spawn,
 * a trigger.
 *
 * <p><b>ACTION vs RULE.</b> Only ACTION datapacks (something the child re-triggers on demand) pass an
 * entry and get a template; a RULE datapack auto-loads and needs no button, so it is never taught one
 * — see {@link com.moddingfromamod.wish.ReplayContract} and docs/DATAPACKS.md. Called with no entry
 * this just confirms the pack is active.
 *
 * <p>Permission 0, like {@code /wish} and {@code /wishbuild}: it can only run a datapack the agent
 * already authored and saved, and the entry function itself is the datapack's own code.
 */
public final class WishDatapackCommand {

    private WishDatapackCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // put a made thing down at a position — what make_item's `at` runs under wishspot.
        com.moddingfromamod.item.WishPut.register(event.getDispatcher());
        event.getDispatcher().register(Commands.literal("wishdatapack")
                // A REPLAY command, not something a child types: the stored form of every
                // saved button, run by the executor at permission 2. Hidden from a child's tab list.
                .requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests((ctx, b) -> {
                            DatapackStore.listIds().forEach(b::suggest);
                            return b.buildFuture();
                        })
                        .executes(ctx -> run(ctx, null))
                        .then(Commands.argument("entry", StringArgumentType.word())
                                .executes(ctx -> run(ctx, StringArgumentType.getString(ctx, "entry"))))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, String entry)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(ctx, "id");
        MinecraftServer server = ctx.getSource().getServer();

        if (!DatapackStore.exists(id)) {
            ctx.getSource().sendFailure(Component.literal("No datapack called '" + id
                    + "'. Known: " + String.join(", ", DatapackStore.listIds())));
            return 0;
        }

        // Every compiled datapack has a <id>:reset (its load function), so its presence is the cheap
        // "is this pack loaded?" probe. If it isn't, install + reload once. The reload is async, so
        // the entry can't run this tick — and it used to answer "do that again!" and give nothing,
        // with nobody to press again. The press now WAITS and runs when the pack answers.
        if (!loaded(server, id)) {
            try {
                DatapackReloader.installAndReload(server, id, DatapackStore.zipFor(id));
            } catch (IOException e) {
                ctx.getSource().sendFailure(Component.literal("Couldn't load '" + id + "': " + e));
                return 0;
            }
            if (entry == null || entry.isBlank()) {
                ctx.getSource().sendSuccess(() -> Component.literal("✨ Loaded '" + id + "'!"), false);
            } else {
                PendingPressTicker.queue(player, id, entry);
                ctx.getSource().sendSuccess(() -> Component.literal("✨ Getting '" + id
                        + "' ready…"), false);
            }
            return 1;
        }

        if (entry == null || entry.isBlank()) {
            ctx.getSource().sendSuccess(() -> Component.literal("✨ '" + id + "' is on!"), false);
            return 1;
        }

        if (!runEntry(server, player, id, entry)) {
            ctx.getSource().sendFailure(Component.literal("'" + id + ":" + entry
                    + "' isn't a function in that datapack."));
            return 0;
        }
        return 1;
    }

    /** Is {@code id}'s pack loaded? Its {@code <id>:reset} load function is the probe. */
    static boolean loaded(MinecraftServer server, String id) {
        return PendingPressTicker.probe(server, id);
    }

    /** Run {@code <id>:<entry>} as the datapack's own code for {@code player}; false if absent. */
    static boolean runEntry(MinecraftServer server, ServerPlayer player, String id, String entry) {
        ServerFunctionManager functions = server.getFunctions();
        var fn = functions.get(Identifier.fromNamespaceAndPath(id, entry));
        if (fn.isEmpty()) {
            return false;
        }
        // Elevated + output-suppressed, like running the datapack's own code, not the player's.
        CommandFunction<CommandSourceStack> function = fn.get();
        functions.execute(function, com.moddingfromamod.compat.EntityCommands.source(player)
                .withPermission(2).withSuppressedOutput());
        return true;
    }
}
