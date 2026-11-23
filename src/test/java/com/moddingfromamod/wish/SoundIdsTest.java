package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The sound-id corrector —, the phoenix whose rebirth never loaded. */
class SoundIdsTest {

    private static String fix(String text, List<String> notes) {
        return SoundIds.correct(text, notes);
    }

    @Test
    @DisplayName("the phoenix's own lines: every extra colon becomes a dot, and the agent is told")
    void thePhoenixLines() {
        List<String> notes = new ArrayList<>();
        String src = "playsound minecraft:entity:phoenix:ambient master @a ~ ~ ~ 1 1\n"
                + "execute at @s run playsound minecraft:block:fire:extinguish master @a ~ ~ ~ 1 0.5";
        assertEquals("playsound minecraft:entity.phoenix.ambient master @a ~ ~ ~ 1 1\n"
                + "execute at @s run playsound minecraft:block.fire.extinguish master @a ~ ~ ~ 1 0.5",
                fix(src, notes));
        assertEquals(2, notes.size(), notes.toString());
        assertTrue(notes.get(0).contains("minecraft:entity.phoenix.ambient"), notes.toString());
    }

    @Test
    @DisplayName("a vanilla sound root where the namespace goes is given minecraft:")
    void aRootWithoutItsNamespace() {
        assertEquals("playsound minecraft:entity.pig.ambient master @a",
                fix("playsound entity:pig:ambient master @a", new ArrayList<>()));
    }

    @Test
    @DisplayName("a mod's namespace is kept: only the colons after it change")
    void aModNamespaceIsKept() {
        assertEquals("playsound create:mixing.loop block @a", fix("playsound create:mixing:loop block @a", new ArrayList<>()));
    }

    @Test
    @DisplayName("stopsound's sound slot is fixed; its source and the * wildcard are not touched")
    void stopsound() {
        assertEquals("stopsound @a master minecraft:music.creative",
                fix("stopsound @a master minecraft:music:creative", new ArrayList<>()));
        assertEquals("stopsound @a * minecraft:music.creative",
                fix("stopsound @a * minecraft:music.creative", new ArrayList<>()));
    }

    @Test
    @DisplayName("upper case is lowered, since a resource location is lower case only")
    void upperCase() {
        assertEquals("playsound minecraft:entity.pig.ambient master @a",
                fix("playsound minecraft:Entity.Pig.Ambient master @a", new ArrayList<>()));
    }

    @Test
    @DisplayName("a sound that already works is left byte for byte, and says nothing")
    void aWorkingCommandIsLeftAlone() {
        List<String> notes = new ArrayList<>();
        for (String cmd : List.of(
                "playsound minecraft:entity.generic.explode master @a ~ ~ ~ 1 1.2",
                "playsound entity.pig.ambient master @s",
                "playsound $(sound) master @a",
                "say playsound is fun: a:b:c",
                "tellraw @a \"no sound here\"")) {
            assertEquals(cmd, fix(cmd, notes));
        }
        assertTrue(notes.isEmpty(), notes.toString());
    }

    @Test
    @DisplayName("CommandDialect runs it, so both choke points (commands and datapacks) get it")
    void theDialectRunsIt() {
        var fixed = CommandDialect.correct("playsound minecraft:entity:phoenix:ambient master @a", id -> true);
        assertEquals("playsound minecraft:entity.phoenix.ambient master @a", fixed.command());
        assertEquals(1, fixed.notes().size());
    }
}
