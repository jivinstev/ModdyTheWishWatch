package com.moddingfromamod.datapack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** a parameter that is a list, picked from each use. */
class PickListTest {

    @Test
    void theModeIsReadOffTheFrontAndABlockIdIsNotAMode() {
        assertEquals("cycle", PickList.modeOf("cycle: minecraft:red_wool, minecraft:blue_wool"));
        assertEquals("random", PickList.modeOf(" RANDOM:minecraft:poppy"));
        assertNull(PickList.modeOf("minecraft:red_wool"), "a namespace is not a mode");
        assertNull(PickList.modeOf("minecraft:red_wool, minecraft:blue_wool"));
        assertEquals(List.of("minecraft:red_wool", "minecraft:blue_wool"),
                PickList.items(PickList.withoutMode("cycle: minecraft:red_wool, , minecraft:blue_wool ")));
    }

    @Test
    void oneBlockRendersExactlyTheLineItAlwaysDid() {
        assertEquals("    /fill ~ ~ ~ ~ ~ ~ minecraft:stone replace #x",
                PickList.render(List.of("fill ~ ~ ~ ~ ~ ~ minecraft:stone replace #x"), "random"));
    }

    @Test
    void aCycleCountsWrapsAndPicksByIndex() {
        String out = PickList.render(List.of("setblock ~ ~ ~ a", "setblock ~ ~ ~ b", "setblock ~ ~ ~ c"), "cycle");
        assertTrue(out.contains(" mfam_pick 0\n"), "an unset counter is started at 0: " + out);
        assertTrue(out.indexOf("matches 0 run") < out.indexOf("mfam_pick 1\n"),
                "the pick happens BEFORE the step, so the first use is the first colour: " + out);
        assertTrue(out.contains("matches 3.. run scoreboard players set #pick_"), out);
        assertTrue(out.contains("matches 0 run setblock ~ ~ ~ a") && out.contains("matches 2 run setblock ~ ~ ~ c"), out);
        assertTrue(!out.contains("random value"), out);
        for (String line : out.split("\n")) {
            assertTrue(line.startsWith("    /"), "every line is a function command: " + line);
        }
    }

    @Test
    void randomRollsOverTheWholeList() {
        String out = PickList.render(List.of("x a", "x b"), "random");
        assertTrue(out.contains("run random value 0..1"), out);
    }

    @Test
    void theCounterIsTheListsOwn() {
        String a = PickList.key(List.of("a", "b"), "cycle");
        assertEquals(a, PickList.key(List.of("a", "b"), "cycle"), "same list, same counter");
        assertNotEquals(a, PickList.key(List.of("b", "a"), "cycle"));
        assertTrue(a.matches("pick_[0-9a-f]{10}"), a);
    }
}
