package com.moddingfromamod.safety;

import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detector and the scrub behind (the film's line, "Question answered; nothing built, so
 * no finish_building.", in a child's chat). The real path is {@code NoInternalsInChatGameTest}.
 */
class AgentInternalsTest {

    private static final Set<String> TOOLS = Set.of("finish_building", "plan_wish", "notify_player",
            "list_creatures", "photograph");

    @BeforeAll
    static void learnTheTools() {
        AgentInternals.know(TOOLS);
    }

    @Test
    void theFilmsLineIsCaught() {
        assertNotNull(AgentInternals.found(
                "Question answered; nothing built, so no finish_building.", TOOLS));
    }

    @Test
    void anyToolNameIsCaughtButOnlyAsAWholeName() {
        assertEquals("plan_wish", AgentInternals.found("I'll call plan_wish now!", TOOLS));
        assertEquals("list_creatures", AgentInternals.found("Used LIST_CREATURES to look", TOOLS));
        assertNull(AgentInternals.found("my_plan_wishes are great", TOOLS),
                "part of a longer identifier is not the tool");
    }

    @Test
    void aOneWordToolNameIsAnOrdinaryWord() {
        assertNull(AgentInternals.found("Here's a photograph of your castle!", TOOLS));
    }

    @Test
    void processTalkIsCaughtWithoutAToolName() {
        assertNotNull(AgentInternals.found("Question answered.", TOOLS));
        assertNotNull(AgentInternals.found("Nothing built this time.", TOOLS));
        assertNotNull(AgentInternals.found("The agent made 3 tool calls.", TOOLS));
    }

    @Test
    void ordinaryKidLinesPass() {
        for (String line : List.of(
                "SPIKOR IS HERE and he BREATHES FIRE! Look!",
                "Hold your watch and press the button, then say your wish!",
                "Your dragon is building a nest — go and look!",
                "I built you a garage with a finish line on the race track!",
                "A secret agent hideout with a slide!",
                "Have fun with your watch!")) {
            assertNull(AgentInternals.found(line, TOOLS), line);
        }
    }

    @Test
    void scrubBlanksTheChildVisibleFieldAndLeavesTheRest() {
        JsonObject args = new JsonObject();
        args.addProperty("id", "abc");
        args.addProperty("verdict", "done");
        args.addProperty("note", "Question answered; nothing built, so no finish_building.");
        args.addProperty("tried", "nothing built, so no finish_building");   // his eyes never see it
        var held = AgentInternals.scrub("finish_wish", args);
        assertEquals(1, held.size());
        assertEquals("note", held.get(0).field());
        assertEquals("", args.get("note").getAsString());
        assertEquals("nothing built, so no finish_building", args.get("tried").getAsString());
        assertTrue(AgentInternals.notice("finish_wish", held).contains("NOT shown to the child"));
    }

    @Test
    void scrubDropsOnlyTheBadPlanStep() {
        JsonObject args = new JsonObject();
        JsonArray steps = new JsonArray();
        steps.add("Build a big red castle");
        steps.add("Call finish_building");
        args.add("steps", steps);
        var held = AgentInternals.scrub("plan_wish", args);
        assertEquals(1, held.size());
        assertEquals(1, args.getAsJsonArray("steps").size());
        assertEquals("Build a big red castle", args.getAsJsonArray("steps").get(0).getAsString());
    }

    @Test
    void aToolWithNoChildVisibleFieldIsUntouched() {
        JsonObject args = new JsonObject();
        args.addProperty("target", "finish_building");
        assertTrue(AgentInternals.scrub("list_creatures", args).isEmpty());
        assertEquals("", AgentInternals.notice("list_creatures", List.of()));
    }
}
