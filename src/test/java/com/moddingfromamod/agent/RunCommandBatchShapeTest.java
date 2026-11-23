package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.tools.RunCommandTool;
import org.junit.jupiter.api.Test;

/**
 * <b>An optional field is not an input to generation.</b>
 *
 * <p>{@code run_command} has accepted a list, and its description explains batching
 * clearly and correctly. Measured over the real action log: <b>the list form was used ZERO times in
 * 752 calls</b>, while 480 of those calls sat in unbroken streaks of three or more — a script being
 * typed one line at a time at about four seconds a line, with a child watching a ticker through
 * every one.
 *
 * <p>Offering "string OR list" gets the string. So the schema now declares a list and nothing else,
 * and this pins that, because the friendlier shape is exactly the sort of thing a later tidy-up
 * restores.
 */
class RunCommandBatchShapeTest {

    private static JsonObject commandProperty() {
        return new RunCommandTool().inputSchema()
                .getAsJsonObject("properties").getAsJsonObject("command");
    }

    @Test
    void theSchemaOffersOnlyAList() {
        JsonObject command = commandProperty();
        assertEquals("array", command.get("type").getAsString(),
                "a schema that offers a simpler shape gets the simpler shape, however well the "
                        + "description argues otherwise");
        assertFalse(command.has("oneOf"),
                "'string or list' is the shape that produced 0 batches in 752 calls");
        assertEquals("string", command.getAsJsonObject("items").get("type").getAsString());
    }

    @Test
    void theBatchIsStillBounded() {
        assertEquals(RunCommandTool.MAX_BATCH,
                commandProperty().get("maxItems").getAsInt(),
                "the whole batch runs in one server-thread hop, so it cannot be unbounded");
    }

    @Test
    void theDescriptionTellsItToAlwaysUseAList() {
        String description = new RunCommandTool().description().toLowerCase();
        assertTrue(description.contains("always pass a list"),
                "the schema shape and the words must agree; when they disagreed, the words lost");
    }

    @Test
    void theStandingBriefSaysItToo() {
        // The schema is read per call; the brief is read once, up front, which is where a habit is
        // formed. Both, because either alone has already been shown not to be enough.
        String brief = AgentSession.prompt().toLowerCase();
        assertTrue(brief.contains("one run_command call"),
                "the brief never tells it to batch, so nothing forms the habit before the first call");
        assertTrue(brief.contains("takes a list"));
    }

    @Test
    void aBareStringStillWorksSoAnOldHabitDegradesToWorking() {
        // The BODY must stay tolerant even though the SCHEMA is strict: a model that sends the old
        // shape should run the command, not get an error it has to recover from — that recovery is
        // exactly the wasted turn this change exists to remove.
        JsonObject args = new JsonObject();
        args.addProperty("command", "say hello");
        assertTrue(RunCommandTool.commandCount(args) == 1,
                "a bare string must still be understood as one command");
    }
}
