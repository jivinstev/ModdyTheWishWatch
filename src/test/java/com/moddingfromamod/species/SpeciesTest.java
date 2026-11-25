package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Gate A for creature definitions. These values arrive from an AI agent acting on a 7-year-old's
 * request, so the important property isn't "parses correct input" — it's "<b>survives anything</b>":
 * absurd numbers get clamped, unknown names fall back, and nothing throws.
 */
class SpeciesTest {

    private static Species parse(String json) {
        return Species.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void parsesAFullDefinition() {
        Species s = parse("""
            {"id":"blorp","name":"Blorp the Pink Terror","body":"slime","texture":"magma_cube",
             "scale":2.5,"health":60,"speed":0.35,"damage":8,"armor":4,
             "knockbackResistance":0.5,"powers":["leaps","fireproof"]}
            """);
        assertNotNull(s);
        assertEquals("blorp", s.id());
        assertEquals("Blorp the Pink Terror", s.name());
        assertEquals(Species.Body.SLIME, s.body());
        assertEquals("magma_cube", s.texture());
        assertEquals(2.5, s.scale());
        assertTrue(s.has(Species.Power.LEAPS) && s.has(Species.Power.FIREPROOF));
        assertTrue(s.isHostile(), "no FRIENDLY/SKITTISH power means hostile");
    }

    @Test
    void clampsAbsurdNumbers() {
        // A kid will ask for a "1 MILLION health" mob and an agent may well oblige.
        Species s = parse("""
            {"id":"chonk","scale":9999,"health":1000000,"speed":50,"damage":9999,
             "armor":9999,"knockbackResistance":75}
            """);
        assertEquals(Species.MAX_SCALE, s.scale());
        assertEquals(Species.MAX_HEALTH, s.health());
        assertEquals(Species.MAX_SPEED, s.speed());
        assertEquals(Species.MAX_DAMAGE, s.damage());
        assertEquals(Species.MAX_ARMOR, s.armor());
        assertEquals(1.0, s.knockbackResistance());
    }

    @Test
    void clampsNegativesAndNonsense() {
        Species s = parse("""
            {"id":"weird","scale":-5,"health":-100,"speed":-1,"damage":-7,"knockbackResistance":-3}
            """);
        assertTrue(s.scale() >= Species.MIN_SCALE);
        assertTrue(s.health() >= Species.MIN_HEALTH);
        assertTrue(s.speed() >= Species.MIN_SPEED);
        assertTrue(s.damage() >= 0);
        assertTrue(s.knockbackResistance() >= 0);
    }

    @Test
    void unknownBodyAndTextureFallBackInsteadOfBreaking() {
        // An agent WILL invent a texture name; a missing texture would render as a purple cube.
        Species s = parse("{\"id\":\"x\",\"body\":\"dragon\",\"texture\":\"sparkly_unicorn_hide\"}");
        assertEquals(Species.Body.HUMANOID, s.body());
        assertEquals(Species.DEFAULT_TEXTURE, s.texture());
        assertTrue(Species.TEXTURES.containsValue(s.texturePath()),
                "texturePath must always resolve to a real shipped texture");
    }

    @Test
    void everyPaletteTextureIsAPlausibleVanillaPath() {
        for (var e : Species.TEXTURES.entrySet()) {
            assertTrue(e.getValue().startsWith("textures/entity/") && e.getValue().endsWith(".png"),
                    "bad texture path for " + e.getKey() + ": " + e.getValue());
        }
        assertTrue(Species.TEXTURES.containsKey(Species.DEFAULT_TEXTURE));
    }

    @Test
    void aSpeciesWithNoIdIsRejected() {
        // Without an id it can't be referenced, stored or spawned — better to refuse than half-store.
        assertNull(parse("{\"name\":\"nameless\"}"));
        assertNull(parse("{\"id\":\"   \"}"));
        assertNull(Species.fromJson(null));
    }

    @Test
    void unknownPowersAreIgnoredNotFatal() {
        Species s = parse("{\"id\":\"p\",\"powers\":[\"leaps\",\"teleports\",\"friendly\",\"???\"]}");
        assertTrue(s.has(Species.Power.LEAPS));
        assertTrue(s.has(Species.Power.FRIENDLY));
        assertEquals(2, s.powers().size(), "unknown powers should be dropped: " + s.powers());
        assertTrue(!s.isHostile(), "FRIENDLY should make it non-hostile");
    }

    @Test
    void missingFieldsGetPlayableDefaults() {
        Species s = parse("{\"id\":\"minimal\"}");
        assertEquals("Wish Creature", s.name());
        // #100: no longer a flat 20/3 whatever it is, but its size's: a plain humanoid at scale 1 is small
        assertEquals(PowerBand.forVolume(PowerBand.bodyVolume(Species.Body.HUMANOID)).health(), s.health());
        assertEquals(PowerBand.forVolume(PowerBand.bodyVolume(Species.Body.HUMANOID)).damage(), s.damage());
        assertTrue(s.scale() == 1.0 && s.speed() > 0);
    }

    @Test
    void survivesAJsonRoundTrip() {
        Species original = parse("""
            {"id":"round","name":"Round Trip","body":"spider","texture":"cave_spider",
             "scale":1.75,"health":42,"speed":0.3,"damage":6,"armor":2,
             "knockbackResistance":0.25,"powers":["glowing","persistent"]}
            """);
        JsonObject json = original.toJson();
        Species again = Species.fromJson(json);
        assertEquals(original, again, "a species must survive save/load unchanged");
    }

    @Test
    void tintAcceptsNamesAndHex() {
        // An agent should be able to write "pink" as easily as a hex code.
        assertEquals(Species.parseColour("pink"), parse("{\"id\":\"a\",\"tint\":\"pink\"}").tint());
        assertEquals(0xFF69B4, parse("{\"id\":\"b\",\"tint\":\"#FF69B4\"}").tint());
        assertEquals(0xFF69B4, parse("{\"id\":\"c\",\"tint\":\"FF69B4\"}").tint());
        assertEquals(Species.parseColour("hot_pink"), Species.parseColour("hot pink"),
                "spaces in a colour name should work too");
    }

    @Test
    void anUnreadableTintMeansNoTintRatherThanBreaking() {
        // A creature described as "sparkly" must still render, just untinted.
        assertEquals(Species.NO_TINT, parse("{\"id\":\"d\",\"tint\":\"sparkly\"}").tint());
        assertEquals(Species.NO_TINT, parse("{\"id\":\"e\"}").tint(), "no tint field = no tint");
        assertEquals(Species.NO_TINT, Species.parseColour(null));
    }

    @Test
    void tintSurvivesTheRoundTrip() {
        Species original = parse("{\"id\":\"pinky\",\"tint\":\"#FF69B4\",\"body\":\"slime\"}");
        assertEquals(original, Species.fromJson(original.toJson()),
                "a coloured creature must come back the same colour after save/load");
    }

    @Test
    void longNamesAreTrimmedNotRejected() {
        Species s = parse("{\"id\":\"long\",\"name\":\"" + "x".repeat(500) + "\"}");
        assertTrue(s.name().length() <= 48, "name should be trimmed, got " + s.name().length());
    }

    /**
     * a creature's powers round-trip in the order they were written. Set.copyOf's order is
     * salted per JVM, so every save in a new process reordered them, and the upgrade census read
     * creatures nobody touched as CHANGED the first time the backfill saved at load.
     */
    @Test
    void powersRoundTripInTheirWrittenOrder() {
        Species s = parse("{\"id\":\"order\",\"powers\":[\"glowing\",\"fireproof\",\"friendly\"]}");
        assertEquals("[\"glowing\",\"fireproof\",\"friendly\"]",
                s.toJson().get("powers").toString());
    }
}
