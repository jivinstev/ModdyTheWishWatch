package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.wishbuilder.MakeFacts;
import org.junit.jupiter.api.Test;

/** #69: the Magician's tool arguments fitted to a world's limits, the core's note, the brief's data, the lesson. */
class ToolArgFitTest {

    @Test
    void aMappedArgumentOverItsLimitIsSetToTheLimitAndTheCoreSaysSo() {
        JsonObject args = JsonParser.parseString("{\"id\":\"dragon\",\"health\":500,\"damage\":4,\"scale\":6,"
                + "\"powers\":[\"my_custom_power\"]}").getAsJsonObject();
        List<ToolArgFit.Lowered> lowered = ToolArgFit.fit("sculpt_creature", args,
                Map.of(MakeFacts.HEALTH_HALF_HEARTS, 40.0, MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, 6.0,
                        MakeFacts.SCALE_TIMES, 3.0));
        // a creature's stats and size are held where it stands (live fit), so its record keeps what was asked
        assertEquals(500.0, args.get("health").getAsDouble(), "held where it stands, never written into the species");
        assertEquals(4.0, args.get("damage").getAsDouble(), "within its limit: as asked");
        assertEquals(6.0, args.get("scale").getAsDouble());
        assertEquals("[\"my_custom_power\"]", args.get("powers").toString(), "what no fact measures is kept as it is");
        assertEquals(List.of("health is held to 40 where it stands: this world's limit",
                        "scale is held to 3 where it stands: this world's limit"),
                lowered.stream().map(ToolArgFit.Lowered::note).toList(), "the core's own words, from the key and limit");
        assertTrue(ToolArgFit.notes(lowered).contains("health is held to 40 where it stands: this world's limit"));
    }

    @Test
    void withNoLimitsOrAToolNoFactMeasuresNothingChanges() {
        JsonObject args = JsonParser.parseString("{\"health\":500}").getAsJsonObject();
        assertTrue(ToolArgFit.fit("sculpt_creature", args, Map.of()).isEmpty());
        assertTrue(ToolArgFit.fit("notify_player", args, Map.of(MakeFacts.HEALTH_HALF_HEARTS, 40.0)).isEmpty());
        assertEquals(500.0, args.get("health").getAsDouble());
        assertEquals("", ToolArgFit.notes(List.of()));
    }

    @Test
    void theBriefCarriesTheLimitsAsDataOnly() {
        String brief = ToolArgFit.brief(Map.of(MakeFacts.HEALTH_HALF_HEARTS, 40.0, "aWorldsOwnWords", 1.0,
                MakeFacts.COUNT_MADE, 3.0));
        assertTrue(brief.contains("healthHalfHearts=40") && brief.contains("countMade=3"), brief);
        assertTrue(!brief.contains("aWorldsOwnWords"), "only the closed vocabulary: " + brief);
        assertEquals("", ToolArgFit.brief(Map.of()));
    }

    @Test
    void theLessonToPreferTheStrongestAllowedIsPinned() throws Exception {
        // pinned so a later trim of the lessons cannot drop it (#69)
        for (String lesson : List.of("creature.md", "item.md")) {
            String text = Files.readString(Path.of("src/main/resources/lessons/" + lesson));
            assertTrue(text.contains("make the strongest version they allow") && text.contains("THIS WORLD'S LIMITS"),
                    lesson + " has lost the lesson to make the strongest version a world's limits allow");
        }
        assertTrue(Files.readString(Path.of("src/main/resources/lessons/item.md")).contains("the strongest sword ever"));
    }
}
