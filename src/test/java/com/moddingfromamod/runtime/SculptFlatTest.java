package com.moddingfromamod.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sculpt answer says FLAT when the biggest parts carry no pattern (2026-10-10). */
class SculptFlatTest {

    private static List<CreatureSculpt.Part> parse(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    /** The lava turtle Muse built from nothing on 2026-10-10, nine plain boxes (shape abridged). */
    private static final String PLAIN = """
            [{"name":"shell","from":[-8,4,-8],"to":[8,10,8],"colour":"red"},
             {"name":"belly","from":[-7,2,-7],"to":[7,4,7],"colour":"orange"},
             {"name":"head","from":[-3,4,-12],"to":[3,9,-8],"colour":"orange"},
             {"name":"legFL","from":[-8,0,-7],"to":[-5,2,-4],"colour":"orange"},
             {"name":"top","from":[-5,10,-5],"to":[5,11,5],"colour":"yellow"}]""";

    @Test
    void plainBoxesAreCalledFlatByTheirBiggestParts() {
        var flat = SculptCritique.flat(parse(PLAIN));
        assertEquals(List.of("shell", "belly", "head"), flat);
        assertTrue(SculptCritique.critique(parse(PLAIN)).describe().contains("◻ FLAT"));
    }

    @Test
    void aPatternedBodyIsNotFlat() {
        String skinned = PLAIN.replace("\"colour\":\"red\"}",
                "\"colour\":\"red\",\"pattern\":\"scales\",\"pattern_colour\":\"#802020\"}");
        assertEquals(List.of(), SculptCritique.flat(parse(skinned)));
    }

    @Test
    void everyReadyMadeCreatureHasASkin() throws Exception {
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
                assertEquals(List.of(), SculptCritique.flat(parts),
                        d.getFileName() + " is a ready-made creature and should not read as flat");
            }
        }
    }
}
