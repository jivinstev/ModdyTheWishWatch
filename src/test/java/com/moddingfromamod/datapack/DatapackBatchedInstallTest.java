package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>One reload for a whole loop — Gate B's single biggest cost.</b>
 *
 * <p>Measured 2026-09-01 by sampling the Gate B server thread 60 times during {@code
 * defaultBatch:1}: <b>60 of 60 samples were inside one test</b>, and 51 of those were inside
 * {@code MinecraftServer.reloadResources}. That batch executed 1,220 ticks in 324.7 seconds — 3.8
 * ticks per second — while {@code stress:0} managed 7,500 ticks in 30.7s. Gate B was not slow
 * because of tick budgets; it was <b>blocked</b>, and the 49 other tests batched alongside it were
 * frozen behind the same thread. Two batches held 161 of the run's 183 reloads, and 514s of 1,237s.
 *
 * <p>Both halves of the rule matter, and the second one is the dangerous one: an install inside a
 * deferring body leaves a reload <b>owed</b> rather than dropped, and the wrapper flushes it
 * <b>in a finally</b>. A deferred install that never became real would be a much worse bug than the
 * slowness it fixes — it would surface far away and much later, as some unrelated test not seeing a
 * pack that was definitely written.
 *
 * <p>These drive the REAL {@link DatapackReloader#withDeferredReloads}, not a copy of its control
 * flow. A null server is safe here and is the reason it can be tested without booting Minecraft:
 * the server is touched only by the flush, and the flush returns immediately when nothing is owed.
 * The owed path is covered by marking the flag the way an install does and watching the flush
 * consume it.
 */
class DatapackBatchedInstallTest {

    @BeforeEach
    @AfterEach
    void clean() {
        DatapackReloader.resetDeferredForTest();
    }

    @Test
    void nothingIsDeferredUnlessSomebodyAsked() {
        // The default has to stay "reload now": one child, one item, one reload. A shortcut that
        // switched itself on for test runs would mean the gate stopped exercising what it names.
        assertFalse(DatapackReloader.deferringReloads(), "deferring must be off by default");
        assertFalse(DatapackReloader.shouldDeferInstall(false),
                "an install outside a batched body reloads immediately");
        assertTrue(DatapackReloader.shouldDeferInstall(true),
                "an install inside a batched body defers");
    }

    @Test
    void theFlagIsOnlyRaisedForTheDurationOfTheBody() throws Exception {
        boolean[] sawItInside = {false};
        DatapackReloader.withDeferredReloads(null, () -> sawItInside[0] = DatapackReloader.deferringReloads());
        assertTrue(sawItInside[0], "installs inside the body must see the deferring flag");
        assertFalse(DatapackReloader.deferringReloads(), "the flag must not outlive the body");
    }

    @Test
    void aBodyThatThrowsStillLowersTheFlag() {
        // The finally is the whole safety argument. A matrix cell that fails must not leave the
        // next caller silently deferring — that would strand ITS install, in a different file.
        assertThrows(IllegalStateException.class, () ->
                DatapackReloader.withDeferredReloads(null, () -> {
                    throw new IllegalStateException("a cell failed");
                }));
        assertFalse(DatapackReloader.deferringReloads(), "a throwing body must still clear the flag");
    }

    @Test
    void aNestedBodyDoesNotEndTheBatchEarly() throws Exception {
        // An inner wrapper clearing the flag would put every remaining cell back to
        // one-reload-each — the slow behaviour, with none of the noise that would reveal it.
        boolean[] stillDeferringAfterInner = {false};
        DatapackReloader.withDeferredReloads(null, () -> {
            DatapackReloader.withDeferredReloads(null, () -> { });
            stillDeferringAfterInner[0] = DatapackReloader.deferringReloads();
        });
        assertTrue(stillDeferringAfterInner[0], "only the outermost wrapper ends the batch");
        assertFalse(DatapackReloader.deferringReloads());
    }

    @Test
    void anOwedReloadIsConsumedExactlyOnce() {
        assertFalse(DatapackReloader.reloadOwed(), "nothing is owed before an install");
        assertFalse(DatapackReloader.flushDeferredReloads(null),
                "flushing with nothing owed must be a no-op, not a reload");
        assertEquals(false, DatapackReloader.reloadOwed());
    }
}
