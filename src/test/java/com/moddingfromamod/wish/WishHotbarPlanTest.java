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
    void aFullHotbarSendsThePinToTheWishBagAndAsksOnlyWhenTheBagCannotTakeIt() {
        // #189: "Which slot?" read as the pin going nowhere
        assertEquals(WishHotbarPlan.Step.WISH_BAG, WishHotbarPlan.add(hotbar(), true, true).step());
        assertEquals(WishHotbarPlan.Step.WISH_BAG, WishHotbarPlan.add(hotbar(), false, true).step());
        assertEquals(-1, WishHotbarPlan.add(hotbar(), true, true).slot());
        assertEquals(new WishHotbarPlan.Plan(WishHotbarPlan.Step.PLACE, 3), WishHotbarPlan.add(hotbar(3), true, true),
                "an empty hotbar slot still comes first");
        assertEquals(WishHotbarPlan.Step.ASK_WHICH, WishHotbarPlan.add(hotbar(), true, false).step(),
                "a Wish Bag that cannot take it asks, as before");
        assertTrue(WishHotbarPlan.inWishBag(false).contains("Wish Bag (slot 2)"), WishHotbarPlan.inWishBag(false));
        assertTrue(WishHotbarPlan.inWishBag(true).contains("already"), WishHotbarPlan.inWishBag(true));
        assertEquals("🎒 Red cat is in your Wish Bag now. Use it to make another one any time!",
                WishHotbarPlan.pinnedInBag("✨ Red cat"));
        assertTrue(WishHotbarPlan.pinnedInBag("").startsWith("🎒 Your new wish is"), WishHotbarPlan.pinnedInBag(""));
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

    private static List<Boolean> inventory(int... taken) {
        List<Boolean> out = new ArrayList<>(Collections.nCopies(41, true));
        for (int i : taken) {
            out.set(i, false);
        }
        return out;
    }

    private static List<Boolean> full(int... free) {
        List<Boolean> out = new ArrayList<>(Collections.nCopies(41, false));
        for (int i : free) {
            out.set(i, true);
        }
        return out;
    }

    @Test
    void knockedOutWishesComeBackToTheirOwnSlots() {
        assertEquals(List.of(2, 6), WishHotbarPlan.restore(inventory(0, 1), List.of(2, 6)));
        assertEquals(List.of(), WishHotbarPlan.restore(inventory(), List.of()));
        assertEquals(List.of(40), WishHotbarPlan.restore(inventory(), List.of(40)), "the off hand is a slot too");
    }

    @Test
    void aTakenSlotSendsItsWishToTheFirstFreeOneWithoutPushingAnotherOut() {
        // slot 2 was filled meanwhile; slot 0 is free but slot 0 is also the second wish's own, so 2's goes to 1
        assertEquals(List.of(1, 0), WishHotbarPlan.restore(inventory(2), List.of(2, 0)));
        // the hotbar is full: the bag takes it
        assertEquals(List.of(9), WishHotbarPlan.restore(inventory(0, 1, 2, 3, 4, 5, 6, 7, 8), List.of(4)));
        // two wishes that say the same slot (a broken stash) never share it
        assertEquals(List.of(3, 0), WishHotbarPlan.restore(inventory(), List.of(3, 3)));
        // a slot that does not exist is treated as taken
        assertEquals(List.of(0), WishHotbarPlan.restore(inventory(), List.of(99)));
    }

    @Test
    void withNoFreeSlotAWishIsNotPlacedAndNeverGoesToArmour() {
        assertEquals(List.of(5, -1), WishHotbarPlan.restore(full(5), List.of(5, 1)));
        // only an armour slot is free: armour is not where a wish goes
        assertEquals(List.of(-1), WishHotbarPlan.restore(full(37), List.of(1)));
    }

    @Test
    void afterAFireTheSweepIsTheRestTheChargeNamesElseOneSecond() {
        int core = 20;
        assertEquals(200, WishHotbarPlan.shownRest(true, 200, core), "a fire it allowed: the rest it names");
        assertEquals(140, WishHotbarPlan.shownRest(true, 140, core), "refused while resting: the time left, not fresh");
        assertEquals(0, WishHotbarPlan.shownRest(true, 0, core), "refused for another reason, ready: no sweep");
        assertEquals(core, WishHotbarPlan.shownRest(true, -1, core), "a charge that does not say: the one second");
        assertEquals(core, WishHotbarPlan.shownRest(true, -7, core), "any negative is 'does not say'");
        assertEquals(WishHotbarPlan.MAX_REST_TICKS, WishHotbarPlan.shownRest(true, Integer.MAX_VALUE, core),
                "a huge answer is capped, so the game's clock never overflows");
    }

    @Test
    void givenAgainOnlyARestTheChargeNamesIsPutBack() {
        int core = 20;
        assertEquals(130, WishHotbarPlan.shownRest(false, 130, core), "re-added mid-rest: what is left");
        assertEquals(-1, WishHotbarPlan.shownRest(false, 0, core), "ready: the game's own cooldown is left alone");
        assertEquals(-1, WishHotbarPlan.shownRest(false, -1, core), "no answer: left alone, never a fresh second");
    }

    @Test
    void aHotbarProxyAsksAboutAReplayOfItsRecipe() {
        java.util.UUID him = java.util.UUID.randomUUID();
        WishAsk ask = WishAsk.replay(him, "fire_sword");
        assertEquals(him, ask.player());
        assertEquals(WishSource.PRESET, ask.source());
        assertEquals("fire_sword", ask.recipe());
        assertEquals(WishAsk.Magic.REPLAY, ask.magic());
        WishAsk typed = WishAsk.of(him, WishSource.TYPED, WishAsk.Magic.APPRENTICE);
        assertEquals(WishAsk.Magic.APPRENTICE, typed.magic(), "the magic is the one named, never a default");
        assertEquals("", typed.recipe(), "no recipe is the empty id, never null");
        assertEquals("", typed.withRecipe(null).recipe());
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> WishAsk.of(him, WishSource.TYPED, null), "the kind of magic is required");
    }
}
