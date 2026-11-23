package com.moddingfromamod.wish;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.tools.FinishWishToolAccess;

/** the cat run (syn-1-007, 2026-10-10) ended DONE having changed nothing. */
class WorldChangingTest {

    @Test
    void theCatRunsSuccessfulCallsChangedNothing() {
        for (String t : new String[] {"find_content", "list_builds", "list_creatures", "conjure_at",
                "finish_building", "plan_wish", "set_status", "inspect_item", "look_up_character"}) {
            assertFalse(WorldChanging.tool(t), t);
        }
    }

    @Test
    void makingThingsIsAChange() {
        for (String t : new String[] {"sculpt_creature", "modify_mob", "run_command", "make_item",
                "build_structure", "set_rule", "author_datapack", "modify_player", "add_template"}) {
            assertTrue(WorldChanging.tool(t), t);
        }
    }

    @Test
    void aDefaultedDoneOverNothingIsFailedAndAGivenVerdictIsKept() {
        JsonObject none = new JsonObject();
        JsonObject given = new JsonObject();
        given.addProperty("verdict", "done");
        assertEquals(WishVerdict.FAILED, FinishWishToolAccess.nothingChanged(WishVerdict.DONE,
                FinishWishToolAccess.gaveNoVerdict(none), true));
        assertEquals(WishVerdict.DONE, FinishWishToolAccess.nothingChanged(WishVerdict.DONE,
                FinishWishToolAccess.gaveNoVerdict(given), true), "'he said thanks' is a real done");
        assertEquals(WishVerdict.DONE, FinishWishToolAccess.nothingChanged(WishVerdict.DONE,
                FinishWishToolAccess.gaveNoVerdict(none), false), "something was made");
    }
}
