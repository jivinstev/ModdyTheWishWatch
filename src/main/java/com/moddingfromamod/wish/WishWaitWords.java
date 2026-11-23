package com.moddingfromamod.wish;

import java.util.List;

/**
 * <b>Every line a child reads while his wish is being granted, before anything is built</b>.
 *
 * <p>One place, under Gate A ({@code WishWaitWordsTest}) and swept by the lexicon, as
 * {@code SafetyWords} and {@code WishBankWords} are. The owner approved these words exactly as they
 * stand ("Proposed wording for review", 2026-10-04); change them there first.
 *
 * <p>The rule they replaced is the reason they exist. Every agent wish used to open with "Ooh,
 * that's a tricky one! Let me think really hard", and the action bar said "this one is BIG" at
 * 8 s whatever the wish was: three lines in the first ~20 s, written before anything was known.
 * <b>None of these claims difficulty, size or progress the mod cannot see</b> — each names a moment
 * the mod has actually observed ({@link WishStage} decides which).
 *
 * <p>Pure and Minecraft-free.
 */
public final class WishWaitWords {

    private WishWaitWords() {
    }

    // ── chat ────────────────────────────────────────────────────────────────────────────────────

    /** The opening line of a wish that goes to Moddy: one of these, in turn ({@link #opener}). */
    public static final List<String> OPENERS = List.of(
            "✨ Got your wish! Moddy's on it…",
            "🪄 Ooh, a new wish! Grabbing my wand…",
            "✨ Wish received! Let's make some magic…",
            "🌟 On it! Moddy's getting to work…");

    /** Said once per wish, when its spot is marked and it is a thing that appears in the world. */
    public static final String SPARKLES = "✨ See the sparkles? That's where it'll appear!";

    /** A wish that came true and cannot be saved ({@code OneOffWish}); "✨ " is added when said. */
    public static final String ONE_OF_A_KIND =
            "This one's one of a kind — I can't save it for next time, so enjoy it now!";

    // ── the action bar ──────────────────────────────────────────────────────────────────────────

    /** Before a worker has picked the wish up. */
    public static final String READING = "Reading your wish…";

    /** the character the wish names is being looked up, before a worker takes it ({@link #lookingUp}). */
    public static final String LOOKING_UP = "Looking up %s…";

    /** The longest name the bar repeats; a longer one is cut at a word. */
    static final int LOOKUP_NAME_MAX = 28;

    /**
     * "Looking up Scorch…": the name is the one the child typed (the resolver's reading of it), shown only
     * to him, cut short so a long one cannot fill the bar. Blank or unprintable → {@link #READING}.
     */
    public static String lookingUp(String name) {
        String n = name == null ? "" : name.replaceAll("§.?", "").replaceAll("\\p{Cntrl}", "").strip();
        if (n.length() > LOOKUP_NAME_MAX) {
            int cut = n.lastIndexOf(' ', LOOKUP_NAME_MAX);
            n = n.substring(0, cut > 8 ? cut : LOOKUP_NAME_MAX).strip();
        }
        return n.isEmpty() ? READING : String.format(java.util.Locale.ROOT, LOOKING_UP, n);
    }

    /** Picked up, no plan yet. */
    public static final String WORKING_OUT = "Working out how to make it…";

    /** Nothing new for {@link WishStage#STILL_WORKING_MS}. */
    public static final String STILL_WORKING = "Still working on it — thanks for waiting!";

    /** Nothing new for {@link WishStage#STILL_GOING_MS}. */
    public static final String STILL_GOING = "Still going — some wishes take a minute or two!";

    /** Every fixed line here, so the test that sweeps them cannot miss one that was added. */
    public static List<String> all() {
        List<String> out = new java.util.ArrayList<>(OPENERS);
        out.addAll(List.of(SPARKLES, ONE_OF_A_KIND, READING, lookingUp("it"), WORKING_OUT, STILL_WORKING,
                STILL_GOING));
        return List.copyOf(out);
    }

    /**
     * The {@code n}th opener, rotating rather than random: a child who wishes four times reads
     * four different lines, never the same one twice in a row.
     */
    public static String opener(int n) {
        return OPENERS.get(Math.floorMod(n, OPENERS.size()));
    }

    /** A plan step as the bar says it: {@code "Building the floating island…"}. */
    public static String step(String label) {
        if (label == null || label.isBlank()) {
            return WORKING_OUT;
        }
        String s = label.strip();
        while (s.endsWith(".") || s.endsWith("…")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s.isEmpty() ? WORKING_OUT : s + "…";
    }
}
