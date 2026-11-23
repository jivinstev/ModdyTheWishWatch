package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The three ways the wish card's layout was wrong, each of which cost a fifteen-minute client run.
 *
 * <p>Every case here is drawn from a real card of a real thing a real agent made. The numbers are
 * the ones the screen actually uses.
 */
class CardLayoutTest {

    private static final int LINE = 11;
    private static final int HERO_MIN = 40;
    private static final int HERO_MAX = 96;

    /** Name, tier+kind, aliases, two rows of vitals, the times-wished row, and their padding. */
    private static final int FIXED = 84;

    /**
     * The window the gate runs in, on a card that also has work in flight — the tightest real case,
     * where all three bugs showed up. Work in flight costs a whole extra button row.
     */
    private static final int TIGHT = 150;

    private static CardLayout.Fit tight(int powers, int story) {
        return CardLayout.allocate(TIGHT, FIXED, powers, story, LINE, HERO_MIN, HERO_MAX);
    }

    @Test
    void everythingItAllocatesActuallyFitsOnThePage() {
        // FAILURE 1: no floor at all, and the story drew underneath the action buttons.
        for (int powers = 0; powers <= 4; powers++) {
            for (int story = 1; story <= 6; story++) {
                var fit = tight(powers, story);
                assertTrue(fit.height(FIXED, LINE) <= TIGHT,
                        "powers=" + powers + " story=" + story + " overflowed the panel by "
                                + (fit.height(FIXED, LINE) - TIGHT) + "px");
            }
        }
    }

    @Test
    void hisOwnWordsAlwaysGetALineNoMatterHowCrowdedItIs() {
        // FAILURE 2 and 3, which are the same failure: the card stayed inside its floor by dropping
        // the row that quotes his wish. It looked perfectly fine, which is why it survived twice.
        for (int available = 100; available <= 260; available += 7) {
            var fit = CardLayout.allocate(available, FIXED, 3, 5, LINE, HERO_MIN, HERO_MAX);
            assertTrue(fit.storyLines() >= 1,
                    "at " + available + "px the card would say nothing about why he asked for it");
        }
    }

    @Test
    void aBusyCardKeepsItsWordsAndGivesUpThePicture() {
        // The real Spikor card: six powers wrapping to two lines and a long wish. This is the exact
        // case the third version could not fit — it kept a 40px photograph and dropped the story.
        var fit = tight(2, 4);
        assertTrue(fit.powerLines() >= 1, "the row saying what he does must survive");
        assertTrue(fit.storyLines() >= 1, "and so must his own words");
        // At or within a line of its floor — the leftover pixels that cannot make another line of
        // text go to the picture, which is the right home for them. Pinning this to exactly HERO_MIN
        // would be asserting that those few pixels are wasted.
        assertTrue(fit.heroHeight() <= HERO_MIN + LINE,
                "the photograph is what gives way, not the caption — it kept " + fit.heroHeight());
        assertTrue(fit.height(FIXED, LINE) <= TIGHT);
    }

    @Test
    void aRoomyCardSpendsTheSpaceOnThePictureRatherThanOnAir() {
        // A short wish on a tall window: nothing to say, so the photograph should be as big as it is
        // allowed to be. A card that leaves a band of empty panel under a small picture looks broken.
        var fit = CardLayout.allocate(400, FIXED, 1, 1, LINE, HERO_MIN, HERO_MAX);
        assertEquals(HERO_MAX, fit.heroHeight());
    }

    @Test
    void thePictureNeverGrowsAtTheExpenseOfWhatTheThingDoes() {
        // The ordering rule, stated directly: adding a line of powers may shrink the photograph, and
        // may never be refused while the photograph is above its floor.
        var quiet = CardLayout.allocate(200, FIXED, 0, 1, LINE, HERO_MIN, HERO_MAX);
        var busy = CardLayout.allocate(200, FIXED, 3, 1, LINE, HERO_MIN, HERO_MAX);
        assertEquals(3, busy.powerLines(), "there was room for them; the picture should have paid");
        assertTrue(busy.heroHeight() < quiet.heroHeight());
    }

    @Test
    void itNeverAsksForMoreLinesThanThereAreWords() {
        // A two-word wish on a huge screen must not be padded out to six lines of nothing.
        var fit = CardLayout.allocate(600, FIXED, 1, 1, LINE, HERO_MIN, HERO_MAX);
        assertEquals(1, fit.storyLines());
        assertEquals(1, fit.powerLines());
    }

    @Test
    void aThingWithNoPowersSimplyHasNoPowersRow() {
        // A building has materials; some things have neither. An empty row is a hole in the grid.
        assertEquals(0, tight(0, 2).powerLines());
    }
}
