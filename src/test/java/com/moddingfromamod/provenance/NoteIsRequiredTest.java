package com.moddingfromamod.provenance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.McpTool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A field whose doc says ALWAYS must not sit in the optional list.</b>
 *
 * <p>The design note said <i>"ALWAYS include"</i> and was optional on all seven artifact tools. The
 * agent obeyed the schema rather than the prose: measured over the real action log,
 * {@code build_structure.note} was passed <b>0 times in 227 calls</b>.
 *
 * <p>That is not merely a wasted field. It is the only writer of {@code ProvenanceContext.note},
 * which feeds the handover record, the turn journal and the published Manifest — so all three had
 * been receiving an empty string for every building wish, and {@code FinishWishTool} declines to
 * ask for a design note at finish time <i>because</i> "it is already on the run".
 *
 * <p>This test is written as a RULE over every tool rather than a list of the seven, so the eighth
 * tool to offer a note cannot repeat it.
 */
class NoteIsRequiredTest {

    /** Every tool the agent can call, constructed the way the MCP layer constructs them. */
    private static List<McpTool> tools() {
        List<McpTool> out = new ArrayList<>();
        out.add(new com.moddingfromamod.agent.tools.BuildStructureTool());
        out.add(new com.moddingfromamod.agent.tools.AuthorDatapackTool());
        out.add(new com.moddingfromamod.agent.tools.SculptCreatureTool());
        out.add(new com.moddingfromamod.agent.tools.PaintSurfaceTool());
        out.add(new com.moddingfromamod.agent.tools.AddTemplateTool());
        return out;
    }

    private static boolean isRequired(JsonObject schema, String field) {
        if (!schema.has("required") || !schema.get("required").isJsonArray()) {
            return false;
        }
        for (var el : schema.getAsJsonArray("required")) {
            if (field.equals(el.getAsString())) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("every tool that offers a design note requires it")
    void aNoteOfferedIsANoteRequired() {
        List<String> optional = new ArrayList<>();
        for (McpTool t : tools()) {
            JsonObject schema = t.inputSchema();
            boolean offers = schema.has("properties")
                    && schema.getAsJsonObject("properties").has("note");
            if (offers && !isRequired(schema, "note")) {
                optional.add(t.name());
            }
        }
        // Collected rather than failing on the first: "one tool drifted" and "the rule was reverted
        // everywhere" want different fixes, and failing on the first cannot tell them apart.
        assertTrue(optional.isEmpty(),
                "these tools offer a design note and do not require it: " + optional
                + ". The hint says REQUIRED and the agent obeys the SCHEMA — measured 0 of 227 "
                + "calls when the two disagreed. Three things read that note (the handover record, "
                + "the turn journal, the published Manifest) and every one of them was getting an "
                + "empty string.");
    }

    @Test
    @DisplayName("the hint says required, so the schema and the prose cannot disagree")
    void thePromptAndTheSchemaAgree() {
        // The original defect was a DISAGREEMENT, not a missing field: prose said ALWAYS, schema
        // said optional. Pinning only the schema would let the prose drift back to "optional" and
        // leave the agent with two answers again.
        assertTrue(ProvenanceContext.NOTE_HINT.startsWith("REQUIRED"),
                "the hint must lead with REQUIRED, matching the schema — an 'ALWAYS include' that "
                + "sits on an optional field is the exact shape that measured 0/227");
        assertTrue(ProvenanceContext.NOTE_HINT.contains("carried to whoever edits this next"),
                "and it must say WHY, or a required field reads as bureaucracy and gets one word");
    }

    @Test
    @DisplayName("the note the agent passes really does reach the run")
    void theChainIsLive() {
        // The half that made this worth fixing rather than deleting: something downstream depends
        // on it. Asserted here at the seam ProvenanceContext owns; the GameTest carries it the rest
        // of the way to the handover record.
        ProvenanceContext.note("built it in quartz because he said it should look like a palace");
        assertTrue(ProvenanceContext.currentNote().contains("quartz"),
                "a note the agent passes must be readable back, or requiring it changes nothing");
        ProvenanceContext.note("");
    }
}
