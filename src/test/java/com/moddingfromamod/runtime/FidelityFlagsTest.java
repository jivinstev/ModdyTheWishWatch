package com.moddingfromamod.runtime;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The A/B lever.
 *
 * <p>An arm that is only <i>half</i> disabled would measure neither the old sculptor nor the new
 * one, and the resulting numbers would look perfectly reasonable — which is why this is tested
 * rather than assumed.
 */
class FidelityFlagsTest {

    private String before;

    @AfterEach
    void restore() {
        FidelityFlags.forceForTest(before);
    }

    private void off() {
        before = FidelityFlags.forceForTest("off");
    }

    private static final String TILTED_JSON = """
            [{"name":"spike","from":[-1,10,-1],"to":[1,16,1],"colour":"white",
              "rotation":[-40,0,12],"inflate":2,"pattern":"stripes","pattern_colour":"red"}]""";

    private static List<CreatureSculpt.Part> parse(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    @Test
    void onByDefaultSoAShippedBuildIsNeverQuietlyTheOldOne() {
        assertTrue(FidelityFlags.enabled());
        CreatureSculpt.Part p = parse(TILTED_JSON).get(0);
        assertTrue(p.rotated());
        assertTrue(p.patterned());
    }

    @Test
    void offGivesExactlyThePreChangeSculptor() {
        off();
        CreatureSculpt.Part p = parse(TILTED_JSON).get(0);
        // The part still builds — it is simply an upright, flat box again.
        assertFalse(p.rotated(), "arm A must not tilt");
        assertFalse(p.patterned(), "arm A must not pattern");
        assertEquals(0f, p.inflate(), "arm A must not inflate");
    }

    @Test
    void offEmitsGeometryWithNoRotationAtAll() {
        off();
        JsonObject geo = CreatureSculpt.bake(parse(TILTED_JSON)).geometry();
        JsonArray bones = geo.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones");
        for (var el : bones) {
            assertFalse(el.getAsJsonObject().has("rotation"),
                    "arm A geometry must be indistinguishable from the pre-#70 output");
        }
    }

    @Test
    void offBuildsNoKits() {
        off();
        var body = parse("[{\"name\":\"body\",\"from\":[-8,10,-6],\"to\":[8,32,6],"
                + "\"colour\":\"green\"}]");
        JsonObject args = JsonParser.parseString(
                "{\"kits\":[{\"kit\":\"spike_row\",\"on\":\"body\",\"count\":7}]}").getAsJsonObject();
        assertEquals(body.size(),
                com.moddingfromamod.agent.tools.SculptCreatureTool.withKits(body, args).size(),
                "arm A must ignore kits entirely");
    }

    /**
     * The agent must not merely be blocked from tilting — it must never learn that tilting exists.
     * Otherwise arm A spends tokens describing rotations that are thrown away, and is slower than
     * the real baseline while producing identical geometry.
     */
    @Test
    void offHidesTheNewFieldsFromTheToolSchemaCompletely() {
        off();
        JsonObject schema = new com.moddingfromamod.agent.tools.SculptCreatureTool().inputSchema();
        String json = schema.toString();
        assertFalse(json.contains("rotation"), "arm A must not advertise rotation");
        assertFalse(json.contains("pattern"), "arm A must not advertise patterns");
        assertFalse(json.contains("\"kits\""), "arm A must not advertise kits");
        assertTrue(json.contains("parts"), "it is still a sculpting tool");
    }

    @Test
    void onAdvertisesAllThree() {
        JsonObject schema = new com.moddingfromamod.agent.tools.SculptCreatureTool().inputSchema();
        String json = schema.toString();
        assertTrue(json.contains("rotation"));
        assertTrue(json.contains("pattern"));
        assertTrue(json.contains("\"kits\""));
    }

}
