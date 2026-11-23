package com.moddingfromamod.runtime.gate;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The gate that would have caught the thing the colour gate could not.</b>
 *
 * <p>On one measured set (commit {@code be64a04}, arm B ×3), colour continuity read <b>1.00</b>
 * while part survival read <b>0.36</b> — a creature being replaced by a different creature of the
 * same colour, with the colour gate green throughout. Each test here is one sentence of that
 * finding.
 */
class ShapeDriftTest {

    private static List<CreatureSculpt.Part> parts(String json) {
        return CreatureSculpt.parseParts(JsonParser.parseString(json).getAsJsonArray());
    }

    /** A Snorble two wishes in: body, head, eye, and the wings from last time. */
    private static List<CreatureSculpt.Part> snorble() {
        return parts("""
            [{"name":"body","from":[-4,0,-4],"to":[4,16,4],"colour":"orange"},
             {"name":"head","from":[-5,16,-5],"to":[5,26,5],"colour":"orange"},
             {"name":"eye","from":[-2,20,-6],"to":[2,24,-5],"colour":"purple"},
             {"name":"wingL","from":[-8,10,-1],"to":[-4,16,1],"colour":"black"},
             {"name":"wingR","from":[4,10,-1],"to":[8,16,1],"colour":"black"}]
            """);
    }

    /** What a from-scratch rebuild produces: the words he repeated, and nothing else. */
    private static List<CreatureSculpt.Part> rebuilt() {
        return parts("""
            [{"name":"body","from":[-4,0,-4],"to":[4,18,4],"colour":"orange"},
             {"name":"head","from":[-5,18,-5],"to":[5,28,5],"colour":"orange"}]
            """);
    }

    @Test
    @DisplayName("THE FINDING: a rebuild that keeps the colours is still called out for losing the body")
    void aRebuildIsCaught() {
        String said = ShapeDrift.between(snorble(), rebuilt(), "make him a bit taller");
        assertTrue(said.contains("REPLACED THE CREATURE"),
                "losing 3 of 5 parts is a replacement, not an edit: " + said);
        assertTrue(said.contains("wingL") && said.contains("wingR") && said.contains("eye"),
                "it must NAME what went missing — a percentage does not tell the agent what to "
                        + "put back: " + said);
    }

    /**
     * The companion to the finding, and the reason colour could not be the whole gate: the very
     * same pair of versions is silent on colour, because orange survived the rebuild perfectly.
     */
    @Test
    @DisplayName("…and the colour gate says nothing at all about that same rebuild")
    void colourCannotSeeIt() {
        assertTrue(PaletteDrift.between(snorble(), rebuilt(), "make him a bit taller").isEmpty(),
                "if this ever starts reporting, the shape gate is no longer the only thing "
                        + "standing between a rebuild and a green board — but on the measured "
                        + "runs it was");
    }

    @Test
    @DisplayName("a real edit that adds parts is silent")
    void anAdditionIsSilent() {
        List<CreatureSculpt.Part> after = new ArrayList<>(snorble());
        after.addAll(parts("""
            [{"name":"tail","from":[-1,2,4],"to":[1,6,10],"colour":"orange"}]
            """));
        assertTrue(ShapeDrift.between(snorble(), after, "give him a tail").isEmpty(),
                "nothing was lost, so there is nothing to say");
    }

    @Test
    @DisplayName("a removal he asked for is the wish, not drift")
    void aRequestedRemovalIsSilent() {
        List<CreatureSculpt.Part> after = new ArrayList<>(snorble());
        after.removeIf(p -> p.name().startsWith("wing"));
        assertTrue(ShapeDrift.between(snorble(), after, "take off his wings").isEmpty(),
                "he asked for the wings to go; flagging it is the tool arguing with the child");
    }

    @Test
    @DisplayName("losing one small part of many is mentioned, but not as a replacement")
    void aSmallLossIsGentler() {
        List<CreatureSculpt.Part> after = new ArrayList<>(snorble());
        after.removeIf(p -> p.name().equals("eye"));
        String said = ShapeDrift.between(snorble(), after, "make him a bit taller");
        assertFalse(said.isEmpty(), "one part vanished unasked — that is worth a line");
        assertFalse(said.contains("REPLACED THE CREATURE"),
                "one part of five is an edit that went slightly wide, and crying replacement at "
                        + "it is how a warning becomes noise: " + said);
        assertTrue(said.contains("eye"), said);
    }

    @Test
    @DisplayName("a creation has nothing to drift from")
    void aCreationIsSilent() {
        assertTrue(ShapeDrift.between(List.of(), snorble(), "make me a Snorble").isEmpty());
    }
}
