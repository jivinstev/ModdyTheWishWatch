package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The decisions behind "a refinement changes the thing in his hands". */
class HeldRestampTest {

    @Test
    void onlyARefinementForTheChildRestamps() {
        assertTrue(HeldRestamp.applies(ItemLineage.Route.EDIT_IN_PLACE, true));
        assertFalse(HeldRestamp.applies(ItemLineage.Route.COPY_THEN_DIVERGE, true),
                "'and one that…' must leave his first one exactly as it was (#311)");
        assertFalse(HeldRestamp.applies(ItemLineage.Route.FRESH, true));
        assertFalse(HeldRestamp.applies(ItemLineage.Route.EDIT_IN_PLACE, false),
                "a thing handed to a creature is in nobody's bag");
    }

    @Test
    void anOwnedDesignReachesOnlyItsOwnersBag() {
        assertTrue(HeldRestamp.mayRestamp("uuid-a", "uuid-a"));
        assertFalse(HeldRestamp.mayRestamp("uuid-a", "uuid-b"),
                "a refinement never touches another child's things");
        assertTrue(HeldRestamp.mayRestamp("", "uuid-b"), "an unowned design predates owner stamps");
        assertTrue(HeldRestamp.mayRestamp(null, "uuid-b"));
    }

    @Test
    void aGiveIsAskedForOnlyWhenNothingWasChangedOrHeAskedForMore() {
        assertFalse(HeldRestamp.stillHandOver(1, null, 1),
                "the one in his hand IS the edit; a give now is how he ends up with two");
        assertFalse(HeldRestamp.stillHandOver(1, 1, 1), "restating the count is not asking for more");
        assertTrue(HeldRestamp.stillHandOver(0, null, 1), "he was not holding one, so hand it over");
        assertTrue(HeldRestamp.stillHandOver(1, 16, 1), "'a whole pile of them' is a request for more");
    }

    @Test
    void aStackBecomesTheMemberItCanBeToldApartAs() {
        // a rename of the one thing
        assertEquals(0, HeldRestamp.memberFor("Zap Wand", List.of("Zap Wand"), List.of("Zap Wand MEGA")));
        // a stack of something ELSE that happens to share nothing is left alone
        assertEquals(-1, HeldRestamp.memberFor("Older Name", List.of("Zap Wand"), List.of("Zap Wand MEGA")));
        // a flavour that kept its name is that flavour, wherever it moved to
        assertEquals(1, HeldRestamp.memberFor("grape",
                List.of("Grape", "Cherry"), List.of("Cherry", "Grape")));
        // every member renamed, same size: by position
        assertEquals(1, HeldRestamp.memberFor("Cherry",
                List.of("Grape", "Cherry"), List.of("Grape Fizz", "Cherry Fizz")));
        // sizes differ and the name is gone: cannot tell, so leave it
        assertEquals(-1, HeldRestamp.memberFor("Cherry",
                List.of("Grape", "Cherry"), List.of("Grape", "Mint", "Lime")));
    }
}
