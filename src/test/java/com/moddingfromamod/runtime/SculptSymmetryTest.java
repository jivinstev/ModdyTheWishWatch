package com.moddingfromamod.runtime;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** a pair a unit off its mirror is mirrored; a deliberate difference is left alone. */
class SculptSymmetryTest {

    private static JsonArray parts(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    @Test
    void theBearsEyesAreMirrored() {
        // The picked bear: eyes at x -3 and +4 — [-4,-2] and [3,5].
        JsonArray p = parts("""
                [{"name":"body","from":[-6,4,-8],"to":[6,14,8],"colour":"brown"},
                 {"name":"eyeL","from":[-4,16,-13],"to":[-2,18,-12],"colour":"black"},
                 {"name":"eyeR","from":[3,16,-13],"to":[5,18,-12],"colour":"black"}]""");
        assertEquals(List.of("eyeR"), SculptSymmetry.apply(p));
        var r = p.get(2).getAsJsonObject();
        assertEquals(2f, r.getAsJsonArray("from").get(0).getAsFloat(), 1e-4);
        assertEquals(4f, r.getAsJsonArray("to").get(0).getAsFloat(), 1e-4);
        assertTrue(SculptCritique.lopsided(CreatureSculpt.parseParts(p)).isEmpty(), "and the critique agrees");
    }

    @Test
    void aWinkOrARaisedArmIsTheAgentsCall() {
        JsonArray p = parts("""
                [{"name":"body","from":[-4,0,-4],"to":[4,16,4]},
                 {"name":"armL","from":[-8,8,-1],"to":[-4,16,1]},
                 {"name":"armR","from":[4,14,-1],"to":[8,22,1]},
                 {"name":"eyeL","from":[-3,20,-5],"to":[-1,22,-4]},
                 {"name":"eyeR","from":[1,20,-5],"to":[3,21,-4],"rotation":[0,0,30]}]""");
        String before = p.toString();
        assertEquals(List.of(), SculptSymmetry.apply(p), "an arm 6 up, and an eye tilted differently");
        assertEquals(before, p.toString());
    }

    @Test
    void anExactPairIsUntouched() {
        JsonArray p = parts("""
                [{"name":"earL","from":[-3,8,0],"to":[-1,11,1]},
                 {"name":"earR","from":[1,8,0],"to":[3,11,1]}]""");
        assertEquals(List.of(), SculptSymmetry.apply(p));
        assertEquals("", SculptSymmetry.note(List.of()));
    }
}
