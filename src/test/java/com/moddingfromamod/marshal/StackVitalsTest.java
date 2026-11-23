package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.item.ItemDesignId;
import com.moddingfromamod.safety.SafetyLexicon;
import org.junit.jupiter.api.Test;

/** an item card read off the stack itself, for a thing with no design to say it. */
class StackVitalsTest {

    private static StackVitals.Facts pickaxe() {
        Map<String, Integer> ench = new LinkedHashMap<>();
        ench.put("minecraft:efficiency", 5);
        ench.put("minecraft:fortune", 3);
        return new StackVitals.Facts("minecraft:netherite_pickaxe", false, ench, true, 6.0, null);
    }

    @Test
    void theBestPickaxeEverSaysWhatItIsAndDoes() {
        assertEquals("a netherite pickaxe", StackVitals.whatItIs(pickaxe()));
        assertEquals("Mines super fast (level 5), more drops from ores (level 3), never breaks!",
                StackVitals.whatItDoes(pickaxe()));
        assertEquals(List.of("3 hearts of damage"), StackVitals.stats(pickaxe()));
    }

    @Test
    void aSwordHitsInHearts() {
        var sword = new StackVitals.Facts("minecraft:iron_sword", false,
                Map.of("minecraft:sharpness", 5), false, 7.0, null);
        assertEquals("an iron sword", StackVitals.whatItIs(sword));
        assertEquals("Hits extra hard (level 5)!", StackVitals.whatItDoes(sword));
        assertEquals(List.of("3.5 hearts of damage"), StackVitals.stats(sword));
    }

    @Test
    void anUnknownEnchantmentIsItsOwnNameAndLevel() {
        assertEquals("Zap Power level 2", StackVitals.enchantment("othermod:zap_power", 2));
        assertEquals("Zap Power", StackVitals.enchantment("othermod:zap_power", 1));
        // a single-level enchantment never says "(level 1)"
        assertEquals("picks up blocks whole", StackVitals.enchantment("minecraft:silk_touch", 1));
        assertEquals("mines super fast", StackVitals.enchantment("minecraft:efficiency", 1));
    }

    @Test
    void aCarrierIsNotTheThing() {
        var carrier = new StackVitals.Facts("minecraft:leather_horse_armor", true, Map.of(), false,
                null, 0xff0000);
        assertNull(StackVitals.whatItIs(carrier), "our horse-armour carrier says nothing about it");
        assertEquals("", StackVitals.whatItDoes(carrier));
        assertEquals(List.of(), StackVitals.stats(carrier));
        assertEquals("red", StackVitals.colour(carrier, rgb -> rgb == 0xff0000 ? "red" : "?"));
        assertNull(StackVitals.whatItIs(new StackVitals.Facts("moddingfromamod:wish_item", false,
                Map.of(), false, null, null)));
    }

    @Test
    void aBareHandIsNoWeapon() {
        var stick = new StackVitals.Facts("minecraft:stick", false, Map.of(), false, 1.0, null);
        assertEquals(List.of(), StackVitals.stats(stick), "1 point is a bare hand, not damage");
        assertNull(StackVitals.colour(stick, rgb -> "x"));
    }

    @Test
    void everyPhraseIsSafeForAChild() {
        SafetyLexicon lex = SafetyLexicon.shared();
        List<String> lines = new ArrayList<>(StackVitals.PHRASES.values());
        lines.add(StackVitals.whatItDoes(pickaxe()));
        for (String id : StackVitals.PHRASES.keySet()) {
            lines.add(StackVitals.enchantment("minecraft:" + id, 3));
        }
        for (String l : lines) {
            assertFalse(l.isBlank());
            assertTrue(lex.check(l).isEmpty(), "the lexicon flags an item card line: " + l);
        }
    }

    @Test
    void aBehaviourKeyNamesItsDesignByItsNamespace() {
        assertEquals(List.of("cake_fork_heal"), ItemDesignId.candidates("id:cake_fork_heal"));
        assertEquals(List.of("best_mining_pickaxe_ever_plain"),
                ItemDesignId.candidates("id:best_mining_pickaxe_ever_plain"));
        assertEquals(List.of("player_tracker:track", "player_tracker"),
                ItemDesignId.candidates("id:player_tracker:track"));
        assertEquals(List.of("zonk_wand_held", "zonk_wand_held"),
                List.of(ItemDesignId.candidates("id:zonk_wand_held:act").get(1),
                        ItemDesignId.candidates("id:zonk_wand_held").get(0)));
        assertEquals(List.of(), ItemDesignId.candidates("trophy#12#{}"),
                "a keepsake key names no design");
    }
}
