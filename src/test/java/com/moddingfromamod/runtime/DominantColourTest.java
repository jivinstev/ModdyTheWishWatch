package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A sculpted creature's icon colour comes from its PARTS</b>, because its species tint is
 * the white default — which would have drawn every creature a child made as the same white egg.
 * Driven on the real starter dragon.
 */
class DominantColourTest {

    @Test
    @DisplayName("the starter dragon's icon colour is the colour it is mostly made of, not white")
    void theDragonIsGreenNotWhite() throws Exception {
        var sculpt = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/starter-library/species/pet_dragon/sculpt.json"))).getAsJsonObject();
        var species = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/starter-library/species/pet_dragon/species.json"))).getAsJsonObject();
        assertEquals("#FFFFFF", species.get("tint").getAsString(),
                "the premise: a sculpted species' own tint is the white default");
        int rgb = SculptCritique.dominantColour(CreatureSculpt.parseParts(sculpt.getAsJsonArray("parts")));
        assertNotEquals(0xFFFFFF, rgb);
        assertEquals(0x50E050, rgb, "the dragon's body green, by volume");
    }

    @Test
    @DisplayName("no parts, no colour — the caller falls back to the species tint")
    void noPartsNoColour() {
        assertEquals(-1, SculptCritique.dominantColour(java.util.List.of()));
        assertEquals(-1, SculptCritique.dominantColour(null));
    }
}
