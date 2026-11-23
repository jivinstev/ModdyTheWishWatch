package com.moddingfromamod.health;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>"Moddy is tired" as a state machine</b> (D8) — where its times are asserted.
 *
 * <p>The windows are real minutes, so they are argued here with a hand-held clock and never in a
 * GameTest, whose ticks have no relationship to wall time (CLAUDE.md). {@code ModdyTiredGameTest}
 * proves the wiring: a real submit refused, a real dequeue refused, a real re-wish let through.
 */
class ModdyTiredTest {

    private static final long T0 = 1_000_000_000L;

    @Test
    void freshModdyIsNotTired() {
        ModdyTired t = new ModdyTired();
        assertTrue(t.current(T0, true).isEmpty());
    }

    // ── the grader ──────────────────────────────────────────────────────────────────────────

    @Test
    void anUnansweredGradeTiresHimUntilTheNextAnsweredOne() {
        ModdyTired t = new ModdyTired();
        t.graderAnswered(T0);
        t.graderFailed(T0 + 1_000, "no answer after 60000ms");
        var tired = t.current(T0 + 2_000, true);
        assertTrue(tired.isPresent(), "the most recent grade got no answer");
        assertEquals(ModdyTired.Cause.GRADER_UNREACHABLE, tired.get().cause());
        assertEquals("no answer after 60000ms", tired.get().detail());

        t.graderAnswered(T0 + 3_000);
        assertTrue(t.current(T0 + 4_000, true).isEmpty(), "an answered grade wakes him");
    }

    /**
     * Shadow acts on nothing, and "off" has no grader to be unreachable, so neither may refuse a
     * child's wish over a grader outage. The agent and the quota are not safety and still count.
     */
    @Test
    void anUnreachableGraderTiresNobodyWhenTheGradeIsNotEnforced() {
        ModdyTired t = new ModdyTired();
        t.graderFailed(T0, "no answer");
        assertTrue(t.current(T0 + 1, false).isEmpty());
        t.overQuota(true, "90% of the window", T0);
        assertEquals(ModdyTired.Cause.OVER_QUOTA, t.current(T0 + 1, false).get().cause());
    }

    /** One stray timeout on an idle evening must not refuse tomorrow morning's first wish. */
    @Test
    void anOldUnansweredGradeExpires() {
        ModdyTired t = new ModdyTired();
        t.graderFailed(T0, "no answer");
        assertTrue(t.current(T0 + ModdyTired.GRADER_WINDOW_MS - 1, true).isPresent());
        assertTrue(t.current(T0 + ModdyTired.GRADER_WINDOW_MS, true).isEmpty(),
                "past the window the next wish takes the ordinary path and re-derives it");
    }

    // ── the agent ───────────────────────────────────────────────────────────────────────────

    @Test
    void anUnclaimedWishTiresHimUntilAWorkerParks() {
        ModdyTired t = new ModdyTired();
        t.workerParked(T0);
        t.wishNeverClaimed(T0 + 120_000, "never claimed after 120s");
        assertEquals(ModdyTired.Cause.AGENT_UNRESPONSIVE,
                t.current(T0 + 121_000, true).get().cause());
        t.workerParked(T0 + 130_000);
        assertTrue(t.current(T0 + 131_000, true).isEmpty(),
                "a worker parking on await_wish has just taken a model turn: he is answering");
    }

    @Test
    void anUnclaimedWishExpiresSoAWrongInferenceCostsMinutesNotAnEvening() {
        ModdyTired t = new ModdyTired();
        t.wishNeverClaimed(T0, "never claimed after 120s");
        assertTrue(t.current(T0 + ModdyTired.AGENT_WINDOW_MS - 1, true).isPresent());
        assertTrue(t.current(T0 + ModdyTired.AGENT_WINDOW_MS, true).isEmpty());
    }

    // ── the quota seam ───────────────────────────────────────────────────────────────

    @Test
    void overQuotaHoldsUntilLiftedAndNeverExpiresOnItsOwn() {
        ModdyTired t = new ModdyTired();
        t.overQuota(true, "weekly window at 90%", T0);
        assertTrue(t.current(T0 + 7L * 24 * 3_600_000, true).isPresent(),
                "the ceiling is a decision, not an inference: only lifting it ends it");
        t.overQuota(false, "", T0 + 1);
        assertTrue(t.current(T0 + 2, true).isEmpty());
    }

    @Test
    void theMostPersistentCauseIsTheOneReported() {
        ModdyTired t = new ModdyTired();
        t.graderFailed(T0, "g");
        t.wishNeverClaimed(T0, "a");
        assertEquals(ModdyTired.Cause.AGENT_UNRESPONSIVE, t.current(T0 + 1, true).get().cause());
        t.overQuota(true, "q", T0);
        assertEquals(ModdyTired.Cause.OVER_QUOTA, t.current(T0 + 1, true).get().cause());
    }

    // ── the operator's alert ───────────────────────────────────────────────────────────────────

    /** One page per episode, not one per refused wish — and waking up is said too. */
    @Test
    void theOwnerHearsOnceOnEntryAndOnceOnWaking() {
        ModdyTired t = new ModdyTired();
        assertTrue(t.transition(T0, true).isEmpty(), "nothing to say while awake");
        t.graderFailed(T0, "no answer after 60000ms");
        var entered = t.transition(T0 + 1, true);
        assertTrue(entered.isPresent() && entered.get().entered());
        assertEquals("MODDY-ALERT moddy tired: grader_unreachable — no answer after 60000ms",
                entered.get().line());
        assertTrue(t.transition(T0 + 2, true).isEmpty(), "the second refused wish is not a page");

        t.graderAnswered(T0 + 61_001);
        var woke = t.transition(T0 + 61_001, true);
        assertTrue(woke.isPresent());
        assertFalse(woke.get().entered());
        assertFalse(woke.get().line().startsWith("MODDY-ALERT"),
                "waking is good news: it is logged, and it does not page anybody");
        assertEquals("moddy awake again: grader_unreachable cleared after 61s", woke.get().line());
    }

    @Test
    void aChangeOfCauseIsANewPageBecauseTheFixDiffers() {
        ModdyTired t = new ModdyTired();
        t.graderFailed(T0, "g");
        t.transition(T0, true);
        t.overQuota(true, "q", T0 + 1);
        var next = t.transition(T0 + 2, true);
        assertTrue(next.isPresent() && next.get().entered());
        assertEquals(ModdyTired.Cause.OVER_QUOTA, next.get().cause());
    }

    // ── the daily spend cap ─────────────────────────────────────────────────────────

    /** A day's cap ends at the day boundary even if nothing reads the server's verdict again. */
    @Test
    void theSpendCapTiresHimUntilTheDayBoundaryAndNoLonger() {
        ModdyTired t = new ModdyTired();
        long midnight = T0 + 3_600_000L;
        t.spendCapped(true, "$10.31 of $10", midnight, T0);
        var tired = t.current(T0 + 1, false);
        assertTrue(tired.isPresent(), "a tripped cap tires him whether or not the grade is enforced");
        assertEquals(ModdyTired.Cause.SPEND_CAP, tired.get().cause());
        assertEquals(midnight, tired.get().untilMs(), "the child is told when it ends");
        assertTrue(t.current(midnight, false).isEmpty(), "the cap ran past the day it was for");
        t.spendCapped(false, "", 0L, T0 + 2);
        assertTrue(t.current(T0 + 3, false).isEmpty(), "a raised cap wakes him");
    }

    /**
     * A server's own spend tooling sends the operator ONE page, with the numbers; the mod's line for
     * this cause must not be a second page (an alert watcher pages every MODDY-ALERT).
     */
    @Test
    void aTrippedCapIsLoggedButPagedByTheHouseNotTwice() {
        ModdyTired t = new ModdyTired();
        t.spendCapped(true, "$10.31 of $10", T0 + 3_600_000L, T0);
        var entered = t.transition(T0 + 1, true);
        assertTrue(entered.isPresent() && entered.get().entered());
        assertFalse(entered.get().line().contains("MODDY-ALERT"), entered.get().line());
        assertTrue(entered.get().line().contains("spend_cap"), entered.get().line());
    }

    /** Only the cap knows its end; every other cause's Tired says 0, which reads as "for now". */
    @Test
    void onlyTheSpendCapKnowsWhenItEnds() {
        ModdyTired t = new ModdyTired();
        t.overQuota(true, "q", T0);
        assertEquals(0L, t.current(T0 + 1, false).get().untilMs());
    }
}
