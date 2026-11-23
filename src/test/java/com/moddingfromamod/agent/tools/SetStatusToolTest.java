package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** The status tool's contract: the agent must be told to narrate, and 'doing' is required. */
class SetStatusToolTest {

    private final SetStatusTool tool = new SetStatusTool();

    @Test
    void nameIsStable() {
        assertEquals("set_status", tool.name(), "the agent calls it by this exact name");
    }

    @Test
    void requiresDoingAndOffersPhase() {
        JsonObject schema = tool.inputSchema();
        JsonObject props = schema.getAsJsonObject("properties");
        assertTrue(props.has("doing"), "must accept what it's doing");
        assertTrue(props.has("phase"), "and an optional phase for the right magic");
        boolean doingRequired = false;
        for (var el : schema.getAsJsonArray("required")) {
            doingRequired |= "doing".equals(el.getAsString());
        }
        assertTrue(doingRequired, "'doing' must be required");
    }

    @Test
    void tellsTheAgentToNarrateOftenAndInKidWords() {
        String d = tool.description().toLowerCase();
        assertTrue(d.contains("status bar"), "explains it's the status bar");
        assertTrue(d.contains("phase") || d.contains("step"), "mentions steps/phases");
    }
}
