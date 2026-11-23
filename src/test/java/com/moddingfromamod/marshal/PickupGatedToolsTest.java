package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import com.moddingfromamod.agent.AgentSession;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** only LOOKING runs before a wish's verdicts land; everything else, including a new tool, parks. */
class PickupGatedToolsTest {

    private static Set<String> served() {
        Set<String> names = new TreeSet<>();
        Arrays.stream(AgentSession.allowedTools().split(","))
                .map(s -> s.replace("mcp__wishwatch__", "").trim())
                .filter(s -> !s.isEmpty())
                .forEach(names::add);
        return names;
    }

    @Test
    @DisplayName("every read-only name is a tool the registry really serves (the list cannot rot)")
    void readOnlyNamesExist() {
        // an earlier issue's search tools are served only in an arm that turns a kind's search on, so read the
        // registry as that arm sees it — every other name is served either way.
        String key = "moddingfromamod.readymade.search";
        String was = System.getProperty(key);
        Set<String> tools;
        System.setProperty(key, "all");
        try {
            tools = served();
        } finally {
            if (was == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, was);
            }
        }
        assertTrue(tools.size() > 40, "the registry was not read: " + tools);
        for (String r : PickupGatedTools.READ_ONLY) {
            assertTrue(tools.contains(r), r + " is not served — a stale read-only name lets nothing through");
        }
    }

    @Test
    @DisplayName("everything that speaks, builds or searches the web waits")
    void theDangerousOnesWait() {
        for (String t : new String[]{"plan_wish", "set_status", "conjure_at", "notify_player", "ask_which",
                "run_command", "sculpt_creature", "build_structure", "make_item", "apply_effect",
                "author_datapack", "finish_building", "finish_wish", "give_items", "take_me_to",
                "look_up_character", "photograph", "add_template", "modify_mob", "stage_event",
                "verify_datapack"}) {
            assertTrue(PickupGatedTools.gated(t), t + " must wait for the pickup verdicts");
        }
    }

    @Test
    @DisplayName("a tool nobody classified waits (safe by default)")
    void unknownWaits() {
        assertTrue(PickupGatedTools.gated("a_tool_added_tomorrow"));
        assertTrue(PickupGatedTools.gated(null));
        assertFalse(PickupGatedTools.gated("look_around"));
        assertFalse(PickupGatedTools.gated("await_wish"));
    }
}
