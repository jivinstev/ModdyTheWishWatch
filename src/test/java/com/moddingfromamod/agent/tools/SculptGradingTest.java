package com.moddingfromamod.agent.tools;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.gate.DeclaredPalette;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>An edit is graded on the edit.</b>
 *
 * <p>The declared palette is a claim about what the agent SENT. On an inheriting sculpt that is the
 * two wings it is adding, and the old code compared it against the merged body — so a correct diff
 * came back as
 *
 * <blockquote>purple: said 55%, built 0% &lt;-- MISSED … Fix the ones marked MISSED by re-sculpting
 * the same id, or say plainly why you changed your mind</blockquote>
 *
 * Rebuilding the entire creature is the only action that satisfies that instruction, and in
 * thirteen of thirteen ladder runs it is exactly what the agent did.
 *
 * <p><b>Why this is a unit test and not a GameTest.</b> Driving the whole tool pulls in a character
 * reference fetch: the same test returned in one run and timed out in the next, depending on
 * whether the cache was warm. A flaky ratchet test is worse than none, and this suite already
 * carries one. So the decision is a named function and this drives it directly — while
 * {@code SculptEditGameTest} still covers the end-to-end persistence on a real server.
 */
class SculptGradingTest {

    /** An orange creature. */
    private static List<CreatureSculpt.Part> body() {
        return CreatureSculpt.parseParts(JsonParser.parseString("""
            [{"name":"body","from":[-4,0,-4],"to":[4,16,4],"colour":"orange"},
             {"name":"head","from":[-5,16,-5],"to":[5,26,5],"colour":"orange"}]
            """).getAsJsonArray());
    }

    /** The wish that broke it: two red wings, and a palette describing THE WINGS. */
    private static JsonObject wingsEdit() {
        return JsonParser.parseString("""
            {"id":"snorble","start_from":"snorble",
             "palette":[{"colour":"red","percent":100}],
             "parts":[{"name":"wingL","from":[-8,10,-1],"to":[-4,16,1],"colour":"red"},
                      {"name":"wingR","from":[4,10,-1],"to":[8,16,1],"colour":"red"}]}
            """).getAsJsonObject();
    }

    private static List<CreatureSculpt.Part> merged() {
        List<CreatureSculpt.Part> all = new java.util.ArrayList<>(body());
        all.addAll(CreatureSculpt.parseParts(wingsEdit().getAsJsonArray("parts")));
        return all;
    }

    @Test
    @DisplayName("an inheriting sculpt is graded on what it ADDS")
    void anEditIsGradedOnTheEdit() {
        List<CreatureSculpt.Part> graded =
                SculptCreatureTool.partsToGrade(wingsEdit(), merged());
        assertEquals(2, graded.size(), "the two wings, not the whole body: " + graded.size());
    }

    /**
     * The failure itself, as arithmetic: the same declaration passes against the diff and fails
     * catastrophically against the whole.
     */
    @Test
    @DisplayName("THE bug: grading a diff against the whole body reports a correct edit as MISSED")
    void gradingAgainstTheWholeIsWhatBrokeIt() {
        JsonObject edit = wingsEdit();
        assertFalse(DeclaredPalette.check(edit, SculptCreatureTool.partsToGrade(edit, merged()))
                        .describe(List.of()).contains("MISSED"),
                "a correct two-part diff must pass");
        assertTrue(DeclaredPalette.check(edit, merged()).describe(List.of()).contains("MISSED"),
                "…and this is what it used to be graded against, which is why the agent rebuilt "
                        + "the creature from scratch to satisfy the instruction that follows");
    }

    @Test
    @DisplayName("a creation is still graded on everything it built")
    void aCreationIsUnchanged() {
        JsonObject creation = JsonParser.parseString("""
            {"id":"snorble","palette":[{"colour":"orange","percent":100}],
             "parts":[{"name":"body","from":[-4,0,-4],"to":[4,16,4],"colour":"orange"}]}
            """).getAsJsonObject();
        List<CreatureSculpt.Part> built = CreatureSculpt.parseParts(
                creation.getAsJsonArray("parts"));
        assertEquals(built.size(),
                SculptCreatureTool.partsToGrade(creation, built).size(),
                "with no base, the sent parts and the built parts are the same list");
    }

    @Test
    @DisplayName("a start_from with no parts of its own is still an edit")
    void anEmptyDiffIsStillADiff() {
        // The agent's real first attempt, every time: start_from with nothing added yet.
        JsonObject empty = JsonParser.parseString(
                "{\"id\":\"snorble\",\"start_from\":\"snorble\",\"parts\":[]}").getAsJsonObject();
        assertTrue(SculptCreatureTool.partsToGrade(empty, merged()).isEmpty(),
                "grading an empty diff against the merged body is how the very first attempt was "
                        + "reported as a failure");
    }

    /**
     * the other declaration an edit makes. An Opus big cat edited onto a cat declared the
     * WHOLE creature (95% orange here) while adding two white fangs, and was told orange had
     * vanished. Met as the whole creature, it is not drift.
     */
    @Test
    @DisplayName("an edit declaring the WHOLE creature is met by the whole creature")
    void anEditDeclaringTheWholeIsMetByTheWhole() {
        JsonObject fangs = JsonParser.parseString("""
            {"id":"snorble","start_from":"snorble",
             "palette":[{"colour":"orange","percent":95},{"colour":"white","percent":5}],
             "parts":[{"name":"fangL","from":[-2,14,-6],"to":[-1,17,-5],"colour":"white"},
                      {"name":"fangR","from":[1,14,-6],"to":[2,17,-5],"colour":"white"}]}
            """).getAsJsonObject();
        List<CreatureSculpt.Part> whole = new java.util.ArrayList<>(body());
        whole.addAll(CreatureSculpt.parseParts(fangs.getAsJsonArray("parts")));
        String note = SculptCreatureTool.declaredPaletteNote(fangs,
                SculptCreatureTool.partsToGrade(fangs, whole), whole);
        assertFalse(note.contains("MISSED"), note);
        // ...and the wings edit, which declares the DIFF, is still met by the diff.
        assertFalse(SculptCreatureTool.declaredPaletteNote(wingsEdit(),
                SculptCreatureTool.partsToGrade(wingsEdit(), merged()), merged()).contains("MISSED"));
    }
}
