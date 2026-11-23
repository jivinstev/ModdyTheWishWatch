package com.moddingfromamod.datapack;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the wishes vanilla already grants, given the right real item. The facts each one
 * stands on are checked against the running game by {@code VanillaNativeGameTest}; this checks the
 * table is whole and says only things make_item can build.
 */
class VanillaNativeTest {

    @Test
    void everyWayIsAPartE1CanCount() {
        assertEquals(VanillaNative.WAYS.stream().map(VanillaNative.Way::part).collect(Collectors.toSet()),
                VanillaNative.PARTS, "PARTS is what E1 reads; it must be exactly the table's parts");
    }

    @Test
    void everyWayIsSomethingMakeItemCanBuild() {
        for (VanillaNative.Way w : VanillaNative.WAYS) {
            assertTrue(w.baseItem().startsWith("minecraft:"), w.part());
            if (w.enchantment() != null) {
                assertTrue(ItemProperties.ENCHANTMENTS.containsKey(w.enchantment()),
                        w.part() + ": make_item cannot put " + w.enchantment() + " on anything");
            }
            assertTrue(!w.tellHim().isBlank(), w.part() + ": the child must be told what he got");
        }
    }

    @Test
    void theGuidanceNamesEveryWay() {
        String g = VanillaNative.guidance();
        for (VanillaNative.Way w : VanillaNative.WAYS) {
            assertTrue(g.contains(w.baseItem()), g);
        }
    }
}
