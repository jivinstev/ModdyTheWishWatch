package com.moddingfromamod.wish;

import java.util.List;

/**
 * <b>The two commands a child can see, and every line a click sends.</b> Pure, so what a
 * link runs and what is registered are one list, checked by {@code ChildCommandsTest} and
 * {@code WishChatGameTest} rather than kept in step by hand.
 *
 * <p>{@code /wish <anything>} is a WISH, whatever its first word. That became a rule the day a
 * long wish's only route was the chat box: the anvil wish box stopped at 50 characters (a stock
 * client's own cap; the wish box is a dialog since D2), 50 of the child's 84 real typed wishes are longer, and while {@code /wish}
 * also carried {@code again}, {@code stop}, {@code undo}, {@code redirect}, {@code no} and
 * {@code status} as literals, Brigadier's literal-over-argument rule turned
 * {@code /wish no more rain please} into a REJECTION of his last wish and {@code /wish stop the
 * rain} into an error. So the controls moved under {@link #MODDY}, the one other command a child
 * sees, and nothing but text is left under {@link #WISH}.
 *
 * <p>The controls are almost never typed: each is what a blue word in chat RUNS. {@code /moddy}
 * on its own still hands over a lost watch, and now also says what it can do.
 */
public final class ChildCommands {

    /** {@code /wish} — bare opens the panel; with words, it is a wish. Nothing else. */
    public static final String WISH = "wish";
    /** {@code /moddy} — the replacement watch, and the verbs every chat link runs. */
    public static final String MODDY = "moddy";

    /** The verbs under {@code /moddy}, as registered. */
    public static final String AGAIN = "again";
    public static final String STOP = "stop";
    public static final String UNDO = "undo";
    public static final String REDIRECT = "redirect";
    public static final String NO = "no";
    public static final String STATUS = "status";
    public static final String GO = "go";
    public static final String BACK = "back";
    public static final String GIVE = "give";
    /** {@code /moddy hide <tile id>} — take one thing off his watch; wishing it again brings it back. */
    public static final String HIDE = "hide";
    /** {@code /moddy try <wish id>} — his own copy of what any wish made. */
    public static final String TRY = "try";
    /** {@code /moddy credits} — who made the world he is standing in (the SkyBlock packs). */
    public static final String CREDITS = "credits";
    /** {@code /moddy wishes} — the wish bank: how many, the next, full again. */
    public static final String WISHES = "wishes";
    /** {@code /moddy which <id>} — his answer to "which one do you mean?" ({@code ask_which}). */
    public static final String WHICH = "which";
    /** {@code /moddy help} — this world's intro and how to use Moddy, again. */
    public static final String HELP = "help";
    /**
     * {@code /moddy guide} — a fresh copy of this world's guide book. Registered only on a
     * world whose manifest has one ({@code WorldGuide.hasBook}), so it is NOT in {@link #VERBS}, which
     * every server registers.
     */
    public static final String GUIDE = "guide";
    /**
     * {@code /moddy builder [edit <id>]} — the Wish Builder's menus, and the tune dialog's
     * {@code /moddy builder make <token> …}. Registered on every server (it says so when this world's Builder
     * is off), but not in {@link #VERBS}: the help line names what every world does.
     */
    public static final String BUILDER = "builder";
    /**
     * {@code /moddy wishbox <words>} — the wish box's Submit (D2; {@code WishCompose}): his words,
     * recorded as {@code TYPED}. Bare, it says to type something first. Not in {@link #VERBS}: a child
     * never types it, the dialog's button runs it.
     */
    public static final String WISHBOX = "wishbox";
    /**
     * {@code /moddy apprentice <words>}: answer these words with Apprentice magic, whoever he is and
     * whatever his watch's toggle says: Builder makes on a pick page, half a wish, no AI. A branch of
     * {@code /moddy}, never a top-level command. Not in {@link #VERBS}: the help line stays the list
     * every world has, and the wish box and the watch's toggle are how a child meets the Apprentice.
     */
    public static final String APPRENTICE = "apprentice";
    /**
     * {@code /moddy report <player> [what happened]} — tells the people who run Moddy about someone.
     * It does nothing to that player by itself ({@code chat/PlayerReport}).
     */
    public static final String REPORT = "report";
    /**
     * {@code /moddy rescue}: to a safe spot by his home, fireproof for a few seconds — the link a
     * child dying in a loop on his own base is offered, since chat works when the watch cannot be reached.
     * Not in {@link #VERBS}: the offer is how he meets it.
     */
    public static final String RESCUE = "rescue";
    /**
     * {@code /moddy island new [confirm]}: start a fresh island. Bare it asks (a dialog); its button
     * runs {@code confirm}. Registered everywhere; a world with no islands says so. Not in {@link #VERBS}.
     */
    public static final String ISLAND = "island";

    /** Every verb, in the order the help line says them. */
    public static final List<String> VERBS =
            List.of(HELP, GO, BACK, GIVE, TRY, AGAIN, HIDE, STOP, UNDO, REDIRECT, NO, STATUS, WISHES, CREDITS,
                    WHICH, REPORT);

    /**
     * Commands that are the STORED FORM of a button rather than something a child types — every
     * starter and learned template replays through one of these, at permission 2 (the executor's
     * level). They keep their names (a saved template holds them) and leave a child's tab list.
     */
    public static final List<String> REPLAY_COMMANDS = List.of(
            "wishbuild", "wishdatapack", "wishpaint", "wishmob",
            "wishsummon", "wishvisit", "wishspot");

    /** Names that were child-facing and must not come back. */
    public static final List<String> RETIRED = List.of("wishgo", "wishback", "wishgive");

    private ChildCommands() {
    }

    public static String again(String recipe) {
        return "/" + MODDY + " " + AGAIN + " " + quoted(recipe);
    }

    /**
     * A recipe id as one brigadier string: bare when it is a plain word (every whole-wish id), in
     * quotes when it is a PART recipe — {@code oasis@bar~1f2e3d4c} carries {@code @} and
     * {@code ~}, which an unquoted argument cannot.
     */
    static String quoted(String recipe) {
        String r = recipe == null ? "" : recipe;
        if (r.matches("[0-9A-Za-z_\\-.+]+")) {
            return r;
        }
        return "\"" + r.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** The card's [🙈 Hide from my watch]: a tile id, quoted when it carries {@code : ~ # @}. */
    public static String hide(String tileId) {
        return "/" + MODDY + " " + HIDE + " " + quoted(tileId);
    }

    public static String stop(String shortId) {
        return withId(STOP, shortId);
    }

    public static String undo(String shortId) {
        return withId(UNDO, shortId);
    }

    /**
     * The SUGGESTED line for {@code [no — the other one]}: the id and a space, left in his chat box
     * for the words he really meant. It was a RUN link to {@code /wish redirect <id>}, a command
     * that cannot run without those words, so the click only ever produced an error.
     */
    public static String redirectPrompt(String shortId) {
        return "/" + MODDY + " " + REDIRECT + " " + clean(shortId) + " ";
    }

    public static String go(String shortId) {
        return withId(GO, shortId);
    }

    public static String back() {
        return "/" + MODDY + " " + BACK;
    }

    public static String give(String thing) {
        return "/" + MODDY + " " + GIVE + " " + thing;
    }

    /** His answer to an {@code ask_which} question: one of the offered ids, or {@code none}. */
    public static String which(String id) {
        return "/" + MODDY + " " + WHICH + " " + quoted(id);
    }

    /** The line the tower's copy button puts on the clipboard: {@code /moddy try <wish id>}. */
    public static String tryWish(String wishId) {
        return withId(TRY, wishId);
    }

    /** A new wish, for a SUGGEST link: whatever follows is his wish, first word included. */
    public static String wish(String words) {
        return "/" + WISH + " " + (words == null ? "" : words);
    }

    /** What bare {@code /moddy} says after the watch. One line, read-aloud short. */
    public static String helpLine() {
        return "✨ Type /wish and your wish in chat to wish for anything — long wishes too! "
                + "(Moddy's links use /moddy: " + String.join(", ", VERBS) + ")";
    }

    private static String withId(String verb, String shortId) {
        String id = clean(shortId);
        return ("/" + MODDY + " " + verb + (id.isEmpty() ? "" : " " + id));
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
