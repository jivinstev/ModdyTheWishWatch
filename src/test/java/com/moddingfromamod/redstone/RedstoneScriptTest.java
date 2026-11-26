package com.moddingfromamod.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The naming convention and the engine every driven mechanism shares. */
class RedstoneScriptTest {

    @Test
    void namesFollowTheConvention() {
        assertEquals("rs.vault", RedstoneScript.mechTag("vault"));
        assertEquals("rs.vault.in.2", RedstoneScript.inTag("vault", 2));
        assertEquals("rs.vault.out", RedstoneScript.outTag("vault"));
    }

    @Test
    void theEngineReadsEverySwitchKindAndOnlyChangesAFeedWhenItMust() {
        String s = RedstoneScript.engine();
        assertTrue(s.contains("clock tick"));
        assertTrue(s.contains("function feed()"));
        assertTrue(s.contains("run function moddy_redstone:feed"));
        for (String t : RedstoneScript.ON_TESTS) {
            assertTrue(s.contains(t), t);
        }
        assertTrue(s.contains("if block ~ ~ ~ minecraft:air run setblock ~ ~ ~ minecraft:redstone_block"));
        assertTrue(s.contains("if block ~ ~ ~ minecraft:redstone_block run setblock ~ ~ ~ minecraft:air"));
        RedstoneScript.OBJECTIVES.forEach(o -> assertTrue(s.contains("objectives add " + o + " dummy"), o));
        assertFalse(s.matches("(?s).*setblock -?\\d.*"), "no coordinates");
    }
}
