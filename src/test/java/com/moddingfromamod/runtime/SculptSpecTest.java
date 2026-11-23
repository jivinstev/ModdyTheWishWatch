package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * The editable sculpt source is a faithful, re-runnable copy of the tool args: it keeps the parts and
 * stats, drops only the transient {@code spawn} flag, and pins the sanitized id.
 */
class SculptSpecTest {

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void keepsPartsAndStatsDropsSpawnAndPinsId() {
        JsonObject args = parse("{\"id\":\"raw ID\",\"name\":\"Spikor\",\"health\":400,"
                + "\"scale\":2.6,\"spawn\":true,"
                + "\"parts\":[{\"name\":\"body\",\"from\":[-10,13,-6],\"to\":[10,32,6],"
                + "\"colour\":\"#F0B040\"}]}");

        JsonObject spec = parse(SculptSpec.editable(args, "spikor"));

        assertEquals("spikor", spec.get("id").getAsString(), "id is pinned to the sanitized key");
        assertFalse(spec.has("spawn"), "the transient spawn flag is dropped");
        assertEquals("Spikor", spec.get("name").getAsString());
        assertEquals(400, spec.get("health").getAsInt());
        assertTrue(spec.has("parts") && spec.getAsJsonArray("parts").size() == 1,
                "the parts list is preserved verbatim");
        assertEquals("body",
                spec.getAsJsonArray("parts").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void reRunnable_feedingItBackYieldsTheSameSpec() {
        JsonObject args = parse("{\"id\":\"sparky\",\"parts\":[{\"name\":\"head\","
                + "\"from\":[-4,24,-4],\"to\":[4,32,4],\"colour\":\"white\"}],\"spawn\":false}");

        String once = SculptSpec.editable(args, "sparky");
        // Re-feeding the saved spec (which no longer has spawn) is idempotent.
        String twice = SculptSpec.editable(parse(once), "sparky");
        assertEquals(once, twice, "the editable spec is stable — safe to save and re-sculpt from");
    }

    @Test
    void toleratesMissingArgs() {
        String spec = SculptSpec.editable(null, "x");
        assertEquals("x", parse(spec).get("id").getAsString());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#2526: a first sculpt the tool recoloured saves the TURNED parts, the ones it draws")
    void aServerRecolouredFirstSculptSavesTheTurnedParts() {
        var args = com.google.gson.JsonParser.parseString(
                "{\"id\":\"bounce\",\"parts\":[{\"name\":\"body\",\"from\":[0,0,0],\"to\":[4,4,4],\"colour\":\"#FFA500\"}]}")
                .getAsJsonObject();
        var turned = java.util.List.of(new CreatureSculpt.Part("body", null, new float[3],
                new float[]{0, 0, 0}, new float[]{4, 4, 4}, 0xE01010));
        var saved = com.google.gson.JsonParser.parseString(SculptSpec.editableResolved(args, "bounce", turned))
                .getAsJsonObject();
        var back = CreatureSculpt.parseParts(saved.getAsJsonArray("parts"));
        org.junit.jupiter.api.Assertions.assertEquals(0xE01010, back.get(0).colour());
        org.junit.jupiter.api.Assertions.assertTrue(SculptSpec.editable(args, "bounce", turned).contains("#FFA500"),
                "the ordinary first-sculpt path still saves the agent's own arguments");
    }
}
