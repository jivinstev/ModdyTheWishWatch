package com.moddingfromamod.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.moddingfromamod.library.StarterShots.Kind;
import com.moddingfromamod.library.StarterShots.Subject;
import org.junit.jupiter.api.Test;

class StarterShotsTest {

    @Test
    void eachCommandShapeNamesItsSubject() {
        assertEquals(Optional.of(new Subject(Kind.CREATURE, "pet_dragon")),
                StarterShots.subjectOf(List.of("wishmob spawn pet_dragon")));
        assertEquals(Optional.of(new Subject(Kind.BUILDING, "secret_treehouse")),
                StarterShots.subjectOf(List.of("wishbuild secret_treehouse")));
        assertEquals(Optional.of(new Subject(Kind.PICTURE, "starter_creeper_face")),
                StarterShots.subjectOf(List.of("wishpaint starter_creeper_face")));
        assertEquals(Optional.of(new Subject(Kind.ITEM, "tiny_cookie_grow")),
                StarterShots.subjectOf(List.of("wishdatapack tiny_cookie_grow give")));
        assertEquals(Optional.of(new Subject(Kind.EVENT, "tidal_wave")),
                StarterShots.subjectOf(List.of("moddyevent stage tidal_wave")));
    }

    @Test
    void aSummonedVanillaMobIsFoundByTheTagItWears() {
        // The axolotl's first command is the water it swims in; the summon is what it made.
        assertEquals(Optional.of(new Subject(Kind.CREATURE, "rainbow_axolotl")),
                StarterShots.subjectOf(List.of(
                        "fill ~1 ~-1 ~-1 ~3 ~-1 ~1 minecraft:water",
                        "summon minecraft:axolotl ~2 ~-1 ~ {Tags:[\"wishmob_rainbow_axolotl\"]}",
                        "effect give @e[tag=wishmob_rainbow_axolotl] minecraft:glowing")));
    }

    @Test
    void onlyACreatureMoves() {
        assertTrue(new Subject(Kind.CREATURE, "x").moves());
        for (Kind k : List.of(Kind.BUILDING, Kind.ITEM, Kind.PICTURE)) {
            assertEquals(false, new Subject(k, "x").moves(), k.name());
        }
    }

    @Test
    void aPartIsShownByTheCommandAStarterHoldingItRuns() {
        assertEquals(Optional.of(List.of("wishmob spawn wolf")), StarterShots.commandsForPart("body-wolf"));
        assertEquals(Optional.of(List.of("wishbuild underwater_dome")),
                StarterShots.commandsForPart("building-underwater_dome"));
        assertEquals(Optional.of(List.of("wishpaint heart")), StarterShots.commandsForPart("picture-heart"));
        assertEquals(Optional.empty(), StarterShots.commandsForPart("starter_pet_dragon"));
        assertEquals(Optional.empty(), StarterShots.commandsForPart("kit-wings"));
    }

    /** Every starter the jar ships has a subject the camera can find — a new one cannot slip by. */
    @Test
    void everyShippedStarterHasASubject() throws Exception {
        List<String> missing = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/starter-library/templates.json")) {
            var root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (JsonElement t : root.getAsJsonArray("templates")) {
                var o = t.getAsJsonObject();
                List<String> cmds = new ArrayList<>();
                o.getAsJsonArray("commands").forEach(c -> cmds.add(c.getAsString()));
                if (StarterShots.subjectOf(cmds).isEmpty()) {
                    missing.add(o.get("id").getAsString());
                }
            }
        }
        assertTrue(missing.isEmpty(), "starters with nothing to photograph: " + missing);
    }
}
