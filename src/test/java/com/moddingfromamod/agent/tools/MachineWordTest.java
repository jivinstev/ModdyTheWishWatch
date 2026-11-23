package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * which item NAMES make_item questions as machines. Measured: "Popcorn Machine" was made as
 * a plain item he holds. The check is a mechanism word, never a product, so it cannot drift into
 * naming the wishes it was tuned on (C17.1).
 */
class MachineWordTest {

    @Test
    void aNameThatSaysMachineIsQuestioned() {
        assertEquals("machine", MakeItemTool.machineWord("Popcorn Machine"));
        assertEquals("maker", MakeItemTool.machineWord("Juice Maker"));
        assertEquals("factory", MakeItemTool.machineWord("the SLIME factory"));
        assertEquals("facktery", MakeItemTool.machineWord("a glowing facktery"), "the way he spells it");
        assertEquals("mashine", MakeItemTool.machineWord("a candy mashine"));
    }

    @Test
    void aNameThatOnlyContainsTheLettersIsNot() {
        assertNull(MakeItemTool.machineWord("Big Tub of Popcorn"));
        assertNull(MakeItemTool.machineWord("Machinery Gloves"));
        assertNull(MakeItemTool.machineWord("Troublemaker Wand"));
        assertNull(MakeItemTool.machineWord(null));
    }
}
