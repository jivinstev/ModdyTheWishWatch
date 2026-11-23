package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a wish goes on the hotbar, and what its proxy is called. */
class WishHotbarPlanTest {

    private static List<Boolean> hotbar(int... emptySlots) {
        List<Boolean> out = new ArrayList<>(Collections.nCopies(WishHotbarPlan.SLOTS, false));
        for (int i : emptySlots) {
            out.set(i, true);
        }
        return out;
    }

    @Test
    void theFirstEmptySlotTakesIt() {
        assertEquals(new WishHotbarPlan.Plan(WishHotbarPlan.Step.PLACE, 0), WishHotbarPlan.add(hotbar(0, 4), true));
        assertEquals(new WishHotbarPlan.Plan(WishHotbarPlan.Step.PLACE, 4), WishHotbarPlan.add(hotbar(4, 8), false));
        assertEquals(new WishHotbarPlan.Plan(WishHotbarPlan.Step.PLACE, 8), WishHotbarPlan.add(hotbar(8), false));
    }

    @Test
    void aFullHotbarAsksWhichSlotOnlyWhenTheBagCanTakeWhatWasThere() {
        assertEquals(WishHotbarPlan.Step.ASK_WHICH, WishHotbarPlan.add(hotbar(), true).step());
        assertEquals(WishHotbarPlan.Step.NO_ROOM, WishHotbarPlan.add(hotbar(), false).step());
        assertEquals(-1, WishHotbarPlan.add(hotbar(), true).slot());
    }

    @Test
    void aSlotBeyondTheNineIsNeverChosen() {
        List<Boolean> long36 = new ArrayList<>(Collections.nCopies(36, false));
        long36.set(20, true);
        assertEquals(WishHotbarPlan.Step.ASK_WHICH, WishHotbarPlan.add(long36, true).step());
    }

    @Test
    void aCooldownGroupIsStableDistinctAndALegalResourcePath() {
        String a = WishHotbarPlan.group("starter_pet_dragon");
        assertEquals(a, WishHotbarPlan.group("starter_pet_dragon"));
        assertNotEquals(a, WishHotbarPlan.group("starter_pet_dragoN"));
        for (String id : List.of("", "oasis@bar~1f2e3d4c", "A Recipe With Spaces!", "ünï")) {
            assertTrue(WishHotbarPlan.group(id).matches("[a-z0-9/._-]+"), WishHotbarPlan.group(id));
        }
    }

    @Test
    void theNameSaysItIsAWishForTheThing() {
        assertEquals("✨ Wish: Fire Sword", WishHotbarPlan.name("Fire Sword  ✨"));
        assertEquals("✨ Wish: one of your things", WishHotbarPlan.name(" "));
        assertEquals("✨ Wish: one of your things", WishHotbarPlan.name(null));
    }
}
