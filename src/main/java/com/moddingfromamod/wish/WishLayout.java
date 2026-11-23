package com.moddingfromamod.wish;

/**
 * How big Moddy's panel is allowed to be on the screen it actually has.
 *
 * <p>Minecraft-free on purpose, so the arithmetic that decides whether the child can SEE the Close
 * button is unit-tested rather than eyeballed.
 *
 * <p>The bug this exists for: the panel was a fixed 290 tall. Minecraft's default "Auto" GUI scale
 * picks the biggest scale that still leaves at least 320x240 of GUI space — so on a Retina display
 * the scaled screen is exactly <b>240</b> tall. A 290-tall panel centered in 240 hangs 25 pixels off
 * the top and 25 off the bottom, which is precisely where the title and the 👎 button live. Nothing
 * was broken; it was simply drawn off-screen.
 *
 * <p>The fix is to treat the wish list as the elastic part: the fixed chrome (title, text box,
 * Wish!/Close, 👎) is never sacrificed, and the number of visible wish rows shrinks until the whole
 * panel fits. Rows are the right thing to give up because the list already scrolls — fewer rows
 * costs a scroll, a clipped Close button costs the screen.
 */
public final class WishLayout {

    private WishLayout() {}

    /** Widest the panel is ever drawn. */
    public static final int MAX_PANEL_W = 300;

    /** Height of one wish row, including the gap under it. */
    public static final int ROW_H = 22;

    /** Panel top edge to the first wish row (the title sits in here). */
    public static final int CHROME_TOP = 30;

    /**
     * Last wish row to the panel's bottom edge: the 8px gap, the text box, the Wish!/Close pair, the
     * 👎 button, and the bottom margin. This block is never compressed.
     */
    public static final int CHROME_BOTTOM = 84;

    /** Breathing room between the panel and the edge of the screen, per side. */
    public static final int SCREEN_MARGIN = 6;

    /** Rows shown when there's room for them. More than this and the panel is mostly empty list. */
    public static final int MAX_ROWS = 8;

    /**
     * Fewest rows we'll ever show. Below this the list stops reading as a list — and on a screen too
     * small even for these, clipping a wish row is still far better than clipping the buttons.
     */
    public static final int MIN_ROWS = 3;

    /** How many wish rows fit on a screen this tall. */
    public static int rowsFor(int screenHeight) {
        int forRows = screenHeight - 2 * SCREEN_MARGIN - CHROME_TOP - CHROME_BOTTOM;
        int rows = forRows / ROW_H;
        return Math.max(MIN_ROWS, Math.min(MAX_ROWS, rows));
    }

    /** Panel height for a given number of visible wish rows. */
    public static int panelHeight(int rows) {
        return CHROME_TOP + rows * ROW_H + CHROME_BOTTOM;
    }

    /** Panel width on a screen this wide (narrower screens get a narrower panel, not a clipped one). */
    public static int panelWidth(int screenWidth) {
        return Math.min(MAX_PANEL_W, Math.max(1, screenWidth - 2 * SCREEN_MARGIN));
    }

    /**
     * Whether the panel fits with room to spare. False only on a screen so short that even
     * {@link #MIN_ROWS} overflows — the one case where something has to be clipped.
     */
    public static boolean fits(int screenHeight) {
        return panelHeight(rowsFor(screenHeight)) <= screenHeight;
    }
}
