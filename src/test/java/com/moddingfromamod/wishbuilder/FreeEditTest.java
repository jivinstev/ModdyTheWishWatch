package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Apprentice's free change: the last Apprentice make's thing, once, until his next wish. */
class FreeEditTest {

    private final UUID him = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @BeforeEach
    void clean() {
        FreeEdit.clearAll();
    }

    /** An Apprentice make that stood: its thing is free, for him only, and nothing else of his is. */
    private void madeCat() {
        UUID w = UUID.randomUUID();
        FreeEdit.starting(him, w);
        FreeEdit.anotherWish(him, w);   // the make's OWN wish reaching the book does not end it
        assertTrue(FreeEdit.stood(him, w, "cat"));
    }

    @Test
    void theLastApprenticeMakeIsFreeToChangeOnce() {
        madeCat();
        assertTrue(FreeEdit.isFree(him, "cat"));
        assertFalse(FreeEdit.isFree(him, "dog"), "only that thing");
        assertFalse(FreeEdit.isFree(other, "cat"), "only for him");
        assertTrue(FreeEdit.take(him, "cat"));
        assertFalse(FreeEdit.isFree(him, "cat"), "once");
        assertFalse(FreeEdit.take(him, "cat"));
    }

    @Test
    void anyOtherWishEndsIt() {
        madeCat();
        FreeEdit.anotherWish(him, UUID.randomUUID());
        assertFalse(FreeEdit.isFree(him, "cat"));
    }

    @Test
    void someoneElsesWishDoesNot() {
        madeCat();
        FreeEdit.anotherWish(other, UUID.randomUUID());
        assertTrue(FreeEdit.isFree(him, "cat"));
    }

    @Test
    void leavingEndsIt() {
        madeCat();
        FreeEdit.forget(him);
        assertFalse(FreeEdit.isFree(him, "cat"));
    }

    @Test
    void aWishWhileTheMakeIsOnItsWayMeansNothingIsFree() {
        UUID w = UUID.randomUUID();
        FreeEdit.starting(him, w);
        FreeEdit.anotherWish(him, UUID.randomUUID());
        assertFalse(FreeEdit.stood(him, w, "cat"));
        assertFalse(FreeEdit.isFree(him, "cat"));
    }

    @Test
    void aMakeThatDidNotStandFreesNothing() {
        UUID w = UUID.randomUUID();
        FreeEdit.starting(him, w);
        FreeEdit.failed(him, w);
        assertFalse(FreeEdit.stood(him, w, "cat"));
        assertFalse(FreeEdit.isFree(him, "cat"));
    }

    @Test
    void aNewApprenticeMakeMovesItToTheNewThing() {
        madeCat();
        UUID w = UUID.randomUUID();
        FreeEdit.starting(him, w);
        assertFalse(FreeEdit.isFree(him, "cat"), "the old one stops being free as soon as the new make starts");
        assertTrue(FreeEdit.stood(him, w, "dragon"));
        assertTrue(FreeEdit.isFree(him, "dragon"));
    }

    @Test
    void aFreeChangeThatFailsComesBackUnlessAnotherWishCame() {
        madeCat();
        // what BuilderMake does with a free change: take it, hold its wish, then settle
        assertTrue(FreeEdit.take(him, "cat"));
        UUID change = UUID.randomUUID();
        FreeEdit.starting(him, change);
        FreeEdit.anotherWish(him, change);
        assertTrue(FreeEdit.stood(him, change, "cat"), "a change that did not stand gives the free change back");
        assertTrue(FreeEdit.isFree(him, "cat"));

        assertTrue(FreeEdit.take(him, "cat"));
        UUID again = UUID.randomUUID();
        FreeEdit.starting(him, again);
        FreeEdit.anotherWish(him, UUID.randomUUID());
        assertFalse(FreeEdit.stood(him, again, "cat"), "…but not past another wish");
    }
}
