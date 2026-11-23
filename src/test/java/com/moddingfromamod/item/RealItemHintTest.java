package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Q1: which made-item names are really a real item that does something. */
class RealItemHintTest {

    private static String base(String name) {
        return RealItemHint.of(name, null, false, false).map(RealItemHint.Real::baseItem).orElse("");
    }

    @Test
    void theSixMuseShortfallsAreEachCaught() {
        assertEquals("minecraft:diamond_pickaxe", base("Auto-Smelt Pickaxe"));
        assertEquals("minecraft:crossbow", base("Rocket Crossbow"));
        assertEquals("minecraft:trident", base("Mob Magnet Trident"));
        assertEquals("minecraft:bow", base("Egg Bow"), "a launcher, not its ammunition");
        assertEquals("minecraft:snowball", base("Snow Golem Snowball"));
        assertEquals("minecraft:goat_horn", base("Pet Call Horn"));
    }

    @Test
    void wordsInsideOtherWordsAreNotItems() {
        assertEquals("", base("Rainbow Paint Brush"), "rainBOW is not a bow");
        assertEquals("", base("Magic Wand"));
        assertEquals("", base("Pearl Necklace"), "a pearl alone is not an ender pearl");
    }

    @Test
    void aBaseAnInheritanceOrAnExplicitLookAlikeIsLeftAlone() {
        assertTrue(RealItemHint.of("Egg Bow", "minecraft:bow", false, false).isEmpty());
        assertTrue(RealItemHint.of("Egg Bow", null, true, false).isEmpty(), "start_from inherits the base");
        assertTrue(RealItemHint.of("Egg Bow", null, false, true).isEmpty(), "only_looks_like is the way past");
    }

    @Test
    void theRefusalNamesTheBaseAndTheWayPast() {
        String r = RealItemHint.refusal("Egg Bow", RealItemHint.of("Egg Bow", null, false, false).orElseThrow());
        assertTrue(r.contains("base_item \"minecraft:bow\""), r);
        assertTrue(r.contains("only_looks_like: true"), r);
    }

    @Test
    void aMadeFoodOrDrinkIsClickedNotEatenUnlessItIsOnConsume() {
        assertEquals("minecraft:apple", base("Magic Apple"));
        assertEquals("minecraft:potion", base("Tiny Potion"));
        assertTrue(RealItemHint.of("Magic Apple", null, false, false, "on_consume").isEmpty(),
                "on_consume from scratch is built on a real food by make_item itself");
        assertTrue(RealItemHint.of("Egg Bow", null, false, false, "on_consume").isPresent(),
                "only food and drink are excused by on_consume");
    }

    @Test
    void aModelIdNamesTheRealItemItDraws() {
        assertEquals("ender pearl", RealItemHint.forModel("minecraft:ender_pearl").orElseThrow().word());
        assertEquals("pickaxe", RealItemHint.forModel("minecraft:diamond_pickaxe").orElseThrow().word());
        assertTrue(RealItemHint.forModel("wishpack:icon/orb").isEmpty());
    }
}
