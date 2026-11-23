package com.moddingfromamod.agent.tools;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An edit's empty box is a name-only re-skin, not a replacement body (Spark routing arm). */
class SculptEmptyBoxTest {

    @Test
    void anEmptyBoxBecomesANameOnlyReskin() {
        var args = JsonParser.parseString("""
                {"start_from":"horse","parts":[
                  {"name":"body","from":[0,0,0],"to":[0,0,0],"pattern":"stripes","pattern_colour":"black"},
                  {"name":"horn","from":[-1,20,-12],"to":[1,24,-10],"colour":"white"}]}""").getAsJsonObject();
        SculptCreatureTool.emptyBoxesAreNameOnly(args.getAsJsonArray("parts"));
        var body = args.getAsJsonArray("parts").get(0).getAsJsonObject();
        assertFalse(body.has("from") || body.has("to"));
        assertTrue(args.getAsJsonArray("parts").get(1).getAsJsonObject().has("from"), "a real box is kept");
        assertEquals("stripes", SculptCreatureTool.skins(args).get("body").get("pattern").getAsString());
    }
}
