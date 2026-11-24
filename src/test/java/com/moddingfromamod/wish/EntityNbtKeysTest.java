package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Since 1.21.5 an entity's fall distance is the double fall_distance, not the float FallDistance. */
class EntityNbtKeysTest {

    @Test
    void theOldKeyAndAFloatBecomeTheNewKeyAndADouble() {
        List<String> notes = new ArrayList<>();
        assertEquals("summon zombie ~ ~ ~ {fall_distance:0d,NoAI:1b}",
                EntityNbtKeys.correct("summon zombie ~ ~ ~ {FallDistance:0f,NoAI:1b}", notes));
        assertEquals(List.of("the entity NBT key 'FallDistance' to 'fall_distance'",
                "the fall distance 0f to the double 0d"), notes);
    }

    @Test
    void theNewKeyWithAFloatStillMatchesNothingSoItsValueBecomesADouble() {
        assertEquals("execute as @a[nbt={fall_distance:0d}] run say still",
                EntityNbtKeys.correct("execute as @a[nbt={fall_distance:0f}] run say still", new ArrayList<>()));
        assertEquals("data merge entity @s {Health:20f,fall_distance:3d}",
                EntityNbtKeys.correct("data merge entity @s {Health:20f,FallDistance:3}", new ArrayList<>()));
        assertEquals("data merge entity @s {fall_distance:1.5d}",
                EntityNbtKeys.correct("data merge entity @s {FallDistance:1.5F}", new ArrayList<>()));
    }

    @Test
    void aDoubleIsLeftAsWritten() {
        List<String> notes = new ArrayList<>();
        String already = "data merge entity @s {fall_distance:0.0}";
        assertEquals(already, EntityNbtKeys.correct(already, notes));
        assertEquals("data merge entity @s {fall_distance:2d}",
                EntityNbtKeys.correct("data merge entity @s {fall_distance:2d}", notes));
        assertTrue(notes.isEmpty(), "nothing changed, so nothing is said: " + notes);
        assertEquals("data merge entity @s {fall_distance:0.0}",
                EntityNbtKeys.correct("data merge entity @s {FallDistance:0.0}", new ArrayList<>()));
    }

    @Test
    void aDataPathIsRenamed() {
        assertEquals("data get entity @s fall_distance",
                EntityNbtKeys.correct("data get entity @s FallDistance", new ArrayList<>()));
    }

    @Test
    void wordsThatOnlyContainItAreLeftAlone() {
        List<String> notes = new ArrayList<>();
        String said = "tellraw @a {\"text\":\"FallDistanceMeter\"} and MyFallDistance and \"FallDistance\"";
        assertEquals(said, EntityNbtKeys.correct(said, notes));
        assertTrue(notes.isEmpty());
        String attribute = "attribute @s minecraft:safe_fall_distance base set 10";
        assertEquals(attribute, EntityNbtKeys.correct(attribute, notes));
        String modifier = "give @s diamond_boots[attribute_modifiers=[{type:\"minecraft:safe_fall_distance\",amount:3}]]";
        assertEquals(modifier, EntityNbtKeys.correct(modifier, notes));
        assertTrue(notes.isEmpty(), "the safe_fall_distance attribute is not the entity's fall distance: " + notes);
    }

    @Test
    void textWithoutTheKeyIsReturnedAsIs() {
        String s = "say hello";
        assertSame(s, EntityNbtKeys.correct(s, new ArrayList<>()));
    }
}
