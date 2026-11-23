package com.moddingfromamod.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sculpt answer says LOPSIDED when a left/right pair does not mirror (2026-10-10). */
class SculptLopsidedTest {

    private static List<CreatureSculpt.Part> parse(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    /** The ready-made wolf's eyes before 2026-10-10: the right one twice as wide. */
    private static final String WOLF_EYES = """
            [{"name":"head","from":[-4,12,-15],"to":[4,20,-8],"colour":"#A0A0A0"},
             {"name":"eyeL","parent":"head","from":[2,17,-16],"to":[3,18,-15],"colour":"#404040"},
             {"name":"eyeR","parent":"head","from":[-3,17,-16],"to":[-1,18,-15],"colour":"#404040"}]""";

    @Test
    void theOldWolfEyesAreCalledLopsided() {
        var lop = SculptCritique.lopsided(parse(WOLF_EYES));
        assertEquals(List.of("eyeL is 1×1×1 but eyeR is 2×1×1"), lop);
        assertTrue(SculptCritique.critique(parse(WOLF_EYES)).describe().contains("⇔ LOPSIDED"));
    }

    @Test
    void anOffCentreLegIsCalledLopsidedButACatchlightIsNot() {
        var lop = SculptCritique.lopsided(parse("""
                [{"name":"legL","from":[1,0,-1],"to":[3,5,1],"colour":"y"},
                 {"name":"legR","from":[-2,0,-1],"to":[0,5,1],"colour":"y"},
                 {"name":"shineL","from":[-2,9,-18],"to":[-1,10,-17],"colour":"w"},
                 {"name":"shineR","from":[2,9,-18],"to":[3,10,-17],"colour":"w"}]"""));
        assertEquals(1, lop.size(), lop.toString());
        assertTrue(lop.get(0).startsWith("legL and legR"), lop.toString());
    }

    @Test
    void pairsAreFoundByName() {
        assertArrayEquals(new String[] {"eye|", "L"}, SculptCritique.side("eyeL"));
        assertArrayEquals(new String[] {"legB|", "R"}, SculptCritique.side("legBR"));
        assertArrayEquals(new String[] {"|_wing", "L"}, SculptCritique.side("left_wing"));
        assertArrayEquals(new String[] {"wing|", "R"}, SculptCritique.side("wingRight"));
        assertNull(SculptCritique.side("bright_eye"));
        assertNull(SculptCritique.side("tail"));
        assertNull(SculptCritique.side("TAIL"));
    }

    @Test
    void everyReadyMadeCreatureIsSymmetric() throws Exception {
        // The starter library AND the Builder's own bodies: both are offered to a child as they are.
        List<Path> all = new java.util.ArrayList<>();
        for (String root : new String[] {"src/main/resources/starter-library/species", "src/main/resources/builder-bodies/species"}) {
            try (var ds = Files.list(Path.of(root))) {
                all.addAll(ds.toList());
            }
        }
        {
            for (Path d : all) {
                var o = JsonParser.parseString(Files.readString(d.resolve("sculpt.json"))).getAsJsonObject();
                var parts = CreatureSculpt.parseParts(o.getAsJsonArray("parts"));
                assertEquals(List.of(), SculptCritique.lopsided(parts),
                        d.getFileName() + " is a ready-made creature every wish can start from");
            }
        }
    }
}
