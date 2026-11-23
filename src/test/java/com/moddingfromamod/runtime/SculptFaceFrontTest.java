package com.moddingfromamod.runtime;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** a face sunk just inside the head is brought forward together, not sent back. */
class SculptFaceFrontTest {

    private static final String HEAD =
            "{\"name\":\"head\",\"from\":[-4,9,-14],\"to\":[4,16,-8],\"colour\":\"tan\"},"
            + "{\"name\":\"body\",\"from\":[-4,0,-8],\"to\":[4,9,8],\"colour\":\"tan\"},"
            + "{\"name\":\"tail\",\"from\":[-1,4,8],\"to\":[1,6,14],\"colour\":\"tan\"}";

    private static JsonArray parts(String more) {
        return JsonParser.parseString("[" + HEAD + "," + more + "]").getAsJsonArray();
    }

    private static float z(JsonArray p, int i, String k) {
        return p.get(i).getAsJsonObject().getAsJsonArray(k).get(2).getAsFloat();
    }

    @Test
    void sunkenEyesComeForwardTogetherAndKeepTheirLayering() {
        JsonArray p = parts("""
                {"name":"eyeL","from":[1,12,-13],"to":[3,14,-12],"colour":"white"},
                {"name":"pupilL","from":[2,13,-14],"to":[3,14,-13],"colour":"black"}""");
        assertTrue(SculptCritique.buriedFaces(CreatureSculpt.parseParts(p)).size() >= 1,
                "the fixture must actually be buried, or this proves nothing");
        var r = SculptFaceFront.apply(p);
        assertEquals(List.of("eyeL", "pupilL"), r.moved());
        // Sculpt coordinates are whole units once parsed, so the shift is measured from the
        // parsed eye (front at -13) to 0.5 past the face (-14): 1.5.
        assertEquals(-14.5f, z(p, 3, "from"), 1e-4, "the eye now sits 0.5 proud of the face");
        assertTrue(z(p, 4, "from") < z(p, 3, "from"), "and the pupil is still in front of it");
        assertTrue(SculptCritique.buriedFaces(CreatureSculpt.parseParts(p)).isEmpty(),
                "after the move nothing on the face is buried: " + SculptCritique.buriedFaces(
                        CreatureSculpt.parseParts(p)));
        assertTrue(r.note().contains("eyeL"), r.note());
    }

    @Test
    void aMouthUnderABeakIsTheAgentsCall() {
        JsonArray p = parts("""
                {"name":"beak","from":[-2,10,-17],"to":[2,12,-13],"colour":"orange"},
                {"name":"mouth","from":[-1,10.5,-15],"to":[1,11.5,-14],"colour":"black"}""");
        assertEquals(List.of(), SculptFaceFront.apply(p).moved());
    }

    @Test
    void aFaceBuriedDeepIsNotANearMiss() {
        JsonArray p = parts("{\"name\":\"eyeL\",\"from\":[1,12,-9.5],\"to\":[3,14,-8.5],\"colour\":\"white\"}");
        String before = p.toString();
        assertEquals(List.of(), SculptFaceFront.apply(p).moved());
        assertEquals(before, p.toString());
    }

    @Test
    void aFaceAlreadyOnTheFaceIsLeftAlone() {
        JsonArray p = parts("{\"name\":\"eyeL\",\"from\":[1,12,-15],\"to\":[3,14,-14],\"colour\":\"white\"}");
        assertEquals(List.of(), SculptFaceFront.apply(p).moved());
        assertEquals("", SculptFaceFront.apply(p).note());
    }
}
