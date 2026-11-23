package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.marshal.FakeClock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Gate A for the pack host's rate cap</b> (plan §5).
 *
 * <p>Every second here is a {@link FakeClock} second: the whole subject is refill over time, and a
 * test that slept for it would take a minute to prove one subtraction.
 */
class RateLimiterTest {

    @Test
    @DisplayName("the bucket empties after its rate and refuses the next one")
    void theBucketEmpties() {
        FakeClock clock = new FakeClock(0L);
        RateLimiter limiter = new RateLimiter(3, clock);
        assertTrue(limiter.allow("1.2.3.4"));
        assertTrue(limiter.allow("1.2.3.4"));
        assertTrue(limiter.allow("1.2.3.4"));
        assertFalse(limiter.allow("1.2.3.4"), "the fourth request in an instant must be refused");
        assertEquals(0, limiter.remaining("1.2.3.4"));
    }

    @Test
    @DisplayName("and refills over time — a minute buys the whole bucket back")
    void itRefills() {
        FakeClock clock = new FakeClock(0L);
        RateLimiter limiter = new RateLimiter(60, clock);
        for (int i = 0; i < 60; i++) {
            assertTrue(limiter.allow("a"), "request " + i + " should have been allowed");
        }
        assertFalse(limiter.allow("a"));
        clock.advance(1_000L);   // one second at 60/min = one token
        assertTrue(limiter.allow("a"), "a second later exactly one more request fits");
        assertFalse(limiter.allow("a"), "…and only one");
        clock.advance(60_000L);
        assertEquals(60, limiter.remaining("a"), "a minute refills the bucket and no more");
    }

    @Test
    @DisplayName("buckets are PER CALLER — one stranger cannot lock a child out")
    void bucketsArePerKey() {
        FakeClock clock = new FakeClock(0L);
        RateLimiter limiter = new RateLimiter(2, clock);
        assertTrue(limiter.allow("stranger"));
        assertTrue(limiter.allow("stranger"));
        assertFalse(limiter.allow("stranger"));
        assertTrue(limiter.allow("child"), "the child's own bucket is untouched");
        assertEquals(2, limiter.trackedKeys());
    }

    @Test
    @DisplayName("a rate of zero means NO LIMIT, not 'refuse everything'")
    void zeroMeansOff() {
        RateLimiter limiter = new RateLimiter(0, new FakeClock(0L));
        for (int i = 0; i < 1000; i++) {
            assertTrue(limiter.allow("anyone"),
                    "rpm=0 is an operator turning the cap OFF; refusing every request would take "
                            + "every icon off every stock client on a plausible typo");
        }
        assertEquals(0, limiter.trackedKeys(), "and it tracks nothing at all");
    }

    @Test
    @DisplayName("idle FULL buckets are evicted; a busy one is kept")
    void idleBucketsAreForgotten() {
        FakeClock clock = new FakeClock(0L);
        RateLimiter limiter = new RateLimiter(4, clock);
        for (int i = 0; i < RateLimiter.SWEEP_AT; i++) {
            limiter.allow("idle-" + i);
        }
        // One caller that is still out of tokens when the sweep comes round.
        for (int i = 0; i < 4; i++) {
            limiter.allow("busy");
        }
        assertFalse(limiter.allow("busy"));
        clock.advance(RateLimiter.IDLE_EVICT_MS + 1_000L);
        limiter.allow("newcomer");   // crosses SWEEP_AT and triggers the sweep
        assertTrue(limiter.trackedKeys() < RateLimiter.SWEEP_AT,
                "idle buckets must be forgotten — an un-evicted map keyed by remote address is a "
                        + "memory leak any stranger can address; " + limiter.trackedKeys() + " left");
        // "busy" refilled during those ten minutes, so it is allowed to be gone too; what must NOT
        // happen is a bucket being dropped while it is still empty, which would hand the caller
        // hammering hardest a fresh full bucket. Proven directly:
        FakeClock c2 = new FakeClock(0L);
        RateLimiter tight = new RateLimiter(1, c2);
        assertTrue(tight.allow("hammer"));
        assertFalse(tight.allow("hammer"));
        for (int i = 0; i < RateLimiter.SWEEP_AT + 1; i++) {
            tight.allow("filler-" + i);
        }
        assertFalse(tight.allow("hammer"),
                "the sweep must not have refilled an empty bucket by forgetting it");
    }

    @Test
    @DisplayName("a clock that goes backwards mints no tokens")
    void timeGoingBackwardsMintsNothing() {
        FakeClock clock = new FakeClock(100_000L);
        RateLimiter limiter = new RateLimiter(2, clock);
        assertTrue(limiter.allow("a"));
        assertTrue(limiter.allow("a"));
        assertFalse(limiter.allow("a"));
        RateLimiter backwards = new RateLimiter(2, () -> 0L);
        assertTrue(backwards.allow("b"));
        assertTrue(backwards.allow("b"));
        assertFalse(backwards.allow("b"), "a still (or reversed) clock refills nothing");
    }

    @Test
    @DisplayName("the shipped defaults are the ones the docs name")
    void theDefaultsAreTheDocumentedOnes() {
        assertEquals(60, PackHostLimits.DEFAULT_RPM);
        assertEquals(16, PackHostLimits.DEFAULT_MAX_CONCURRENT);
        assertEquals("moddingfromamod.packhost.rpm", PackHostLimits.RPM_PROPERTY);
    }

    @Test
    @DisplayName("the concurrency gate refuses past its cap and recovers on exit")
    void theConcurrencyGateHolds() {
        PackHostLimits limits = new PackHostLimits(0, 2, new FakeClock(0L));
        assertTrue(limits.tryEnter());
        assertTrue(limits.tryEnter());
        assertFalse(limits.tryEnter(), "the third concurrent response must be refused with 503");
        assertEquals(2, limits.inFlight(), "a refused entry must not leave the counter raised — "
                + "a leaked slot is a host that answers 503 forever with nothing running");
        limits.exit();
        assertTrue(limits.tryEnter());
        limits.exit();
        limits.exit();
        assertEquals(0, limits.inFlight());
    }
}
