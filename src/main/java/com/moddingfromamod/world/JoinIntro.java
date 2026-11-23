package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>What a player is told the first time he arrives in a world</b> —
 * pure, so every line is a Gate A test ({@code JoinIntroTest}) and passes the safety lexicon there.
 *
 * <p>Three parts, in this order, a few seconds after he has loaded in:
 * <ol>
 *   <li><b>what this world is</b> — the world manifest's {@code intro} ({@link WorldFacts#intro()}),
 *       different on every world;</li>
 *   <li><b>how to use Moddy</b> — {@link #HOW_TO}, the same on every world;</li>
 *   <li>on a world with a wish bank, the bank's own first-visit line
 *       ({@code WishBankWords.firstVisit}) — computed from the rules IN FORCE, so the intro itself
 *       never repeats a number that a rules edit could make wrong.</li>
 * </ol>
 *
 * <p>Each world's intro is shown ONCE per player ({@link IntroSeenStore}, a world store — never the
 * shelf, which every world shares: a flag there would skip one world's intro for a child who had met
 * another). {@code /moddy help} repeats parts 1 and 2 whenever he asks ({@link #help}).
 *
 * <p>Every sentence was checked against the code that does the thing: the watch lives in hotbar slot
 * 1 ({@code WatchPin.HOME}) and right-clicking it opens the panel ({@code WatchMenu}); the panel's
 * compose cell is "✍ Wish for something new!" and its reject cell "👎 That wasn't right!"
 * ({@code marshal.WatchPanel}); a hand-over goes to the hotbar and then the Wish Bag in slot 2
 * ({@code WishDelivery}, {@code WatchPin.BAG}); {@code /wish <words>} is the long-wish route
 * ({@code WishCommands}); learned wishes come back as panel buttons ({@code LearnedTemplates}).
 */
public final class JoinIntro {

    private JoinIntro() {
    }

    /** "How to use Moddy" — identical on every world, and short enough to read while the world loads. */
    public static final List<String> HOW_TO = List.of(
            "✨ Moddy is the watch in your hotbar (slot 1). Use him to make a wish.",
            "✍ Open him, type what you want in plain words, and send it. "
                    + "A long wish? Type /wish and your words in chat.",
            "✨ What Moddy makes goes in your hotbar, and the rest goes in your Wish Bag (slot 2).",
            "📚 Everything you wished for is in \"My wishes\", so you can make it again with one press.",
            "↩ Not what you wanted? Press \"Undo my last wish\". "
                    + "Type /moddy help to see all this again.");

    /** The heading of the how-to, so it reads as one block after the world's own lines. */
    public static final String HOW_TO_HEADING = "— How to use Moddy —";

    /**
     * The lines of a FIRST arrival, in order: the world's intro, the how-to, then {@code bankLine}
     * when there is one (blank or null: none). Pure.
     */
    public static List<String> firstArrival(WorldFacts world, String bankLine) {
        List<String> out = new ArrayList<>(help(world));
        if (bankLine != null && !bankLine.isBlank()) {
            out.add(bankLine);
        }
        return out;
    }

    /** {@code /moddy help}: this world's intro (if its manifest has one), then how to use Moddy. Pure. */
    public static List<String> help(WorldFacts world) {
        List<String> out = new ArrayList<>();
        if (world != null) {
            out.addAll(world.intro());
        }
        out.add(HOW_TO_HEADING);
        out.addAll(HOW_TO);
        return out;
    }

    /**
     * Whether a player is shown the intro on this login. Never a service client (the camera), never
     * one who has seen this world's intro, and never when the store could not be read — a refused
     * store must not mean "everybody is new" (WISH_STORES.md §2). Pure.
     */
    public static boolean due(boolean enabled, boolean child, boolean storeUsable, boolean seen) {
        return enabled && child && storeUsable && !seen;
    }

    /**
     * Whether the intro runs on this server: {@code on}/{@code off} when configured, else exactly when
     * the world names itself (its world id, or the manifest the launch script derives for a server that
     * names none — {@link WorldFacts#manifestId()}). The dev client
     * and the GameTest server name none, so their mock players are never introduced. Pure.
     */
    public static boolean enabled(String configured, String worldId) {
        String c = configured == null ? "" : configured.strip().toLowerCase(java.util.Locale.ROOT);
        if (c.equals("on") || c.equals("true")) {
            return true;
        }
        if (c.equals("off") || c.equals("false")) {
            return false;
        }
        return worldId != null && !worldId.isBlank();
    }
}
