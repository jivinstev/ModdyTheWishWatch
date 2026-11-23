package com.moddingfromamod.wish.reveal;

/**
 * When each part of a served wish's fanfare happens. Pure, so the feel is decided
 * here and tested without a game.
 *
 * <p>A served wish (a button, a cached wish, a starter) used to land the instant he tapped, with no
 * moment at all. It now gets the agent wish's moment in four to five seconds: the pillar rises at the
 * spot it will land, its helix climbs in three beats, the reveal plays, and the thing itself appears
 * at the reveal's climax, never before. Tapping again while one is on its way gets the quick
 * version: a second's pillar, because the full rise every time would turn a button into a wait.
 */
public final class FanfareTiming {

    /** The full rise: three beats of helix, then the reveal. 3 s. */
    public static final int FULL_RISE = 60;
    /** The quick rise, for a tap that follows another. Under a second. */
    public static final int QUICK_RISE = 16;
    /** A tap this soon after the last reveal started still counts as "again" (4 s). */
    public static final int QUICK_WINDOW = 80;
    /** How long a held item spirals before it is in his hand (the Spiral's length). */
    public static final int ITEM_HANDOFF = 40;

    private FanfareTiming() {
    }

    /** Ticks from the pillar appearing to the reveal starting. */
    public static int rise(boolean quick) {
        return quick ? QUICK_RISE : FULL_RISE;
    }

    /** How full the helix is {@code age} ticks into the rise: three beats, then whole. */
    public static float fraction(int age, boolean quick) {
        int rise = rise(quick);
        if (age >= rise) {
            return 1f;
        }
        if (quick) {
            return age < rise / 2 ? 0.5f : 1f;
        }
        int beat = rise / 3;
        return Math.min(1f, (age / beat) / 3f);
    }

    /**
     * Ticks into the reveal at which the wish's commands run: its climax, so the thing appears in
     * the reveal's flash rather than before it. Null look (a building) runs at once, because the
     * builder's own whirlwind is its moment.
     */
    public static int climax(Reveals.Look look) {
        if (look == null) {
            return 0;
        }
        return switch (look) {
            case CREATURE -> 40;   // Summon: the pillar falls from 30, the flash is at 50
            case EVENT -> 24;      // Launch bursts at 24
            case PICTURE -> 18;    // Frame flashes at 20 (TICKS 26 - 6)
            case ITEM -> 0;        // run at once and held; given at ITEM_HANDOFF
            case BUILD, BURST -> 2;
        };
    }

    /**
     * Whether a tap gets the quick version: another of his is still on its way, or the last one
     * revealed less than {@link #QUICK_WINDOW} ticks ago.
     */
    public static boolean quick(boolean anotherInFlight, long sinceLastReveal) {
        return anotherInFlight || (sinceLastReveal >= 0 && sinceLastReveal < QUICK_WINDOW);
    }

    /** How far in front of him each kind of thing lands, in blocks. */
    public static int forward(Reveals.Look look) {
        if (look == null) {
            return 12;            // a building, as wishbuild always placed it
        }
        return switch (look) {
            case CREATURE -> 5;
            case PICTURE -> 5;    // PictureCommand.FORWARD
            case EVENT -> 6;
            case ITEM, BUILD, BURST -> 4;
        };
    }
}
