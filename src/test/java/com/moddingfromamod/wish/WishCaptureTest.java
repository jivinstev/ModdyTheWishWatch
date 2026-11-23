package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The capture-mode session rules: a burst of photos keeps the child frozen only as long as needed,
 * and a hard watchdog ALWAYS releases him so a wedged agent can never trap him standing still.
 */
class WishCaptureTest {

    @Test
    void staysOpenDuringActivityWithinTheHardCap() {
        long start = 1_000;
        long now = start + 1_000;            // 1s in, just took a shot
        assertFalse(WishCapture.shouldExit(start, now, now),
                "fresh activity, well under the cap -> keep the session open");
    }

    @Test
    void releasesSoonAfterTheLastShot() {
        long start = 1_000;
        long lastShot = start + 500;
        long now = lastShot + WishCapture.IDLE_EXIT_MS;   // idle long enough
        assertTrue(WishCapture.shouldExit(start, lastShot, now),
                "idle since the last shot -> the agent moved on, release him");
    }

    @Test
    void aBurstOfShotsKeepsItOpenUntilTheHardCap() {
        long start = 1_000;
        // Shots keep coming just under the idle timeout, so it never idles out...
        long lastShot = start + WishCapture.MAX_SESSION_MS - 1;
        long now = lastShot + (WishCapture.IDLE_EXIT_MS - 1);
        assertTrue(WishCapture.shouldExit(start, lastShot, now),
                "...but the hard cap still releases him — the freeze can't outlast the watchdog");
    }

    @Test
    void theHardCapIsShorterThanEverIdlingForever() {
        // Even with activity RIGHT NOW, once past the hard cap he is released.
        long start = 1_000;
        long now = start + WishCapture.MAX_SESSION_MS;
        assertTrue(WishCapture.shouldExit(start, now, now),
                "past the hard cap -> released even if a shot just happened");
    }
}
