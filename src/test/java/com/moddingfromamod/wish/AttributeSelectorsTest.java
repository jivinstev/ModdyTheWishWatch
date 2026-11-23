package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An {@code attribute} aimed at many entities is rewritten through {@code execute as}; one entity is left alone. */
class AttributeSelectorsTest {

    private static String fix(String text) {
        return AttributeSelectors.correct(text, new ArrayList<>());
    }

    @Test
    @DisplayName("the archived datapack's line: a clock's attribute over every zombie, refused at pack load")
    void theArchivedLine() {
        List<String> notes = new ArrayList<>();
        String src = "    /execute if score dayTime friendly_day matches 0..12300 run attribute @e[type=zombie] "
                + "minecraft:follow_range base set 0";
        assertEquals("    /execute if score dayTime friendly_day matches 0..12300 run execute as @e[type=zombie] "
                + "run attribute @s minecraft:follow_range base set 0", AttributeSelectors.correct(src, notes));
        assertEquals(1, notes.size(), notes.toString());
        assertTrue(notes.get(0).contains("execute as @e[type=zombie] run attribute @s"), notes.toString());
    }

    @Test
    @DisplayName("a raw command at a line's head, with and without a slash")
    void atTheHead() {
        assertEquals("execute as @e[type=minecraft:zombie] run attribute @s minecraft:generic.follow_range base set 0",
                fix("attribute @e[type=minecraft:zombie] minecraft:generic.follow_range base set 0"));
        assertEquals("/execute as @a run attribute @s minecraft:scale base set 2",
                fix("/attribute @a minecraft:scale base set 2"));
        assertEquals("execute as @e run attribute @s minecraft:scale base get",
                fix("attribute @e minecraft:scale base get"));
    }

    @Test
    @DisplayName("brackets, braces and quotes inside the selector stay one selector")
    void nestedSelector() {
        String sel = "@e[type=cow,nbt={Tags:[\"a]b\"]},tag=!x]";
        assertEquals("execute as " + sel + " run attribute @s minecraft:scale base set 3",
                fix("attribute " + sel + " minecraft:scale base set 3"));
    }

    @Test
    @DisplayName("several lines of a function: each many-entity line is rewritten, the rest byte for byte")
    void manyLines() {
        String src = "say hi\nattribute @e[type=husk] minecraft:attack_damage base set 0\n"
                + "attribute @s minecraft:scale base set 2\n";
        assertEquals("say hi\nexecute as @e[type=husk] run attribute @s minecraft:attack_damage base set 0\n"
                + "attribute @s minecraft:scale base set 2\n", fix(src));
    }

    @Test
    @DisplayName("one entity already: @s, @p, @r, @n, limit=1 and a name are untouched")
    void singlesUntouched() {
        for (String ok : List.of(
                "attribute @s minecraft:scale base set 2",
                "attribute @p minecraft:scale base set 2",
                "attribute @r minecraft:scale base set 2",
                "attribute @n[type=cow] minecraft:scale base set 2",
                "attribute @e[type=cow,limit=1,sort=nearest] minecraft:scale base set 2",
                "attribute @a[limit=1] minecraft:scale base set 2",
                "attribute Steve minecraft:scale base set 2",
                "execute as @e[type=cow] run attribute @s minecraft:scale base set 2",
                "$attribute $(who) minecraft:scale base set 2",
                "tellraw @a \"the attribute @e is fun\"")) {
            assertSame(ok, fix(ok), ok);
        }
    }

    @Test
    @DisplayName("a limit above one is many, even on @p or @r")
    void limitAboveOne() {
        assertEquals("execute as @r[limit=3] run attribute @s minecraft:scale base set 2",
                fix("attribute @r[limit=3] minecraft:scale base set 2"));
    }

    @Test
    @DisplayName("it runs as part of the dialect every command and datapack goes through")
    void wiredIntoTheDialect() {
        var fixed = CommandDialect.correct("attribute @e[type=zombie] minecraft:scale base set 2", id -> true);
        assertEquals("execute as @e[type=zombie] run attribute @s minecraft:scale base set 2", fixed.command());
        assertTrue(fixed.notes().stream().anyMatch(n -> n.contains("ONE entity")), fixed.notes().toString());
    }
}
