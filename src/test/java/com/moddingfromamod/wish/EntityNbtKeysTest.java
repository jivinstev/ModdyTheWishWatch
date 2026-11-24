package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 26.1 renamed the entity NBT key FallDistance to fall_distance (Entity.TAG_FALL_DISTANCE). */
class EntityNbtKeysTest {

    @Test
    void aCompoundKeyIsRenamed() {
        List<String> notes = new ArrayList<>();
        assertEquals("summon zombie ~ ~ ~ {fall_distance:0f,NoAI:1b}",
                EntityNbtKeys.correct("summon zombie ~ ~ ~ {FallDistance:0f,NoAI:1b}", notes));
        assertEquals(List.of("the entity NBT key 'FallDistance' to 'fall_distance'"), notes);
    }

    @Test
    void aDataPathIsRenamed() {
        assertEquals("execute as @a run data merge entity @s {fall_distance:0.0}",
                EntityNbtKeys.correct("execute as @a run data merge entity @s {FallDistance:0.0}", new ArrayList<>()));
        assertEquals("data get entity @s fall_distance",
                EntityNbtKeys.correct("data get entity @s FallDistance", new ArrayList<>()));
    }

    @Test
    void wordsThatOnlyContainItAreLeftAlone() {
        List<String> notes = new ArrayList<>();
        String said = "tellraw @a {\"text\":\"FallDistanceMeter\"} and MyFallDistance";
        assertEquals(said, EntityNbtKeys.correct(said, notes));
        assertTrue(notes.isEmpty());
        String quoted = "tellraw @a \"FallDistance\"";
        assertEquals(quoted, EntityNbtKeys.correct(quoted, notes));
    }

    @Test
    void textWithoutTheKeyIsReturnedAsIs() {
        String s = "say hello";
        assertSame(s, EntityNbtKeys.correct(s, new ArrayList<>()));
    }
}
