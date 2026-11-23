package com.moddingfromamod.marshal;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rule itself. Where it RUNS is {@code PlanFirstGameTest}, through real tools/call. */
class PlanFirstTest {

    @Test
    void aBuildToolForABoundWishWithNoPlanIsRefusedNamingPlanWish() {
        String r = PlanFirst.refusal("make_item", true, false, false);
        assertNotNull(r, "a bound wish with no plan must not change the world");
        assertTrue(r.contains("plan_wish"), "the refusal must name the tool that unblocks it: " + r);
        assertTrue(r.contains("nothing changed"), "and say the call did nothing: " + r);
        assertTrue(r.contains("make_item"), "and which call to make again: " + r);
    }

    @Test
    void onceTheWishHasAPlanItRuns() {
        assertNull(PlanFirst.refusal("make_item", true, true, false));
        assertNull(PlanFirst.refusal("build_structure", true, true, true));
    }

    @Test
    void aCallWithNoWishBoundIsNeverAskedForAPlan() {
        // Nothing to price and nobody to tell: a worker poking at a tool between wishes.
        assertNull(PlanFirst.refusal("make_item", false, false, false));
        assertNull(PlanFirst.refusal("run_command", false, false, true));
    }

    @Test
    void readingAndTalkingNeverNeedAPlan() {
        for (String t : List.of("look_around", "find_content", "notify_player", "set_status",
                "plan_wish", "finish_building", "finish_wish", "photograph", "await_wish")) {
            assertNull(PlanFirst.refusal(t, true, false, true), t + " changes nothing he sees");
        }
    }

    @Test
    void aTeleportNeedsNoPlan() {
        // take_me_to moves HIM, not the world. Gated, the agent called it
        // first and was refused in 2 of 34 real wishes — a model turn spent on nothing.
        assertTrue(PlanFirst.EXEMPT.contains("take_me_to"));
        assertNull(PlanFirst.refusal("take_me_to", true, false, true), "a teleport is never refused for a plan");
    }

    @Test
    void runCommandNeedsAPlanOnlyInAWorldWithAWishBudget() {
        assertNull(PlanFirst.refusal("run_command", true, false, false),
                "a world with no budget: \"make it daytime\" is not made to plan first");
        assertNotNull(PlanFirst.refusal("run_command", true, false, true),
                "where wishes are priced, the escape hatch is priced too — or it is the way round the price");
    }

    @Test
    void theBudgetDefaultsToOffAndTheSeamCanTurnItOn() {
        try {
            PlanFirst.budgetedWorld(null);
            assertFalse(PlanFirst.budgetedWorld(), "no world has a wish budget until T17 gives it one");
            PlanFirst.budgetedWorld(() -> true);
            assertTrue(PlanFirst.budgetedWorld());
        } finally {
            PlanFirst.budgetedWorld(null);
        }
        assertFalse(PlanFirst.budgetedWorld(), "null restores the default");
    }

    @Test
    void theWorkerIsToldThePlanIsRequired() {
        // One hand-written prompt line until it is generated. A model told nothing meets the
        // refusal cold and pays a turn to learn what one sentence could have said.
        String p = com.moddingfromamod.worker.WorkerPrompt.text();
        assertTrue(p.contains("plan_wish is REQUIRED"), "the prompt no longer says the plan is required");
    }

    @Test
    void theTwoListsDoNotOverlap() {
        for (String t : PlanFirst.NEEDS_PLAN) {
            assertFalse(PlanFirst.EXEMPT.contains(t), t + " is both required and exempt");
        }
    }

    @Test
    void everyWorldChangingWorkerToolHasADecision() throws Exception {
        // A new tool that changes the world and is in neither list would run unplanned — and unpriced
        // on a world with a wish bank — with nothing to say so. Same shape as UndoContract's own gate.
        List<String> undecided = new ArrayList<>();
        for (String t : workerTools()) {
            if (UndoContract.touchesTheWorld(t) && !PlanFirst.NEEDS_PLAN.contains(t)
                    && !PlanFirst.EXEMPT.contains(t) && !PlanFirst.BUDGETED_ONLY.equals(t)) {
                undecided.add(t);
            }
        }
        assertEquals(List.of(), undecided,
                "world-changing tools with no plan decision — add each to NEEDS_PLAN or EXEMPT");
    }

    @Test
    void everyListedToolIsARealWorkerTool() throws Exception {
        // A typo in either list is a tool that is silently never gated (or never exempt).
        List<String> tools = workerTools();
        for (String t : PlanFirst.NEEDS_PLAN) {
            assertTrue(tools.contains(t), "NEEDS_PLAN names a tool the worker does not have: " + t);
        }
        for (String t : PlanFirst.EXEMPT) {
            assertTrue(tools.contains(t), "EXEMPT names a tool the worker does not have: " + t);
        }
    }

    /** Every tool a worker is offered, from the generated list (AllowedToolsResourceTest keeps it true). */
    private static List<String> workerTools() throws Exception {
        try (InputStream in = PlanFirstTest.class.getResourceAsStream("/agent/allowed-tools.txt")) {
            assertNotNull(in, "the generated tool list is not on the classpath");
            List<String> out = new ArrayList<>();
            for (String s : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("[,\\r\\n]")) {
                String t = s.trim();
                if (t.startsWith("mcp__wishwatch__")) {
                    out.add(t.substring("mcp__wishwatch__".length()));
                }
            }
            assertTrue(out.size() > 30, "the tool list is suspiciously short: " + out);
            return out;
        }
    }
}
