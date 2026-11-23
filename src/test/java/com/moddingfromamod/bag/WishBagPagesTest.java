package com.moddingfromamod.bag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The Wish Bag's pages and its click reconciliation. */
class WishBagPagesTest {

    record S(String item, int n) {
    }

    private static List<S> page(S... cells) {
        List<S> out = new ArrayList<>(Arrays.asList(cells));
        while (out.size() < WishBagPages.PER_PAGE) {
            out.add(null);
        }
        return out;
    }

    private static List<S> rec(List<S> entries, int page, List<S> before, List<S> after) {
        return WishBagPages.reconcile(entries, page, before, after,
                (a, b) -> a.item().equals(b.item()), S::n);
    }

    @Test
    void pageMaths() {
        assertEquals(1, WishBagPages.pages(0), "an empty bag still opens");
        assertEquals(1, WishBagPages.pages(45));
        assertEquals(2, WishBagPages.pages(46));
        assertEquals("Page 2 of 3", WishBagPages.label(1, 100));
        assertEquals(2, WishBagPages.clampPage(9, 100));
        assertEquals(10, WishBagPages.page(java.util.Collections.nCopies(100, new S("x", 1)), 2).size());
    }

    @Test
    void theBackstopIsAThousandPagesNotASmallCap() {
        assertEquals(45_000, WishBagPages.CAPACITY);
        assertTrue(WishBagPages.hasRoom(44_999, 1));
        assertFalse(WishBagPages.hasRoom(45_000, 1));
    }

    @Test
    void takingOneOutRemovesIt() {
        List<S> bag = List.of(new S("a", 1), new S("b", 2), new S("c", 3));
        assertEquals(List.of(new S("a", 1), new S("c", 3)),
                rec(bag, 0, page(bag.toArray(new S[0])), page(new S("a", 1), null, new S("c", 3))));
    }

    @Test
    void takingHalfLeavesItWhereItWas() {
        List<S> bag = List.of(new S("a", 1), new S("b", 32));
        assertEquals(List.of(new S("a", 1), new S("b", 16)),
                rec(bag, 0, page(bag.toArray(new S[0])), page(new S("a", 1), new S("b", 16))));
    }

    @Test
    void puttingOneInGoesToTheFront() {
        List<S> bag = List.of(new S("a", 1), new S("b", 2));
        assertEquals(List.of(new S("z", 1), new S("a", 1), new S("b", 2)),
                rec(bag, 0, page(new S("a", 1), new S("b", 2)), page(new S("a", 1), new S("b", 2), new S("z", 1))));
    }

    @Test
    void aSwapTakesOneOutAndPutsTheOtherAtTheFront() {
        List<S> bag = List.of(new S("a", 1), new S("b", 2));
        assertEquals(List.of(new S("z", 1), new S("a", 1)),
                rec(bag, 0, page(new S("a", 1), new S("b", 2)), page(new S("a", 1), new S("z", 1))));
    }

    @Test
    void aClickOnPageTwoTouchesPageTwosEntries() {
        List<S> bag = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            bag.add(new S("i" + i, 1));
        }
        List<S> before = page(WishBagPages.page(bag, 1).toArray(new S[0]));
        List<S> after = new ArrayList<>(before);
        after.set(0, null);                      // took i45
        List<S> out = rec(bag, 1, before, after);
        assertEquals(49, out.size());
        assertFalse(out.contains(new S("i45", 1)));
        assertEquals(new S("i0", 1), out.get(0));
    }

    @Test
    void anUntouchedPageIsUnchanged() {
        List<S> bag = List.of(new S("a", 1));
        assertEquals(bag, rec(bag, 0, page(new S("a", 1)), page(new S("a", 1))));
    }

    // ── the trim row ────────────────────────────────────────────────────────────────

    @Test
    void aPageIsTheTopFiveRowsAndTheTrimTakesTheSixth() {
        assertEquals(45, WishBagPages.PER_PAGE, "54 cells less a row of trim");
        assertEquals(WishBagPages.SLOTS - WishBagPages.PER_PAGE, WishBagPages.bottomRow(0, 0).size());
        assertEquals(45, WishBagPages.OLDER_SLOT, "the older arrow is bottom-left");
        assertEquals(53, WishBagPages.NEWER_SLOT, "the newer arrow is bottom-right");
    }

    @Test
    void aOnePageBagHasNoArrowsOnlyTheStarAndGlass() {
        List<WishBagPages.Trim> row = WishBagPages.bottomRow(0, 10);
        assertFalse(row.contains(WishBagPages.Trim.OLDER));
        assertFalse(row.contains(WishBagPages.Trim.NEWER));
        assertEquals(WishBagPages.Trim.COUNTER, row.get(WishBagPages.LABEL_SLOT - WishBagPages.PER_PAGE));
        assertEquals(8, row.stream().filter(t -> t == WishBagPages.Trim.GLASS).count());
    }

    @Test
    void anArrowAppearsOnlyWhenThereIsAPageThatWay() {
        int size = 100;                                   // three pages, newest first
        List<WishBagPages.Trim> first = WishBagPages.bottomRow(0, size);
        assertEquals(WishBagPages.Trim.OLDER, first.get(0), "page 1 can go to older wishes");
        assertEquals(WishBagPages.Trim.GLASS, first.get(8), "page 1 is the newest: no newer arrow");
        List<WishBagPages.Trim> middle = WishBagPages.bottomRow(1, size);
        assertEquals(WishBagPages.Trim.OLDER, middle.get(0));
        assertEquals(WishBagPages.Trim.NEWER, middle.get(8));
        List<WishBagPages.Trim> last = WishBagPages.bottomRow(2, size);
        assertEquals(WishBagPages.Trim.GLASS, last.get(0), "the last page has nothing older");
        assertEquals(WishBagPages.Trim.NEWER, last.get(8));
    }

    @Test
    void pressingAnArrowTurnsThePageAndAnythingElseDoesNot() {
        int size = 100;
        assertEquals(1, WishBagPages.turn(WishBagPages.OLDER_SLOT, 0, size), "older = a later page");
        assertEquals(0, WishBagPages.turn(WishBagPages.NEWER_SLOT, 1, size), "newer = an earlier page");
        assertEquals(0, WishBagPages.turn(WishBagPages.NEWER_SLOT, 0, size), "no page newer than the first");
        assertEquals(2, WishBagPages.turn(WishBagPages.OLDER_SLOT, 2, size), "no page older than the last");
        assertEquals(1, WishBagPages.turn(WishBagPages.LABEL_SLOT, 1, size), "the star is not a button");
        assertEquals(1, WishBagPages.turn(47, 1, size), "glass is not a button");
    }
}
