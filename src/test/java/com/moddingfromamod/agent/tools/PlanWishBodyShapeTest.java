package com.moddingfromamod.agent.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** the ready-made body a wish names is described with its boxes, before it is sculpted on. */
class PlanWishBodyShapeTest {

    private static List<CreatureSculpt.Part> starter(String key) throws Exception {
        var o = JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/starter-library/species/" + key + "/sculpt.json"))).getAsJsonObject();
        return CreatureSculpt.parseParts(o.getAsJsonArray("parts"));
    }

    /** The witch cat put its hat 14 px above the head, then on an ear's corner: it never knew y 16. */
    @Test
    void theStarterCatsOutlineSaysWhereItsHeadReallyIs() throws Exception {
        String o = SculptBase.outline("cat", starter("cat"));
        assertTrue(o.contains("body [-4,6,-8] → [4,13,8]"), o);
        assertTrue(o.contains("head [-4,9,-14] → [4,16,-8]"), o);
        assertTrue(o.contains("its head's TOP is y 16, centred on x 0, z -11"), o);
        assertTrue(o.contains("its FACE is the z -14 side"), o);
    }

    @Test
    void onlyABodyTheChildsWordsNameIsDescribed() {
        assertEquals("", PlanWishTool.bodyShape("a dragon that sneezes fire", List.of("pet_dragon", "cat")),
                "a body is matched by its whole name, and 'pet dragon' is not 'dragon'");
        assertEquals("", PlanWishTool.bodyShape("a catapult", List.of("cat")),
                "a whole word, not a prefix");
        assertEquals("", PlanWishTool.bodyShape("", List.of("cat")));
    }
}
