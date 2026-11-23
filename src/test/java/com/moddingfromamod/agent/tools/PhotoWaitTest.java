package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;

/**
 * the photograph wait, driven by a fake clock. The file is a function of time, so each
 * case says exactly what is on disk when, and the wait's answer is read off the clock.
 */
class PhotoWaitTest {

    static final long BEFORE = 1_000L;
    static final long APPEAR = 30_000L;
    static final long QUIET = 4_000L;
    static final long CAP = 30_000L;
    static final long POLL = 150L;

    static final PhotoWait.Probe ABSENT = new PhotoWait.Probe(0L, 0L);

    /** A fake disk: what the probe sees at time t, and a counter of decode attempts. */
    static final class Rig {
        long now = 0L;
        int reads = 0;
        final LongFunction<PhotoWait.Probe> disk;
        final LongFunction<Boolean> readable;

        Rig(LongFunction<PhotoWait.Probe> disk, LongFunction<Boolean> readable) {
            this.disk = disk;
            this.readable = readable;
        }

        PhotoWait.Outcome<String> run(long appearMs) {
            return PhotoWait.await(BEFORE, appearMs, QUIET, CAP, POLL,
                    () -> now,
                    () -> disk.apply(now),
                    () -> {
                        reads++;
                        return readable.apply(now) ? "png" : null;
                    },
                    ms -> now += ms);
        }
    }

    static PhotoWait.Probe file(long mtime, long size) {
        return new PhotoWait.Probe(mtime, size);
    }

    @Test
    void anAbsentFileIsNotProgressItGivesUpAtTheAppearDeadline() {
        // A short appear deadline makes the point: the quiet period is 4 s and the cap 30 s, and
        // the wait must end at the APPEAR deadline — not at the cap, which is where the old
        // bookkeeping (absent size 0 != -1, every poll) sent it.
        Rig rig = new Rig(t -> ABSENT, t -> true);
        PhotoWait.Outcome<String> out = rig.run(6_000L);
        assertEquals(PhotoWait.Status.NEVER_APPEARED, out.status());
        assertFalse(out.appeared());
        assertNull(out.value());
        assertTrue(out.waitedMs() >= 6_000L && out.waitedMs() < 6_000L + POLL, "waited " + out.waitedMs());
        assertEquals(0, rig.reads, "nothing to decode, nothing decoded");
    }

    @Test
    void anAbsentFileWithTheShippedDeadlineEndsAtItNotBeyond() {
        Rig rig = new Rig(t -> ABSENT, t -> true);
        PhotoWait.Outcome<String> out = rig.run(APPEAR);
        assertEquals(PhotoWait.Status.NEVER_APPEARED, out.status());
        assertTrue(out.waitedMs() >= APPEAR && out.waitedMs() < APPEAR + POLL, "waited " + out.waitedMs());
    }

    @Test
    void aStaleFileFromAnEarlierShotIsNotTheNewPhoto() {
        // The shape: a file is on disk, but it is not newer than `before`.
        Rig rig = new Rig(t -> file(BEFORE, 50_000L), t -> true);
        PhotoWait.Outcome<String> out = rig.run(6_000L);
        assertEquals(PhotoWait.Status.NEVER_APPEARED, out.status());
        assertEquals(0, rig.reads);
    }

    @Test
    void aFileThatAppearsLateThenSettlesIsReadSoonAfter() {
        // First byte at 9 s — past the quiet period, which must not have started yet — whole by 10 s.
        Rig rig = new Rig(t -> t < 9_000 ? ABSENT
                : t < 10_000 ? file(BEFORE + t, t - 8_000) : file(BEFORE + 10_000, 2_000_000L),
                t -> t >= 10_000);
        PhotoWait.Outcome<String> out = rig.run(APPEAR);
        assertEquals(PhotoWait.Status.SETTLED, out.status());
        assertEquals("png", out.value());
        assertTrue(out.waitedMs() >= 10_000 && out.waitedMs() <= 10_000 + 2 * POLL, "waited " + out.waitedMs());
    }

    @Test
    void aFileThatKeepsGrowingStopsAtTheCap() {
        Rig rig = new Rig(t -> t < 1_000 ? ABSENT : file(BEFORE + t, t), t -> true);
        PhotoWait.Outcome<String> out = rig.run(APPEAR);
        assertEquals(PhotoWait.Status.STILL_GROWING_AT_CAP, out.status());
        assertTrue(out.appeared());
        assertTrue(out.waitedMs() >= CAP && out.waitedMs() < CAP + POLL, "waited " + out.waitedMs());
        assertEquals(0, rig.reads, "never two equal sizes, never decoded");
    }

    @Test
    void aFileCreatedEmptyThenWrittenIsWaitedFor() {
        // Created at 2 s with size 0, sits at 0 for 3 s (under the quiet period), then written.
        Rig rig = new Rig(t -> t < 2_000 ? ABSENT
                : t < 5_000 ? file(BEFORE + 2_000, 0L)
                : file(BEFORE + 5_000, 1_500_000L),
                t -> true);
        PhotoWait.Outcome<String> out = rig.run(APPEAR);
        assertEquals(PhotoWait.Status.SETTLED, out.status());
        assertTrue(out.waitedMs() >= 5_000 && out.waitedMs() <= 5_000 + 2 * POLL, "waited " + out.waitedMs());
    }

    @Test
    void anEmptyFileThatNeverFillsGoesQuietAfterItAppears() {
        Rig rig = new Rig(t -> t < 2_000 ? ABSENT : file(BEFORE + 2_000, 0L), t -> true);
        PhotoWait.Outcome<String> out = rig.run(APPEAR);
        assertEquals(PhotoWait.Status.WENT_QUIET_UNREADABLE, out.status());
        assertTrue(out.appeared());
        // Quiet counts from the appearance (≈2 s), not from the start.
        assertTrue(out.waitedMs() >= 2_000 + QUIET && out.waitedMs() <= 2_000 + QUIET + 2 * POLL,
                "waited " + out.waitedMs());
        assertEquals(0, rig.reads, "a zero-byte file is never decoded");
    }

    @Test
    void aWholeFileThatNeverDecodesGoesQuiet() {
        Rig rig = new Rig(t -> t < 500 ? ABSENT : file(BEFORE + 500, 100L), t -> false);
        PhotoWait.Outcome<String> out = rig.run(APPEAR);
        assertEquals(PhotoWait.Status.WENT_QUIET_UNREADABLE, out.status());
        assertTrue(rig.reads > 0);
    }

    @Test
    void theHealthyCaseStillReturnsOnTheFirstQuietPoll() {
        // Already whole at the first poll after the shutter: one poll to see it, one to see it
        // unchanged, one decode — the success path every caller relies on.
        Rig rig = new Rig(t -> file(BEFORE + 1, 1_000_000L), t -> true);
        PhotoWait.Outcome<String> out = rig.run(APPEAR);
        assertEquals(PhotoWait.Status.SETTLED, out.status());
        assertEquals(POLL, out.waitedMs());
        assertEquals(1, rig.reads);
    }
}
