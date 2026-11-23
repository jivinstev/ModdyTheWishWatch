package com.moddingfromamod.runtime;

import java.util.List;
import java.util.Set;

import com.google.gson.JsonParser;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An edit that names a base part and sends no box re-skins it in place (2026-10-10). */
class SculptReskinTest {

    private static final List<CreatureSculpt.Part> SNAPPER = CreatureSculpt.parseParts(JsonParser.parseString("""
            [{"name":"shell","from":[-6,4,-6],"to":[6,9,6],"colour":"#3A6B2A"},
             {"name":"head","from":[-2,4,-10],"to":[2,8,-6],"colour":"#4C8A35"}]""").getAsJsonArray());

    /** The call Muse made on 2026-10-10, which used to change nothing. */
    private static final String CALL = """
            {"id":"snaper","start_from":"snaper",
             "parts":[{"name":"shell","pattern":"scales","pattern_colour":"#2E4A1A"}]}""";

    @Test
    void aNameOnlyPartReskinsTheBasePartAndKeepsItsBox() {
        var args = JsonParser.parseString(CALL).getAsJsonObject();
        var skins = SculptCreatureTool.skins(args);
        assertEquals(Set.of("shell"), skins.keySet());
        var out = SculptBase.merge(SculptBase.reskinned(SNAPPER, skins), List.of(), Set.of(), java.util.Map.of());
        var shell = out.get(0);
        assertEquals("scales", shell.pattern());
        assertEquals(0x2E4A1A, shell.patternColour());
        assertEquals(0x3A6B2A, shell.colour(), "a colour it did not send stays");
        assertArrayEquals(new float[] {-6, 4, -6}, shell.from());
        assertTrue(!out.get(1).patterned(), "a part it did not name is untouched");
        assertTrue(SculptCritique.flat(out).isEmpty() || out.size() <= 3);
    }

    @Test
    void aPartWithABoxIsNotASkin() {
        var args = JsonParser.parseString("""
                {"parts":[{"name":"shell","from":[0,0,0],"to":[1,1,1],"pattern":"spots"}]}""").getAsJsonObject();
        assertTrue(SculptCreatureTool.skins(args).isEmpty());
    }
}
