package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Gate A for "which pixels are his belt?". The rectangle maths behind painting one body part has to
 * be right before any pixels move — a wrong rect silently paints the creature's face green.
 */
class UvRegionsTest {

    /** A humanoid-ish model: a body, two wings, and a head — the shape a child names parts on. */
    private static final BedrockGeometry MODEL = BedrockGeometry.fromJson(
            JsonParser.parseString("""
                {"minecraft:geometry":[{"description":{"texture_width":64,"texture_height":64},
                  "bones":[
                    {"name":"body","cubes":[{"origin":[-4,0,-2],"size":[8,12,4],"uv":[0,0]}]},
                    {"name":"head","parent":"body","cubes":[{"origin":[-4,12,-4],"size":[8,8,8],"uv":[32,0]}]},
                    {"name":"wingL","parent":"body","cubes":[{"origin":[4,4,0],"size":[6,6,1],"uv":[0,20]}]},
                    {"name":"wingR","parent":"body","cubes":[{"origin":[-10,4,0],"size":[6,6,1],"uv":[0,32]}]}
                  ]}]}
                """).getAsJsonObject());

    private static BedrockGeometry.Cube cubeOf(String bone) {
        for (BedrockGeometry.Bone b : MODEL.bones()) {
            if (b.name().equals(bone)) {
                return b.cubes().get(0);
            }
        }
        throw new IllegalArgumentException(bone);
    }

    @Test
    void cubeRectCoversTheWholeBoxUvNet() {
        // body: uv(0,0), size 8x12x4 -> net is (2*dz + 2*dx) x (dz + dy) = 24 x 16
        UvRegions.Rect r = UvRegions.cubeRect(cubeOf("body"));
        assertEquals(0, r.x());
        assertEquals(0, r.y());
        assertEquals(24, r.width());
        assertEquals(16, r.height());
    }

    @Test
    void faceRectsTileTheNetWithoutOverlappingTheWrongFace() {
        BedrockGeometry.Cube body = cubeOf("body");
        // The bedrock net: [east][north][west][south] across, with [up][down] on the top strip.
        UvRegions.Rect north = UvRegions.faceRect(body, UvRegions.Face.NORTH);
        UvRegions.Rect up = UvRegions.faceRect(body, UvRegions.Face.UP);
        assertEquals(4, north.x());   // after the dz-wide east face
        assertEquals(4, north.y());   // below the dz-tall top strip
        assertEquals(8, north.width());
        assertEquals(12, north.height());
        assertEquals(4, up.x());
        assertEquals(0, up.y());
        assertEquals(8, up.width());
        assertEquals(4, up.height());
    }

    @Test
    void bonePixelsFindsAPartByLooseName() {
        // Exact.
        assertEquals(1, UvRegions.bonePixels(MODEL, "body").size());
        // "wing" should find BOTH wings — that's what "make his wings purple" means.
        assertEquals(2, UvRegions.bonePixels(MODEL, "wing").size());
        // Case-insensitive.
        assertEquals(1, UvRegions.bonePixels(MODEL, "HEAD").size());
        // Nothing matches -> empty, so the caller can say "I couldn't find that part".
        assertTrue(UvRegions.bonePixels(MODEL, "tail").isEmpty());
        assertTrue(UvRegions.bonePixels(MODEL, "").isEmpty());
        assertTrue(UvRegions.bonePixels(null, "body").isEmpty());
    }

    @Test
    void exactNameWinsOverASubstringMatch() {
        // "body" must resolve to the body bone alone even though other names could contain it.
        List<BedrockGeometry.Bone> hit = UvRegions.matchBones(MODEL, "body");
        assertEquals(1, hit.size());
        assertEquals("body", hit.get(0).name());
    }

    @Test
    void aBeltIsAHorizontalBandOnTheSideFacesOnly() {
        // 4 side faces of the one body cube.
        List<UvRegions.Rect> belt = UvRegions.bandPixels(MODEL, "body", 0.35f, 0.55f);
        assertEquals(4, belt.size());

        UvRegions.Rect north = UvRegions.faceRect(cubeOf("body"), UvRegions.Face.NORTH);
        UvRegions.Rect band = belt.stream().filter(r -> r.x() == north.x()).findFirst().orElseThrow();
        // The band sits inside the face, is thinner than it, and is nowhere near the top (the head end).
        assertTrue(band.height() < north.height(), "a belt is thinner than the whole torso");
        assertTrue(band.y() > north.y(), "a belt is below the top of the torso");
        assertTrue(band.y() + band.height() <= north.y() + north.height(),
                "the band must stay inside the face");
        // v grows downward, so a belt at 35-55% height sits in the LOWER half of the face rect.
        assertTrue(band.y() > north.y() + north.height() / 3, "belt should be around the middle/low");
    }

    @Test
    void bandsAreClampedToSaneFractions() {
        List<UvRegions.Rect> all = UvRegions.bandPixels(MODEL, "body", -5f, 99f);
        UvRegions.Rect north = UvRegions.faceRect(cubeOf("body"), UvRegions.Face.NORTH);
        UvRegions.Rect band = all.stream().filter(r -> r.x() == north.x()).findFirst().orElseThrow();
        // Clamped to the full face, never larger.
        assertEquals(north.height(), band.height());
        assertEquals(north.y(), band.y());
        // Reversed arguments still produce a valid band.
        assertFalse(UvRegions.bandPixels(MODEL, "body", 0.6f, 0.2f).isEmpty());
    }

    @Test
    void perFaceUvCubesAreUnionedRatherThanAssumedToBeANet() {
        BedrockGeometry g = BedrockGeometry.fromJson(JsonParser.parseString("""
            {"minecraft:geometry":[{"bones":[{"name":"b","cubes":[
              {"origin":[0,0,0],"size":[2,2,2],
               "uv":{"north":{"uv":[10,10],"uv_size":[2,2]},
                     "up":{"uv":[20,4],"uv_size":[2,2]}}}]}]}]}
            """).getAsJsonObject());
        UvRegions.Rect r = UvRegions.cubeRect(g.bones().get(0).cubes().get(0));
        assertEquals(10, r.x());
        assertEquals(4, r.y());
        assertEquals(12, r.width());   // 10 -> 22
        assertEquals(8, r.height());   // 4 -> 12
        assertNotNull(UvRegions.faceRect(g.bones().get(0).cubes().get(0), UvRegions.Face.NORTH));
    }

    @Test
    void boneNamesAreListedForTheDidNotFindThatMessage() {
        List<String> names = UvRegions.boneNames(MODEL);
        assertEquals(List.of("body", "head", "wingL", "wingR"), names);
        assertTrue(UvRegions.boneNames(null).isEmpty());
    }
}
