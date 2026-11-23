package com.moddingfromamod.place;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Gate A 's action-log reader: every place a call put something at. */
class AtUsesTest {

    @Test
    void aToolsOwnAt() {
        assertEquals(List.of(new AtUses.Use("define_creature", "castle:top")),
                AtUses.from("define_creature", "{\"id\":\"rex\",\"at\":\"castle:top\"}"));
    }

    @Test
    void aWishspotTypedInRunCommand() {
        assertEquals(List.of(new AtUses.Use("run_command", "garage:inside")),
                AtUses.from("run_command", "{\"command\":[\"wishspot garage:inside run summon minecraft:minecart ~ ~ ~\","
                        + "\"wishspot garage:inside run summon minecraft:minecart ~1 ~ ~\"]}"));
    }

    @Test
    void aCreatureSpawnedAtAPlaceByCommand() {
        assertEquals(List.of(new AtUses.Use("run_command", "tall_stone_tower:top")),
                AtUses.from("run_command", "{\"command\":[\"wishmob spawn pet_dragon at tall_stone_tower:top\"]}"));
        assertEquals(List.of(new AtUses.Use("run_command", "well:around")),
                AtUses.from("run_command", "{\"command\":[\"wishmob spawn sheep_x 3 at well:around\"]}"));
    }

    @Test
    void aClippedRecordStillGivesItsAt() {
        assertEquals(List.of(new AtUses.Use("sculpt_creature", "me:front")),
                AtUses.from("sculpt_creature", "{\"id\":\"pup\",\"at\":\"me:front\",\"parts\":[{\"box\":[1,2"));
    }

    @Test
    void noAtIsNothing() {
        assertEquals(List.of(), AtUses.from("make_item", "{\"name\":\"Wand\"}"));
        assertEquals(List.of(), AtUses.from("make_item", "{\"name\":\"Wand\",\"at\":\"\"}"));
    }
}
