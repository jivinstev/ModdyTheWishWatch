package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** a scheme lands on every ready-made creature, names only real parts, and leaves the face alone. */
class SchemesTest {

    @Test
    void everySchemeRepaintsEveryStarterCreatureAndSparesTheFace() {
        int creatures = 0;
        for (String preset : Catalogue.presets()) {
            if (!preset.startsWith("species/") && !preset.startsWith("bodies/")) {
                continue;
            }
            JsonObject sculpt = LibraryPresets.SHIPPED.read(preset);
            assertNotNull(sculpt, preset);
            JsonArray parts = sculpt.getAsJsonArray("parts");
            Set<String> names = new HashSet<>();
            for (JsonElement e : parts) {
                names.add(e.getAsJsonObject().get("name").getAsString());
            }
            creatures++;
            for (Schemes.Scheme s : Schemes.ALL) {
                JsonObject r = Schemes.recolour(parts, s);
                assertTrue(r.size() > 0, s.id() + " repaints nothing on " + preset);
                assertTrue(r.entrySet().stream().anyMatch(e -> e.getValue().getAsString().equals(s.main())),
                        s.id() + "'s main colour lands on " + preset);
                for (String k : r.keySet()) {
                    assertTrue(names.contains(k), preset + ": '" + k + "' is not one of its parts");
                    JsonObject part = new JsonObject();
                    part.addProperty("name", k);
                    assertFalse(Schemes.isFace(part), preset + ": the face part '" + k + "' was repainted");
                }
            }
        }
        assertTrue(creatures >= 27, "the starter creatures and the Builder bodies were read: " + creatures);
    }

    @Test
    void theBiggestColourBecomesTheMain() {
        JsonArray parts = com.google.gson.JsonParser.parseString("["
                + "{\"name\":\"body\",\"from\":[0,0,0],\"to\":[8,8,8],\"colour\":\"#111111\"},"
                + "{\"name\":\"tail\",\"from\":[0,0,0],\"to\":[1,1,4],\"colour\":\"#222222\"},"
                + "{\"name\":\"eyeL\",\"from\":[0,0,0],\"to\":[9,9,9],\"colour\":\"#333333\"}]").getAsJsonArray();
        Schemes.Scheme ocean = Schemes.byId("ocean");
        JsonObject r = Schemes.recolour(parts, ocean);
        assertEquals(ocean.main(), r.get("body").getAsString());
        assertEquals(ocean.accent(), r.get("tail").getAsString());
        assertFalse(r.has("eyeL"), "an eye keeps its colour however big it is");
    }

    // ------------------------------------------------------------------: skins ("made of ice")

    @Test
    void aSkinRepaintsEveryPartButTheFaceOnEveryCreature() {
        for (String preset : Catalogue.presets()) {
            if (!preset.startsWith("species/") && !preset.startsWith("bodies/")) {
                continue;
            }
            JsonArray parts = LibraryPresets.SHIPPED.read(preset).getAsJsonArray("parts");
            for (Schemes.Scheme s : Schemes.ALL) {
                if (!s.skin()) {
                    continue;
                }
                JsonObject r = Schemes.recolour(parts, s);
                for (JsonElement e : parts) {
                    JsonObject p = e.getAsJsonObject();
                    if (p.has("colour") && p.has("name") && !Schemes.isFace(p)) {
                        String n = p.get("name").getAsString();
                        assertTrue(r.has(n), preset + " made of " + s.id() + " kept the old colour on '" + n + "'");
                        assertTrue(s.colours().contains(r.get(n).getAsString()), preset + ": '" + n + "' is not a " + s.id() + " shade");
                    }
                }
            }
        }
    }

    @Test
    void aSkinRebuildsEveryBlockButTheKeptOnesAndNeverInOneThatHurts() {
        JsonArray shapes = com.google.gson.JsonParser.parseString("["
                + "{\"block\":\"minecraft:oak_planks\",\"width\":9,\"height\":5,\"depth\":9,\"hollow\":true},"
                + "{\"block\":\"minecraft:cobblestone\",\"width\":9,\"height\":1,\"depth\":9},"
                + "{\"block\":\"minecraft:bricks\",\"width\":3,\"height\":3,\"depth\":3},"
                + "{\"block\":\"minecraft:red_wool\",\"width\":1,\"height\":1,\"depth\":2},"
                + "{\"block\":\"minecraft:glass\",\"width\":2,\"height\":2,\"depth\":1}]").getAsJsonArray();
        java.util.Set<String> hurts = java.util.Set.of("minecraft:magma_block", "minecraft:lava", "minecraft:slime_block",
                "minecraft:cactus", "minecraft:fire", "minecraft:tnt", "minecraft:powder_snow");
        for (Schemes.Scheme s : Schemes.ALL) {
            if (!s.skin()) {
                continue;
            }
            var to = Schemes.reblock(shapes, s);
            assertEquals(4, to.size(), s.id() + " reblocked " + to);
            assertFalse(to.containsKey("minecraft:glass"), "a window stays a window");
            for (String b : s.blocks()) {
                assertFalse(hurts.contains(b), s.id() + " builds in " + b);
            }
        }
    }

    @Test
    void aSchemeStillRepaintsOnlyTheBiggestThree() {
        JsonArray parts = com.google.gson.JsonParser.parseString("["
                + "{\"name\":\"a\",\"from\":[0,0,0],\"to\":[8,8,8],\"colour\":\"#111111\"},"
                + "{\"name\":\"b\",\"from\":[0,0,0],\"to\":[4,4,4],\"colour\":\"#222222\"},"
                + "{\"name\":\"c\",\"from\":[0,0,0],\"to\":[2,2,2],\"colour\":\"#333333\"},"
                + "{\"name\":\"d\",\"from\":[0,0,0],\"to\":[1,1,1],\"colour\":\"#444444\"}]").getAsJsonArray();
        assertFalse(Schemes.recolour(parts, Schemes.byId("ocean")).has("d"), "a scheme leaves the small details");
        assertTrue(Schemes.recolour(parts, Schemes.byId("ice")).has("d"), "a skin leaves nothing of the old colours");
    }
}
