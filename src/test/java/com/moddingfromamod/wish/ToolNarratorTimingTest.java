package com.moddingfromamod.wish;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Does the narrator's thread actually fire?</b>
 *
 * <p>Separate from {@link ToolNarrationTest}, which only chooses words, and from
 * {@code ToolNarratorGameTest}, which cannot do this at all: a GameTest server ticks as fast as it
 * can rather than at 20 per second, so 120 of its ticks go by in about a fifth of a second, and an
 * assertion phrased in ticks failed twice while the feature worked. The narrator's beats are
 * wall-clock — a child waiting 40 seconds waits 40 real seconds — so the test has to be too.
 *
 * <p>What it caught: the first implementation used a {@code ScheduledExecutorService} whose task
 * <b>never ran once</b> inside the game and passed here, because a scheduled task that dies takes
 * its reason into a Future nobody reads. It is a plain daemon thread now.
 */
class ToolNarratorTimingTest {

    private static final java.util.function.Supplier<Long> LONG_SILENT =
            () -> System.currentTimeMillis() - ToolNarration.AGENT_LINE_FRESH_MS - 5_000;

    /** Wait up to this long for the first beat, which is scheduled at 2.5s. */
    private static final long PATIENCE_MS = 8_000;

    private static String waitForLine(AtomicReference<String> heard) throws InterruptedException {
        long deadline = System.currentTimeMillis() + PATIENCE_MS;
        while (System.currentTimeMillis() < deadline && heard.get().isEmpty()) {
            Thread.sleep(100);
        }
        return heard.get();
    }

    @Test
    @DisplayName("the mod really does speak during a long call — the dead air is filled")
    void theThreadFires() throws Exception {
        AtomicReference<String> heard = new AtomicReference<>("");
        var handle = ToolNarrator.start("author_datapack", null, LONG_SILENT,
                (doing, phase) -> heard.set(doing));
        try {
            assertTrue(!waitForLine(heard).isEmpty(),
                    "nothing was said in " + PATIENCE_MS + "ms — the ticker is not running");
        } finally {
            handle.stop();
        }
    }

    @Test
    @DisplayName("stopping it stops it — a stale line on screen is the original bug")
    void stoppingIsImmediate() throws Exception {
        AtomicReference<String> heard = new AtomicReference<>("");
        ToolNarrator.start("author_datapack", null, LONG_SILENT,
                (doing, phase) -> heard.set(doing)).stop();
        Thread.sleep(4_000);
        assertTrue(heard.get().isEmpty(), "a stopped narrator kept talking: '" + heard.get() + "'");
    }

    @Test
    @DisplayName("a fresh agent line is never talked over by a generic one")
    void aFreshAgentLineWins() throws Exception {
        AtomicReference<String> heard = new AtomicReference<>("");
        var handle = ToolNarrator.start("author_datapack", null, System::currentTimeMillis,
                (doing, phase) -> heard.set(doing));
        try {
            Thread.sleep(4_000);
            assertTrue(heard.get().isEmpty(),
                    "a generic line replaced the agent's own words: '" + heard.get() + "'");
        } finally {
            handle.stop();
        }
    }
}
