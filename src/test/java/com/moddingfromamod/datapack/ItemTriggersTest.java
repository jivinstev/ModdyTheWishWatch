package com.moddingfromamod.datapack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the trigger-compatibility table. The cases are the plan's groups
 * (docs/ITEM_TRIGGERS_PLAN.md §6); {@code tools/test-parts-coverage.py} asserts the same pairs from
 * the Python side, so the two readers of {@code items/trigger-compat.json} cannot disagree.
 */
class ItemTriggersTest {

    @Test
    void aWishMayNotTakeOverWhatTheBaseAlreadyDoes() {
        // group A: each of these is the laser-bow bug in another item.
        assertConflict("minecraft:shield", "held", "block", "on_block");
        assertConflict("minecraft:cookie", "held", "eat", "on_consume");
        assertConflict("minecraft:potion", "held", "drink", "on_consume");
        assertConflict("minecraft:snowball", "thrown", "throw", "on_throw_land");
        assertConflict("minecraft:ender_pearl", "held", "throw", "on_throw_land");
        assertConflict("minecraft:goat_horn", "held", "toot", "on_use_done");
        assertConflict("minecraft:brush", "held", "brush", "on_use_done");
        assertConflict("minecraft:water_bucket", "held", "bucket", null);
    }

    @Test
    void aBowRidesItsOwnShot() {
        // group B, and the laser bow itself: `held` on a bow cancels the draw; `arrow` rides it.
        assertConflict("minecraft:bow", "held", "draw", "arrow");
        assertConflict("minecraft:crossbow", "thrown", "draw", "arrow");
        assertConflict("minecraft:trident", "held", "spear", "arrow");
        for (String b : List.of("minecraft:bow", "minecraft:crossbow", "minecraft:trident")) {
            assertTrue(ItemTriggers.check(b, "arrow").ok(), b);
        }
        assertEquals(List.of("minecraft:bow", "minecraft:crossbow", "minecraft:trident"),
                ItemTriggers.basesFor("arrow"));
    }

    @Test
    void anArrowNeedsSomethingThatShoots() {
        ItemTriggers.Verdict v = ItemTriggers.check("minecraft:stick", "arrow");
        assertFalse(v.ok());
        assertNull(v.redirect());
        assertTrue(v.reason().contains("minecraft:bow"), v.reason());
    }

    @Test
    void aThingThatDoesNothingOfItsOwnMayTakeTheClick() {
        // group D: wands and swords on held, tools on on_mine, armour worn.
        assertOk("minecraft:stick", "held");
        assertOk("minecraft:diamond_sword", "held");
        assertOk("minecraft:diamond_sword", "on_hit");
        assertOk("minecraft:diamond_pickaxe", "on_mine");
        assertOk("minecraft:diamond_boots", "worn_feet");
        assertOk("minecraft:torch", "held");
        assertOk("minecraft:feather", "thrown");
    }

    @Test
    void armourIsPutOnNotClicked() {
        assertEquals("equip_feet", ItemTriggers.actionOf("minecraft:golden_boots"));
        assertConflict("minecraft:diamond_helmet", "held", "equip_head", "worn_head");
        assertConflict("minecraft:elytra", "held", "equip_chest", "worn_chest");
    }

    @Test
    void placedAndMachineNeverRunOnARealBase() {
        assertConflict("minecraft:stick", "placed", ItemTriggers.NONE, "held");
        assertConflict("minecraft:cookie", "machine", "eat", "on_consume");
        assertConflict("minecraft:bucket", "placed", "bucket", null);
    }

    @Test
    void aWishFromScratchHasNothingToTakeOver() {
        assertOk(null, "held");
        assertOk("", "placed");
        assertOk(ItemBase.WISH_ITEM, "machine");
    }

    @Test
    void aNamedItemBeatsItsSuffixAndIdsAreNormalised() {
        assertEquals("drink", ItemTriggers.actionOf("minecraft:milk_bucket"));
        assertEquals("bucket", ItemTriggers.actionOf("minecraft:axolotl_bucket"));
        assertEquals("eat", ItemTriggers.actionOf("Cookie"));
        assertEquals("use", ItemTriggers.actionOf("minecraft:cow_spawn_egg"));
        assertEquals(ItemTriggers.NONE, ItemTriggers.actionOf("othermod:cookie_boots"));
    }

    @Test
    void foodOrDrinkFromScratchIsARealOneChosenByItsIcon() {
        assertEquals("minecraft:potion", ItemTriggers.defaultBase("on_consume", "cup"));
        assertEquals("minecraft:potion", ItemTriggers.defaultBase("on_consume", "Bottle"));
        assertEquals("minecraft:cookie", ItemTriggers.defaultBase("on_consume", "food_round"));
        assertEquals("minecraft:cookie", ItemTriggers.defaultBase("on_consume", null));
        assertNull(ItemTriggers.defaultBase("held", "cup"));
        assertTrue(ItemTriggers.check(ItemTriggers.defaultBase("on_consume", "cup"), "on_consume").ok());
        assertTrue(ItemTriggers.check(ItemTriggers.defaultBase("on_consume", "wand"), "on_consume").ok());
    }

    private static void assertConflict(String base, String shape, String action, String redirect) {
        ItemTriggers.Verdict v = ItemTriggers.check(base, shape);
        assertFalse(v.ok(), base + " + " + shape + " should conflict");
        assertEquals(action, v.action(), base);
        assertEquals(redirect, v.redirect(), base + " + " + shape);
        assertFalse(v.reason().isBlank());
    }

    private static void assertOk(String base, String shape) {
        ItemTriggers.Verdict v = ItemTriggers.check(base, shape);
        assertTrue(v.ok(), base + " + " + shape + ": " + v.reason());
    }
}
