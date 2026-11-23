package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 *, Gate A: when a wish datapack is eating the server, and which one — both sides. Healthy streams (the
 * servers' measured worst, one big spike, a "can't keep up" burst, a slow server with no pack behind it) are
 * never a quarantine; a sustained runaway names its pack, by its own function time or by the crowd it spawned.
 */
class QuarantineRuleTest {

    private static final long T0 = 1_000_000L;

    /** Feed {@code n} ticks of {@code ms} each, end to end, starting at {@code at}; returns the last verdict. */
    private static QuarantineRule.Verdict feed(QuarantineRule r, long[] at, int n, double ms,
                                               Map<String, Double> fn, Map<String, Integer> spawned, int entities) {
        QuarantineRule.Verdict v = null;
        for (int i = 0; i < n; i++) {
            at[0] += (long) Math.max(ms, 50);
            v = r.add(new QuarantineRule.Tick(at[0], ms, fn, spawned, entities));
        }
        return v;
    }

    @Test
    void theHousesMeasuredWorstMinuteIsCalm() {
        // nine days of [tick] lines: worst mean 38 ms, worst p95 102 ms — here, a minute of 102 ms ticks with a pack
        QuarantineRule r = new QuarantineRule();
        long[] at = {T0};
        var v = feed(r, at, 1200, 102, Map.of("trail", 5.0), Map.of(), 0);
        assertFalse(v.breach(), v.why());
        assertNull(v.pack());
    }

    @Test
    void oneSpikyTickIsNotABreach() {
        QuarantineRule r = new QuarantineRule();
        long[] at = {T0};
        feed(r, at, 100, 20, Map.of(), Map.of(), 0);
        var v = feed(r, at, 1, 1_600, Map.of("reveal", 1_500.0), Map.of("reveal", 300), 400);   // the servers' measured worst tick
        assertFalse(v.breach(), v.why());
        v = feed(r, at, 100, 20, Map.of(), Map.of(), 0);
        assertFalse(v.breach(), v.why());
    }

    @Test
    void aCantKeepUpBurstIsNotABreach() {
        // real servers logged single ticks up to 11.7 s behind: one huge tick, then fast catch-up ticks
        QuarantineRule r = new QuarantineRule();
        long[] at = {T0};
        feed(r, at, 50, 20, Map.of(), Map.of(), 0);
        assertFalse(feed(r, at, 1, 11_700, Map.of("p", 100.0), Map.of(), 0).breach());
        for (int i = 0; i < 200; i++) {
            at[0] += 5;
            assertFalse(r.add(new QuarantineRule.Tick(at[0], 5, Map.of(), Map.of(), 0)).breach(), "catch-up tick " + i);
        }
    }

    @Test
    void twoSlowTicksAreNotEnough() {
        QuarantineRule r = new QuarantineRule();
        long[] at = {T0};
        assertFalse(feed(r, at, 2, 3_000, Map.of("p", 3_000.0), Map.of(), 0).breach());
    }

    @Test
    void aSustainedRunawayPackIsNamedByItsFunctionTime() {
        QuarantineRule r = new QuarantineRule();
        long[] at = {T0};
        var v = feed(r, at, 30, 400, Map.of("loop", 360.0, "trail", 2.0), Map.of(), 0);
        assertTrue(v.breach(), v.why());
        assertEquals("loop", v.pack(), v.why());
        assertTrue(v.share() > 0.8, String.valueOf(v.share()));
    }

    @Test
    void aFloodIsNamedByTheCrowdItSpawnedEvenWhenItsFunctionsAreCheap() {
        // bh-g03: the time is vanilla's (carriers colliding), not the pack's functions
        QuarantineRule r = new QuarantineRule();
        long[] at = {T0};
        var v = feed(r, at, 20, 900, Map.of("babies", 1.0, "trail", 1.0), Map.of("babies", 300), 400);
        assertTrue(v.breach());
        assertEquals("babies", v.pack(), v.why());
    }

    @Test
    void aSlowServerWithNoPackBehindItPausesNothing() {
        QuarantineRule r = new QuarantineRule();
        long[] at = {T0};
        var v = feed(r, at, 30, 600, Map.of("trail", 2.0, "breath", 3.0), Map.of("trail", 2), 3_000);
        assertTrue(v.breach(), "slow it is");
        assertNull(v.pack(), "but nobody to blame: " + v.why());
    }

    @Test
    void twoPacksSharingTheBlameEquallyPausesNeither() {
        QuarantineRule r = new QuarantineRule();
        long[] at = {T0};
        var v = feed(r, at, 30, 600, Map.of("a", 280.0, "b", 280.0), Map.of(), 0);
        assertTrue(v.breach());
        assertNull(v.pack(), "LEAD: a tie names nobody — " + v.why());
    }

    @Test
    void forgettingAPackClearsItsBlame() {
        QuarantineRule r = new QuarantineRule();
        long[] at = {T0};
        assertEquals("loop", feed(r, at, 30, 400, Map.of("loop", 380.0), Map.of(), 0).pack());
        r.forget("loop");
        assertNull(r.judge(at[0]).pack());
    }

    @Test
    void oneHardStopIsACutTwoInAMinuteAreAQuarantine() {
        QuarantineRule r = new QuarantineRule();
        assertFalse(r.hardStop("p", T0));
        assertFalse(r.hardStop("p", T0 + QuarantineRule.HARD_STOP_WINDOW_MS + 1), "the first fell out of the window");
        assertTrue(r.hardStop("p", T0 + QuarantineRule.HARD_STOP_WINDOW_MS + 2_000));
        assertFalse(r.hardStop("other", T0), "per pack");
    }

    @Test
    void afterACrashTheMetersSuspectComesFirstThenWhatWasInstalledJustBefore() {
        long crash = T0 + 3_600_000L;
        var installed = List.of(new QuarantineRule.Installed("old", crash - 3 * 3_600_000L),
                new QuarantineRule.Installed("fresh", crash - 60_000), new QuarantineRule.Installed("later", crash + 60_000));
        assertEquals(List.of("babies"), QuarantineRule.crashSuspects(crash, "babies", crash - 20_000, installed));
        assertEquals(List.of("fresh"), QuarantineRule.crashSuspects(crash, "babies", crash - 3_600_000L, installed),
                "a stale suspect is not evidence");
        assertEquals(List.of("fresh"), QuarantineRule.crashSuspects(crash, null, 0, installed));
        assertTrue(QuarantineRule.crashSuspects(crash, null, 0,
                List.of(new QuarantineRule.Installed("old", crash - 3 * 3_600_000L))).isEmpty(), "nothing points: nothing");
    }

    @Test
    void theChildIsToldInHisOwnWords() {
        assertEquals("Moddy paused the baby calling magic because it was making the world freeze. Everything else "
                + "still works, and a grown-up can turn it back on.", DatapackQuarantine.kidWords("baby_calling", true));
        assertEquals("The baby calling magic is back on!", DatapackQuarantine.kidWords("baby_calling", false));
        assertTrue(DatapackQuarantine.isWatchdogCrash("ServerHangWatchdog detected that a single server tick took 60.00 seconds"));
        assertTrue(DatapackQuarantine.isWatchdogCrash("Description: Watching Server"));
        assertFalse(DatapackQuarantine.isWatchdogCrash("Description: Exception in server tick loop"));
    }

    /**
     * bh-g03's hypothesis, PINNED: does a baby wear its parent's tag (and so run the parent's on_hit)? A species
     * made with {@code start_from} under a NEW id does NOT — only a fork (the same creature made his own,
     * {@code <id>~<owner>}) wears its ancestor's tag. So bh-g03's babies did not carry the trigger: the growth came
     * from each baby's hit on the child firing the PARENT's on_hit again (on_hit keys on any victim hurt nearby,
     * never on who hurt it). The quarantine is built for either case; QuarantineGameTest's flood runs the worse one.
     */
    @Test
    void aStartFromBabyDoesNotWearItsParentsTagOnlyAForkDoes() {
        List<String> baby = com.moddingfromamod.vanilla.DisplayRig.speciesTags("bh_g03_baby");
        assertEquals(List.of(com.moddingfromamod.entity.WishMobTags.forSpecies("bh_g03_baby")), baby);
        List<String> fork = com.moddingfromamod.vanilla.DisplayRig.speciesTags("bh_g03_mum~ab12cd34");
        assertTrue(fork.contains(com.moddingfromamod.entity.WishMobTags.forSpecies("bh_g03_mum")), fork.toString());
    }
}
