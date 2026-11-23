package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Gate A for "the whole of Moddy is on the screen".
 *
 * <p>The case that matters is 240: Minecraft's Auto GUI scale guarantees at least 320x240 of GUI
 * space and, on a Retina display, lands on exactly that. The old fixed 290-tall panel therefore hung
 * off both ends on the machine this is actually played on.
 */
class WishLayoutTest {

    /** The scaled height Minecraft's Auto GUI scale bottoms out at. */
    private static final int AUTO_SCALE_MIN_H = 240;

    @Test
    void theOldFixedPanelDidNotFitTheSmallestAutoScaleScreen() {
        // 8 rows is what the panel used to hard-code; this is the bug, stated as arithmetic.
        assertTrue(WishLayout.panelHeight(WishLayout.MAX_ROWS) > AUTO_SCALE_MIN_H,
                "the 8-row panel is taller than the smallest Auto-scale screen — that was the bug");
    }

    @Test
    void everythingFitsOnTheSmallestAutoScaleScreen() {
        assertTrue(WishLayout.fits(AUTO_SCALE_MIN_H),
                "Moddy must fit whole on a 240-tall screen — buttons included");
        assertTrue(WishLayout.panelHeight(WishLayout.rowsFor(AUTO_SCALE_MIN_H)) <= AUTO_SCALE_MIN_H);
    }

    @Test
    void aShortScreenStillShowsSeveralWishes() {
        int rows = WishLayout.rowsFor(AUTO_SCALE_MIN_H);
        assertTrue(rows >= WishLayout.MIN_ROWS,
                "shrinking must not leave a list too short to read; got " + rows);
        assertTrue(rows < WishLayout.MAX_ROWS, "a 240-tall screen genuinely cannot show 8 rows");
    }

    @Test
    void aRoomyScreenGetsTheFullList() {
        assertEquals(WishLayout.MAX_ROWS, WishLayout.rowsFor(720),
                "a big screen should show the whole 8-row list, unchanged from before");
        assertEquals(WishLayout.MAX_ROWS, WishLayout.rowsFor(400));
    }

    @Test
    void everyPlausibleScreenHeightFits() {
        // Sweep the whole range a real client can hand us, so no size is quietly clipped.
        for (int h = AUTO_SCALE_MIN_H; h <= 2000; h++) {
            assertTrue(WishLayout.fits(h), "panel overflows a " + h + "-tall screen");
        }
    }

    @Test
    void rowsOnlyEverGrowWithTheScreen() {
        int previous = 0;
        for (int h = 100; h <= 2000; h++) {
            int rows = WishLayout.rowsFor(h);
            assertTrue(rows >= previous, "row count went DOWN as the screen grew, at h=" + h);
            previous = rows;
        }
    }

    @Test
    void anImpossiblyShortScreenKeepsTheButtonsRatherThanTheList() {
        // Below the floor we deliberately stop shrinking: a clipped wish row is recoverable by
        // scrolling, a clipped Close button is not.
        assertEquals(WishLayout.MIN_ROWS, WishLayout.rowsFor(80));
        assertEquals(WishLayout.MIN_ROWS, WishLayout.rowsFor(0));
    }

    @Test
    void aNarrowScreenNarrowsThePanelInsteadOfClippingIt() {
        assertEquals(WishLayout.MAX_PANEL_W, WishLayout.panelWidth(426),
                "the normal Auto-scale width has room for the full panel");
        assertEquals(WishLayout.MAX_PANEL_W, WishLayout.panelWidth(320));
        assertTrue(WishLayout.panelWidth(200) <= 200 - 2 * WishLayout.SCREEN_MARGIN + 1);
        assertTrue(WishLayout.panelWidth(1) >= 1, "never a zero/negative width");
    }
}
