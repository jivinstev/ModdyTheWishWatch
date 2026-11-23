package com.moddingfromamod.agent.tools;

import java.util.UUID;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.WishQueue;
import com.moddingfromamod.marshal.Marshal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>An agent holding a wish cannot be handed another one.</b>
 *
 * <p>{@code Marshal.claim()} has always forbidden this and says so in its own comment. The wish path
 * never consulted it: {@code await_wish} blocks on the wish QUEUE and calls {@code assign()}
 * directly. And {@code byWorker} holds one entry per worker, so the second assignment did not merely
 * double-book — it <b>overwrote</b> the first, after which nothing could say the earlier wish was
 * ever that worker's. That is the child's progress bar tracking the wrong build.
 *
 * <p>Measured, world {@code daddus moddy 4}, 2026-08-14: the agent called {@code await_wish} four
 * times while still building the factory (23:20:09, 23:20:12, 23:21:04, 23:21:09); the skyscraper
 * landed on it at 23:22:00 and the factory did not finish until 23:25:26. It was still running
 * factory commands at 23:22:08, after the second wish had been assigned.
 *
 * <h2>Why the refusal is where it is</h2>
 *
 * <p>Above {@code queue.await()} — the only point in the path where <b>nothing has been dequeued
 * yet</b>. Refusing after the await (in {@code assign()}, which looks like the natural home for it)
 * would take a wish off the queue and then drop it on the floor. Here, a refusal costs nothing: the
 * wish waits for a free worker, or for this one the moment it finishes.
 */
class AwaitWishRefusesABusyWorkerTest {

    /** Unique per run so this can never collide with another test's worker in the global marshal. */
    private final String worker = "test-141-" + UUID.randomUUID();
    private UUID open;

    @AfterEach
    void releaseTheWish() {
        // The marshal is a process-wide singleton. Leaving a WORKING run behind would make this
        // test change the outcome of anything that ran after it -- exactly the class of
        // cross-contamination that made the GameTest plot collisions so hard to attribute.
        if (open != null) {
            Marshal.global().finish(open);
        }
        AgentContext.unbind();
    }

    private ToolResult awaitWish() {
        // A queue that is never fed: if the guard fails to fire, the call falls through to
        // queue.await() and this test would block for the full timeout instead of failing. So the
        // timeout is set to the floor (1s) -- a hang becomes a fast, legible failure.
        JsonObject args = new JsonObject();
        args.addProperty("timeoutSeconds", 1);
        return new AwaitWishTool(new WishQueue()).call(args);
    }

    @Test
    void aWorkerStillBuildingIsToldToFinishFirst() {
        open = UUID.randomUUID();
        AgentContext.bind(worker);
        Marshal.global().submit(open, UUID.randomUUID(), "make me a factory");
        Marshal.global().assign(worker, open);

        ToolResult r = awaitWish();

        assertTrue(r.isError(), "await_wish handed a second wish to a worker that is still building "
                + "the first. Live, that overwrote the factory wish with the skyscraper and the "
                + "child's progress bar followed the wrong one");
        // The refusal has to be ACTIONABLE. An agent told only "no" will call await_wish again in a
        // loop; it needs the open wish named and the way out spelled out.
        assertTrue(r.text().contains("finish_wish"),
                "the refusal must name the way out, or the agent just retries: " + r.text());
        // Named via displayName(), which falls back to the child's own words when plan_wish has
        // not run yet -- and a worker refused this early usually has not planned. Asserting on
        // subject() instead is what caught that: it is empty until plan_wish lands, so the refusal
        // read 'You are still working on a wish: ""'.
        assertTrue(r.text().contains("make me a factory"),
                "the refusal must name the wish still open: " + r.text());
    }

    @Test
    void aWorkerThatHasFinishedIsFreeAgain() {
        // THE OTHER HALF, and the one that matters most: this must be a nudge, not a lock. A worker
        // permanently unable to await is a child permanently unable to wish, which is far worse
        // than the bug being fixed.
        UUID done = UUID.randomUUID();
        AgentContext.bind(worker);
        Marshal.global().submit(done, UUID.randomUUID(), "make me a skyscraper");
        Marshal.global().assign(worker, done);
        Marshal.global().finish(done);

        ToolResult r = awaitWish();

        assertTrue(!r.isError(), "a worker whose wish is finished was still refused — that is a "
                + "lockout, not a guard: " + r.text());
    }

    @Test
    void anIdleWorkerIsNotRefused() {
        AgentContext.bind(worker);

        ToolResult r = awaitWish();

        assertTrue(!r.isError(), "a worker holding nothing was refused: " + r.text());
        // With an empty queue and a 1s timeout, the honest answer is "nothing arrived" -- which is
        // also the proof that the guard let the call through to the await rather than short-
        // circuiting it.
        assertTrue(r.text().contains("No wish arrived"),
                "expected the empty-queue answer, got: " + r.text());
    }

    /**
     * <b>A blank worker id can never be recorded as busy — asserted at the layer that decides it.</b>
     *
     * <p>{@code AgentContext.workerId()} returns {@code ""} when nothing is bound, and
     * {@code await_wish} ASSIGNS with that value. If a run could be filed under {@code ""}, every
     * unbound {@code await_wish} would then be refused for the life of the process — a child unable
     * to wish at all, caused by the guard meant to protect him.
     *
     * <p><b>It cannot, and this test says where that is decided:</b> {@code Marshal.assign} rejects a
     * blank id outright. {@code AwaitWishTool} also skips the check for a blank id, which is belt and
     * braces rather than the protection.
     *
     * <p>Written this way after the first version of it turned out to be <b>vacuous</b>: it asserted
     * the tool was not refused, which was true whether or not either guard existed, because its own
     * setup ({@code assign("", …)}) is a no-op. A test whose precondition silently fails to hold
     * passes forever and guards nothing — so this asserts the no-op directly.
     */
    @Test
    void aBlankWorkerIdCanNeverBeRecordedAsBusy() {
        UUID orphan = UUID.randomUUID();
        Marshal.global().submit(orphan, UUID.randomUUID(), "a wish that arrived unbound");

        assertNull(Marshal.global().assign("", orphan),
                "Marshal.assign accepted a blank worker id. Every unbound await_wish would now be "
                        + "refused for the life of the process — nobody could wish for anything");
        assertNull(Marshal.global().runForWorker(""),
                "a run is filed under the blank worker id");

        AgentContext.unbind();
        assertTrue(!awaitWish().isError(), "an unbound caller was refused");
        Marshal.global().finish(orphan);
    }

    @Test
    void theMarshalIsLeftCleanForTheNextTest() {
        AgentContext.bind(worker);
        UUID first = UUID.randomUUID();
        Marshal.global().submit(first, UUID.randomUUID(), "make me a factory");
        Marshal.global().assign(worker, first);
        assertEquals(first, Marshal.global().runForWorker(worker).wishId());
        Marshal.global().finish(first);
        // finish() must actually clear WORKING, or the @AfterEach above is a no-op and this whole
        // class quietly poisons the global marshal for everything that runs after it.
        ToolResult r = awaitWish();
        assertTrue(!r.isError(), "finish() did not release the worker: " + r.text());
    }
}
