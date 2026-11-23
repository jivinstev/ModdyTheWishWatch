package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.moddingfromamod.RunContext;

/**
 * The test-run wait budget —.
 *
 * <p>{@code modifyMobGlowsInAColour} failed intermittently on a 10s budget while, seconds either
 * side on the same thread, a train assembled and another train drove 13 blocks. Nothing was wrong
 * with the mob modification. The gate suite deliberately loads the server thread, and a wait chosen
 * for an idle game does not survive that.
 *
 * <p>A red gate that needs a re-run before it means anything is corrosive, so the rule gets a test
 * of its own rather than being trusted because it looks obvious.
 */
class AgentBridgeBudgetTest {

    @Test
    void aTestRunWaitsLongerThanItAsksFor() {
        assertTrue(RunContext.isTestRun(),
                "this test only proves something if the JVM knows it is a test run — if this "
                        + "fails, the detection is broken and the budget will never be extended "
                        + "where it is needed");
        assertTrue(AgentBridge.budgetFor(10_000L) >= 10_000L * AgentBridge.TEST_RUN_BUDGET_FACTOR,
                "modify_mob's 10s budget was not extended under test, so #54 comes straight back");
    }

    @Test
    void realPlayGetsExactlyTheWaitTheCallerAskedFor() {
        // The short budget exists for a reason: a child must not be left staring at a frozen watch.
        // Extending it in play would be a real regression, so prove the stretch is test-only rather
        // than assuming the flag is read the right way round.
        System.setProperty(RunContext.FORCE_REAL_RUN, "true");
        try {
            assertEquals(10_000L, AgentBridge.budgetFor(10_000L),
                    "real play had its wait stretched — a busy game would now leave the player "
                            + "waiting six times as long before saying anything");
            assertEquals(AgentBridge.DEFAULT_TIMEOUT_MS,
                    AgentBridge.budgetFor(AgentBridge.DEFAULT_TIMEOUT_MS));
        } finally {
            System.clearProperty(RunContext.FORCE_REAL_RUN);
        }
    }

    @Test
    void theStretchIsGenerousEnoughToOutlastTheSuiteAndStillFinite() {
        assertTrue(AgentBridge.TEST_RUN_BUDGET_FACTOR >= 3,
                "too small a factor and the flake simply returns at a higher load");
        assertTrue(AgentBridge.TEST_RUN_BUDGET_FACTOR <= 20,
                "a budget this large stops being a timeout: a genuine hang would stall the whole "
                        + "suite instead of failing it");
    }

    /**
     * and the reason a factor alone was never going to hold.
     *
     * <p>The factor scales off whatever the caller asked for, and callers pick that number for an
     * idle game. {@code DEFAULT_TIMEOUT_MS} is 5s, so the stretched budget was 30s — while
     * {@code DatapackReloader}'s own javadoc says a SINGLE reload occupies the server thread for
     * 10-30s, and Gate B measured <b>109 reloads queued inside one 50-test batch</b>. The small
     * caller is exactly the one that cannot survive, which is why the fix is a floor.
     */
    @Test
    void theSmallestCallerStillOutlastsAReloadChain() {
        assertTrue(RunContext.isTestRun(), "only meaningful when the JVM knows it is a test run");

        long worstSingleReloadMs = 30_000L;
        assertTrue(AgentBridge.budgetFor(AgentBridge.DEFAULT_TIMEOUT_MS) > worstSingleReloadMs * 3,
                "the DEFAULT 5s caller — the one that actually failed in #383 — must outlast "
                        + "several queued datapack reloads, not one. A multiple of a number chosen "
                        + "for an idle game cannot do that; only a floor can.");
    }

    @Test
    void theFloorIsAHangBackstopAndNotForever() {
        assertTrue(AgentBridge.TEST_RUN_BUDGET_FLOOR_MS >= 120_000L,
                "below two minutes the floor is back to racing the suite's own load");
        assertTrue(AgentBridge.TEST_RUN_BUDGET_FLOOR_MS <= 600_000L,
                "a genuine deadlock must still FAIL the run rather than stall it indefinitely");
    }

    @Test
    void theFloorIsTestOnly() {
        // The floor must never reach a child: it would leave them staring at a frozen watch for
        // five minutes. Same argument as realPlayGetsExactlyTheWaitTheCallerAskedFor, one level up.
        System.setProperty(RunContext.FORCE_REAL_RUN, "true");
        try {
            assertEquals(AgentBridge.DEFAULT_TIMEOUT_MS,
                    AgentBridge.budgetFor(AgentBridge.DEFAULT_TIMEOUT_MS),
                    "the test-run floor leaked into real play");
        } finally {
            System.clearProperty(RunContext.FORCE_REAL_RUN);
        }
    }
}
