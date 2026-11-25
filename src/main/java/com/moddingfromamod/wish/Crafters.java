package com.moddingfromamod.wish;

import java.util.List;
import java.util.function.Predicate;

import com.moddingfromamod.world.WorldCrafters;

/**
 * <b>Wish Crafters and Wish Magicians</b> — the rule and the words (step 5, docs/OPEN_DOOR_PLAN.md
 * §2, §5, §11.7). Pure, no Minecraft: {@code CraftersTest} is the Gate A surface, and
 * {@code CrafterService} only reads the game and carries out what this says.
 *
 * <p><b>Glyphs a stock client can draw</b>: Minecraft's chat font has no colour emoji — a magic wand
 * came out as a bare slash and a party popper as a squiggle (measured, CrafterShotsClientTest) — so in
 * game a Magician is ✪ and a celebration ✨; the website, which a browser draws, may use the emoji.
 *
 * <ul>
 *   <li><b>Everybody wishes.</b> A Crafter CRAFTS wishes — ready-made ones, and (later) the Builder's
 *       parts — with no model anywhere; a Magician can also INVENT a brand-new one, which is the agent.</li>
 *   <li>A Crafter's ranks are earned by PLAYING (the world's goals). Magician is not the top rank: it is
 *       an unlock, a grown-up's account (18+), so a child is never set a level he cannot reach by play.</li>
 *   <li>The nudge is a FACT addressed to grown-ups, at most one unasked every {@link #NUDGE_GAP_MS}
 *       (the UK Children's Code, standard 13) — never "ask your parents", never a countdown.</li>
 * </ul>
 */
public final class Crafters {

    private Crafters() {
    }

    public enum Role { MAGICIAN, CRAFTER }

    /** At most one unasked nudge in this long (§5). */
    public static final long NUDGE_GAP_MS = 10 * 60 * 1000L;

    /** A Crafter only where the door is open, only off the world's list, never an operator. */
    public static Role role(boolean open, boolean listed, boolean operator) {
        return open && !listed && !operator ? Role.CRAFTER : Role.MAGICIAN;
    }

    /**
     * The rank a Crafter holds: the LAST rank whose advancement is done (the first needs none), or
     * {@code null} for a world with no ranks.
     */
    public static WorldCrafters.Rank rank(List<WorldCrafters.Rank> ranks, Predicate<String> done) {
        WorldCrafters.Rank held = null;
        for (WorldCrafters.Rank r : ranks) {
            if (r.after() == null || r.after().isBlank() || done.test(r.after())) {
                held = r;
            }
        }
        return held;
    }

    /** {@code "✦✦ Skilled Crafter"}, or the Magician's. */
    public static String title(Role role, WorldCrafters.Rank rank) {
        if (role == Role.MAGICIAN) {
            return "✪ Wish Magician";
        }
        return rank == null ? "✦ Wish Crafter" : "✦".repeat(Math.max(1, rank.stars())) + " " + rank.name();
    }

    /** Whether an unasked nudge may be said now. */
    public static boolean mayNudge(long lastNudgeMs, long nowMs) {
        return lastNudgeMs <= 0 || nowMs - lastNudgeMs >= NUDGE_GAP_MS;
    }

    /** After the 1st ready-made wish, and every 5th after it. */
    public static boolean nudgeAfterMade(int made) {
        return made == 1 || (made > 1 && made % 5 == 0);
    }

    // ── the words ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The second line of a Crafter's "✍ Wish for something new!" tile: the same tile a Magician has, and it
     * says who answers. Short lines on purpose: the tile sits right of centre, and a vanilla tooltip that would run
     * off the screen's edge is wrapped by the client wherever it lands. Each is at most {@link #LORE_MAX}.
     */
    public static final List<String> COMPOSE_LORE = List.of(
            "Type what you wish for",
            "✨ Moddy's Apprentice finds it",
            "in Moddy's Parts");

    /** The longest line a panel tooltip should carry (CraftersTest). */
    public static final int LORE_MAX = 30;

    /** First thing a Crafter hears in a session. */
    public static String welcome(String title) {
        return "✨ You're a " + title.replaceFirst("^✦+ ", "") + "! Press your watch, or type a wish: Moddy's "
                + "Apprentice finds it in Moddy's Parts. "
                + "Grown-ups (18+) can become Wish Magicians and invent brand-new wishes.";
    }

    /** A Crafter asked a question: the agent answers those. */
    public static final String QUESTION =
            "Moddy answers questions for Wish Magicians. Stuck? Open your wish watch and press 📖 What do I do now? — "
                    + "it has a hint for every step!";

    /** After a ready-made wish (1st, then every 5th), at most every {@link #NUDGE_GAP_MS}. */
    public static final String AFTER_MADE = "Like it? Wish Magicians can describe their very own — grown-ups (18+) start at ";

    /** A rank earned by playing. {@code next} is the next rank's name, or blank at the top. */
    public static String rankUp(String title, String next) {
        return "✨ " + title + "! You earned it on your island."
                + (next == null || next.isBlank() ? "" : " Keep exploring — " + next + " is next!");
    }

    /** A Crafter whose account was just seated: unlocked at once, no rejoin. */
    public static final String PROMOTED =
            "✨ You're a Wish Magician now! Your watch can make any wish you can think of — open it and type your wish.";
}
