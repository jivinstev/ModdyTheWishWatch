package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** What a creature's ranged move throws: kept, written only when named, and read back. */
class SpeciesThrowsTest {

    private static Species parse(String json) {
        return Species.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void anItemIsNamespacedAndTheSnowballIsTheDefaultLeftUnsaid() {
        assertEquals("minecraft:fire_charge", Species.throwsItem("fire_charge"));
        assertEquals("minecraft:fire_charge", Species.throwsItem(" Minecraft:Fire_Charge "));
        assertEquals("", Species.throwsItem("minecraft:snowball"));
        assertEquals("", Species.throwsItem(""));
        assertEquals("", Species.throwsItem("not an item!"));
    }

    @Test
    void itRidesTheSpeciesFileAndEveryEditKeepsIt() {
        Species s = parse("{\"id\":\"imp\",\"name\":\"Imp\",\"abilities\":[\"ranged\"],\"throws\":\"fire_charge\"}");
        assertEquals("minecraft:fire_charge", s.throwsItem());
        assertEquals("minecraft:fire_charge", s.toJson().get("throws").getAsString());
        assertEquals("minecraft:fire_charge", parse(s.toJson().toString()).throwsItem());
        assertEquals("minecraft:fire_charge", s.withOwner("someone").throwsItem());
        assertEquals("minecraft:fire_charge", s.withVoice(Voice.NONE).throwsItem());
    }

    @Test
    void aCreatureThatNamesNothingIsWrittenAsBefore() {
        Species s = parse("{\"id\":\"plain\",\"name\":\"Plain\",\"abilities\":[\"ranged\"]}");
        assertEquals("", s.throwsItem());
        assertFalse(s.toJson().has("throws"));
    }
}
