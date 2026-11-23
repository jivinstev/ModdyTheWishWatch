package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** make_item's {@code block} object folds into {@code params._block}. */
class MagicBlockSettingsFoldTest {

    private static JsonObject fold(String json) {
        return MakeItemTool.foldBlockSettings(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void theBlockSettingsMoveIntoParams() {
        JsonObject out = fold("{\"effect\":\"launch\",\"block\":{\"when\":\"stepped_on\"}}");
        assertFalse(out.has("block"));
        assertEquals("stepped_on", out.getAsJsonObject("params")
                .getAsJsonObject(MakeItemTool.BLOCK_KEY).get("when").getAsString());
    }

    @Test
    void aPullReachesAsFarAsTheBlockWhenNotToldOtherwise() {
        // Both models set REACH to the block's radius every time in the smoke; one spent a turn
        // misspelling it first. Said once, it is not asked for again.
        JsonObject p = fold("{\"effect\":\"pull\",\"block\":{\"radius\":12}}").getAsJsonObject("params");
        assertEquals(12, p.get("REACH").getAsInt());
    }

    @Test
    void aReachHeGaveIsKeptAndOtherEffectsGetNone() {
        assertEquals(4, fold("{\"effect\":\"pull\",\"params\":{\"REACH\":4},\"block\":{\"radius\":12}}")
                .getAsJsonObject("params").get("REACH").getAsInt());
        assertTrue(!fold("{\"effect\":\"launch\",\"block\":{\"radius\":12}}")
                .getAsJsonObject("params").has("REACH"));
    }
}
