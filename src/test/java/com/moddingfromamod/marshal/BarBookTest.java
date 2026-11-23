package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Three wishes, three bars.
 *
 * <p>Steve made three wishes at once and saw ONE bar, showing the first. The progress layer was keyed
 * by player while wishes became parallel, which was three bugs wearing one symptom — and all three
 * are questions about a map, which is why they are answered here in microseconds.
 */
class BarBookTest {

    private final UUID kid = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @Test
    void threeWishesGetThreeBars() {
        // startThinking used to refuse a second plan ("a real plan always wins"), so wishes two and
        // three were silent no-ops and the child was shown one bar for the first thing he asked for.
        var book = new BarBook();
        assertTrue(book.open(a, kid, "your ice dragon"));
        assertTrue(book.open(b, kid, "your castle"));
        assertTrue(book.open(c, kid, "your javelin"));
        assertEquals(3, book.countFor(kid));
    }

    @Test
    void finishingOneWishKeepsTheOthers() {
        // THE WORST OF THE THREE. clearPlan took the child's single shared bar down, so the first
        // wish to finish left two still building with no bar at all — which reads to a seven-year-old
        // exactly like everything having stopped.
        var book = new BarBook();
        book.open(a, kid, "your ice dragon");
        book.open(b, kid, "your castle");
        book.open(c, kid, "your javelin");
        assertTrue(book.close(a));
        assertEquals(2, book.countFor(kid), "finishing one wish closed the others' bars");
        assertTrue(book.isOpen(b) && book.isOpen(c));
    }

    @Test
    void eachBarKeepsItsOwnName() {
        // With three stacked, the name is the only thing telling them apart — the bar text used to
        // be the status alone, which was fine only while there was ever one.
        var book = new BarBook();
        book.open(a, kid, "your ice dragon");
        book.open(b, kid, "your castle");
        assertEquals("your ice dragon", book.nameOf(a));
        assertEquals("your castle", book.nameOf(b));
    }

    @Test
    void aWishCanBeRenamedOnceWeKnowWhatHeMeant() {
        // The bar opens within a tick, before the resolver has decided anything, so it starts out
        // called what he typed and is upgraded to "your ice dragon" a moment later.
        var book = new BarBook();
        book.open(a, kid, "make it bigger");
        book.rename(a, "your ice dragon");
        assertEquals("your ice dragon", book.nameOf(a));
    }

    @Test
    void renamingToNothingKeepsTheNameItHad() {
        // An unnamed bar among three is precisely the confusion the name exists to remove, so a
        // blank must never win.
        var book = new BarBook();
        book.open(a, kid, "your ice dragon");
        book.rename(a, "   ");
        assertEquals("your ice dragon", book.nameOf(a));
    }

    @Test
    void openingTheSameWishTwiceDoesNotRestartIt() {
        var book = new BarBook();
        assertTrue(book.open(a, kid, "your ice dragon"));
        assertFalse(book.open(a, kid, "something else"),
                "a bar that resets every time something touches it looks stuck");
        assertEquals("your ice dragon", book.nameOf(a));
    }

    @Test
    void stoppingEverythingClosesOnlyThisChildsBars() {
        var book = new BarBook();
        book.open(a, kid, "your ice dragon");
        book.open(b, other, "somebody else's castle");
        for (UUID id : book.forPlayer(kid)) {
            book.close(id);
        }
        assertEquals(0, book.countFor(kid));
        assertEquals(1, book.countFor(other), "another player's bar must not come down with his");
    }

    @Test
    void barsStackInTheOrderHeWishedThem() {
        // So the first thing he asked for stays at the top rather than shuffling each time one ends.
        var book = new BarBook();
        book.open(a, kid, "first");
        book.open(b, kid, "second");
        book.open(c, kid, "third");
        assertEquals(java.util.List.of(a, b, c), book.forPlayer(kid));
    }
}
