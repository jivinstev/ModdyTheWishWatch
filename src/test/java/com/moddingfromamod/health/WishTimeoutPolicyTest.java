package com.moddingfromamod.health;


import java.util.List;
import java.util.UUID;

import com.moddingfromamod.marshal.Clock;
import com.moddingfromamod.marshal.FakeClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The timeout state machine, where the seconds are asserted</b>.
 *
 * <p>Here rather than in a GameTest, and that is CLAUDE.md's rule rather than a preference: a
 * GameTest server ticks as fast as it can, so a ceiling expressed in real seconds cannot be
 * asserted in ticks — it fails while the feature works and reads as a dead feature. The GameTest
 * proves only that the sweeper is wired to the book, the bar and the log.
 */
class WishTimeoutPolicyTest {

    private static final UUID WISH = UUID.nameUUIDFromBytes("timeout-test".getBytes());

    private static WishTimeoutPolicy policy(long claimS, long runS, Clock clock) {
        return new WishTimeoutPolicy(claimS, runS, clock);
    }

    // ── NEVER CLAIMED ────────────────────────────────────────────────────────────────────────

    @Test
    void anUnclaimedWishSurvivesUntilItsCeiling() {
        FakeClock clock = new FakeClock(1_000_000L);
        WishTimeoutPolicy p = policy(120, 900, clock);
        WishTimeoutPolicy.Candidate c = new WishTimeoutPolicy.Candidate(WISH, clock.nowMs(), 0L);

        clock.advance(119_000L);
        assertTrue(p.judge(c).isEmpty(), "119 s is inside a 120 s ceiling");

        clock.advance(1_000L);
        var expiry = p.judge(c);
        assertTrue(expiry.isPresent(), "120 s is the ceiling and the ceiling is reached");
        assertEquals(WishTimeoutPolicy.Reason.NEVER_CLAIMED, expiry.get().reason());
        assertEquals(120L, expiry.get().ageS());
        assertEquals("never claimed after 120s", expiry.get().line());
    }

    // ── RAN TOO LONG ─────────────────────────────────────────────────────────────────────────

    /**
     * A wish a worker TOOK is judged from when it was taken, and against the long ceiling. The
     * dangerous bug here is the opposite: judging a running wish against the claim ceiling would
     * kill every normal wish at two minutes, and a Spikor takes 164 s.
     */
    @Test
    void aClaimedWishIsJudgedFromWhenItWasClaimedAndAgainstTheLongCeiling() {
        FakeClock clock = new FakeClock(1_000_000L);
        WishTimeoutPolicy p = policy(120, 900, clock);
        long created = clock.nowMs();
        clock.advance(60_000L);
        WishTimeoutPolicy.Candidate c =
                new WishTimeoutPolicy.Candidate(WISH, created, clock.nowMs());

        clock.advance(300_000L);
        assertTrue(p.judge(c).isEmpty(),
                "six minutes into a real grant is not a timeout — a Spikor measured 164 s");

        clock.advance(600_000L);
        var expiry = p.judge(c);
        assertTrue(expiry.isPresent());
        assertEquals(WishTimeoutPolicy.Reason.RAN_TOO_LONG, expiry.get().reason());
        assertEquals(900L, expiry.get().ageS(), "measured from the claim, not from the wish");
    }

    @Test
    void theTwoCeilingsAreIndependent() {
        FakeClock clock = new FakeClock(0L);
        WishTimeoutPolicy p = policy(10, 1_000, clock);
        long created = clock.nowMs();
        clock.advance(5_000L);
        WishTimeoutPolicy.Candidate claimed =
                new WishTimeoutPolicy.Candidate(WISH, created, clock.nowMs());
        clock.advance(60_000L);
        assertTrue(p.judge(claimed).isEmpty(),
                "a claimed wish must never be judged against the CLAIM ceiling, however old it is");
    }

    // ── the sweep ────────────────────────────────────────────────────────────────────────────

    @Test
    void sweepReturnsOnlyTheExpiredOnes() {
        FakeClock clock = new FakeClock(0L);
        WishTimeoutPolicy p = policy(100, 900, clock);
        UUID young = UUID.nameUUIDFromBytes("young".getBytes());
        UUID old = UUID.nameUUIDFromBytes("old".getBytes());
        // Both unclaimed (startedMs 0); neither createdMs is 0, because 0 means "we do not know
        // when this wish was made" and judge() deliberately leaves those alone — see
        // aWishWithNoCreationTimeIsLeftAlone. A fixture that used 0 as "long ago" would be
        // asserting the guard rather than the sweep.
        List<WishTimeoutPolicy.Candidate> live = List.of(
                new WishTimeoutPolicy.Candidate(young, 150_000L, 0L),
                new WishTimeoutPolicy.Candidate(old, 1_000L, 0L));
        var expired = p.sweep(live, 200_000L);
        assertEquals(1, expired.size());
        assertEquals(old, expired.get(0).wishId());
    }

    @Test
    void aWishWithNoCreationTimeIsLeftAlone() {
        WishTimeoutPolicy p = policy(1, 1, new FakeClock(999_999L));
        assertTrue(p.judge(new WishTimeoutPolicy.Candidate(WISH, 0L, 0L)).isEmpty(),
                "a candidate we know nothing about is never killed on a guess");
        assertTrue(p.sweep(null, 1L).isEmpty());
    }

    // ── A FULL POOL IS A QUEUE, NOT "NOBODY IS BEHIND THE BAR" ──────────────────────────

    /**
     * The bug: every worker busy on somebody's wish, one more wish arrives, and at 120 s the child
     * is told "Moddy fell asleep" — while the pool is working normally and a wish takes p50 117 s.
     * Measured from creation alone, this candidate expires; measured from the last moment the pool
     * was full, it has barely started waiting.
     */
    @Test
    void aWishQueuedBehindAFullPoolIsNotTimedOutWhileThePoolIsFull() {
        FakeClock clock = new FakeClock(1_000_000L);
        WishTimeoutPolicy p = policy(120, 900, clock);
        long created = clock.nowMs();
        WishTimeoutPolicy.Candidate c = new WishTimeoutPolicy.Candidate(WISH, created, 0L);
        long now = created + 300_000L;                 // five minutes behind a busy pool
        long lastFull = now - 10_000L;                 // ...which freed a worker 10 s ago
        assertTrue(p.judge(c, now, 0L).isPresent(),
                "the old reading: judged from creation, it has 'never been claimed'");
        assertTrue(p.judge(c, now, lastFull).isEmpty(),
                "somebody WAS behind it the whole time; the claim clock starts when a worker frees");
        var later = p.judge(c, lastFull + 120_000L, lastFull);
        assertTrue(later.isPresent(), "a free worker that still does not take it IS the failure");
        assertEquals(WishTimeoutPolicy.Reason.NEVER_CLAIMED, later.get().reason());
        assertEquals(1, p.sweep(List.of(c), lastFull + 120_000L, lastFull).size());
    }

    @Test
    void saturatedMeansEveryWorkerBusyAndNeverAPoolOfNobody() {
        assertTrue(WishTimeoutPolicy.saturated(3, 3));
        assertFalse(WishTimeoutPolicy.saturated(3, 2), "a free worker");
        assertFalse(WishTimeoutPolicy.saturated(0, 0),
                "no workers at all is the case the claim ceiling EXISTS for, never a full pool");
    }

    // ── configuration ────────────────────────────────────────────────────────────────────────

    @Test
    void theDefaultsAreThePlansNumbers() {
        assertEquals(120L, WishTimeoutPolicy.DEFAULT_CLAIM_TIMEOUT_S);
        assertEquals(900L, WishTimeoutPolicy.DEFAULT_RUN_TIMEOUT_S,
                "the run ceiling IS the MCP client timeout — past it nothing is driving the call");
    }

    @Test
    void thePropertiesAreReadAndRubbishFallsBackRatherThanDisablingTheSweep() {
        String claim = WishTimeoutPolicy.CLAIM_PROPERTY;
        String run = WishTimeoutPolicy.RUN_PROPERTY;
        String oldClaim = System.getProperty(claim);
        String oldRun = System.getProperty(run);
        try {
            System.setProperty(claim, "7");
            System.setProperty(run, "11");
            assertEquals(7L, WishTimeoutPolicy.fromProperties().claimTimeoutS());
            assertEquals(11L, WishTimeoutPolicy.fromProperties().runTimeoutS());

            System.setProperty(claim, "not-a-number");
            System.setProperty(run, "-5");
            assertEquals(120L, WishTimeoutPolicy.fromProperties().claimTimeoutS(),
                    "a typo'd timeout must not be a server that never times a wish out");
            assertEquals(900L, WishTimeoutPolicy.fromProperties().runTimeoutS());
        } finally {
            restore(claim, oldClaim);
            restore(run, oldRun);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /**
     * <b>The one test that really sleeps.</b> Everything above drives a {@link FakeClock}, which
     * proves the arithmetic and not the wiring of the clock itself — a policy accidentally reading
     * {@code System.currentTimeMillis()} instead of its injected clock passes every one of them.
     * This one runs the shipped {@link Clock#system()} against a one-second ceiling.
     */
    @Test
    void theSystemClockPathReallyExpiresAfterRealSeconds() throws InterruptedException {
        WishTimeoutPolicy p = policy(1, 900, Clock.system());
        WishTimeoutPolicy.Candidate c = new WishTimeoutPolicy.Candidate(
                WISH, System.currentTimeMillis(), 0L);
        assertFalse(p.judge(c).isPresent(), "not yet");
        Thread.sleep(1_100L);
        assertTrue(p.judge(c).isPresent(), "a second really did go by");
    }
}
