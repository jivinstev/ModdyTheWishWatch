package com.moddingfromamod.item.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.moddingfromamod.item.delivery.DeliveryWords.Landed;
import com.moddingfromamod.item.delivery.DeliveryWords.Where;
import org.junit.jupiter.api.Test;

/** What Moddy says about a hand-over — the specified sentences, exactly. */
class DeliveryWordsTest {

    @Test
    void theOwnersExampleWordForWord() {
        String line = DeliveryWords.forChild(List.of(
                new Landed("Pineapple Paradise", 1, Where.HOTBAR, 8),
                new Landed("Mango Splash", 1, Where.HOTBAR, 9),
                new Landed("Tropical Oasis Bar", 1, Where.BAG, 0),
                new Landed("Melon Slice", 32, Where.BAG, 0)), "your oasis");
        assertEquals("Pineapple Paradise is in slot 8, Mango Splash in slot 9, and the Tropical Oasis Bar"
                + " and 32 Melon Slices are in your Wish Bag!", line);
    }

    @Test
    void oneThingInOneSlot() {
        assertEquals("Hot Coffee is in slot 3!", DeliveryWords.forChild(
                List.of(new Landed("Hot Coffee", 1, Where.HOTBAR, 3)), null));
        assertEquals("16 Cocoa Beans are in slot 4!", DeliveryWords.forChild(
                List.of(new Landed("Cocoa Beans", 16, Where.HOTBAR, 4)), null));
    }

    @Test
    void oneThingInTheBag() {
        assertEquals("the Tracker is in your Wish Bag!", DeliveryWords.forChild(
                List.of(new Landed("Tracker", 1, Where.BAG, 0)), null));
    }

    @Test
    void oneThingSplitAcrossTwoSlotsAndTheBag() {
        assertEquals("128 Diamonds are in slots 8–9, and 72 Diamonds are in your Wish Bag!",
                DeliveryWords.forChild(List.of(
                        new Landed("Diamond", 64, Where.HOTBAR, 8),
                        new Landed("Diamond", 64, Where.HOTBAR, 9),
                        new Landed("Diamond", 72, Where.BAG, 0)), null));
    }

    @Test
    void aLongListIsGrouped() {
        String line = DeliveryWords.forChild(List.of(
                new Landed("Pineapple Paradise", 1, Where.HOTBAR, 8),
                new Landed("Mango Splash", 1, Where.HOTBAR, 9),
                new Landed("Tropical Oasis Bar", 1, Where.BAG, 0),
                new Landed("Melon Slice", 32, Where.BAG, 0),
                new Landed("Cocoa Beans", 16, Where.BAG, 0),
                new Landed("Ice", 8, Where.BAG, 0)), "your oasis");
        assertEquals("6 things from your oasis: 2 in slots 8–9, 4 in your Wish Bag!", line);
    }

    @Test
    void scatteredSlotsAreListed() {
        assertEquals("slots 3, 5 and 8", DeliveryWords.slots(new java.util.TreeSet<>(List.of(8, 3, 5))));
    }

    @Test
    void theAgentIsToldEverythingNeverGrouped() {
        String agent = DeliveryWords.forAgent(List.of(
                new Landed("Pineapple Paradise", 1, Where.HOTBAR, 8),
                new Landed("Melon Slice", 32, Where.BAG, 0)));
        assertTrue(agent.startsWith("Handed over: Pineapple Paradise → hotbar slot 8; 32 × Melon Slice"
                + " → his Wish Bag."), agent);
    }

    @Test
    void nothingLandedSaysNothing() {
        assertEquals("", DeliveryWords.forChild(List.of(), null));
        assertEquals("", DeliveryWords.forAgent(List.of()));
    }

    @Test
    void theWhereLineForACallerWithItsOwnGreeting() {
        assertEquals("It's in slot 4!", DeliveryWords.where(List.of(new Landed("Egg", 1, Where.HOTBAR, 4))));
        assertEquals("It's in your Wish Bag!", DeliveryWords.where(List.of(new Landed("Egg", 1, Where.BAG, 0))));
    }

    @Test
    void plurals() {
        assertEquals("Melon Slices", DeliveryWords.pluralOf("Melon Slice"));
        assertEquals("Cocoa Beans", DeliveryWords.pluralOf("Cocoa Beans"));
        assertEquals("Berries", DeliveryWords.pluralOf("Berry"));
        assertEquals("Torches", DeliveryWords.pluralOf("Torch"));
        assertEquals("Keys", DeliveryWords.pluralOf("Key"));
    }

    @Test
    void aBehaviourGiveIsReportedToTheAgentAsTheThingsOwnNotAHandOver() {
        String agent = DeliveryWords.forAgent(List.of(
                new Landed("Cookie", 8, Where.INVENTORY, 0),
                new Landed("Cookie", 4, Where.FLOOR, 0)));
        assertTrue(agent.startsWith("Given by the thing itself"), agent);
        assertTrue(agent.contains("8 × Cookie → his inventory"), agent);
        assertTrue(
                agent.contains("4 × Cookie → on the ground at his feet (his inventory is full)"), agent);
        assertFalse(agent.contains("Moddy told him"), agent);
    }
}
