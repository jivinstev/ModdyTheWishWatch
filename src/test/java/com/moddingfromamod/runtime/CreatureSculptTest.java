package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Gate A for building a creature out of coloured boxes.
 *
 * <p>The fixture is deliberately <b>Chucko McBurger</b> — the request that exposed why this exists.
 * Reskinning a vanilla mob could never produce him: every humanoid texture the game ships is a
 * monster, and a tint only darkens, so a white face was unreachable. Here it's just a white box, and
 * these tests assert that the white stays white and lands where the face is.
 */
class CreatureSculptTest {

    /** A clown: white face, red hair, yellow body, red limbs. */
    private static final String CHUCKO = """
        [
          {"name":"body","from":[-4,12,-2],"to":[4,24,2],"colour":"yellow"},
          {"name":"head","parent":"body","from":[-4,24,-4],"to":[4,32,4],"colour":"white"},
          {"name":"hair","parent":"head","from":[-5,31,-5],"to":[5,34,5],"colour":"red"},
          {"name":"armR","parent":"body","from":[-7,12,-2],"to":[-4,24,2],"colour":"red"},
          {"name":"armL","parent":"body","from":[4,12,-2],"to":[7,24,2],"colour":"red"},
          {"name":"legR","parent":"body","from":[-4,0,-2],"to":[0,12,2],"colour":"red"},
          {"name":"legL","parent":"body","from":[0,0,-2],"to":[4,12,2],"colour":"red"}
        ]
        """;

    private static List<CreatureSculpt.Part> parse(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    @Test
    void parsesEveryPartOfACharacter() {
        List<CreatureSculpt.Part> parts = parse(CHUCKO);
        assertEquals(7, parts.size());
        CreatureSculpt.Part head = parts.stream()
                .filter(p -> p.name().equals("head")).findFirst().orElseThrow();
        assertEquals("body", head.parent());
        assertEquals(0xFFFFFF, head.colour(), "a white face must stay white");
        assertEquals(8, head.width());
        assertEquals(8, head.height());
    }

    @Test
    void theColourThatWasImpossibleBeforeIsJustAColourNow() {
        // The whole point: white and bright yellow are unreachable by multiplying a zombie texture.
        List<CreatureSculpt.Part> parts = parse(CHUCKO);
        int white = parts.stream().filter(p -> p.name().equals("head"))
                .findFirst().orElseThrow().colour();
        int yellow = parts.stream().filter(p -> p.name().equals("body"))
                .findFirst().orElseThrow().colour();
        assertEquals(0xFFFFFF, white);
        assertTrue((yellow >> 16 & 0xFF) > 200 && (yellow >> 8 & 0xFF) > 180,
                "yellow should be bright, was #" + Integer.toHexString(yellow));
    }

    @Test
    void limbsPivotAtTheirTopAndOtherPartsAtTheirBase() {
        // A leg must swing from the hip, not spin around the foot.
        float[] leg = CreatureSculpt.defaultPivot("legR",
                new float[]{-4, 0, -2}, new float[]{0, 12, 2});
        assertEquals(12f, leg[1], 1e-6, "a leg pivots at the hip (its top)");
        float[] head = CreatureSculpt.defaultPivot("head",
                new float[]{-4, 24, -4}, new float[]{4, 32, 4});
        assertEquals(24f, head[1], 1e-6, "a head pivots at the neck (its base)");
        // Centred on X/Z either way.
        assertEquals(-2f, leg[0], 1e-6);
        assertEquals(0f, head[0], 1e-6);
    }

    @Test
    void anExplicitPivotWins() {
        List<CreatureSculpt.Part> parts = parse("""
            [{"name":"armR","from":[-7,12,-2],"to":[-4,24,2],"pivot":[-5,23,0],"colour":"red"}]
            """);
        assertEquals(23f, parts.get(0).pivot()[1], 1e-6);
    }

    @Test
    void bakingProducesGeometryEveryPartCanBeFoundIn() {
        CreatureSculpt.Baked baked = CreatureSculpt.bake(parse(CHUCKO));
        assertNotNull(baked);
        var geo = baked.geometry().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        var bones = geo.getAsJsonArray("bones");
        assertEquals(7, bones.size());
        // The atlas size is recorded so the renderer maps UVs correctly.
        int texSize = geo.getAsJsonObject("description").get("texture_width").getAsInt();
        assertEquals(baked.textureSize(), texSize);
        // Parent survives, so hair moves with the head.
        boolean hairParented = false;
        for (var el : bones) {
            var b = el.getAsJsonObject();
            if ("hair".equals(b.get("name").getAsString())) {
                hairParented = "head".equals(b.get("parent").getAsString());
            }
        }
        assertTrue(hairParented, "hair should be attached to the head");
    }

    @Test
    void everyPartGetsItsOwnPatchOfTheSkin() {
        // The bug this guards: an editor's auto-UV gave every cube [0,0], so all 17 boxes sampled
        // the same corner and the whole creature was one colour.
        CreatureSculpt.Baked baked = CreatureSculpt.bake(parse(CHUCKO));
        assertNotNull(baked);
        assertEquals(7, baked.paint().size());
        for (int i = 0; i < baked.paint().size(); i++) {
            for (int j = i + 1; j < baked.paint().size(); j++) {
                assertFalse(overlaps(baked.paint().get(i), baked.paint().get(j)),
                        "parts " + i + " and " + j + " share texture pixels");
            }
        }
        // And every rect is inside the atlas.
        for (var r : baked.paint()) {
            assertTrue(r.x() >= 0 && r.y() >= 0, "negative UV");
            assertTrue(r.x() + r.width() <= baked.textureSize(), "UV overflows the atlas");
            assertTrue(r.y() + r.height() <= baked.textureSize(), "UV overflows the atlas");
        }
    }

    private static boolean overlaps(CreatureSculpt.PaintRect a, CreatureSculpt.PaintRect b) {
        return a.x() < b.x() + b.width() && b.x() < a.x() + a.width()
                && a.y() < b.y() + b.height() && b.y() < a.y() + a.height();
    }

    @Test
    void theAtlasGrowsWhenACreatureNeedsMoreRoom() {
        // One box far too big for a 32px atlas must still bake, on a larger one.
        CreatureSculpt.Baked baked = CreatureSculpt.bake(parse("""
            [{"name":"body","from":[-20,0,-20],"to":[20,40,20],"colour":"green"}]
            """));
        assertNotNull(baked);
        assertTrue(baked.textureSize() >= 128,
                "a big creature needs a big atlas, got " + baked.textureSize());
    }

    @Test
    void junkPartsAreSkippedRatherThanLosingTheWholeCreature() {
        List<CreatureSculpt.Part> parts = parse("""
            [
              {"name":"body","from":[-4,0,-4],"to":[4,12,4],"colour":"blue"},
              {"from":[0,0,0],"to":[2,2,2]},
              {"name":"flat","from":[0,0,0],"to":[0,5,5]},
              {"name":"body","from":[0,0,0],"to":[2,2,2],"colour":"red"},
              {"name":"broken","from":"nope","to":[1,1,1]}
            ]
            """);
        // Only the first, valid body survives: unnamed, flat, duplicate and malformed are dropped.
        assertEquals(1, parts.size());
        assertEquals("body", parts.get(0).name());
        assertEquals(0x5080FF, parts.get(0).colour());
    }

    @Test
    void nothingUsableBakesToNullRatherThanThrowing() {
        assertNull(CreatureSculpt.bake(List.of()));
        assertNull(CreatureSculpt.bake(null));
        assertTrue(CreatureSculpt.parseParts(null).isEmpty());
        assertTrue(CreatureSculpt.parseParts(new JsonArray()).isEmpty());
    }

    @Test
    void aParentThatDoesNotExistIsDroppedNotWritten() {
        // Referencing a missing bone would make the renderer treat it as a root anyway; better to
        // not claim it in the file at all.
        CreatureSculpt.Baked baked = CreatureSculpt.bake(parse("""
            [{"name":"body","from":[-4,0,-4],"to":[4,12,4],"colour":"green",
              "parent":"nonexistent"}]
            """));
        assertNotNull(baked);
        var bone = baked.geometry().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones").get(0).getAsJsonObject();
        assertFalse(bone.has("parent"), "a dangling parent should not be written");
    }

    /** an earlier issue's lopsided Puffo: the archived sculpt (corpus/wish-runs/2026-11-18-2539-briefs, s2539a-mob_puffo-r2). */
    private static final String LOPSIDED_PUFFO = "[{\"name\":\"body\",\"from\":[-8,4,-8],\"to\":[8,20,8]},"
            + "{\"name\":\"head\",\"from\":[-7,8,-9],\"to\":[7,18,-8]},"
            + "{\"name\":\"eyeL\",\"from\":[-4,12,-9.5],\"to\":[-2,17,-8],\"colour\":\"blue\"},"
            + "{\"name\":\"eyeR\",\"from\":[2,12,-9.5],\"to\":[4,17,-8],\"colour\":\"blue\"},"
            + "{\"name\":\"pupilL\",\"from\":[-4,15,-10.4],\"to\":[-3,17,-9.4],\"colour\":\"black\"},"
            + "{\"name\":\"pupilR\",\"from\":[3,15,-10],\"to\":[4,17,-9],\"colour\":\"black\"}]";

    private static CreatureSculpt.Part named(java.util.List<CreatureSculpt.Part> ps, String n) {
        return ps.stream().filter(p -> p.name().equals(n)).findFirst().orElseThrow();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#2539: the archived Puffo's eyes stay proud of its face plate after rounding, both of them")
    void halfProudFrontDetailsStayProud() {
        var ps = CreatureSculpt.parseParts(com.google.gson.JsonParser.parseString(LOPSIDED_PUFFO).getAsJsonArray());
        var head = named(ps, "head");
        for (String eye : new String[]{"eyeL", "eyeR"}) {
            var e = named(ps, eye);
            org.junit.jupiter.api.Assertions.assertTrue(e.from()[2] < head.from()[2],
                    eye + " front " + e.from()[2] + " must stand in front of the plate's " + head.from()[2]
                            + ", or the two faces fight and one eye shows");
        }
        org.junit.jupiter.api.Assertions.assertEquals(named(ps, "eyeL").from()[2], named(ps, "eyeR").from()[2]);
        org.junit.jupiter.api.Assertions.assertTrue(named(ps, "pupilL").from()[2] < named(ps, "eyeL").from()[2]
                || named(ps, "pupilL").from()[1] > named(ps, "eyeL").from()[1], "the pupil is still in front of its eye");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#2539: a pair mirrored on half units is still mirrored after rounding (half away from zero)")
    void halfUnitPairsStayMirrored() {
        var ps = CreatureSculpt.parseParts(com.google.gson.JsonParser.parseString(
                "[{\"name\":\"body\",\"from\":[-6,0,-4],\"to\":[6,10,4]},"
                        + "{\"name\":\"earL\",\"from\":[-4.5,10,-1],\"to\":[-2.5,14,1]},"
                        + "{\"name\":\"earR\",\"from\":[2.5,10,-1],\"to\":[4.5,14,1]}]").getAsJsonArray());
        var l = named(ps, "earL");
        var r = named(ps, "earR");
        org.junit.jupiter.api.Assertions.assertEquals(-l.from()[0], r.to()[0], 1e-6);
        org.junit.jupiter.api.Assertions.assertEquals(-l.to()[0], r.from()[0], 1e-6);
    }
}
