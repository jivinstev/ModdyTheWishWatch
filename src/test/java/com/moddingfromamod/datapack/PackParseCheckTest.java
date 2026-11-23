package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The pure half of {@link PackParseCheck}: what is a function, and which failures count. */
class PackParseCheckTest {

    @Test
    void onlyTheSingularFunctionFolderIsAFunction() {
        assertEquals("on_step_x:tick", PackParseCheck.functionId("data/on_step_x/function/tick.mcfunction"));
        assertEquals("a:sub/dir/f", PackParseCheck.functionId("data/a/function/sub/dir/f.mcfunction"));
        // 1.21 ignores the plural folder: refusing a pack over a file the server never reads would be wrong.
        assertNull(PackParseCheck.functionId("data/a/functions/tick.mcfunction"));
        assertNull(PackParseCheck.functionId("data/minecraft/tags/function/tick.json"));
    }

    @Test
    void dataACommandIsResolvedAgainstWhileParsing() {
        assertTrue(PackParseCheck.isParseTimeData("data/a/predicate/p.json"));
        assertTrue(PackParseCheck.isParseTimeData("data/a/loot_table/l.json"));
        assertTrue(PackParseCheck.isParseTimeData("data/a/item_modifier/m.json"));
        assertTrue(PackParseCheck.isParseTimeData("data/a/tags/entity_type/monsters.json"));
        assertFalse(PackParseCheck.isParseTimeData("data/minecraft/tags/function/tick.json"));
        assertFalse(PackParseCheck.isParseTimeData("data/a/function/tick.mcfunction"));
        assertFalse(PackParseCheck.isParseTimeData("data/a/recipe/r.json"));
    }

    @Test
    void aBadNumberIsRefusedWhateverThePackShips() {
        String bad = "Whilst parsing command on line 18: Expected float at position 391: ...damage @s <--[HERE]";
        assertTrue(PackParseCheck.countsAsRefusal(bad, false));
        assertTrue(PackParseCheck.countsAsRefusal(bad, true));
    }

    @Test
    void aMissingReferenceIsLeftToTheReloadOnlyWhenThePackShipsWhatItMightName() {
        String missing = "Whilst parsing command on line 2: Can't find element 'a:p' in registry 'minecraft:predicate'";
        String tag = "Whilst parsing command on line 3: Unknown block tag 'a:soft'";
        assertFalse(PackParseCheck.countsAsRefusal(missing, true));
        assertFalse(PackParseCheck.countsAsRefusal(tag, true));
        // A pack that ships none of it cannot supply it: the reload would refuse it too.
        assertTrue(PackParseCheck.countsAsRefusal(missing, false));
        assertTrue(PackParseCheck.countsAsRefusal(tag, false));
    }
}
