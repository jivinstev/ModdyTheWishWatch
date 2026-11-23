package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every branch of the settle wait, driven by a fake clock — so the worst case can be exercised in
 * microseconds instead of the twelve real seconds it describes.
 *
 * <p>The WRONG_THREAD case is the one that could not be tested any other way: proving it by
 * actually calling from the server thread would deadlock the tick, and a deadlocked Gate B is a
 * twenty-minute timeout reported as an unrelated failure. Injecting the thread question is what
 * makes the guard assertable at all.
 */
class CaptureSettleTest {

    /** A clock that only moves when a sleep is taken — so a "twelve second" wait is instant. */
    private static final class Fake {
        final AtomicLong now = new AtomicLong(1_000);
        int sleeps;

        CaptureSettle.Sleeper sleeper() {
            return ms -> {
                sleeps++;
                now.addAndGet(ms);
                return true;
            };
        }
    }

    @Test
    @DisplayName("nobody holding him costs nothing at all")
    void freeIsInstant() {
        Fake f = new Fake();
        assertEquals(CaptureSettle.Verdict.ALREADY_FREE,
                CaptureSettle.await(() -> false, () -> false, f.now::get, f.sleeper(), 12_000));
        assertEquals(0, f.sleeps, "the common case must not sleep even once — this runs on every "
                + "look_around, and a poll that always costs one tick of latency is a tax on the "
                + "99% to pay for the 1%");
    }

    @Test
    @DisplayName("held then released: it returns as soon as he is free, not when the budget ends")
    void itReturnsPromptly() {
        Fake f = new Fake();
        int[] looks = {0};
        var v = CaptureSettle.await(() -> ++looks[0] <= 3, () -> false, f.now::get, f.sleeper(),
                12_000);
        assertEquals(CaptureSettle.Verdict.SETTLED, v);
        assertTrue(f.now.get() - 1_000 < 500,
                "it waited " + (f.now.get() - 1_000) + " ms for something that cleared in three "
                        + "polls; a wait that runs to its deadline anyway is a fixed cost wearing "
                        + "a budget's clothes");
    }

    @Test
    @DisplayName("still held at the deadline is TIMED_OUT — a fault, and reported as one")
    void itGivesUp() {
        Fake f = new Fake();
        var v = CaptureSettle.await(() -> true, () -> false, f.now::get, f.sleeper(), 12_000);
        assertEquals(CaptureSettle.Verdict.TIMED_OUT, v);
        assertFalse(v.settled());
        assertTrue(f.now.get() - 1_000 >= 12_000, "it must actually wait the whole budget first");
    }

    @Test
    @DisplayName("🔴 called from the server thread it REFUSES rather than deadlocking")
    void theServerThreadIsRefused() {
        Fake f = new Fake();
        var v = CaptureSettle.await(() -> true, () -> true, f.now::get, f.sleeper(), 12_000);
        assertEquals(CaptureSettle.Verdict.WRONG_THREAD, v);
        assertFalse(v.settled());
        assertEquals(0, f.sleeps, "it must not sleep even once: the release it is waiting for can "
                + "only happen on the thread it would be blocking");
    }

    @Test
    @DisplayName("...but merely ASKING from the server thread is free and correct")
    void theGuardFiresOnWaitingAndNotOnAsking() {
        // The ordering inside await() is load-bearing. A guard placed first would turn the ordinary
        // "nothing is held" case into an error for any caller that happens to be on the server
        // thread -- refusing to answer a question that needed no waiting at all.
        Fake f = new Fake();
        assertEquals(CaptureSettle.Verdict.ALREADY_FREE,
                CaptureSettle.await(() -> false, () -> true, f.now::get, f.sleeper(), 12_000));
    }

    @Test
    @DisplayName("interrupted is UNKNOWN, never settled")
    void interruptionIsNotSuccess() {
        Fake f = new Fake();
        var v = CaptureSettle.await(() -> true, () -> false, f.now::get, ms -> false, 12_000);
        assertEquals(CaptureSettle.Verdict.INTERRUPTED, v);
        assertFalse(v.settled(), "treating an interrupted wait as a settled body is how a shutdown "
                + "in the middle of a burst becomes a wrong answer rather than a missing one");
    }

    @Test
    @DisplayName("a settled wait says nothing — silence is the normal outcome")
    void noNoticeWhenItWorked() {
        assertEquals("", CaptureSettle.notice(CaptureSettle.Verdict.ALREADY_FREE, "1 2 3"));
        assertEquals("", CaptureSettle.notice(CaptureSettle.Verdict.SETTLED, "1 2 3"));
        assertEquals("", CaptureSettle.notice(null, "1 2 3"));
    }

    @Test
    @DisplayName("a fault says so, names the budget, and warns off building on it")
    void theNoticeCannotBeMistakenForAnAnswer() {
        String n = CaptureSettle.notice(CaptureSettle.Verdict.TIMED_OUT, "180 70 -40");
        assertTrue(n.contains("not where he lives"),
                "the whole point is to state the assumption the report would otherwise leave "
                        + "unsaid — that the position is his own");
        assertTrue(n.contains("180 70 -40"), "and to name the spot that IS his");
        assertTrue(n.contains(String.valueOf(CaptureTimings.settleWaitMs())),
                "how long it waited is what distinguishes a fault from a busy moment");
        assertTrue(n.contains("Do not build here"),
                "an agent that reads this and builds anyway has been told; one that reads a bare "
                        + "position has not");
        assertTrue(n.endsWith("\n\n"), "it prefixes the report, so it must not run into it");
    }

    @Test
    @DisplayName("...and still says something useful when even home is unknown")
    void theNoticeSurvivesAMissingOrigin() {
        String n = CaptureSettle.notice(CaptureSettle.Verdict.WRONG_THREAD, null);
        assertTrue(n.contains("not where he lives"));
        assertFalse(n.contains("his own spot"), "no half-sentence trailing off into nothing");
    }

    @Test
    @DisplayName("the real budget is the derived one, not a number typed at the call site")
    void theBudgetComesFromCaptureTimings() {
        Fake f = new Fake();
        CaptureSettle.await(() -> true, () -> false, f.now::get, f.sleeper(),
                CaptureTimings.settleWaitMs());
        assertTrue(f.now.get() - 1_000 >= CaptureTimings.MAX_SESSION_MS,
                "whatever the numbers become, the wait must still outlast the hold");
    }
}
