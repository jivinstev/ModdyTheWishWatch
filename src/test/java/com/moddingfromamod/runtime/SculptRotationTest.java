package com.moddingfromamod.runtime;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rotation and inflate on a sculpted part — the change that makes a spike a spike rather than
 * a staircase.
 */
class SculptRotationTest {

    private static JsonArray parts(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    private static JsonObject geometryOf(List<CreatureSculpt.Part> ps) {
        return CreatureSculpt.bake(ps).geometry();
    }

    private static JsonObject boneNamed(JsonObject geo, String name) {
        JsonArray bones = geo.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones");
        for (var el : bones) {
            JsonObject b = el.getAsJsonObject();
            if (b.get("name").getAsString().equals(name)) {
                return b;
            }
        }
        return null;
    }

    @Test
    void aPartWithNoRotationIsUnchangedFromBefore() {
        List<CreatureSculpt.Part> ps = CreatureSculpt.parseParts(parts("""
                [{"name":"body","from":[-4,0,-2],"to":[4,12,2],"colour":"green"}]"""));
        assertEquals(1, ps.size());
        assertFalse(ps.get(0).rotated());
        assertEquals(0f, ps.get(0).inflate());
        // The legacy 6-arg constructor must still build a valid part: ~20 call sites use it.
        CreatureSculpt.Part legacy = new CreatureSculpt.Part("x", null,
                new float[]{0, 0, 0}, new float[]{0, 0, 0}, new float[]{1, 1, 1}, 0xFF0000);
        assertFalse(legacy.rotated());
    }

    @Test
    void rotationAndInflateSurviveParsing() {
        List<CreatureSculpt.Part> ps = CreatureSculpt.parseParts(parts("""
                [{"name":"spike","from":[-1,10,-1],"to":[1,16,1],"colour":"white",
                  "rotation":[-40,0,12],"inflate":1.5}]"""));
        CreatureSculpt.Part p = ps.get(0);
        assertTrue(p.rotated());
        assertEquals(-40f, p.rotation()[0]);
        assertEquals(12f, p.rotation()[2]);
        assertEquals(1.5f, p.inflate());
    }

    /**
     * <b>The trap this test exists for.</b>
     *
     * <p>{@code BedrockGeometry} parses cube-level rotation, so writing the tilt onto the cube looks
     * right and would pass any test that just asked "is the rotation in the JSON". But
     * {@code RuntimeGeoModel} only ever applies BONE rotation — its {@code bakeCube} honours inflate
     * and mirror and silently drops cube rotation. A cube rotation therefore renders as <b>nothing
     * at all</b>, and we would have shipped "spikes work" having changed zero pixels on screen.
     */
    @Test
    void theTiltIsWrittenOnTheBoneBecauseTheRendererIgnoresCubeRotation() {
        List<CreatureSculpt.Part> ps = CreatureSculpt.parseParts(parts("""
                [{"name":"body","from":[-4,0,-2],"to":[4,12,2],"colour":"green"},
                 {"name":"spike","from":[-1,10,-1],"to":[1,16,1],"colour":"white",
                  "rotation":[-40,0,0]}]"""));
        JsonObject bone = boneNamed(geometryOf(ps), "spike");
        assertTrue(bone.has("rotation"), "the bone must carry the tilt — the renderer reads it here");
        assertEquals(-40f, bone.getAsJsonArray("rotation").get(0).getAsFloat());
        JsonObject cube = bone.getAsJsonArray("cubes").get(0).getAsJsonObject();
        assertFalse(cube.has("rotation"),
                "a cube rotation is parsed but never rendered, so writing one is a silent no-op");
    }

    @Test
    void inflateGoesOnTheCubeBecauseThatIsWhereTheRendererHonoursIt() {
        List<CreatureSculpt.Part> ps = CreatureSculpt.parseParts(parts("""
                [{"name":"shell","from":[-6,0,-3],"to":[6,10,3],"colour":"green","inflate":2}]"""));
        JsonObject cube = boneNamed(geometryOf(ps), "shell")
                .getAsJsonArray("cubes").get(0).getAsJsonObject();
        assertEquals(2f, cube.get("inflate").getAsFloat());
    }

    @Test
    void anAbsurdAngleIsClampedRatherThanDroppingThePart() {
        // A horn that silently vanishes costs a child the horn and reports nothing, so out-of-range
        // is clamped into the readable band rather than rejected.
        List<CreatureSculpt.Part> ps = CreatureSculpt.parseParts(parts("""
                [{"name":"horn","from":[-1,0,-1],"to":[1,6,1],"colour":"white",
                  "rotation":[400,-999,0]}]"""));
        assertEquals(1, ps.size(), "the part must survive");
        assertEquals(CreatureSculpt.MAX_ROTATION, ps.get(0).rotation()[0]);
        assertEquals(-CreatureSculpt.MAX_ROTATION, ps.get(0).rotation()[1]);
    }

    @Test
    void garbageRotationDoesNotBreakTheCreature() {
        List<CreatureSculpt.Part> ps = CreatureSculpt.parseParts(parts("""
                [{"name":"body","from":[-4,0,-2],"to":[4,12,2],"colour":"green",
                  "rotation":"very tilted","inflate":"a lot"}]"""));
        assertEquals(1, ps.size());
        assertFalse(ps.get(0).rotated());
        assertEquals(0f, ps.get(0).inflate());
    }
}
