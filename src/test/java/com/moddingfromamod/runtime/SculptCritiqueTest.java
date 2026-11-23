package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mirror must reflect accurately, or it is worse than no mirror — an agent told "everything is
 * fine" trusts it.
 */
class SculptCritiqueTest {

    private static CreatureSculpt.Part box(String name, float x0, float y0, float z0,
                                           float x1, float y1, float z1, int colour) {
        return new CreatureSculpt.Part(name, null, new float[]{0, 0, 0},
                new float[]{x0, y0, z0}, new float[]{x1, y1, z1}, colour);
    }

    @Test
    @DisplayName("a part swallowed by a bigger one is reported as buried")
    void aSwallowedPartIsBuried() {
        // This is the Chucko shape: a face inside a head-sized block of hair.
        List<CreatureSculpt.Part> parts = List.of(
                box("face", -2, 20, -2, 2, 24, 2, 0xFFFFFF),
                box("hair", -5, 18, -5, 5, 27, 5, 0xCC0000));
        List<String> buried = SculptCritique.buried(parts);
        assertTrue(buried.stream().anyMatch(s -> s.startsWith("face")),
                "a face entirely inside the hair cannot be seen, so as far as the child is "
                        + "concerned it was never built. Got: " + buried);
    }

    @Test
    @DisplayName("a face framed by hair rather than wrapped in it is NOT buried")
    void aFramedFaceIsVisible() {
        // The accepted Chucko shape: hair at the sides and on top, face proud of it at the front.
        List<CreatureSculpt.Part> parts = List.of(
                box("face", -3, 20, -5, 3, 26, -3, 0xFFFFFF),
                box("hairL", -6, 20, -3, -3, 27, 3, 0xCC0000),
                box("hairR", 3, 20, -3, 6, 27, 3, 0xCC0000),
                box("hairTop", -6, 26, -3, 6, 29, 3, 0xCC0000));
        assertFalse(SculptCritique.buried(parts).stream().anyMatch(s -> s.startsWith("face")),
                "an over-eager buried check that flags a perfectly visible face would push the "
                        + "agent into rebuilding good work: " + SculptCritique.buried(parts));
    }

    @Test
    @DisplayName("the palette is reported by VOLUME, so one big block outweighs many small ones")
    void paletteIsVolumeWeighted() {
        List<CreatureSculpt.Part> parts = List.of(
                box("shell", -8, 0, -8, 8, 16, 8, 0x2E7D32),      // big green
                box("spike1", 0, 17, 0, 1, 18, 1, 0xCC0000),      // tiny red
                box("spike2", 2, 17, 0, 3, 18, 1, 0xCC0000));
        var pal = SculptCritique.palette(parts);
        assertTrue(pal.containsKey("green") && pal.get("green") > 0.9,
                "a body dominated by one big green block is a green creature, whatever the part "
                        + "COUNT says: " + pal);
    }

    /**
     * The Spikor regression, as a unit test. v1 was mostly tan with a green shell; the later
     * revisions were overwhelmingly green. The critique must make that difference legible.
     */
    @Test
    @DisplayName("the critique makes a palette collapse legible")
    void aPaletteCollapseIsVisibleInTheReport() {
        List<CreatureSculpt.Part> spikorish = List.of(
                box("belly", -6, 4, -4, 6, 14, 4, 0xF0B040),
                box("shell", -7, 5, 2, 7, 15, 6, 0x2E7D32),
                box("hair", -3, 16, -2, 3, 19, 2, 0xCC0000));
        List<CreatureSculpt.Part> allGreen = List.of(
                box("belly", -6, 4, -4, 6, 14, 4, 0x3FA34D),
                box("shell", -7, 5, 2, 7, 15, 6, 0x2E7D32),
                box("hair", -3, 16, -2, 3, 19, 2, 0xCC0000));
        String good = SculptCritique.critique(spikorish).describe();
        String bad = SculptCritique.critique(allGreen).describe();
        assertTrue(good.contains("tan/orange"),
                "the tan belly must be named, or the agent cannot tell it lost it: " + good);
        assertFalse(bad.contains("tan/orange"),
                "the all-green version must NOT claim a tan share: " + bad);
        assertTrue(bad.contains("green"), bad);
    }

    @Test
    @DisplayName("the report always tells the agent to compare against the real character")
    void theReportAsksForAComparison() {
        String text = SculptCritique.critique(List.of(
                box("body", 0, 0, 0, 8, 8, 8, 0x3FA34D))).describe();
        assertTrue(text.contains("REALLY looks like"),
                "the facts are useless unless the agent is told what to do with them: " + text);
    }

    // ----: a report has to name a REMEDY, not just a problem --------------------------

    @Test
    void aBuriedPartNamesWhatIsCoveringIt() {
        // Measured 2026-08-30: the agent was told "head -- only 47% of it can be seen" four times
        // on one wish and shipped anyway, because it had to infer the occluder from a 59-part
        // list. The blocking part is in hand where the sample is marked blocked, so name it.
        var head = box("head", 0, 20, 0, 8, 28, 8, 0xC08040);
        var mane = box("mane", -2, 18, -2, 10, 30, 10, 0xAA2222);
        var report = SculptCritique.critique(List.of(head, mane));

        assertFalse(report.buried().isEmpty(), "the head is wrapped in the mane");
        String line = report.buried().get(0);
        assertTrue(line.contains("head"), line);
        assertTrue(line.contains("covered by"), "must say what is covering it: " + line);
        assertTrue(line.contains("mane"), "must NAME the occluder: " + line);
    }

    @Test
    void theOccluderIsInTheAgentFacingText() {
        // A check the agent is never told about is worth zero, so assert on describe(), not just
        // on the record.
        var head = box("head", 0, 20, 0, 8, 28, 8, 0xC08040);
        var mane = box("mane", -2, 18, -2, 10, 30, 10, 0xAA2222);
        assertTrue(SculptCritique.critique(List.of(head, mane)).describe().contains("covered by mane"));
    }

    // ----: floating parts ---------------------------------------------------------------

    @Test
    void aDetachedPartIsFlagged() {
        // A REAL creature shape: a connected body and arm, plus one part off on its own. A
        // two-part fixture where neither touches would flag BOTH -- correctly, and uselessly.
        var body = box("body", 0, 0, 0, 8, 16, 8, 0x00AA00);
        var arm = box("armL", 8, 4, 0, 12, 14, 4, 0x00AA00);    // flush against the body
        var horn = box("horn", 40, 30, 0, 43, 36, 3, 0xEEEEDD); // nowhere near anything
        var report = SculptCritique.critique(List.of(body, arm, horn));

        assertEquals(1, report.floating().size(), report.floating().toString());
        assertTrue(report.floating().get(0).contains("horn"));
        assertTrue(report.describe().contains("FLOATING"), "the agent has to be told");
        assertTrue(report.hasProblems());
    }

    @Test
    void apartThatMerelyTouchesIsConnected() {
        // A limb sitting flush against a torso is the normal case and must never be flagged --
        // a check that cries wolf gets ignored, which is worse than not having it.
        var body = box("body", 0, 0, 0, 8, 16, 8, 0x00AA00);
        var arm = box("armL", 8, 4, 0, 12, 14, 4, 0x00AA00);    // shares the x=8 face exactly
        assertTrue(SculptCritique.critique(List.of(body, arm)).floating().isEmpty());
    }

    @Test
    void aSinglePartCreatureIsNeverFloating() {
        // Nothing to be connected TO. Flagging it would make every blob creature "broken".
        var blob = box("body", 0, 0, 0, 8, 8, 8, 0x00AA00);
        assertTrue(SculptCritique.critique(List.of(blob)).floating().isEmpty());
    }

    @Test
    void anEmptySculptHasNoFloatingList() {
        assertTrue(SculptCritique.critique(List.of()).floating().isEmpty());
    }

    // ---- the sampler was confidently wrong three ways; each is pinned here -------------------

    private static CreatureSculpt.Part boxP(String name, String parent, float x0, float y0, float z0,
                                            float x1, float y1, float z1, int colour) {
        return new CreatureSculpt.Part(name, parent, new float[]{0, 0, 0},
                new float[]{x0, y0, z0}, new float[]{x1, y1, z1}, colour);
    }

    @Test
    void aPartIsNotBuriedByItsOwnDetail() {
        // The false positive that mattered most: a head is covered by the snout and eyes that
        // BELONG to it. Reported on four real creatures in five, and its remedy ("move them out")
        // is what produces a bulging eye. Nothing that hangs off a part can bury it.
        var head = boxP("head", null, -7, 20, -6, 7, 32, 6, 0x2E8B57);
        var snout = boxP("snout", "head", -4, 22, -10, 4, 28, -6, 0xC08040);
        var eyeL = boxP("eyeL", "head", -6, 27, -7, -2, 31, -6, 0xFFFFFF);
        var eyeR = boxP("eyeR", "head", 2, 27, -7, 6, 31, -6, 0xFFFFFF);
        var report = SculptCritique.critique(List.of(head, snout, eyeL, eyeR));
        assertTrue(report.buried().isEmpty(),
                "a face built out of its own parts is not buried: " + report.buried());
    }

    @Test
    void somethingUNRELATEDCoveringTheFaceIsStillCaught() {
        // The defect the rule exists for has to survive the fix above.
        // A "snout" gives the creature a facing cue, so the face is judged from the FRONT --
        // which is the whole point: a head visible only from behind is still a hidden face.
        var head = boxP("head", null, -7, 20, -6, 7, 32, 6, 0x2E8B57);
        var snout = boxP("snout", "head", -3, 23, -8, 3, 27, -6, 0xC08040);
        var hair = boxP("hair", "body", -9, 18, -13, 9, 34, -6, 0xAA2222);
        assertFalse(SculptCritique.critique(List.of(head, snout, hair)).buried().isEmpty(),
                "a head wrapped in hair that is not part of it must still be reported");
    }

    @Test
    void rotationChangesWhereABoxActuallyIs() {
        // 53% of the parts on a real Spikor carry a rotation, and the sampler used to read
        // from/to straight -- testing a tilted spike as its upright footprint.
        var flat = new CreatureSculpt.Part("spike", null, new float[]{0, 0, 0},
                new float[]{0, 0, 0}, new float[]{2, 8, 2}, 0xEEEEDD);
        var tilted = new CreatureSculpt.Part("spike", null, new float[]{0, 0, 0},
                new float[]{0, 0, 0}, new float[]{2, 8, 2}, 0xEEEEDD,
                new float[]{90f, 0f, 0f}, 0f, "", 0);
        float[] a = SculptCritique.bounds(flat);
        float[] b = SculptCritique.bounds(tilted);
        assertTrue(Math.abs((a[4] - a[1]) - 8f) < 0.01f, "upright: 8 tall");
        assertTrue(Math.abs((b[5] - b[2]) - 8f) < 0.5f,
                "rotated 90 about X: the 8 is now in Z, not Y");
    }

    @Test
    void theRemedyDoesNotTellItToPushTheEyeOut() {
        // "Move them out" is what produced bulging eyes on a real creature. The advice has to lead
        // with the fix that cannot make the creature worse.
        var head = boxP("head", null, -7, 20, -6, 7, 32, 6, 0x2E8B57);
        var snout = boxP("snout", "head", -3, 23, -8, 3, 27, -6, 0xC08040);
        var hair = boxP("hair", "body", -9, 18, -13, 9, 34, -6, 0xAA2222);
        String note = SculptCritique.critique(List.of(head, snout, hair)).describe();
        assertTrue(note.contains("FIRST make whatever covers them smaller"), note);
        assertTrue(note.contains("last resort"), "moving the part itself must be the fallback");
    }

    private static java.util.List<CreatureSculpt.Part> face(String extra) {
        return CreatureSculpt.parseParts(com.google.gson.JsonParser.parseString("[{\"name\":\"head\",\"from\":[-4,0,-4],\"to\":[4,8,4],\"colour\":\"#E8B88A\"},"
                + "{\"name\":\"eyeL\",\"from\":[-3,4,-5],\"to\":[-1,6,-4],\"colour\":\"#2050C0\"},"
                + "{\"name\":\"eyeR\",\"from\":[1,4,-5],\"to\":[3,6,-4],\"colour\":\"#2050C0\"}" + extra + "]").getAsJsonArray());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#2539: solid one-colour eyes are reported as BLANK EYES; a pupil, a darker box in front, or his words asking for solid eyes are not")
    void blankEyes() {
        var blank = SculptCritique.critique(face(""), "make me Link");
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("eyeL", "eyeR"), blank.blankEyes());
        org.junit.jupiter.api.Assertions.assertTrue(blank.describe().contains("⚠ BLANK EYES — eyeL, eyeR"), blank.describe());
        var pupils = face(",{\"name\":\"pupilL\",\"from\":[-2,4,-6],\"to\":[-1,5,-5],\"colour\":\"black\"}");
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), SculptCritique.critique(pupils, "").blankEyes());
        var unnamed = face(",{\"name\":\"dotL\",\"from\":[-2,4,-6],\"to\":[-1,5,-5],\"colour\":\"black\"}");
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), SculptCritique.critique(unnamed, "").blankEyes());
        var spot = face(",{\"name\":\"spec\",\"from\":[-2,5,-6],\"to\":[-1,6,-5],\"colour\":\"white\"}");
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), SculptCritique.critique(spot, "").blankEyes());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(),
                SculptCritique.critique(face(""), "a robot with solid black eyes").blankEyes());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(),
                SculptCritique.critique(face(""), "a teddy with button eyes").blankEyes());
    }
}
