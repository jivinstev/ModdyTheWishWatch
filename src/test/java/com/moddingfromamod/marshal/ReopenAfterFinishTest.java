package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * <b>A wish id can outlive its run</b> — CS-D7, the defect the CS-D2 ledger row recorded and left.
 *
 * <p>Two of the three re-wish paths keep the wish and throw away only the attempt:
 * {@code /moddy redirect} stops the run and re-opens the same wish id carrying what the child
 * actually meant, and {@code /moddy no} does the same to a wish the agent already finished. Neither
 * mints a new wish, on purpose — the id is what the bar, the journal and {@code /moddy stop} all
 * name it by.
 *
 * <p>So {@link Marshal#submit} sees a wish id it already has a run for, and until CS-D7 it handed
 * that run straight back. The run was <b>finished</b>, which made every consequence quiet:
 * {@link Marshal#assign} refuses a finished run and returns null, {@link Marshal#liveFor} excludes
 * it, and nothing downstream reads either — so the re-aimed wish was narrated, built and finished
 * by a worker the marshal had no record of, and <b>{@code /moddy stop} on it afterwards found
 * nothing</b>. A child who re-aims a wish had lost the one control the watch promises him.
 *
 * <p>These are the marshal-side claims. {@code WishReopenGameTest} drives the same thing through
 * the real {@code WishCancel}, the real {@code await_wish} and the real command, which is the only
 * place the wiring can be seen.
 */
class ReopenAfterFinishTest {

    private static final UUID CHILD = UUID.nameUUIDFromBytes("steve".getBytes());

    private static UUID wish(String t) {
        return UUID.nameUUIDFromBytes(t.getBytes());
    }

    private static WishRun submit(Marshal m, String t) {
        return m.submit(wish(t), CHILD, t);
    }

    // ---- the two paths that re-open a wish id -------------------------------------------------

    /** {@code /moddy redirect}: the run was STOPPED, and the wish is going back to an agent. */
    @Test
    void reSubmittingAStoppedWishGivesItALiveRunAgain() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun stopped = submit(m, "make it bigger");
        m.claim("w1");
        assertTrue(m.cancel(stopped.wishId(), CancelMode.REDIRECT, "", "the stone tower").stopped());

        WishRun fresh = m.submit(stopped.wishId(), CHILD, "make it bigger — aimed at: the stone tower");

        assertNotNull(fresh, "re-opening a stopped wish handed back nothing at all");
        assertNotSame(stopped, fresh,
                "submit handed back the STOPPED run, so assign() will refuse it and the re-aimed "
                        + "wish is built by a worker the marshal has no record of");
        assertEquals(RunState.GETTING_READY, fresh.state(),
                "the re-opened run is not live, so liveFor and /moddy stop cannot see it");
        assertEquals(RunState.STOPPED, stopped.state(),
                "the attempt the child stopped must stay stopped — it is what the wind-down "
                        + "refusal is built from");
    }

    /** {@code /moddy no}: the run was DONE, and the same wish goes back on the agent's list. */
    @Test
    void reSubmittingARejectedWishGivesItALiveRunAgain() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun done = submit(m, "a rainbow sword");
        m.claim("w1");
        assertTrue(m.finish(done.wishId()));

        WishRun fresh = m.submit(done.wishId(), CHILD, "a rainbow sword");

        assertNotSame(done, fresh, "the same defect on the rejection path");
        assertTrue(fresh.state().isLive(), "a rejected wish going back to the agent is live again");
        assertEquals(RunState.DONE, done.state(), "the finished attempt stays finished");
    }

    /** …and the whole point of it being live: the worker can take it, and the child can stop it. */
    @Test
    void aReOpenedWishIsAssignableAndStoppable() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun first = submit(m, "make it bigger");
        m.claim("w1");
        m.cancel(first.wishId(), CancelMode.REDIRECT, "", "the tower");

        WishRun fresh = m.submit(first.wishId(), CHILD, "make it bigger — aimed at: the tower");
        assertNotNull(m.assign("w1", first.wishId()),
                "await_wish could not record who took the re-aimed wish");
        assertSame(fresh, m.runForWorker("w1"));
        assertEquals(1, m.liveFor(CHILD).size(),
                "/moddy stop looks the child's wishes up through liveFor, and it cannot see this one");
        assertSame(fresh, m.liveFor(CHILD).get(0));

        // And stopping it really stops THIS attempt, which is what the child pressed.
        assertTrue(m.cancel(first.wishId(), CancelMode.STOP, "", "").stopped(),
                "the child could not stop the wish he had just re-aimed");
        assertEquals(RunState.STOPPED, fresh.state());
        assertTrue(m.liveFor(CHILD).isEmpty());
    }

    // ---- the contract that must NOT change ----------------------------------------------------

    /**
     * A LIVE run is still handed back unchanged, and this is load-bearing rather than tidy.
     *
     * <p>{@code AwaitWishTool} calls {@code submit} before {@code assign} on <i>every</i> wake
     * (its own comment: "a wish can reach an agent by paths that never went through
     * WishService"), so a submit that minted a second run for a wish already in flight would
     * detach the in-flight one from its worker and its anchor.
     */
    @Test
    void submitIsStillIdempotentWhileTheRunIsLive() {
        Marshal m = Marshal.forTest(new FakeClock(0), 2);
        WishRun run = submit(m, "an ice dragon");
        assertSame(run, m.submit(run.wishId(), CHILD, "an ice dragon"), "not yet taken");
        m.claim("w1");
        assertSame(run, m.submit(run.wishId(), CHILD, "an ice dragon"), "being worked on");
        assertEquals("w1", m.runForWorker("w1").workerId());
        assertEquals(1, m.gettingReady().size() + m.working().size(),
                "one wish, one run");
    }

    /**
     * One wish, one slot — before and after a re-open, with nothing released twice.
     *
     * <p>The stopped attempt gave its slot back within a tick (that is the whole visible-stop
     * contract), so the re-opened run has to take exactly one and no more, and finishing it has to
     * give back exactly one.
     */
    @Test
    void aReOpenedWishTakesExactlyOneSlot() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun first = submit(m, "make it bigger");
        m.claim("w1");
        assertEquals(1, m.activeCount());
        m.cancel(first.wishId(), CancelMode.REDIRECT, "", "the tower");
        assertEquals(0, m.activeCount(), "the slot came back within a tick, not on the wind-down");
        assertTrue(m.hasFreeSlot());

        m.submit(first.wishId(), CHILD, "make it bigger — aimed at: the tower");
        assertEquals(1, m.gettingReady().size(),
                "the re-opened wish is waiting exactly once — twice would let one wish be handed "
                        + "to two workers");
        assertEquals(0, m.activeCount(), "waiting is not working");

        assertNotNull(m.claim("w2"), "a free worker could not pick the re-opened wish up");
        assertEquals(1, m.activeCount(), "one wish must never hold two slots");
        assertFalse(m.hasFreeSlot());
        assertNull(m.claim("w3"), "and the last slot is genuinely taken");

        assertTrue(m.finish(first.wishId()));
        assertEquals(0, m.activeCount(), "finishing gave back exactly the one slot it held");
        assertTrue(m.hasFreeSlot());
    }

    /**
     * <b>The wind-down refusal still describes the attempt the child stopped.</b>
     *
     * <p>The worker holding the stopped run is mid-inference and finds out on its next tool call,
     * and the sentence it is given is built from that run — including "he wanted it aimed at
     * something else", which is the whole reason a redirect is not a bare stop. Re-opening the wish
     * id must not be able to substitute the fresh run there: the refusal would then cite a run
     * nobody cancelled and drop the one line that says what happened.
     */
    @Test
    void theWindDownStillNamesTheStoppedAttemptAfterTheWishIsReOpened() {
        Marshal m = Marshal.forTest(new FakeClock(0), 1);
        WishRun stopped = submit(m, "make it bigger");
        m.claim("w1");
        m.cancel(stopped.wishId(), CancelMode.REDIRECT, "", "the stone tower");

        m.submit(stopped.wishId(), CHILD, "make it bigger — aimed at: the stone tower");

        WishRun winding = m.windingDown("w1");
        assertSame(stopped, winding,
                "the worker was handed the RE-OPENED run to wind down from, so its refusal cites a "
                        + "run nobody stopped");
        String refusal = CancelGate.refusalFor(m, "w1", "build_structure");
        assertNotNull(refusal, "the stopped worker was not refused at all");
        assertTrue(refusal.contains("aimed at something else"),
                "the refusal lost the sentence that says a redirect is not an abandonment: "
                        + refusal);
        assertTrue(refusal.contains("the stone tower"), refusal);
    }
}
