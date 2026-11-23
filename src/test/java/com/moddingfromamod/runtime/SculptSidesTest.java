package com.moddingfromamod.runtime;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**, a maintainer's grades: fangs on the tail, toes backwards, a tail on the chest. */
class SculptSidesTest {

    /** The cat starter's layout: face at −z (head z −14..−8), a tail at +z. */
    private static final List<CreatureSculpt.Part> CAT = CreatureSculpt.parseParts(JsonParser.parseString("""
            [{"name":"body","from":[-4,6,-8],"to":[4,13,8],"colour":"orange"},
             {"name":"head","from":[-4,9,-14],"to":[4,16,-8],"colour":"orange"},
             {"name":"eyeL","from":[1,13,-15],"to":[3,14,-14],"colour":"green"},
             {"name":"nose","from":[-1,11,-15],"to":[1,12,-14],"colour":"pink"},
             {"name":"tail","from":[-1,10,8],"to":[1,12,16],"colour":"orange"}]""").getAsJsonArray());

    private static JsonArray parts(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    @Test
    void fangsAddedOnTheTailEndMoveToTheFace() {
        // The real Opus edit: start_from the cat, fangs at z 11..12.
        JsonArray sent = parts("""
                [{"name":"fangL","from":[-2,5,11],"to":[-1,11,12],"colour":"white"},
                 {"name":"fangR","from":[1,5,11],"to":[2,11,12],"colour":"white"}]""");
        assertEquals(List.of("fangL", "fangR"), SculptSides.onTheFace(sent, CAT));
        var fang = sent.get(0).getAsJsonObject();
        assertEquals(-12f, fang.getAsJsonArray("from").get(2).getAsFloat(), 1e-4);
        assertEquals(-11f, fang.getAsJsonArray("to").get(2).getAsFloat(), 1e-4);
        assertTrue(SculptSides.movedNote(List.of("fangL")).contains("fangL"));
    }

    @Test
    void facePartsAlreadyOnTheFaceAndReplacementsStay() {
        JsonArray sent = parts("""
                [{"name":"fangL","from":[-2,8,-15],"to":[-1,11,-14],"colour":"white"},
                 {"name":"nose","from":[-1,11,5],"to":[1,12,6],"colour":"black"},
                 {"name":"tuft","from":[-1,10,14],"to":[1,13,17],"colour":"white"}]""");
        String before = sent.toString();
        assertEquals(List.of(), SculptSides.onTheFace(sent, CAT),
                "on the face already; a REPLACED nose is the agent's call; a tuft is not a face part");
        assertEquals(before, sent.toString());
    }

    @Test
    void aBaseWithNoFaceIsLeftAlone() {
        var blob = CreatureSculpt.parseParts(parts("[{\"name\":\"body\",\"from\":[-4,0,-4],\"to\":[4,8,4]}]"));
        assertEquals(List.of(), SculptSides.onTheFace(parts(
                "[{\"name\":\"eyeL\",\"from\":[1,5,4],\"to\":[2,6,5]}]"), blob));
    }

    @Test
    void aTailOnTheChestIsSaid() {
        // The Spark bunny: eyes and the tail both at +z.
        var bunny = CreatureSculpt.parseParts(parts("""
                [{"name":"body","from":[-3,6,-3],"to":[3,14,3]},
                 {"name":"eyeL","from":[-3,18,4],"to":[-1,20,5]},
                 {"name":"tail","from":[-2,7,3],"to":[2,11,7]}]"""));
        assertTrue(SculptSides.note(bunny).contains("TAIL ON THE FACE SIDE"), SculptSides.note(bunny));
        assertEquals("", SculptSides.note(CAT), "the cat's tail is behind it");
    }

    @Test
    void toesAllPointingAwayFromTheFaceAreSaidButABirdsHindToeIsNot() {
        // The Spark shark with chicken legs: face at −z, every toe at +z.
        var shark = CreatureSculpt.parseParts(parts("""
                [{"name":"head","from":[-7,12,-22],"to":[7,19,-12]},
                 {"name":"eyeL","from":[5,15,-23],"to":[7,17,-21]},
                 {"name":"toeL1","from":[1,0,4],"to":[2,1,7]},
                 {"name":"toeL2","from":[3,0,4],"to":[4,1,7]}]"""));
        assertTrue(SculptSides.note(shark).contains("TOES POINT BACKWARDS"), SculptSides.note(shark));
        var bird = CreatureSculpt.parseParts(parts("""
                [{"name":"head","from":[-3,12,-8],"to":[3,18,-2]},
                 {"name":"beak","from":[-1,14,-11],"to":[1,15,-8]},
                 {"name":"toeFront","from":[0,0,-4],"to":[1,1,-1]},
                 {"name":"toeBack","from":[0,0,1],"to":[1,1,3]}]"""));
        assertEquals("", SculptSides.note(bird), "a bird's one hind toe is right");
    }
}
