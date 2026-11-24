package com.moddingfromamod.wish;

import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.TuneDialog;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The wish box: a vanilla dialog with a text box</b> — the ONE way the watch asks "what do you wish
 * for?" (D2,: a text box replaces the anvil, which was only ever a workaround from 1.21.1,
 * when there was no other option).
 *
 * <p>It is a {@link TuneDialog} opened by {@link MenuKit#tune}, so it gets the measured Java → Bedrock
 * mapping for free: Java draws vanilla's dialog screen, and a Bedrock bridge (Geyser) turns the same
 * dialog into a Bedrock form with a text field and Submit. Both editions now do the same thing.
 *
 * <p>Its one action runs {@code /moddy wishbox <words>} ({@link ChildCommands#WISHBOX}), which submits
 * the words as {@link WishSource#TYPED} — the child's own words, which is what
 * {@code tools/mine-real-wishes.py} keeps. {@code /wish <text>} records {@code COMMAND} and could not be
 * the action without losing that. A blank box is a cancel and simply
 * closes: Escape on Java and a closed phone form both arrive as one.
 *
 * <p>What the anvil (WishAnvilMenu, A1–A14) taught that still applies here:
 * <ul>
 *   <li><b>The box opens with exactly the words meant for it</b> — empty, or the help/"wish about this"
 *       words — never a placeholder he types after (the anvil's first film submitted
 *       "Type your wish heremake me a Chucko McBurger"). The instruction lives in the label.</li>
 *   <li><b>An empty wish is refused, never submitted</b> (A8), and he is told why.</li>
 *   <li><b>The cap is the client's own and must be honest</b> (A6): {@link WishText#BOX_MAX}, the
 *       box's {@code max_length}. The anvil's was 50, a stock client's rename cap; this one fits a long
 *       wish, so the "type /wish in chat for more" detour is no longer needed.</li>
 *   <li><b>Opening it is intent</b>: the agent pool starts so its boot overlaps the
 *       typing. A dialog sends nothing per keystroke, so the anvil's per-key intent has no successor.</li>
 *   <li><b>The title says the wish-bank balance</b>, so the screen he types into answers "can I?".</li>
 * </ul>
 * Everything else the anvil carried (XP cost, eaten items, a block to stand at, a predicted result that
 * flickered) was the anvil's own meaning, and went with it.
 */
public final class WishCompose {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/wishbox");

    /** The dialog's title. It says what the screen is for, as the anvil's did. */
    public static final String TITLE = "✨ Make a Wish!";
    /** The one action. */
    public static final String ACTION = "✨ Make my wish";   //: the same words as the text-first box
    /** The box's label: the instruction lives here, never in the box (see the class note). */
    public static final String LABEL = "What do you wish for? Type anything you can imagine.";
    /** The box's input key, i.e. the {@code $(wish)} in the action's command. */
    public static final String KEY = "wish";
    /** Said when an empty box is submitted; the box then opens again. */
    public static final String BLANK = "Type what you wish for first!";
    /** How tall the box is, in lines. A height, not a limit ({@link TuneDialog.Words}). */
    static final int LINES = 4;

    // ── WHICH MAGIC, said and chosen where the wish is typed ──
    /** The dropdown's input key, and the literal its choice rides behind: {@code moddy wishbox magic <id> <words>}. */
    public static final String MAGIC_KEY = "magic";
    /** The dropdown's label, for the dialog's structure only: it is hidden on Java and Geyser sends it empty. */
    public static final String MAGIC_LABEL = "Magic";
    public static final String MAGIC_MAGICIAN = "magician";
    /** The Magician's id before the rename: a box opened on an older jar can still send it. */
    static final String MAGIC_MAGICIAN_BEFORE = "wizard";
    public static final String MAGIC_APPRENTICE = "apprentice";
    /** The box's own label when the lines above it say which magic: it must be true of both. */
    public static final String LABEL_EITHER = "What do you wish for?";
    /** As wide as the box under it, so an option is not cut off on Java. */
    static final int MAGIC_WIDTH = TuneDialog.Words.WIDTH;
    /** A Crafter's Magician line: where the option would be, where to sign up. The address follows. */
    public static final String MAGICIAN_SIGN_UP = "✪ Magician: sign up (18+ only) at ";

    /** One magic in a line: who answers and why, with the price only on a world with a wish bank. */
    static String magicWords(boolean apprentice, boolean priced) {
        if (apprentice) {
            return priced ? "✨ Apprentice: ½ wish. Faster, but more limited." : "✨ Apprentice: faster, but more limited.";
        }
        return priced ? "✪ Magician: 1 wish, but infinite opportunities." : "✪ Magician: infinite opportunities.";
    }

    private WishCompose() {
    }

    /** How many times the wish box has opened for each player: a dialog leaves no server state a test can read. */
    private static final java.util.Map<java.util.UUID, Integer> OPENED = new java.util.concurrent.ConcurrentHashMap<>();

    /** How many times the wish box has opened for him (a test compares it before and after a click). */
    public static int openedCount(java.util.UUID player) {
        return OPENED.getOrDefault(player, 0);
    }

    /**
     * Open the wish box for a new wish: with the next-wish idea already typed when there is one ({@link NextWish},
     * step 5), and Moddy says so; else empty.
     */
    public static boolean open(ServerPlayer player) {
        NextWish.Idea idea = NextWish.offer(player);
        if (idea == null) {
            return open(player, "");
        }
        boolean opened = openWith(player, idea.words());
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(NextWish.SAID)
                .withStyle(net.minecraft.ChatFormatting.YELLOW));
        return opened;
    }

    /**
     * Open the wish box with {@code prefill} already in it (the help wish, a thing's "wish about
     * this"): he can change it or send it as it is. Closes any open chest first, as the Builder's tune
     * does — the server must not believe the watch is still open under the dialog.
     */
    public static boolean open(ServerPlayer player, String prefill) {
        NextWish.withdraw(player.getUUID());   // a box opened with other words (a help wish) holds no idea of ours
        return openWith(player, prefill);
    }

    /**
     * <b>The text-first watch on Java</b>: the wish box, with the next-wish idea as {@link #open} has it, and
     * {@code buttons} under it ({@link WatchHome}). Its Submit says "✨ Make my wish".
     */
    public static boolean openHome(ServerPlayer player, List<com.moddingfromamod.ui.WishBoxDialog.Button> buttons) {
        NextWish.Held held = NextWish.hold(player);   // the same idea while he has not used it, said once
        boolean opened = openWith(player, held == null ? "" : held.idea().words(), buttons, held != null);
        if (held != null && held.fresh()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(NextWish.SAID)
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
        }
        return opened;
    }

    private static boolean openWith(ServerPlayer player, String prefill) {
        return openWith(player, prefill, null, false);
    }

    /**
     * an empty box that leaves Moddy's idea where it is (on its own Bedrock tile, {@link WatchHome#IDEA_KNOB}),
     * where {@link #open(ServerPlayer, String)} would withdraw it.
     */
    public static boolean openEmpty(ServerPlayer player) {
        return openWith(player, "");
    }

    /** the ✖ on a box Moddy filled: the idea goes, and the box opens again, empty. */
    public static final String CLEAR_VERB = "--clear";

    public static int clearBox(ServerPlayer player) {
        NextWish.clear(player.getUUID());
        return WatchHome.open(player) ? 1 : 0;
    }

    private static boolean openWith(ServerPlayer player, String prefill,
                                    List<com.moddingfromamod.ui.WishBoxDialog.Button> buttons, boolean clearable) {
        // INTENT: the wish box is the clearest sign yet that a wish is coming —
        // start the pool now if it was stopped, so its boot overlaps the typing.
        com.moddingfromamod.agent.AgentSession.intent(player.getUUID(), "wishbox");
        player.closeContainer();
        LOGGER.info("wish box opened for {}", player.getGameProfile().name());
        OPENED.merge(player.getUUID(), 1, Integer::sum);
        boolean priced = WishCharge.get().priced(player.getUUID());
        TuneDialog d;
        if (offersChoice(player) && !ApprenticeService.magicianAnswers(player)) {
            // the Magician is off, so there is nothing to choose: the ✪ line says so, and for the host it is
            // the way in to the setup
            d = magicianOffDialog(title(player), prefill,
                    com.moddingfromamod.config.MagicianSetup.watchTile(player)
                            .filter(com.moddingfromamod.config.MagicianPresets.TURN_ON::equals).isPresent());
        } else if (offersChoice(player)) {
            d = dialog(title(player), prefill, ApprenticeService.prefersApprentice(player), priced);
        } else if (saysBoth(player)) {
            d = crafterDialog(title(player), prefill, priced, CrafterService.signupShown(), CrafterService.signupUrl());
        } else {
            d = dialog(title(player), prefill, label(player));
        }
        if (buttons != null && !com.moddingfromamod.net.PlayerClient.isBedrock(player)) {
            MenuKit.wishBox(player, clearable ? home(d, buttons, true) : home(d, buttons));
        } else {
            MenuKit.tune(player, d);
        }
        return true;
    }

    /**
     * Is the choice of magic on this player's wish box? Only for a Wish Magician, on a world whose Builder
     * is on: a Crafter's words are always the Apprentice's, and with no Builder there is no Apprentice to choose.
     */
    static boolean offersChoice(ServerPlayer player) {
        return !CrafterService.isCrafter(player) && com.moddingfromamod.wishbuilder.BuilderSettings.forPlayer(player.getUUID()).enabled();
    }

    /**
     * Does this player's box say both magics WITHOUT a choice? A Crafter's (a Visitor's too), on a world
     * whose Builder is on: the Apprentice answers him, and where a Magician has the other option he has one calm
     * line saying where a grown-up signs up. It is part of the box, so it is not the capped nudge and never
     * counts as one ({@code CrafterService.takeNudge} is not asked).
     */
    static boolean saysBoth(ServerPlayer player) {
        return CrafterService.isCrafter(player) && com.moddingfromamod.wishbuilder.BuilderSettings.forPlayer(player.getUUID()).enabled();
    }

    /**
     * The box's label, which says WHO will answer and what it costs: a Crafter, and a Magician whose watch
     * is set to Apprentice, read "Apprentice magic"; a Magician reads the label he always had, with "Magician magic"
     * and the price added only where the Apprentice is a choice he could have made.
     */
    static String label(ServerPlayer player) {
        boolean builder = com.moddingfromamod.wishbuilder.BuilderSettings.forPlayer(player.getUUID()).enabled();
        boolean priced = WishCharge.get().priced(player.getUUID());
        boolean apprentice = ApprenticeService.route(player, WishRoute.Hint.AUTO, false) == WishRoute.Route.APPRENTICE;
        if (!apprentice && !builder) {
            return LABEL;   // no Apprentice on this world: nothing to tell apart
        }
        return com.moddingfromamod.wishbuilder.ApprenticeWords.boxLabel(apprentice, priced);
    }

    /** The title, with the wish-bank balance on a world that has one. */
    static String title(ServerPlayer player) {
        return WishCharge.get().composeTitle(TITLE, player.getUUID());
    }

    /** The text-first box: the same box, its Submit "✨ Make my wish", the buttons under it. Pure. */
    public static com.moddingfromamod.ui.WishBoxDialog home(TuneDialog d,
                                                             List<com.moddingfromamod.ui.WishBoxDialog.Button> buttons) {
        return home(d, buttons, false);
    }

    /** The home box; with {@code clearable}, a small ✖ beside its Submit that empties it. */
    public static com.moddingfromamod.ui.WishBoxDialog home(TuneDialog d,
                                                             List<com.moddingfromamod.ui.WishBoxDialog.Button> buttons,
                                                             boolean clearable) {
        return new com.moddingfromamod.ui.WishBoxDialog(
                new TuneDialog(d.title(), d.body(), d.inputs(), WatchHome.MAKE_MY_WISH, d.command()), buttons,
                clearable ? new com.moddingfromamod.ui.WishBoxDialog.Button(com.moddingfromamod.ui.WishBoxDialog.CLEAR_LABEL,
                        ChildCommands.MODDY + " " + ChildCommands.WISHBOX + " " + CLEAR_VERB) : null);
    }

    /** The dialog itself — pure, so a test reads exactly what a client is sent. */
    public static TuneDialog dialog(String title, String prefill) {
        return dialog(title, prefill, LABEL);
    }

    public static TuneDialog dialog(String title, String prefill, String label) {
        String seed = prefill == null ? "" : prefill.strip();
        if (!seed.isEmpty() && prefill.endsWith(" ")) {
            seed = seed + " ";   // "I want my castle to " — the cursor sits after the space, ready
        }
        if (seed.length() > WishText.BOX_MAX) {
            seed = seed.substring(0, WishText.BOX_MAX).strip();
        }
        return new TuneDialog(title,
                List.of(new TuneDialog.Words(KEY, label, seed, WishText.BOX_MAX, LINES)),
                ACTION, ChildCommands.MODDY + " " + ChildCommands.WISHBOX);
    }

    /**
     * <b>A Magician's wish box</b>: the same box with ONE dropdown above it, whose options each say which magic, starting on what he
     * chose last. A dropdown and not a toggle or a second button, because that is what the measured Java →
     * Bedrock mapping allows (docs/BEDROCK_COMPAT.md, "Menu mapping"): the same screen on both editions. Its
     * action runs {@code /moddy wishbox magic <id> <words>}.
     */
    public static TuneDialog dialog(String title, String prefill, boolean apprentice, boolean priced) {
        TuneDialog plain = dialog(title, prefill, LABEL_EITHER);
        return new TuneDialog(title,
                List.of(TuneDialog.Choice.wide(MAGIC_KEY, MAGIC_LABEL,
                                List.of(new TuneDialog.Option(MAGIC_MAGICIAN, magicWords(false, priced)),
                                        new TuneDialog.Option(MAGIC_APPRENTICE, magicWords(true, priced))),
                                apprentice ? MAGIC_APPRENTICE : MAGIC_MAGICIAN, MAGIC_WIDTH),
                        plain.inputs().get(0)),
                ACTION, ChildCommands.MODDY + " " + ChildCommands.WISHBOX + " " + MAGIC_KEY);
    }

    /**
     * <b>A Crafter's wish box</b>: no choice, because a dialog has no option that can be shown and not
     * picked. Two plain lines instead, above the same one text box: the magic he has, and where the Magician option
     * would be, where a grown-up signs up ({@code shown}, a link to {@code url} on Java, words on Bedrock). Its
     * Submit is the plain box's: {@code /moddy wishbox <words>}.
     */
    public static TuneDialog crafterDialog(String title, String prefill, boolean priced, String shown, String url) {
        TuneDialog plain = dialog(title, prefill, LABEL_EITHER);
        return new TuneDialog(title,
                List.of(new TuneDialog.Line(magicWords(true, priced)), new TuneDialog.Line(MAGICIAN_SIGN_UP, shown, url)),
                plain.inputs(), ACTION, plain.command());
    }

    /** The ✪ line while the Magician is off: the host's ends in a link that opens the setup. */
    public static final String MAGICIAN_OFF = "✨ The Apprentice answers. ✪ Magician: off. ";
    public static final String MAGICIAN_TURN_ON = "Turn it on";
    /** A guest's: the host sets it up (D18). */
    public static final String MAGICIAN_OFF_GUEST = "✨ Apprentice answers. ✪ Magician: off (the host sets it up).";

    /**
     * <b>A Magician's wish box while the Magician is off</b>: no choice, since only the Apprentice can answer.
     * ONE plain line above the box (two pushed "Make my wish" below the fold at 854×480 with Moddy's idea line, as
     * photographed): who answers, and "✪ Magician: off." For the host it ends in "Turn it on", a link that opens the Magician setup ({@code /moddy magician}); a click runs it on
     * Java, and the watch's own "✪ Turn on the Magician" tile is the same door on both editions. Submit is the plain
     * box's ({@code /moddy wishbox <words>}).
     */
    public static TuneDialog magicianOffDialog(String title, String prefill, boolean host) {
        TuneDialog plain = dialog(title, prefill, LABEL_EITHER);
        TuneDialog.Line magician = host
                ? new TuneDialog.Line(MAGICIAN_OFF, MAGICIAN_TURN_ON, "/" + ChildCommands.MODDY + " "
                        + com.moddingfromamod.config.MagicianSetup.VERB)
                : new TuneDialog.Line(MAGICIAN_OFF_GUEST);
        return new TuneDialog(title, List.of(magician), plain.inputs(), ACTION, plain.command());
    }

    /**
     * {@code /moddy wishbox magic <id> <words>}: Submit on a Magician's box. The choice is KEPT (it is the
     * same setting as the 📖 page's tile, on his shelf), then the words are submitted like any typed wish, so who
     * answers is still decided in one place ({@link WishRoute}): a question stays the agent's even with Apprentice
     * chosen. A blank box is a cancel and changes nothing. A Crafter has no choice, whatever arrives here; and
     * anything that is not one of the two ids was typed by hand, and is read as his words.
     */
    public static int submitChosen(ServerPlayer player, String magic, String words) {
        boolean known = MAGIC_MAGICIAN.equals(magic) || MAGIC_MAGICIAN_BEFORE.equals(magic) || MAGIC_APPRENTICE.equals(magic);
        if (!known) {
            return submit(player, MAGIC_KEY + " " + magic + " " + TuneDialog.Words.unmark(words));
        }
        String typed = WishText.fromDialog(TuneDialog.Words.unmark(words)).strip();
        if (!WishText.sanitize(typed).isEmpty() && offersChoice(player)) {
            ApprenticeService.choose(player, MAGIC_APPRENTICE.equals(magic));
        }
        return submit(player, words);
    }

    /**
     * {@code /moddy wishbox <words>}: the box's Submit. The words arrive escaped by the client
     * ({@link WishText#fromDialog}); blank ones are refused and the box opens again.
     */
    public static int submit(ServerPlayer player, String words) {
        String typed = WishText.fromDialog(TuneDialog.Words.unmark(words)).strip();
        if (NextWish.sent(player, typed)) {
            return 1;   // step 5: the idea, sent as it was offered, granted its instant way
        }
        if (WishText.sanitize(typed).isEmpty()) {
            // A blank send IS a cancel: a Java notice dialog runs its one button on Escape, and on a
            // phone a closed form (or the keyboard's Enter) sends the box's empty default. Reopening the box
            // here, as this used to, meant Cancel could never cancel; so a blank box just closes, quietly.
            LOGGER.info("wish box closed empty by {}", player.getGameProfile().name());
            return 0;
        }
        // A Crafter's words are never written down (docs/OPEN_DOOR_PLAN.md §7.3): only that he wished.
        LOGGER.info("wish box submitted by {}: {}", player.getGameProfile().name(),
                CrafterService.isCrafter(player) ? "(a Crafter's words are not kept)" : typed);
        // TYPED, exactly as the anvil and the mod screen's free-text box recorded it: the affordance
        // changed, what the wish IS did not.
        WishService.Outcome out = WishService.submit(player, typed, WishSource.TYPED);
        return out == WishService.Outcome.FULFILLING || out == WishService.Outcome.APPRENTICE ? 1 : 0;
    }
}
