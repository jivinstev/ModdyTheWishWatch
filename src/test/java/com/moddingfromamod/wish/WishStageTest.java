package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Gate A 's stage rule: the bar names what the mod has OBSERVED, and the "still" lines
 * wait for real quiet. Timing lives here, with an explicit clock, because a GameTest does not tick
 * in real time (CLAUDE.md).
 */
class WishStageTest {

    private static String say(long nowMs, WishStage.Track t, String step, String agent, long agentMs) {
        return WishStage.choose(nowMs, t, step, agent, agentMs).words();
    }

    @Test
    void beforeAWorkerHasItTheBarIsReadingTheWish() {
        WishStage.Track t = new WishStage.Track(1_000);
        assertEquals(WishWaitWords.READING, say(1_000, t, "", null, 0));
        assertEquals(WishWaitWords.READING, say(20_000, t, "", null, 0),
                "19 s unclaimed is still the reading stage — not yet 30 s of quiet");
        assertEquals(WishWaitWords.READING, say(0, null, "", null, 0), "no track: just submitted");
    }

    @Test
    void claimedWithNoPlanIsWorkingOutHowToMakeIt() {
        WishStage.Track t = new WishStage.Track(0);
        t.claimed(6_600);
        assertTrue(t.isClaimed());
        assertEquals(WishWaitWords.WORKING_OUT, say(7_000, t, "", null, 0));
    }

    @Test
    void plannedShowsThePlansCurrentStep() {
        WishStage.Track t = new WishStage.Track(0);
        t.claimed(6_000);
        t.planned(20_000);
        assertEquals("Building the floating island…",
                say(21_000, t, "Building the floating island", null, 0));
        assertEquals("Giving it wings…", say(22_000, t, "Giving it wings...", null, 0),
                "a step that already trails off is not given a second ellipsis");
    }

    @Test
    void aPlanWithNoStepLabelFallsBackToWorkingOut() {
        WishStage.Track t = new WishStage.Track(0);
        t.planned(10_000);
        assertTrue(t.isClaimed(), "a plan is made by somebody who has the wish");
        assertEquals(WishWaitWords.WORKING_OUT, say(11_000, t, "", null, 0));
    }

    @Test
    void theAgentsLineWinsWhileItIsTheNewestThing() {
        WishStage.Track t = new WishStage.Track(0);
        t.claimed(5_000);
        WishStage.Line l = WishStage.choose(16_000, t, "", "Shaping it out of blocks…", 15_000);
        assertTrue(l.agent());
        assertEquals("Shaping it out of blocks…", l.words());
        // …and a plan that lands after it takes the bar back to the plan's step.
        t.planned(20_000);
        WishStage.Line after = WishStage.choose(21_000, t, "Building Spikor", "Shaping it out of blocks…", 15_000);
        assertFalse(after.agent());
        assertEquals("Building Spikor…", after.words());
    }

    @Test
    void stillWorkingIsThirtySecondsOfQuietNotThirtySecondsOfWish() {
        WishStage.Track t = new WishStage.Track(0);
        t.claimed(7_000);
        assertEquals(WishWaitWords.WORKING_OUT, say(36_999, t, "", null, 0), "29.999 s of quiet");
        assertEquals(WishWaitWords.STILL_WORKING, say(37_000, t, "", null, 0), "30 s of quiet");
        assertEquals(WishWaitWords.STILL_WORKING, say(66_999, t, "", null, 0));
        assertEquals(WishWaitWords.STILL_GOING, say(67_000, t, "", null, 0), "60 s of quiet");
        assertEquals(WishWaitWords.STILL_GOING, say(600_000, t, "", null, 0));
    }

    @Test
    void aLongButBusyWishKeepsShowingItsRealSteps() {
        WishStage.Track t = new WishStage.Track(0);
        t.claimed(7_000);
        t.planned(20_000);
        // A tool call every 20 s for five minutes: never 30 s of quiet.
        for (long at = 40_000; at <= 300_000; at += 20_000) {
            t.event(at);
            assertEquals("Building the castle…", say(at + 19_000, t, "Building the castle", null, 0),
                    "busy at " + at);
        }
        // The agent's own line counts as an event too.
        assertEquals("Painting the walls", say(345_000, t, "", "Painting the walls", 330_000));
        assertEquals(WishWaitWords.STILL_WORKING, say(360_000, t, "", "Painting the walls", 330_000));
    }

    @Test
    void anEventNeverMovesTheClockBackwards() {
        WishStage.Track t = new WishStage.Track(50_000);
        t.event(10_000);
        assertEquals(50_000, t.lastEventMs());
        t.claimed(60_000);
        t.claimed(90_000);
        assertEquals(60_000, t.stageMs(), "the first claim is the stage change");
    }

    @Test
    void theSparklesAreSaidOnlyForThingsThatAppearInTheWorld() {
        for (String k : new String[] {"building", "creature", "picture", "event", "train", "mob"}) {
            assertTrue(WishStage.saySparkles(true, k, false), k);
        }
        for (String k : new String[] {"item", "tool", "other", "teleport", "question"}) {
            assertFalse(WishStage.saySparkles(true, k, false), k);
        }
        assertFalse(WishStage.saySparkles(true, null, false), "kind not known yet: wait for plan_wish");
        assertFalse(WishStage.saySparkles(true, "", false));
        assertFalse(WishStage.saySparkles(false, "building", false), "no conjure_at, no line");
        assertFalse(WishStage.saySparkles(true, "building", true), "once per wish");
    }

    @Test
    void sparklesOncePerWishWhicheverCallComesSecond() {
        var conjureFirst = com.moddingfromamod.marshal.WishRun.detachedForTest(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "a castle");
        conjureFirst.conjured();
        assertFalse(conjureFirst.saySparklesOnce(), "kind not known yet");
        conjureFirst.planKind("building");
        assertTrue(conjureFirst.saySparklesOnce(), "said at plan_wish");
        assertFalse(conjureFirst.saySparklesOnce(), "and never again");

        var planFirst = com.moddingfromamod.marshal.WishRun.detachedForTest(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "a sword");
        planFirst.planKind("item");
        planFirst.conjured();
        assertFalse(planFirst.saySparklesOnce(), "an item is handed over, it does not appear");
    }

    /** while the character is looked up before a worker takes the wish, the bar says so. */
    @Test
    void theLookupIsNamedUntilAWorkerTakesTheWish() {
        WishStage.Track t = new WishStage.Track(0);
        assertEquals(WishWaitWords.READING, say(1_000, t, "", null, 0));
        t.lookingUp("Scorch", 2_000);
        assertEquals("Looking up Scorch…", say(3_000, t, "", null, 0));
        t.claimed(10_000);
        assertEquals(WishWaitWords.WORKING_OUT, say(11_000, t, "", null, 0), "taken: the lookup is over");
        t.lookingUp("Other", 12_000);
        assertEquals(WishWaitWords.WORKING_OUT, say(13_000, t, "", null, 0), "never after the claim");
    }
}
