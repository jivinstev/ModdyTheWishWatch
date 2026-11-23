package com.moddingfromamod.runtime;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** a box too thin to draw is made 1 thick, outward, instead of dropped and sent back. */
class SculptThicknessTest {

    private static JsonArray parts(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    @Test
    void aThinPupilGrowsOutwardAndIsKept() {
        // A face at z -14, a pupil 0.5 deep sitting on it: its outer face is the more negative one.
        JsonArray p = parts("""
                [{"name":"head","from":[-4,9,-14],"to":[4,16,-8],"colour":"tan"},
                 {"name":"pupilL","from":[1,12,-14.5],"to":[2,13,-14],"colour":"black"}]""");
        assertEquals(List.of("pupilL"), SculptThickness.thicken(p));
        var pupil = p.get(1).getAsJsonObject();
        assertEquals(-15.0f, pupil.getAsJsonArray("from").get(2).getAsFloat(), 1e-4,
                "it grows away from the middle, so it stays proud of the face");
        assertEquals(-14.0f, pupil.getAsJsonArray("to").get(2).getAsFloat(), 1e-4);
        var parsed = CreatureSculpt.parseParts(p);
        assertTrue(parsed.stream().anyMatch(x -> x.name().equals("pupilL")),
                "and the parser no longer drops it");
    }

    @Test
    void aThinBrowGrowsBothWaysVertically() {
        JsonArray p = parts("[{\"name\":\"browL\",\"from\":[1,14,-15],\"to\":[3,14.4,-14]}]");
        SculptThickness.thicken(p);
        var o = p.get(0).getAsJsonObject();
        assertEquals(13.7f, o.getAsJsonArray("from").get(1).getAsFloat(), 1e-4);
        assertEquals(14.7f, o.getAsJsonArray("to").get(1).getAsFloat(), 1e-4);
    }

    @Test
    void aDrawableBoxIsUntouchedAndNothingIsSaid() {
        JsonArray p = parts("[{\"name\":\"body\",\"from\":[-4,6,-8],\"to\":[4,13,8]}]");
        String before = p.toString();
        assertEquals(List.of(), SculptThickness.thicken(p));
        assertEquals(before, p.toString());
        assertEquals("", SculptThickness.note(List.of()));
    }

    @Test
    void theNoteSaysItIsDoneNotToDo() {
        String note = SculptThickness.note(List.of("eyeL", "eyeR"));
        assertTrue(note.contains("eyeL, eyeR"), note);
        assertTrue(note.contains("Nothing to fix"), note);
    }
}
