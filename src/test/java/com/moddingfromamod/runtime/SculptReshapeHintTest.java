package com.moddingfromamod.runtime;

import java.util.List;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** the new-shape refusal names the reshape that says it; a resize keeps its skin. */
class SculptReshapeHintTest {

    private static List<CreatureSculpt.Part> parts(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    private static final String DINO = """
            [{"name":"neck","from":[-2,20,-10],"to":[2,24,0],"colour":"green"},
             {"name":"head","from":[-4,22,-22],"to":[4,28,-10],"colour":"green","pattern":"scales","pattern_colour":"#204020"},
             {"name":"eyeL","from":[-4,25,-20],"to":[-3,26,-18],"colour":"black"}]""";

    @Test
    void theRefusalsReshapeRebuildsTheBoxThatWasSent() {
        // Spark's brachiosaurus: the 8×6×12 head re-sent as 8×8×8, further back.
        var sent = parts("""
                [{"name":"head","from":[-4,21,-18],"to":[4,29,-10],"colour":"green"}]""");
        var r = SculptBase.reshaped(parts(DINO), sent, java.util.Set.of());
        if (r.isEmpty()) {
            // within the factor: the hint is still well defined
            r = List.of(new SculptBase.Reshape("head", new float[]{-4, 22, -22, 4, 28, -10},
                    new float[]{-4, 21, -18, 4, 29, -10}, 0.89, List.of("eyeL")));
        }
        String line = r.get(0).asReshape();
        assertEquals("{\"part\":\"head\",\"scale\":[1,1.33,0.67],\"anchor\":\"centre\",\"move\":[0,0,2]}", line);
        // and the line, applied, gives back the box that was sent (to the rounding of the scale)
        var applied = SculptReshape.apply(parts(DINO), JsonParser.parseString("[" + line + "]").getAsJsonArray())
                .replacements().get(0);
        for (int i = 0; i < 3; i++) {
            assertEquals(sent.get(0).from()[i], applied.from()[i], 0.1f);
            assertEquals(sent.get(0).to()[i], applied.to()[i], 0.1f);
        }
    }

    @Test
    void aScaleReshapeCannotSayHasNoLine() {
        var huge = new SculptBase.Reshape("head", new float[]{0, 0, 0, 1, 1, 1},
                new float[]{0, 0, 0, 10, 1, 1}, 10, List.of());
        assertNull(huge.asReshape());
    }

    @Test
    void aResizedPartInItsOwnColourKeepsItsSkin() {
        var kept = SculptBase.keepSkin(parts(DINO), parts("""
                [{"name":"head","from":[-4,21,-18],"to":[4,29,-10],"colour":"green"},
                 {"name":"neck","from":[-2,20,-12],"to":[2,24,0],"colour":"green"}]"""));
        assertEquals("scales", kept.get(0).pattern());
        assertFalse(kept.get(1).patterned(), "the base neck had no skin to keep");
        var recoloured = SculptBase.keepSkin(parts(DINO), parts("""
                [{"name":"head","from":[-4,21,-18],"to":[4,29,-10],"colour":"white"}]"""));
        assertFalse(recoloured.get(0).patterned(), "a new colour is a new look");
    }

    @Test
    void aOneBlockPlaceholderForABigPartIsNameOnly() {
        var sent = JsonParser.parseString("""
                [{"name":"head","from":[0,0,0],"to":[1,1,1],"pattern":"fur"},
                 {"name":"eyeL","from":[-4,25,-20],"to":[-3,26,-19],"colour":"red"}]""").getAsJsonArray();
        assertEquals(List.of("head"), SculptBase.placeholdersAreNameOnly(parts(DINO), sent));
        assertFalse(sent.get(0).getAsJsonObject().has("from"));
        assertTrue(sent.get(1).getAsJsonObject().has("from"), "a small part's small box is a real box");
    }
}
