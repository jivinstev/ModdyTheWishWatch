package com.moddingfromamod.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning creatures the right way round.
 *
 * <p>The ground truth is a photograph, not an argument: {@code FacingProbeClientTest} put a red slab
 * at model +Z and a blue slab at −Z on a creature with body-yaw 0, stood the camera where the
 * creature was looking, and saw <b>blue</b>. Model −Z is the front.
 */
class SculptOrientationTest {

    private static List<CreatureSculpt.Part> parse(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    /** Built the way the agent actually builds: snout at +z, tail at −z. Backwards. */
    private static final String AS_BUILT = """
            [{"name":"body","from":[-6,10,-8],"to":[6,26,8],"colour":"green"},
             {"name":"snout","from":[-4,16,8],"to":[4,22,20],"colour":"tan"},
             {"name":"eyeL","from":[1,22,6],"to":[3,24,10],"colour":"white"},
             {"name":"tail","from":[-3,12,-20],"to":[3,18,-8],"colour":"green"}]""";

    @Test
    void aCreatureBuiltSnoutForwardIsDetectedAsBackwards() {
        assertTrue(SculptOrientation.facesBackwards(parse(AS_BUILT)));
    }

    @Test
    void flippingPutsTheSnoutInFrontAndTheTailBehind() {
        var fixed = SculptOrientation.faceForward(parse(AS_BUILT));
        double snoutZ = mid(fixed, "snout");
        double tailZ = mid(fixed, "tail");
        assertTrue(snoutZ < 0, "the snout must end up at negative z (the front), was " + snoutZ);
        assertTrue(tailZ > 0, "the tail must end up at positive z (the back), was " + tailZ);
        assertTrue(snoutZ < tailZ, "a snout cannot be behind a tail");
    }

    @Test
    void aCreatureAlreadyFacingTheRightWayIsLeftAlone() {
        var already = parse("""
                [{"name":"body","from":[-6,10,-8],"to":[6,26,8],"colour":"green"},
                 {"name":"snout","from":[-4,16,-20],"to":[4,22,-8],"colour":"tan"},
                 {"name":"tail","from":[-3,12,8],"to":[3,18,20],"colour":"green"}]""");
        assertFalse(SculptOrientation.facesBackwards(already));
        assertEquals(already, SculptOrientation.faceForward(already));
    }

    /** No face, no tail, no evidence — mirroring on a guess is worse than leaving it. */
    @Test
    void aCreatureWithNoFacingCuesIsNotTouched() {
        var blob = parse("""
                [{"name":"body","from":[-8,0,-8],"to":[8,16,8],"colour":"purple"},
                 {"name":"blobTop","from":[-6,16,-6],"to":[6,22,6],"colour":"purple"}]""");
        assertFalse(SculptOrientation.facesBackwards(blob));
        assertEquals(blob, SculptOrientation.faceForward(blob));
    }

    /**
     * Reflecting z inverts handedness. If X and Y tilts did not reverse with it, every spike would
     * end up raked the wrong way — the same class of defect a human caught by eye in the kits.
     */
    @Test
    void mirroringReversesXandYTiltsButNotZ() {
        var p = new CreatureSculpt.Part("spike", null, new float[]{0, 10, 4},
                new float[]{-1, 10, 2}, new float[]{1, 16, 6}, 0xFFFFFF,
                new float[]{30, 20, 45}, 0f, "", 0);
        var m = SculptOrientation.mirrorZ(p);
        assertEquals(-30f, m.rotation()[0], "X tilt must reverse");
        assertEquals(-20f, m.rotation()[1], "Y tilt must reverse");
        assertEquals(45f, m.rotation()[2], "Z tilt must NOT reverse");
        // ...and the box occupies the mirrored span, with from still the low corner.
        assertEquals(-6f, m.from()[2]);
        assertEquals(-2f, m.to()[2]);
        assertEquals(-4f, m.pivot()[2]);
    }

    @Test
    void mirroringKeepsEverythingElseIntact() {
        var p = new CreatureSculpt.Part("skirt", "body", new float[]{0, 5, 0},
                new float[]{-4, 0, -3}, new float[]{4, 8, 3}, 0x00AA00,
                CreatureSculpt.NO_ROTATION, 1.5f, "stripes", 0xFFFF00);
        var m = SculptOrientation.mirrorZ(p);
        assertEquals("skirt", m.name());
        assertEquals("body", m.parent());
        assertEquals(0x00AA00, m.colour());
        assertEquals(1.5f, m.inflate());
        assertEquals("stripes", m.pattern());
        assertEquals(0xFFFF00, m.patternColour());
        // X and Y untouched: a mirror front-to-back must not change left/right or height.
        assertEquals(-4f, m.from()[0]);
        assertEquals(8f, m.to()[1]);
    }

    /**
     * <b>The repro, on the creatures a human actually judged.</b>
     *
     * <p>Before the fix, {@code inspect_creature} reported Spikor's mouth at forward −1.78 and his
     * tail at +2.41 — the tail in front of the mouth. That contradiction is what sent a live agent
     * probing with marker entities for four minutes.
     */
    @Test
    void realCapturedCreaturesStopReportingTheirTailInFront() throws IOException {
        int checked = 0;
        for (String f : List.of("/tmp/mfam-ab-B/B__bowser.json",
                                "/tmp/mfam-ab2-A/A__sonic.json",
                                "/tmp/mfam-ab3-B/B__zizzle.json")) {
            Path p = Path.of(f);
            if (!Files.isRegularFile(p)) {
                continue;
            }
            JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            var parts = SculptCreatureTool.withKits(
                    CreatureSculpt.parseParts(o.getAsJsonArray("parts")), o);
            var geo = BedrockGeometry.fromJson(CreatureSculpt.bake(parts).geometry());
            double scale = o.has("scale") ? o.get("scale").getAsDouble() : 1.0;

            Double mouth = null;
            Double tail = null;
            for (CreatureAnatomy.Landmark l : CreatureAnatomy.landmarks(geo, scale)) {
                if (l.name().equals("mouth") || l.name().equals("snout")) {
                    mouth = mouth == null ? l.forward() : mouth;
                }
                if (l.name().equals("tail")) {
                    tail = l.forward();
                }
            }
            if (mouth == null || tail == null) {
                continue;
            }
            checked++;
            assertTrue(mouth > tail,
                    p.getFileName() + ": the mouth (" + mouth + ") must be further forward than "
                            + "the tail (" + tail + ")");
            assertTrue(mouth > 0,
                    p.getFileName() + ": a mouth must be in FRONT of the creature, was " + mouth);
        }
        if (checked == 0) {
            System.out.println("[86] no captured sculpts on disk — skipped the live repro");
        }
    }

    /**
     * <b>The invariant that has to survive both fixes at once (+).</b>
     *
     * <p>Kits are expanded in the creature's OWN frame and the whole assembly is mirrored
     * afterwards, so the two corrections must not fight. Whichever way the agent built the
     * creature, the finished model has to come out with its face at −Z (where the renderer looks
     * for it) and its tail at +Z. Asserted end-to-end rather than on either half, because each
     * half is individually correct and it is the ORDER that could be wrong.
     */
    @Test
    void aTailEndsUpBehindTheRenderedFrontWhicheverWayItWasBuilt() {
        var tailKit = com.google.gson.JsonParser
                .parseString("[{\"kit\":\"tail\",\"on\":\"body\"}]").getAsJsonArray();
        for (int sign : new int[]{1, -1}) {
            int snoutNear = 12 * sign;
            int snoutFar = 20 * sign;
            var built = CreatureSculpt.parseParts(com.google.gson.JsonParser.parseString(
                    "[{\"name\":\"body\",\"from\":[-6,10,-12],\"to\":[6,26,12],\"colour\":\"green\"},"
                    + "{\"name\":\"snout\",\"from\":[-4,16," + Math.min(snoutNear, snoutFar)
                    + "],\"to\":[4,22," + Math.max(snoutNear, snoutFar)
                    + "],\"colour\":\"green\"}]").getAsJsonArray());

            var grown = new java.util.ArrayList<>(built);
            grown.addAll(SculptKits.expand(tailKit, built));
            var finished = SculptOrientation.faceForward(grown);

            double snoutZ = mid(finished, "snout");
            double tailZ = finished.stream()
                    .filter(p -> p.name().startsWith(SculptKits.MARK + "tail"))
                    .mapToDouble(p -> (p.from()[2] + p.to()[2]) / 2.0).average().orElseThrow();
            assertTrue(snoutZ < 0, "built at " + sign + ": the face must end up at -Z, was " + snoutZ);
            assertTrue(tailZ > 0, "built at " + sign + ": the tail must end up at +Z, was " + tailZ);
        }
    }

    private static double mid(List<CreatureSculpt.Part> parts, String name) {
        for (CreatureSculpt.Part p : parts) {
            if (p.name().equals(name)) {
                return (p.from()[2] + p.to()[2]) / 2.0;
            }
        }
        throw new IllegalStateException("no part " + name);
    }

    /** a face named only with sunglasses and a smile still says which way is front. */
    @org.junit.jupiter.api.Test
    void aFaceOfSunglassesAndASmileIsAFront() {
        var parts = CreatureSculpt.parseParts(com.google.gson.JsonParser.parseString("""
                [{"name":"body","from":[-4,0,-4],"to":[4,12,4],"colour":"yellow"},
                 {"name":"sunglassL","from":[-3,8,4],"to":[-1,9,6],"colour":"black"},
                 {"name":"smile","from":[-2,5,4],"to":[2,6,5],"colour":"black"}]""").getAsJsonArray());
        org.junit.jupiter.api.Assertions.assertEquals(1, SculptOrientation.frontSign(parts));
    }

    /** a visor at the front outvotes a backpack sent on the same side. */
    @org.junit.jupiter.api.Test
    void aVisorIsAFront() {
        var parts = CreatureSculpt.parseParts(com.google.gson.JsonParser.parseString("""
                [{"name":"body","from":[-4,0,-3],"to":[4,20,3],"colour":"white"},
                 {"name":"visor","from":[-3,22,4],"to":[3,26,5],"colour":"black"},
                 {"name":"visorGlint","from":[-2,24,5],"to":[0,25,6],"colour":"cyan"},
                 {"name":"backpack","from":[-3,10,3],"to":[3,18,6],"colour":"grey"}]""").getAsJsonArray());
        org.junit.jupiter.api.Assertions.assertEquals(1, SculptOrientation.frontSign(parts));
    }
}
