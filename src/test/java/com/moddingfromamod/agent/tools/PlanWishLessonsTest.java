package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * the planned kind's lessons ride on the FIRST plan for the kinds whose authoring tools hand
 * them over only on a refusal (items, events, pictures), and never twice or for the kinds that
 * already get them on the way (creatures, buildings).
 */
class PlanWishLessonsTest {

    @Test
    void theFirstPlanOfAnItemCarriesTheItemLessons() {
        String item = PlanWishTool.planLessons(true, "item");
        assertTrue(item.contains("LESSONS FROM REAL FAILURES"), "an item plan carries lessons");
        assertTrue(item.contains("load_and_pour"), "including the machine rule the arm found unread");
        assertTrue(PlanWishTool.planLessons(true, "event").contains("crowd"), "an event plan carries the event lessons");
        assertTrue(PlanWishTool.planLessons(true, "picture").contains("LESSONS"), "a picture plan carries painting's");
    }

    @Test
    void neverTwiceAndNotForKindsThatAlreadyGetThem() {
        assertEquals("", PlanWishTool.planLessons(false, "item"), "a second plan does not repeat them");
        assertTrue(PlanWishTool.planLessons(true, "creature").contains("LESSONS"),
                "a creature plan carries the creature lessons: a creature granted as a real mob calls neither "
                        + "list_creatures nor sculpt_creature (#1677)");
        assertEquals("", PlanWishTool.planLessons(true, "building"), "buildings read theirs in build_structure");
        assertEquals("", PlanWishTool.planLessons(true, null), "no kind, no lessons");
    }

    @Test
    void aWishIsHandedALessonOnceWhereverItAsksFirst() {
        // the plan now carries the creature lessons, so list_creatures and sculpt_creature
        // must not hand the same 30 KB over again in the same wish.
        java.util.UUID wish = java.util.UUID.randomUUID();
        com.moddingfromamod.wish.BuildGate.startWish(wish);
        var gate = com.moddingfromamod.wish.BuildGate.forWish(wish);
        assertTrue(gate.firstLessons("creature"));
        assertTrue(!gate.firstLessons("creature"), "a second hand-over in the same wish");
        assertTrue(gate.firstLessons("item"), "another kind is its own");
        com.moddingfromamod.wish.BuildGate.settle(wish);
        com.moddingfromamod.wish.BuildGate.startWish(wish);
        assertTrue(com.moddingfromamod.wish.BuildGate.forWish(wish).firstLessons("creature"), "a new wish starts again");
        com.moddingfromamod.wish.BuildGate.settle(wish);
    }
}
