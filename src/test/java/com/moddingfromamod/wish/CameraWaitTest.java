package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A camera that sleeps between wishes (zero running at rest; a wish
 * resumes one), and the one number an operator reads about it — how long a wish's FIRST photograph
 * waited for a camera. Deterministic clock throughout: a wait timed in a test's real seconds would be
 * a test of the machine it ran on.
 */
class CameraWaitTest {

    private static CameraLifecycle.State s(int wanting, int wishes, boolean up, boolean starting,
                                           long idleSince) {
        return new CameraLifecycle.State(wanting, wishes, up, starting, idleSince, 0, 0);
    }

    // ── the lifecycle in wishes mode: wanted = a wish in flight, a 60 s linger ──────────────────

    @Test
    void aWishInFlightStartsTheCameraEvenWithTheChildAlreadyOnline() {
        // The supervisor passes WISHES as "who wants one" in this mode — a child online with no wish
        // in flight is zero, so nothing runs at rest.
        assertEquals(CameraLifecycle.Action.START, CameraLifecycle.decide(s(1, 1, false, false, 0), 1000, 60_000));
        assertEquals(CameraLifecycle.Action.NONE, CameraLifecycle.decide(s(0, 0, false, false, 500), 1000, 60_000));
    }

    @Test
    void theCameraLingersSixtySecondsAfterTheLastWishThenStops() {
        long idle = 100_000;
        assertEquals(CameraLifecycle.Action.NONE,
                CameraLifecycle.decide(s(0, 0, true, false, idle), idle + 59_999, 60_000),
                "a wish inside the linger reuses the RUNNING camera — no resume");
        assertEquals(CameraLifecycle.Action.STOP,
                CameraLifecycle.decide(s(0, 0, true, false, idle), idle + 60_000, 60_000));
    }

    @Test
    void theOldTwoArgumentRuleIsUnchanged() {
        long idle = 100_000;
        assertEquals(CameraLifecycle.Action.NONE,
                CameraLifecycle.decide(s(0, 0, true, false, idle), idle + 60_000));
        assertEquals(CameraLifecycle.Action.STOP,
                CameraLifecycle.decide(s(0, 0, true, false, idle), idle + CameraLifecycle.LINGER_MS));
    }

    // ── the wait ────────────────────────────────────────────────────────────────────────────────

    private static final class Clock {
        final AtomicLong now = new AtomicLong(1_000_000);

        CameraWait.Sleeper sleeper() {
            return now::addAndGet;
        }
    }

    @Test
    void aCameraAlreadyThereIsZeroAndFree() {
        Clock c = new Clock();
        var r = CameraWait.await(true, () -> true, () -> false, 90_000, 250, c.now::get, c.sleeper());
        assertEquals(new CameraWait.Result(CameraWait.How.FREE, 0), r);
    }

    @Test
    void aSleepingCameraIsWaitedForAndTheWaitIsTheResumeTime() {
        Clock c = new Clock();
        long joinsAt = c.now.get() + 12_500;     // the camera joins 12.5 s after the photo asked
        var r = CameraWait.await(true, () -> c.now.get() >= joinsAt, () -> false, 90_000, 250,
                c.now::get, c.sleeper());
        assertEquals(CameraWait.How.WOKEN, r.how());
        assertEquals(12_500, r.waitedMs());
    }

    @Test
    void aCameraThatNeverComesIsWaitedForOnlyTheBound() {
        Clock c = new Clock();
        var r = CameraWait.await(true, () -> false, () -> false, 90_000, 250, c.now::get, c.sleeper());
        assertEquals(CameraWait.How.TIMED_OUT, r.how());
        assertEquals(90_000, r.waitedMs());
    }

    @Test
    void aSupervisorThatGaveUpEndsTheWaitAtOnce() {
        Clock c = new Clock();
        AtomicBoolean gave = new AtomicBoolean(false);
        long givesUpAt = c.now.get() + 3_000;
        var r = CameraWait.await(true, () -> false, () -> c.now.get() >= givesUpAt, 90_000, 250,
                c.now::get, c.sleeper());
        assertEquals(CameraWait.How.TIMED_OUT, r.how());
        assertEquals(3_000, r.waitedMs());
        assertEquals(new CameraWait.Result(CameraWait.How.NONE, 0),
                CameraWait.await(true, () -> false, () -> true, 90_000, 250, c.now::get, c.sleeper()),
                "already given up: nothing to wait for");
        assertFalse(gave.get());
    }

    @Test
    void childrenModeNeverWaitsItBorrowsTheEyesAsBefore() {
        Clock c = new Clock();
        assertEquals(new CameraWait.Result(CameraWait.How.NONE, 0),
                CameraWait.await(false, () -> false, () -> false, 90_000, 250, c.now::get, c.sleeper()));
        assertFalse(CameraWait.shouldWait(false, false, false));
        assertTrue(CameraWait.shouldWait(true, false, false));
        assertFalse(CameraWait.shouldWait(true, true, false));
        assertFalse(CameraWait.shouldWait(true, false, true));
    }

    // ── one line per wish ───────────────────────────────────────────────────────────────────────

    @Test
    void onlyTheFirstPhotographOfAWishIsRecorded() {
        UUID w = UUID.randomUUID();
        assertTrue(CameraWait.firstFor(w));
        assertFalse(CameraWait.firstFor(w));
        assertFalse(CameraWait.firstFor(null), "no wish, no line");
    }

    @Test
    void theLineIsWhatKnobSyncReads(@TempDir Path dir) throws Exception {
        UUID w = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String line = CameraWait.line(1_790_000_000_123L, w, new CameraWait.Result(CameraWait.How.WOKEN, 12_500), true);
        assertEquals("{\"at\":1790000000,\"wish\":\"00000000-0000-0000-0000-000000000001\",\"how\":\"woken\","
                + "\"waitedMs\":12500,\"mode\":\"wishes\"}", line);
        Path f = dir.resolve("sub").resolve("camera-waits.jsonl");
        CameraWait.record(f, line);
        CameraWait.record(f, line);
        assertEquals(2, Files.readAllLines(f).size(), "appended, never rewritten");
    }

    @Test
    void aPhotographWaitsOnlyForWhatIsLeftOfALateJoin() {
        // being started right now: the whole late-join bound
        assertEquals(CameraWait.LATE_JOIN_MS, CameraWait.budgetMs(90_000, 0));
        // started 20 s ago: the rest of it
        assertEquals(CameraWait.LATE_JOIN_MS - 20_000, CameraWait.budgetMs(90_000, 20_000));
        // the gold-dragon demo's camera, started at submit and still absent 75 s later: no wait
        assertEquals(0, CameraWait.budgetMs(90_000, 75_000));
        // never more than the configured ceiling
        assertEquals(5_000, CameraWait.budgetMs(5_000, 0));
    }

    @Test
    void theSettingsDefaultToTheOldBehaviour() {
        System.clearProperty(CameraWait.DEMAND);
        System.clearProperty(CameraWait.LINGER_MS);
        assertFalse(CameraWait.demandIsWishes());
        assertEquals(CameraLifecycle.LINGER_MS, CameraWait.lingerMs());
        assertEquals(CameraLifecycle.START_TIMEOUT_MS, CameraWait.maxWaitMs());
        System.setProperty(CameraWait.DEMAND, "wishes");
        System.setProperty(CameraWait.LINGER_MS, "60000");
        try {
            assertTrue(CameraWait.demandIsWishes());
            assertEquals(60_000, CameraWait.lingerMs());
        } finally {
            System.clearProperty(CameraWait.DEMAND);
            System.clearProperty(CameraWait.LINGER_MS);
        }
    }
}
