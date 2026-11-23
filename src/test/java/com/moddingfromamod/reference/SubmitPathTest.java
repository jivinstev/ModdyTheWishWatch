package com.moddingfromamod.reference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Nothing on the wish-submission path may block.</b>
 *
 * <p>{@code WishService.submit} runs on the server thread the instant a child presses enter.
 * {@link CharacterResolver} spawns a model process and waits up to 15 seconds; calling it inline
 * there would freeze the game for up to fifteen seconds per wish, before the wish was even
 * acknowledged — on the one path whose entire purpose is that nothing waits.
 */
class SubmitPathTest {

    @Test
    @DisplayName("prefetchFrom returns immediately, whatever the resolver is doing")
    void prefetchIsNonBlocking() {
        CharacterResolver.clear();
        long t = System.nanoTime();
        var work = ReferenceHint.prefetchFrom("Make SPIKOR, king of the Kroaks! He can breathe FIRE!");
        long ms = (System.nanoTime() - t) / 1_000_000;
        drain(work);
        assertTrue(ms < 500,
                "took " + ms + "ms on the submission path; the game would stall for this long "
                        + "every time a child makes a wish");
    }

    @Test
    @DisplayName("a wish naming nobody is just as fast")
    void noCharacterIsAlsoFast() {
        CharacterResolver.clear();
        long t = System.nanoTime();
        var work = ReferenceHint.prefetchFrom("make me a cool monster please");
        assertTrue((System.nanoTime() - t) / 1_000_000 < 500, "must not block");
        drain(work);
    }

    /**
     * LEAVE NOTHING RUNNING. The prefetch writes into whatever cache directory is current when it
     * lands, and in a full Gate A that was ANOTHER test's @TempDir mid-deletion: 1.21.1's run
     * failed ReferenceResearcherTest with "Failed to delete temp directory". Measured
     * AFTER the timing assertion, so the non-blocking claim is still the thing tested.
     */
    private static void drain(java.util.concurrent.CompletableFuture<Void> work) {
        try {
            work.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // finished either way is all this needs
        }
        ReferenceResearcher.quiesceForTest();
    }
}
