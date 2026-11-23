package com.moddingfromamod.reference;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A child must never wait on a reference.</b>
 *
 * <p>Measured on five real lookups: 75, 90, 97, 109 and 109 seconds. Not a tail — the whole
 * distribution, with the fastest at 75s. A blocking lookup adds a minute and a half to a wish while
 * a seven-year-old watches a ticker, and no cap short enough to fix that could ever succeed,
 * because the minimum exceeds any tolerable wait.
 */
class ReferenceLatencyTest {

    /**
     * <b>Wait for the background prefetch before JUnit deletes the directory it is writing into.</b>
     *
     * <p>Without this the suite fails intermittently with {@code Failed to delete temp directory},
     * and — the part that makes it expensive — the failure is reported against whichever test lost
     * the race rather than the one that started the prefetch. One Gate A run failed on
     * {@code cachedIsInstant} and passed on a straight re-run with no code between them, which reads
     * as environmental flakiness and is really a writer outliving its own test.
     *
     * <p>{@code lookUp} returns immediately by design ({@code MAX_WAIT_SECONDS} is 0) and leaves a
     * prefetch running, so <b>any</b> test here that calls it leaks one.
     */
    @AfterEach
    void stopWritingIntoTheTempDir() {
        ReferenceResearcher.quiesceForTest();
    }

    @Test
    @DisplayName("the cap is short enough to matter to a child")
    void capIsHumane() {
        assertTrue(ReferenceResearcher.MAX_WAIT_SECONDS <= 30,
                "measured lookups take 75-109s; a cap above ~30s is not a cap, it is a stall");
    }

    @Test
    @DisplayName("a cached reference returns instantly — the case prefetching is designed to create")
    void cachedIsInstant(@TempDir Path dir) throws Exception {
        ReferenceResearcher.cacheDir(dir);
        Files.writeString(dir.resolve("instantmon.json"),
                "{\"colours\":[{\"colour\":\"green\",\"percent\":80}],\"confidence\":\"HIGH\"}");
        long t = System.nanoTime();
        var spec = ReferenceResearcher.lookUp("instantmon");
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertTrue(!spec.isEmpty(), "should have read the cache");
        assertTrue(ms < 1000, "a cache hit took " + ms + "ms; prefetching relies on this being ~0");
    }

    @Test
    @DisplayName("no CLI means no reference, immediately — never a hang")
    void missingProviderDoesNotStall(@TempDir Path dir) {
        ReferenceResearcher.cacheDir(dir);
        long t = System.nanoTime();
        var spec = ReferenceResearcher.lookUp("nothing-will-resolve-this-" + System.nanoTime());
        long s = (System.nanoTime() - t) / 1_000_000_000;
        assertTrue(spec.isEmpty());
        assertTrue(s <= ReferenceResearcher.MAX_WAIT_SECONDS + 5,
                "took " + s + "s, which is past the cap — the child is waiting");
    }
}
