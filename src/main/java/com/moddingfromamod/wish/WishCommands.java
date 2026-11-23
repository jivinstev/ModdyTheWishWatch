package com.moddingfromamod.wish;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * The two commands a child can see ({@link ChildCommands}): {@code /wish <text>} — the same
 * pipeline the watch uses — and
 * {@code /moddy}, the replacement watch plus every verb a chat link runs.
 *
 * <p>Deliberately available to everyone (permission 0): making a wish is the whole point, and every
 * wish still goes through sanitising, throttling and the command allowlist.
 */
public final class WishCommands {

    private WishCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // /wish IS A WISH, AND NOTHING ELSE. It was a long wish's only route while the watch's
        // box was an anvil (50 characters; a dialog since D2), so every sentence after /wish must
        // reach the pipeline, whatever its first word. While `again`/`status`/`stop`/`undo`/`redirect`/`no` were
        // literals here, Brigadier's literal-over-argument rule made `/wish no more rain please`
        // REJECT his last wish and `/wish stop the rain` an error. They live under /moddy now.
        LiteralArgumentBuilder<CommandSourceStack> wish = Commands.literal(ChildCommands.WISH);
        // BARE /wish opens the panel. A stock client has no mod screen of any kind, so the only
        // door to the zero-typing preset path has to be something the SERVER can open, and this is
        // the keyboard half of that door -- the other half is right-clicking Moddy, which needs no
        // typing at all.
        wish.executes(ctx -> WatchHome.openWatch(ctx.getSource().getPlayerOrException()) ? 1 : 0);
        wish.then(Commands.argument("text", StringArgumentType.greedyString())
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    String text = StringArgumentType.getString(ctx, "text");
                    WishService.Outcome outcome =
                            WishService.submit(player, text, WishSource.COMMAND);
                    return outcome == WishService.Outcome.FULFILLING ? 1 : 0;
                }));
        event.getDispatcher().register(wish);

        event.getDispatcher().register(moddyTree(com.moddingfromamod.plugin.Plugins.shared()));
    }

    /**
     * The whole {@code /moddy} tree, with {@code plugins}' {@link ChildBranch}es. The game registers it
     * with the mod's own plug-ins; a GameTest builds it with none to see the public mod's tree.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> moddyTree(com.moddingfromamod.plugin.Plugins plugins) {
        // /moddy: THE REPLACEMENT WATCH, AND EVERY VERB A CHAT LINK RUNS. Permission 0 -- stopping,
        // undoing, re-aiming, visiting or getting back your own thing is not an operator action,
        // and each verb only ever acts on this player's own wishes. They are almost never TYPED:
        // each is what a blue word in chat runs (ChildCommands builds every such line, and
        // WishChatGameTest parses each one against this tree).
        LiteralArgumentBuilder<CommandSourceStack> moddy = Commands.literal(ChildCommands.MODDY);
        // LOST MODDY? Bare /moddy hands over another, then says what the command can do. The join
        // and respawn hooks and the top-up cover the lifecycle edges; this is for NOW.
        moddy.executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            boolean gave = VanillaWatchItem.give(player);
            ctx.getSource().sendSuccess(() -> Component.literal(gave
                    ? "✨ Here is Moddy! Use him to make a wish."
                    : "✨ You already have Moddy — look in your bag!"), false);
            ctx.getSource().sendSuccess(() -> Component.literal(ChildCommands.helpLine()), false);
            return 1;
        });
        // TAKE ME THERE / TAKE ME BACK -- the completion ping's link, the watch's Go-see-it cell.
        moddy.then(Commands.literal(ChildCommands.GO)
                .executes(ctx -> WishGo.go(ctx.getSource().getPlayerOrException(), "")
                        == WishGo.Result.WENT ? 1 : 0)
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> WishGo.go(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "id"))
                                == WishGo.Result.WENT ? 1 : 0)));
        moddy.then(Commands.literal(ChildCommands.BACK)
                .executes(ctx -> WishGo.back(ctx.getSource().getPlayerOrException()) ? 1 : 0));
        // "GIVE ME THAT ONE AGAIN": items AND creatures through one verb -- a copy of a
        // thing he already has, not a new wish. Free on a world with no wish bank; ONE wish on a world with a
        // wish bank, charged inside WishGiveAgain just before the hand-over. Greedy, because a catalogue key for a
        // pre-#353 keepsake is `name#icon#nbt` and contains spaces.
        moddy.then(Commands.literal(ChildCommands.GIVE)
                .then(Commands.argument("thing", StringArgumentType.greedyString())
                        .executes(ctx -> WishGiveAgain.give(
                                ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "thing"))
                                == WishGiveAgain.Result.GAVE ? 1 : 0)));
        // "LET ME TRY THAT ONE": a copy of what ANY wish made, for whoever holds
        // its id — a full v4 UUID or its 8-character short id. Permission 0: the id is the
        // capability. It counts as the caller's own wish and never touches the maker's shelf.
        moddy.then(Commands.literal(ChildCommands.TRY)
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "✨ /moddy try <wish id> — get your own copy of a wish, if you "
                                    + "know its number."), false);
                    return 0;
                })
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> WishTry.tryIt(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "id"))
                                == WishTry.Result.GRANTED ? 1 : 0)));
        // MAKE ANOTHER ONE, by RECIPE -- the one path that does no matching at all, and one text
        // cannot express. The id is verified against the live template set inside submit(), so a
        // client cannot invent one.
        //
        // a STRING, not a word, because a PART recipe ("<wish>@<part>") carries characters a
        // word cannot; a plain id still parses unquoted exactly as before. The wish text is the
        // part's name for a part, so his history says what he asked for again.
        moddy.then(Commands.literal(ChildCommands.AGAIN)
                .then(Commands.argument("recipe", StringArgumentType.string())
                        .executes(ctx -> again(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "recipe"))
                                == WishService.Outcome.FULFILLING ? 1 : 0)));
        // HIDE IT FROM MY WATCH: the card's [🙈 Hide from my watch]. Only a tile his own
        // watch shows (WatchMenu.hide re-checks); a string, because a creation id carries `:`/`~`.
        // THE WISH BUILDER: its menus, the tune dialog's Make, and "change one thing".
        moddy.then(com.moddingfromamod.wishbuilder.BuilderMenus.childBranch());
        //, the text-first watch's buttons: "📚 My wishes" (the library chest) and "📖 What do I do now?".
        moddy.then(Commands.literal(WatchHome.LIBRARY_VERB)
                .executes(ctx -> WatchMenu.openLibrary(ctx.getSource().getPlayerOrException()) ? 1 : 0));
        moddy.then(Commands.literal(WatchHome.NOW_VERB)
                .executes(ctx -> WatchHome.openNow(ctx.getSource().getPlayerOrException()) ? 1 : 0));
        // THE WISH BOX'S SUBMIT (D2): the dialog's button runs `/moddy wishbox <words>`, which
        // records his words as TYPED (`/wish` records COMMAND). Bare = an empty box: told, and reopened.
        moddy.then(Commands.literal(ChildCommands.WISHBOX)
                .executes(ctx -> WishCompose.submit(ctx.getSource().getPlayerOrException(), ""))
                // the ✖ beside Submit on a box Moddy filled
                .then(Commands.literal(WishCompose.CLEAR_VERB)
                        .executes(ctx -> WishCompose.clearBox(ctx.getSource().getPlayerOrException())))
                // A MAGICIAN'S BOX carries his choice of magic ahead of the words: `wishbox magic <id> <words>`.
                .then(Commands.literal(WishCompose.MAGIC_KEY)
                        .then(Commands.argument("magic", StringArgumentType.word())
                                .then(Commands.argument("words", StringArgumentType.greedyString())
                                        .executes(ctx -> WishCompose.submitChosen(ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "magic"),
                                                StringArgumentType.getString(ctx, "words"))))))
                .then(Commands.argument("words", StringArgumentType.greedyString())
                        .executes(ctx -> WishCompose.submit(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "words")))));
        // APPRENTICE MAGIC, BY NAME: `/moddy apprentice <words>`. The same submit as every typed wish
        // (the safety floor reads the words first), asking WishRoute for the Apprentice. Bare, it says how.
        moddy.then(Commands.literal(ChildCommands.APPRENTICE)
                .executes(ctx -> {
                    ctx.getSource().getPlayerOrException().sendSystemMessage(Component.literal(
                            "✨ Moddy's Apprentice: type /moddy apprentice and what you wish for."));
                    return 0;
                })
                .then(Commands.argument("words", StringArgumentType.greedyString())
                        .executes(ctx -> WishService.submit(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "words"), WishSource.COMMAND,
                                WishRoute.Hint.APPRENTICE) == WishService.Outcome.APPRENTICE ? 1 : 0)));
        moddy.then(Commands.literal(ChildCommands.HIDE)
                .then(Commands.argument("tile", StringArgumentType.string())
                        .executes(ctx -> hide(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "tile")))));
        // STOPPING. WishCancel only ever picks from the runs this player owns.
        moddy.then(Commands.literal(ChildCommands.STOP)
                .executes(ctx -> stop(ctx.getSource().getPlayerOrException(), "",
                        com.moddingfromamod.marshal.CancelMode.STOP, ""))
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> stop(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "id"),
                                com.moddingfromamod.marshal.CancelMode.STOP, ""))));
        moddy.then(Commands.literal(ChildCommands.UNDO)
                .executes(ctx -> stop(ctx.getSource().getPlayerOrException(), "",
                        com.moddingfromamod.marshal.CancelMode.UNDO, ""))
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> stop(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "id"),
                                com.moddingfromamod.marshal.CancelMode.UNDO, ""))));
        // RE-AIM: the link SUGGESTS "/moddy redirect <id> " and he types what he meant.
        moddy.then(Commands.literal(ChildCommands.REDIRECT)
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("what", StringArgumentType.greedyString())
                                .executes(ctx -> stop(
                                        ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "id"),
                                        com.moddingfromamod.marshal.CancelMode.REDIRECT,
                                        StringArgumentType.getString(ctx, "what"))))));
        moddy.then(Commands.literal(ChildCommands.NO)
                .executes(ctx -> reject(ctx.getSource().getPlayerOrException(), ""))
                .then(Commands.argument("why", StringArgumentType.greedyString())
                        .executes(ctx -> reject(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "why")))));
        moddy.then(Commands.literal(ChildCommands.STATUS).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            WishBook book = WishBook.get(player.getServer());
            int pending = book.pending().size();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "✨ Wishes made: " + book.size()
                            + " | waiting: " + pending
                            + " | wishes left right now: " + WishService.wishesLeft(player)
                            + " | jobs running: " + WishExecutor.pendingJobs()), false);
            return 1;
        }));
        // WHICH ONE DID HE MEAN: the answer to the agent's ask_which, from the picture
        // picker, the stock client's chest row or a chat link. A STRING, as quoted() writes it.
        moddy.then(Commands.literal(ChildCommands.WHICH)
                .then(Commands.argument("id", StringArgumentType.string())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            boolean answered = WhichQuestion.answer(player.getUUID(),
                                    StringArgumentType.getString(ctx, "id"));
                            if (!answered) {
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "✨ That question is already answered."), false);
                            }
                            return answered ? 1 : 0;
                        })));
        // HOW MANY WISHES HAVE I GOT: the wish bank — a refill bar, the next wish, full again.
        moddy.then(Commands.literal(ChildCommands.WISHES).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            java.util.List<String> lines = WishCharge.get().wishesCommand(player.getUUID());
            for (String line : lines) {
                ctx.getSource().sendSuccess(() -> Component.literal(line)
                        .withStyle(net.minecraft.ChatFormatting.GOLD), false);
            }
            return 1;
        }));
        // HOW DOES THIS WORK AGAIN: this world's intro and "how to use Moddy", the same words
        // his first arrival showed him (world/JoinIntro). Repeats freely; records nothing.
        moddy.then(Commands.literal(ChildCommands.HELP).executes(ctx -> {
            com.moddingfromamod.world.JoinIntroService.help(ctx.getSource().getPlayerOrException());
            return 1;
        }));
        // A FRESH GUIDE BOOK, only where the world has one (WorldGuide.hasBook): a child who
        // dropped, burned or lost the book he was given on arrival gets another, free.
        if (com.moddingfromamod.world.WorldFacts.current().guide().hasBook()) {
            moddy.then(Commands.literal(ChildCommands.GUIDE).executes(ctx -> {
                com.moddingfromamod.world.GuideBookService.command(ctx.getSource().getPlayerOrException());
                return 1;
            }));
        }
        // TELL SOMEBODY ABOUT A PLAYER: a record the server's operator reads
        // — never an action against the player named. Any name: the person may have left.
        moddy.then(Commands.literal(ChildCommands.REPORT)
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            com.moddingfromamod.safety.ChatWords.REPORT_HOW), false);
                    return 0;
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                ctx.getSource().getServer().getPlayerList().getPlayerNamesArray(), b))
                        .executes(ctx -> com.moddingfromamod.chat.PlayerReports.report(
                                ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "player"), "") ? 1 : 0)
                        .then(Commands.argument("what", StringArgumentType.greedyString())
                                .executes(ctx -> com.moddingfromamod.chat.PlayerReports.report(
                                        ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "what")) ? 1 : 0))));
        // GET ME OUT: to a safe spot by his home, and a few fireproof seconds.
        moddy.then(Commands.literal(ChildCommands.RESCUE).executes(ctx ->
                com.moddingfromamod.world.RespawnGuard.rescue(ctx.getSource().getPlayerOrException())));
        // THE SETTINGS: the one action that changes one, refusing what a player may not change.
        moddy.then(com.moddingfromamod.config.SettingsCommand.branch());
        moddy.then(com.moddingfromamod.config.MagicianSetup.branch());   //: the host's Magician setup
        // A plug-in's branches: e.g. an islands module's START OVER, `/moddy island new [confirm]`.
        for (ChildBranch b : plugins.all(ChildBranch.class)) {
            moddy.then(b.branch());
        }
                // WHO MADE THIS: the SkyBlock packs' credit, shown only where they run.
        moddy.then(Commands.literal(ChildCommands.CREDITS).executes(ctx -> {
            for (String line : Credits.lines(ctx.getSource().getServer().getPackRepository().getSelectedIds())) {
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            }
            return 1;
        }));
        return moddy;
    }

    /**
     * {@code /moddy again <recipe>} — MAKE ANOTHER ONE by recipe, and a left-click on a
     * remakeable watch tile. One body, so the tile and its card's link cannot drift apart. The wish
     * text is the part's name for a part, so his history says what he asked for again.
     */
    static WishService.Outcome again(ServerPlayer player, String recipe) {
        String[] part = com.moddingfromamod.marshal.TaughtJoin.splitPart(recipe);
        String text = part == null ? recipe
                : part[1].replace('_', ' ').replaceAll("~.*$", "").trim();
        return WishService.submit(player, text.isEmpty() ? recipe : text, WishSource.PRESET, recipe);
    }

    /** {@code /moddy hide <tile>} — {@link WatchMenu#hideFor}, which also says what happened. */
    static int hide(ServerPlayer player, String tile) {
        WatchMenu.Hid r = WatchMenu.hideFor(player, tile);
        return r == WatchMenu.Hid.HIDDEN || r == WatchMenu.Hid.FORGOT ? 1 : 0;
    }

    private static int reject(ServerPlayer player, String why) {
        return WishService.reject(player, why) == WishService.Outcome.UNDONE ? 1 : 0;
    }

    private static int stop(ServerPlayer player, String shortId,
                            com.moddingfromamod.marshal.CancelMode mode, String redirectTo) {
        return WishCancel.stop(player, shortId, mode, redirectTo).stopped() ? 1 : 0;
    }

    /** Don't let queued jobs or a cached book straddle a world unload. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServedFanfare.clear(event.getServer());
        WishExecutor.clear();
        WishProgress.clear();
        WishBook.reset();
        com.moddingfromamod.marshal.Marshal.global().clear();
        WishGo.clear();
        WishTopic.clear();
        WhichQuestion.clear();
    }
}
