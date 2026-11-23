package com.moddingfromamod.agent.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The wish card is framed even when the agent names no subject</b>.
 *
 * <p>Every framing path in {@link PhotographTool} — the buildings orbit, the held-item shot
 *, the creature orbit, and the train framing — is gated on {@code subject}. Measured
 * on a real three-wish train ladder: {@code finish_building} was called with
 * {@code argKeys ['say','label','claims']} on every rung, so all four were skipped in one go and
 * the child's wish card was a plain snapshot from wherever the player stood.
 *
 * <p>Three of those were fixed as framing bugs while the picture was never reaching the framing
 * code at all.
 */
class PhotoSubjectDefaultTest {

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    @Test
    @DisplayName("an empty subject is defaulted before any framing path is chosen")
    void anEmptySubjectIsDefaulted() {
        String src = read("src/main/java/com/moddingfromamod/agent/tools/PhotographTool.java");
        int cap = src.indexOf("public static ToolResult capture(String label");
        assertTrue(cap > 0, "capture() moved; update this test deliberately");
        String head = src.substring(cap, src.indexOf("captureAimedAt(label", cap));
        assertTrue(head.contains("lastArtifactForCurrentPlayer()"),
                "with no default, an agent that names no subject gets the unframed snapshot for "
                        + "every class at once. The head of capture() was:\n" + head);
    }

    @Test
    @DisplayName("the default cannot cost the photograph if it fails")
    void theDefaultIsBestEffort() {
        // No server in a unit test: it must answer "" rather than throwing, or a missing default
        // would turn every wish card into an error instead of an unframed picture.
        assertEquals("", PhotographTool.lastArtifactForCurrentPlayer());
    }

    @Test
    @DisplayName("the subject is not described to the agent as a CREATURE id")
    void theSchemaDoesNotSayCreature() {
        String src = read("src/main/java/com/moddingfromamod/agent/tools/FinishBuildingTool.java");
        int p = src.indexOf("\"subject\", \"string\"");
        assertTrue(p > 0, "the subject param moved; update this test deliberately");
        String desc = src.substring(p, Math.min(src.length(), p + 500));
        assertFalse(desc.contains("the creature id"),
                "an agent that had just built a train read this and left it blank on all three "
                        + "rungs of a real ladder: " + desc);
        for (String kind : new String[] {"building", "machine", "item"}) {
            assertTrue(desc.toLowerCase().contains(kind),
                    "the classes it actually serves should be named, so the next one is not "
                            + "skipped (missing '" + kind + "')");
        }
    }
}
