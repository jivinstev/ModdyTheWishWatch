package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Gate A for the throttle. The clock is injected, so these run instantly and never flake. */
class WishRateLimiterTest {

    @Test
    void allowsABurstThenRateLimits() {
        WishRateLimiter rl = new WishRateLimiter(3, 1_000L, 0L);
        long t = 10_000L;
        for (int i = 0; i < 3; i++) {
            assertEquals(WishRateLimiter.Decision.ALLOW, rl.check("kid", "wish" + i, t),
                    "burst wish " + i + " should be allowed");
        }
        assertEquals(WishRateLimiter.Decision.RATE_LIMITED, rl.check("kid", "wish4", t));
    }

    @Test
    void refillsOverTime() {
        WishRateLimiter rl = new WishRateLimiter(2, 1_000L, 0L);
        long t = 0L;
        rl.check("kid", "a", t);
        rl.check("kid", "b", t);
        assertEquals(WishRateLimiter.Decision.RATE_LIMITED, rl.check("kid", "c", t));
        assertEquals(WishRateLimiter.Decision.ALLOW, rl.check("kid", "d", t + 1_000L),
                "one token should be back after the interval");
    }

    @Test
    void ignoresTheSameWishTwiceInARow() {
        // Double-clicking a preset button must not queue two wishes.
        WishRateLimiter rl = new WishRateLimiter(10, 1_000L, 3_000L);
        assertEquals(WishRateLimiter.Decision.ALLOW, rl.check("kid", "rain chickens", 0L));
        assertEquals(WishRateLimiter.Decision.DUPLICATE, rl.check("kid", "rain chickens", 500L));
        assertEquals(WishRateLimiter.Decision.ALLOW, rl.check("kid", "rain chickens", 4_000L),
                "the same wish is fine again once the window passes");
    }

    @Test
    void rejectionDoesNotCostAToken() {
        WishRateLimiter rl = new WishRateLimiter(2, 10_000L, 5_000L);
        rl.check("kid", "same", 0L);                       // ALLOW, 1 left
        rl.check("kid", "same", 100L);                     // DUPLICATE — must not consume
        assertEquals(1, rl.tokensFor("kid", 100L), "a duplicate must not spend a token");
    }

    @Test
    void playersAreThrottledIndependently() {
        WishRateLimiter rl = new WishRateLimiter(1, 10_000L, 0L);
        assertEquals(WishRateLimiter.Decision.ALLOW, rl.check("kid", "a", 0L));
        assertEquals(WishRateLimiter.Decision.ALLOW, rl.check("dad", "a", 0L),
                "one player's spending must not throttle another");
    }

    @Test
    void neverExceedsCapacityAndSurvivesAClockJump() {
        WishRateLimiter rl = new WishRateLimiter(5, 1_000L, 0L);
        rl.check("kid", "a", 0L);
        // A huge forward jump must cap at capacity, not hand out unlimited tokens.
        assertEquals(5, rl.tokensFor("kid", 10_000_000L));
        // A backwards clock must not grant free tokens or throw.
        rl.check("kid", "b", 10_000_000L);
        assertTrue(rl.tokensFor("kid", 0L) <= 5);
    }

    @Test
    void forgettingAPlayerResetsThem() {
        WishRateLimiter rl = new WishRateLimiter(1, 10_000L, 0L);
        rl.check("kid", "a", 0L);
        assertEquals(0, rl.tokensFor("kid", 0L));
        rl.forget("kid");
        assertNotEquals(0, rl.tokensFor("kid", 0L), "a logged-out player's state should be dropped");
    }
}
