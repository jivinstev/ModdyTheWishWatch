package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** when the sculpt tool re-hangs a part from the head, it TELLS the agent (P4). */
class SculptHeadAdoptionNoteTest {

    private static JsonObject args(String parts) {
        JsonObject a = new JsonObject();
        a.add("parts", JsonParser.parseString(parts));
        return a;
    }

    @Test
    void itNamesWhatItMovedAndTheFormThatFixesItNextTime() {
        String note = SculptCreatureTool.headAdoptionNote(args("""
                [{"name":"head","from":[-4,24,-4],"to":[4,32,4],"colour":"pink"},
                 {"name":"hair","from":[-5,27,-2],"to":[5,34,5],"colour":"brown"}]"""));
        assertTrue(note.contains("'hair' -> 'head'"), note);
        assertTrue(note.contains("\"parent\":\"head\""), note);
    }

    @Test
    void aCorrectlyParentedHeadSaysNothing() {
        assertEquals("", SculptCreatureTool.headAdoptionNote(args("""
                [{"name":"head","from":[-4,24,-4],"to":[4,32,4],"colour":"pink"},
                 {"name":"hair","parent":"head","from":[-5,27,-2],"to":[5,34,5],"colour":"brown"}]""")));
    }
}
