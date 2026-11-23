package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Two datapack wishes at once must not stack resource reloads.
 *
 * <p>A reload occupies the server thread for 10–30 seconds on a modded instance, and this repo has
 * the scar to prove what happens when they pile up: <b>35 retries produced 34 full data reloads and
 * 48 JEI restarts in fifteen minutes</b> and made the game unplayable. Concurrency makes that
 * reachable again from a direction the existing guard did not cover — not a retrying agent, but two
 * agents authoring at the same time.
 *
 * <p>The second reload is <b>deferred, never skipped</b>. Skipping it would be the older, worse bug:
 * that reload is the one that makes a just-authored pack real, and dropping it leaves a wish that
 * reports success and does nothing.
 */
class DatapackReloadCoalescingTest {

    @AfterEach
    void tearDown() {
        DatapackReloader.resetCoalescing();
    }

    @Test
    void aReloadArrivingDuringAnotherIsDeferred() {
        CompletableFuture<Void> running = new CompletableFuture<>();
        DatapackReloader.trackReload(running);

        assertTrue(DatapackReloader.reloadInProgress());
        assertTrue(DatapackReloader.shouldCoalesce(),
                "a second reload must wait for the first rather than start on top of it");

        running.complete(null);
        assertFalse(DatapackReloader.shouldCoalesce(),
                "and once the first is done the next one runs immediately — deferred, not dropped");
    }

    @Test
    void withNothingRunningAReloadStartsAtOnce() {
        DatapackReloader.resetCoalescing();
        assertFalse(DatapackReloader.shouldCoalesce());
        assertFalse(DatapackReloader.reloadDeferred());
    }

    @Test
    void aFinishedReloadDoesNotLookLikeARunningOne() {
        DatapackReloader.trackReload(CompletableFuture.completedFuture(null));
        assertFalse(DatapackReloader.reloadInProgress(),
                "a completed future must not hold the door shut on every future reload");
    }
}
