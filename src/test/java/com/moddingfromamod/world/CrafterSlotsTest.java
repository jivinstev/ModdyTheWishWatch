package com.moddingfromamod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** step 6: 20 at once, the last 5 kept for Wish Magicians, nobody bumped. */
class CrafterSlotsTest {

    static CrafterSlots.Decision d(boolean listed, int crafters, int online) {
        return CrafterSlots.decide(true, listed, false, crafters, online, 20, 5);
    }

    @Test
    void aCrafterGetsInWhileACrafterSlotIsFree() {
        assertEquals(CrafterSlots.Decision.ADMIT, d(false, 0, 0));
        assertEquals(CrafterSlots.Decision.ADMIT, d(false, 14, 14));
    }

    @Test
    void theSixteenthCrafterIsTurnedAwaySoFiveMagiciansAlwaysFit() {
        assertEquals(CrafterSlots.Decision.CRAFTERS_FULL, d(false, 15, 15));
        assertEquals(CrafterSlots.Decision.CRAFTERS_FULL, d(false, 5, 20), "a full server");
    }

    @Test
    void aMagicianAnOperatorAndAShutDoorAreVanillas() {
        assertEquals(CrafterSlots.Decision.VANILLA, d(true, 15, 15));
        assertEquals(CrafterSlots.Decision.VANILLA, CrafterSlots.decide(true, false, true, 15, 15, 20, 5));
        assertEquals(CrafterSlots.Decision.VANILLA, CrafterSlots.decide(false, false, false, 0, 0, 20, 5));
    }

    @Test
    void theWordsNameTheWorldAndSayAMagicianFits() {
        String s = CrafterSlots.full("Pebble Bay", "account.moddythewishwatch.com");
        assertTrue(s.startsWith("Pebble Bay's Wish Crafter spots are all taken"), s);
        assertTrue(s.contains("Wish Magicians always have room: account.moddythewishwatch.com"), s);
    }
}
