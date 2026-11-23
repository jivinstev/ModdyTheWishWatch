package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * §1.1 scheduling and §1.2 out-of-order return, at Gate A.
 *
 * <p>Every one of these runs in milliseconds against the scripted pool, which is the point: the same
 * questions asked with real agents cost 200–400 seconds and real tokens per wish, so they would be
 * asked once and then never again.
 *
 * <p>The concurrency families are {@link RepeatedTest}s. <b>A concurrency test that passes once
 * proves nothing</b>, and flakiness here is the finding rather than a reason to retry — the pool is
 * fully deterministic, so a repeat that differs is an instrument bug.
 */
class MarshalSchedulingTest {

    private static final UUID CHILD = UUID.nameUUIDFromBytes("steve".getBytes());

    private static UUID wish(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes());
    }

    private static WishRun submit(Marshal m, String text) {
        return m.submit(wish(text), CHILD, text);
    }

    // ================= admission =================

    @Test
    void aFreeSlotMeansAWorkerGetsTheWishStraightAway() {
        Marshal m = Marshal.forTest(new FakeClock(1000), 1);
        WishRun run = submit(m, "a dragon");
        assertEquals(RunState.GETTING_READY, run.state());

        WishRun claimed = m.claim("w1");
        assertSame(run, claimed);
        assertEquals(RunState.WORKING, run.state());
        assertEquals("w1", run.workerId());
        assertEquals(1000L, run.assignedMs());
    }

    @Test
    void withNoFreeSlotTheWishIsStillAcknowledgedItJustHasNoWorkerYet() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        submit(m, "a dragon");
        WishRun second = submit(m, "a castle");
        m.claim("w1");

        assertNull(m.claim("w2"), "the second slot does not exist at wishslots=1");
        // The thing that matters for the child: it is a live run with a home for its beam and bar,
        // not something parked in a queue with nothing to look at.
        assertEquals(RunState.GETTING_READY, second.state());
        assertTrue(m.live().contains(second));
        assertEquals(1, m.gettingReady().size());
    }

    @Test
    void aWorkerAlreadyHoldingAWishIsNotHandedASecond() {
        Marshal m = Marshal.forTest(new FakeClock(0), 3);
        submit(m, "a dragon");
        submit(m, "a castle");
        assertNotNull(m.claim("w1"));
        assertNull(m.claim("w1"), "one agent interleaving two wishes has no yield point mid-inference");
        assertNotNull(m.claim("w2"));
    }

    // ================= releasing the slot =================

    @Test
    void theSlotComesBackOnFinish() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        submit(m, "a dragon");
        submit(m, "a castle");
        WishRun first = m.claim("w1");

        assertNull(m.claim("w2"));
        assertTrue(m.finish(first.wishId()));
        assertEquals(0, m.activeCount());
        assertNotNull(m.claim("w2"), "finishing frees the slot");
    }

    @Test
    void theSlotComesBackOnCancel() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        submit(m, "a dragon");
        submit(m, "a castle");
        WishRun first = m.claim("w1");

        m.cancel(first.wishId(), CancelMode.STOP, "", null);
        assertEquals(0, m.activeCount());
        assertNotNull(m.claim("w2"), "a stopped wish must not hold its slot until the worker notices");
    }

    @Test
    void theSlotComesBackWhenTheWorkerDies() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun run = submit(m, "a dragon");
        m.claim("w1");

        WishRun back = m.workerDied("w1");
        assertSame(run, back);
        assertEquals(RunState.GETTING_READY, run.state(), "re-dispatched, not lost");
        assertNull(run.workerId());
        assertNotNull(m.claim("w2"), "and another worker can pick it straight up");
    }

    @Test
    void aWishStoppedWhileItWaitedNeverBurnsASlot() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun doomed = submit(m, "a dragon");
        submit(m, "a castle");
        m.cancel(doomed.wishId(), CancelMode.STOP, "", null);

        WishRun claimed = m.claim("w1");
        assertNotNull(claimed);
        assertEquals("a castle", claimed.text(), "the stopped one is skipped, not handed out");
    }

    // ================= the cap, and starvation =================

    @RepeatedTest(20)
    void aBurstOfTwentyNeverExceedsThePoolCap() {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, 3);
        ScriptedWorkerPool pool = new ScriptedWorkerPool(m, clock, 4).defaultDuration(50).step(10);
        for (int i = 0; i < 20; i++) {
            String text = "wish " + i;
            submit(m, text);
            pool.takes(text, 30 + (i % 7) * 20);   // deliberately uneven, so completions cross
        }

        int worst = 0;
        for (int beat = 0; beat < 400; beat++) {
            clock.advance(10);
            pool.settle();
            worst = Math.max(worst, m.activeCount());
        }
        assertTrue(worst <= 3, "never more than the slot count in flight; saw " + worst);
        assertEquals(20, pool.completionOrder().size(), "and all twenty are granted");
    }

    @RepeatedTest(20)
    void theOldestWaitingWishIsNeverOvertaken() {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, 3);
        ScriptedWorkerPool pool = new ScriptedWorkerPool(m, clock, 3).defaultDuration(40).step(10);

        List<String> arrival = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String text = "wish " + i;
            arrival.add(text);
            submit(m, text);
            // A long one early is exactly the shape that starves a naive scheduler.
            pool.takes(text, i == 0 ? 500 : 40 + (i % 5) * 30);
        }
        pool.runToQuiet(5000);

        List<String> handedOut = new ArrayList<>();
        for (String a : pool.assignmentOrder()) {
            handedOut.add(a.substring(a.indexOf(':') + 1));
        }
        assertEquals(arrival, handedOut,
                "work is handed out strictly oldest-first, so nothing can be overtaken indefinitely");
    }

    // ================= out-of-order return (§1.2) =================

    @RepeatedTest(20)
    void wishesComeBackInTheOrderTheyFinishNotTheOrderTheyArrived() {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, 3);
        ScriptedWorkerPool pool = new ScriptedWorkerPool(m, clock, 3).step(5);
        submit(m, "A");
        submit(m, "B");
        submit(m, "C");
        pool.takes("A", 300).takes("B", 200).takes("C", 100);
        pool.runToQuiet(2000);

        assertEquals(List.of("C", "B", "A"), pool.completionOrder(),
                "C before B before A — the case that breaks anything keyed on arrival order");
    }

    @Test
    void oneWishCompletingLeavesAnothersAnchorAlone() {
        Marshal m = Marshal.forTest(new FakeClock(0), 3);
        WishRun a = submit(m, "a dragon");
        WishRun b = submit(m, "a castle");
        a.lockAnchor(new Spot("minecraft:overworld", 10, 64, 10));
        b.lockAnchor(new Spot("minecraft:overworld", 900, 70, -400));
        m.claim("w1");
        m.claim("w2");

        m.finish(b.wishId());

        assertEquals(new Spot("minecraft:overworld", 10, 64, 10), a.anchor(),
                "B finishing must not move A's sparkles — this is the per-player FOCUS global");
    }

    @Test
    void aSecondWishCannotClobberTheFirstsLockedSpot() {
        Marshal m = Marshal.forTest(new FakeClock(0), 3);
        WishRun a = submit(m, "a dragon");
        a.lockAnchor(new Spot("minecraft:overworld", 10, 64, 10));
        a.defaultAnchor(new Spot("minecraft:overworld", 999, 999, 999));

        assertEquals(new Spot("minecraft:overworld", 10, 64, 10), a.anchor(),
                "a wish-time default never overwrites a locked conjure_at spot");
    }

    @Test
    void lastMeansTheNewestCompletedNotTheNewestStarted() {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, 3);
        WishRun a = submit(m, "A");
        // B is started LATER and finishes EARLIER, which is the only arrangement that tells the two
        // definitions apart. Submitting both at the same instant made this pass against a
        // newest-STARTED implementation too — an instrument bug, caught by mutating the rule and
        // watching the test stay green.
        clock.set(50);
        WishRun b = submit(m, "B");
        m.claim("w1");
        m.claim("w2");

        clock.set(100);
        m.finish(b.wishId());
        clock.set(200);
        m.finish(a.wishId());

        // A started first and finished last. "Make it bigger" means the thing he just watched appear.
        assertSame(a, m.lastCompleted(CHILD));
        assertTrue(b.createdMs() > a.createdMs(), "…and B really is the newest STARTED");
    }

    @Test
    void aStoppedWishIsNeverTheLastOne() {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, 3);
        WishRun a = submit(m, "A");
        clock.set(50);
        WishRun b = submit(m, "B");
        m.claim("w1");
        m.claim("w2");
        clock.set(100);
        m.finish(a.wishId());
        clock.set(200);
        m.cancel(b.wishId(), CancelMode.STOP, "", null);

        assertSame(a, m.lastCompleted(CHILD), "he cannot mean the thing he just stopped");
    }

    // ================= what the next refinement is ABOUT =================

    /**
     * <b>A thing with no undo path is still the thing he just made.</b>
     *
     * <p>{@code made()} lists only what the mod can reverse itself, and an item records nothing
     * there because there is no {@code MadeKind.ITEM} and no {@code remove_item}. That is
     * correct — a kind recorded but never reversed makes the {@code [undo]} button lie — and it
     * silently disabled the only routing path a pronoun-only refinement has.
     *
     * <p>What it cost is on the record: the items class's confirming matrix reports
     * {@code refinements_routed_under_the_arm 0.0} with {@code routing_seen {anyone: 16}} in BOTH
     * arms, so arm A and arm B were the same experiment and the class's A/B comparison is not one.
     */
    @Test
    void theLastArtifactCanBeSomethingTheModCannotUndo() {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, 3);
        WishRun run = submit(m, "i want a zonkbolt, a purple wand that shoots lightning");
        m.claim("w1");
        run.finishedArtifact("zonkbolt");          // what finish_wish said the wish was about
        clock.set(100);
        m.finish(run.wishId());

        assertTrue(run.made().isEmpty(), "an item is deliberately not recorded as undoable");
        assertEquals("zonkbolt", m.lastArtifactFor(CHILD),
                "\"make it hurt way more\" names nothing, so this is the only thing that can "
                        + "route it — and without it every item rung routes `anyone`");
    }

    /** An undoable thing still wins: {@code made()} is the stronger claim and stays first. */
    @Test
    void somethingTheModCanUndoIsStillPreferred() {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, 3);
        WishRun run = submit(m, "a castle");
        m.claim("w1");
        run.made(MadeKind.BUILD, "glimmerhold");
        run.finishedArtifact("something_else");
        clock.set(100);
        m.finish(run.wishId());

        assertEquals("glimmerhold", m.lastArtifactFor(CHILD));
    }

    /** And a wish that finished having been about nothing still answers nothing. */
    @Test
    void aWishThatWasAboutNothingIsNotAnArtifact() {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, 3);
        WishRun run = submit(m, "hello");
        m.claim("w1");
        clock.set(100);
        m.finish(run.wishId());

        assertEquals("", m.lastArtifactFor(CHILD));
    }

    // ================= leases =================

    @Test
    void leasesAreAllOrNothing() {
        Marshal m = Marshal.forTest(new FakeClock(0), 3);
        WishRun a = submit(m, "make spikor and dinko bigger");
        WishRun b = submit(m, "make dinko red");
        assertTrue(m.acquireLeases(a.wishId(), List.of("dinko", "spikor")));
        assertFalse(m.acquireLeases(b.wishId(), List.of("dinko")));
        assertTrue(b.leases().isEmpty(), "a refused acquisition takes none of them");
    }

    @RepeatedTest(20)
    void twoWishesWantingTheSamePairInOppositeOrderDoNotWedge() {
        Marshal m = Marshal.forTest(new FakeClock(0), 3);
        WishRun a = submit(m, "A");
        WishRun b = submit(m, "B");

        boolean gotA = m.acquireLeases(a.wishId(), List.of("spikor", "dinko"));
        boolean gotB = m.acquireLeases(b.wishId(), List.of("dinko", "spikor"));

        assertTrue(gotA ^ gotB, "exactly one wins; neither holds half of what the other needs");
        assertTrue(gotA, "and it is the one that asked first — id order is a total order, not a race");
    }

    @Test
    void finishingReturnsTheLeases() {
        Marshal m = Marshal.forTest(new FakeClock(0), 3);
        WishRun a = submit(m, "A");
        WishRun b = submit(m, "B");
        m.acquireLeases(a.wishId(), List.of("spikor"));
        m.finish(a.wishId());

        assertNull(m.leaseHolder("spikor"));
        assertTrue(m.acquireLeases(b.wishId(), List.of("spikor")));
    }

    @Test
    void cancellingReturnsBothLeasesOfATwoArtifactWish() {
        Marshal m = Marshal.forTest(new FakeClock(0), 3);
        WishRun a = submit(m, "make spikor and dinko bigger");
        m.acquireLeases(a.wishId(), List.of("spikor", "dinko"));
        m.cancel(a.wishId(), CancelMode.STOP, "", null);

        assertNull(m.leaseHolder("spikor"));
        assertNull(m.leaseHolder("dinko"));
    }

    @Test
    void aDyingWorkerReturnsItsLeases() {
        Marshal m = Marshal.forTest(new FakeClock(0), 3);
        WishRun a = submit(m, "A");
        m.claim("w1");
        m.acquireLeases(a.wishId(), List.of("spikor"));

        m.workerDied("w1");

        assertNull(m.leaseHolder("spikor"),
                "otherwise a dead agent locks Spikor out of every future wish for the rest of the session");
    }

    // ================= the A/B seam, proven both ways =================

    @RepeatedTest(20)
    void theSameScriptIsSerialAtOneSlotAndOverlappedAtThree() {
        assertEquals(1, maxConcurrent(1), "at wishslots=1 nothing ever overlaps — today's behaviour");
        assertEquals(3, maxConcurrent(3), "at wishslots=3 three wishes are genuinely in flight at once");
    }

    private static int maxConcurrent(int slots) {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, slots);
        ScriptedWorkerPool pool = new ScriptedWorkerPool(m, clock, 4).defaultDuration(100).step(10);
        for (int i = 0; i < 6; i++) {
            submit(m, "wish " + i);
        }
        int worst = 0;
        for (int beat = 0; beat < 200; beat++) {
            clock.advance(10);
            pool.settle();
            worst = Math.max(worst, m.activeCount());
        }
        return worst;
    }

    // ================= housekeeping =================

    @Test
    void finishedRunsAreForgottenEventuallyButNotImmediately() {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, 1);
        WishRun a = submit(m, "A");
        m.claim("w1");
        clock.set(1000);
        m.finish(a.wishId());

        assertNotNull(m.run(a.wishId()), "@last and 'was that undoable?' both need it after the work stops");
        assertEquals(1, m.forgetFinishedBefore(2000));
        assertNull(m.run(a.wishId()));
    }
}
