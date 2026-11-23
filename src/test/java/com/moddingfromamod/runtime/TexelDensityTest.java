package com.moddingfromamod.runtime;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-face UV at a texel density.
 *
 * <p><b>The dangerous change of the set.</b> Box-UV derives a cube's six face rectangles from its
 * own dimensions; per-face UV states them outright. If the stated rectangles do not reproduce
 * exactly what {@code RuntimeGeoModel#bakeCube}'s box-UV branch computes, <b>every creature's skin
 * scrambles at once</b> — not one creature, all of them, including the ones already saved. So the
 * layout is pinned here rather than eyeballed on a screenshot.
 */
class TexelDensityTest {

    @AfterEach
    void restore() {
        CreatureSculpt.setTexelsPerUnitForTest(CreatureSculpt.DEFAULT_TEXELS_PER_UNIT);
    }

    private static List<CreatureSculpt.Part> parse(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    private static int[] rect(JsonObject uv, String face) {
        JsonObject f = uv.getAsJsonObject(face);
        return new int[]{
                f.getAsJsonArray("uv").get(0).getAsInt(),
                f.getAsJsonArray("uv").get(1).getAsInt(),
                f.getAsJsonArray("uv_size").get(0).getAsInt(),
                f.getAsJsonArray("uv_size").get(1).getAsInt()};
    }

    /**
     * The six rectangles, against the bedrock box-UV net computed by hand. w=4 h=6 d=2 at net
     * origin (10,20) texels, density 4.
     */
    @Test
    void theFaceRectanglesReproduceTheBoxUvNet() {
        CreatureSculpt.setTexelsPerUnitForTest(4);
        JsonObject uv = CreatureSculpt.faceUv(10, 20, 4, 6, 2);
        assertArrayEqualsInt(new int[]{18, 20, 16, 8}, rect(uv, "up"));
        assertArrayEqualsInt(new int[]{34, 20, 16, 8}, rect(uv, "down"));
        assertArrayEqualsInt(new int[]{10, 28, 8, 24}, rect(uv, "east"));
        assertArrayEqualsInt(new int[]{18, 28, 16, 24}, rect(uv, "north"));
        assertArrayEqualsInt(new int[]{34, 28, 8, 24}, rect(uv, "west"));
        assertArrayEqualsInt(new int[]{42, 28, 16, 24}, rect(uv, "south"));
    }

    /**
     * The net must tile EXACTLY: the faces together fill (2d+2w) x (d+h) scaled, with nothing
     * hanging off the end into a neighbour's rectangle. A part bleeding into the part packed beside
     * it is precisely how a skin scrambles.
     */
    @Test
    void noFaceEscapesItsOwnPackedRectangle() {
        for (int density : new int[]{1, 2, 4}) {
            CreatureSculpt.setTexelsPerUnitForTest(density);
            int w = 5;
            int h = 9;
            int d = 3;
            int x = 16;
            int y = 32;
            JsonObject uv = CreatureSculpt.faceUv(x, y, w, h, d);
            int netW = (2 * d + 2 * w) * density;
            int netH = (d + h) * density;
            for (String face : new String[]{"up", "down", "east", "north", "west", "south"}) {
                int[] r = rect(uv, face);
                assertTrue(r[0] >= x && r[0] + r[2] <= x + netW,
                        face + " ran outside the net horizontally at density " + density);
                assertTrue(r[1] >= y && r[1] + r[3] <= y + netH,
                        face + " ran outside the net vertically at density " + density);
            }
        }
    }

    @Test
    void theAtlasGrowsWithTheDensityAndTheCreatureStillBakes() {
        String json = """
                [{"name":"body","from":[-9,0,-8],"to":[9,10,8],"colour":"purple"},
                 {"name":"head","from":[-7,10,-6],"to":[7,24,8],"colour":"purple"}]""";
        CreatureSculpt.setTexelsPerUnitForTest(1);
        int small = CreatureSculpt.bake(parse(json)).textureSize();
        CreatureSculpt.setTexelsPerUnitForTest(4);
        var baked = CreatureSculpt.bake(parse(json));
        assertTrue(baked.textureSize() > small,
                "a denser skin needs a bigger atlas: " + small + " -> " + baked.textureSize());
        assertEquals(2, baked.geometry().getAsJsonArray("minecraft:geometry").get(0)
                .getAsJsonObject().getAsJsonArray("bones").size());
    }

    /** Geometry must now carry per-face UV, because box-UV cannot express a density at all. */
    @Test
    void geometryEmitsPerFaceUvNotBoxUv() {
        var baked = CreatureSculpt.bake(parse(
                "[{\"name\":\"body\",\"from\":[-4,0,-2],\"to\":[4,12,2],\"colour\":\"green\"}]"));
        JsonObject cube = baked.geometry().getAsJsonArray("minecraft:geometry").get(0)
                .getAsJsonObject().getAsJsonArray("bones").get(0).getAsJsonObject()
                .getAsJsonArray("cubes").get(0).getAsJsonObject();
        assertTrue(cube.get("uv").isJsonObject(), "uv must be a per-face object, not a [u,v] pair");
        // ...and the parser the renderer uses must agree that it is per-face.
        var geo = BedrockGeometry.fromJson(baked.geometry());
        assertTrue(geo.bones().get(0).cubes().get(0).uv().isPerFace(),
                "BedrockGeometry should read it back as per-face");
    }

    /**
     * The point of the whole change: a micro pattern gets finer with density, and a macro pattern
     * keeps its physical size. Chucko's stripes were correct and must stay correct; Sparky's fur
     * was slabs and must stop being slabs.
     */
    @Test
    void microPatternsGetFinerWhileMacroPatternsKeepTheirSize() {
        var net = new CreatureSculpt.PaintRect(0, 0, 64, 64, 0x00AA00);
        CreatureSculpt.Part fur = patterned("fur");
        CreatureSculpt.Part stripes = patterned("stripes");

        CreatureSculpt.setTexelsPerUnitForTest(1);
        int furAt1 = SculptPattern.overlay(fur, net).size();
        int stripeBandAt1 = firstBandHeight(SculptPattern.overlay(stripes, net));

        CreatureSculpt.setTexelsPerUnitForTest(4);
        int furAt4 = SculptPattern.overlay(fur, net).size();
        int stripeBandAt4 = firstBandHeight(SculptPattern.overlay(stripes, net));

        assertTrue(furAt4 >= furAt1,
                "fur should not get coarser with density: " + furAt1 + " -> " + furAt4);
        assertEquals(stripeBandAt1 * 4, stripeBandAt4,
                "a stripe must stay the same PHYSICAL width, i.e. scale with the density");
    }

    private static int firstBandHeight(List<CreatureSculpt.PaintRect> rects) {
        return rects.isEmpty() ? 0 : rects.get(0).height();
    }

    private static CreatureSculpt.Part patterned(String pattern) {
        return new CreatureSculpt.Part("skin", null, new float[]{0, 0, 0},
                new float[]{0, 0, 0}, new float[]{16, 16, 16}, 0x00AA00,
                CreatureSculpt.NO_ROTATION, 0f, pattern, 0xFFFFFF);
    }

    private static void assertArrayEqualsInt(int[] expected, int[] actual) {
        assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
    }
}
