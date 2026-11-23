package com.moddingfromamod.agent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.agent.tools.AwaitWishTool;
import com.moddingfromamod.marshal.FakeClock;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WorkerRoster;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A stopped worker's last {@code await_wish} can never take a wish</b>.
 *
 * <p>The live case, 2026-10-13: the pool stopped idle extra {@code wish-granter-1}
 * at 22:27:24.476, and at 22:27:24.84 an {@code await_wish} carrying the name
 * {@code wish-granter-1} arrived on a brand-new MCP session ({@code a535b063}). The queue's retire
 * ({@link WishQueueRetireTest}) is keyed by the OLD session, so it never reached this one. The
 * pool booted a fresh {@code wish-granter-1} at 22:28:03 (its own session), and at 22:28:30 wish
 * 05de5b59 went to the ghost — "claimed by wish-granter-1", returned to a process that no longer
 * existed, nothing for 905 s — while the real granter-1's await came back "no wish arrived". The
 * same thing took a 👎 re-wish at 22:41:19 (session {@code 88728ce0}). Each of these tests failed
 * before the fix: the ghost was handed the wish.
 */
class StaleIncarnationTest {

    private static Wish wish(String text) {
        return new Wish(UUID.randomUUID(), text, WishSource.TYPED, WishStatus.PENDING, 0L,
                UUID.randomUUID(), "kid", "minecraft:overworld", 0, 0, 0, "", "", 0L);
    }

    /** One await, asking the roster (as AwaitWishTool does) whether it is still the slot's live session. */
    private static Thread park(WishQueue q, WorkerRoster roster, String name, String session, long ms,
                               AtomicReference<Wish> got) {
        Thread t = new Thread(() -> {
            try {
                got.set(q.await(ms, session, () -> roster.mayTake(name, session)).wish());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "park-" + session);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void waitFor(WishQueue q, int n) throws InterruptedException {
        long until = System.currentTimeMillis() + 3000;
        while (q.waitingWorkers() != n && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        assertEquals(n, q.waitingWorkers(), "parking did not settle");
    }

    @Test
    @DisplayName("roster: only the slot's live incarnation on its current session may take a wish")
    void mayTakeIsTheLiveIncarnationOnly() {
        WorkerRoster roster = new WorkerRoster();
        assertTrue(roster.mayTake("", "s"), "no slot name (a gate, a direct call): not ours to judge");
        assertTrue(roster.mayTake(null, "s"));
        assertTrue(roster.mayTake("wish-granter-9", "s"), "a name this pool never launched: allowed");

        roster.booted("wish-granter-1", 1_000L);
        assertFalse(roster.mayTake("wish-granter-1", "s-old"),
                "booted but not yet heard from: no session is its session yet");
        roster.heard("wish-granter-1", "s-old", 2_000L);
        assertTrue(roster.mayTake("wish-granter-1", "s-old"));

        roster.forget("wish-granter-1");   // the pool stops it
        assertFalse(roster.heard("wish-granter-1", "s-ghost", 3_000L), "a forgotten slot is not heard");
        assertFalse(roster.mayTake("wish-granter-1", "s-ghost"), "the stopped worker's fresh session");
        assertFalse(roster.mayTake("wish-granter-1", "s-old"), "…and its old one");

        roster.booted("wish-granter-1", 4_000L);   // a fresh worker under the same name
        roster.heard("wish-granter-1", "s-new", 5_000L);
        assertTrue(roster.mayTake("wish-granter-1", "s-new"));
        assertFalse(roster.mayTake("wish-granter-1", "s-ghost"),
                "the ghost does not become live because a new worker took its name");

        roster.clear();   // a whole-pool reap
        assertFalse(roster.mayTake("wish-granter-1", "s-new"),
                "after a reap the name is still one this pool launched, and nobody holds it");
    }

    @Test
    @DisplayName("a ghost that parked before the new worker booted hands the wish to the live one")
    void theGhostParkedFirstAndStillGetsNothing() throws Exception {
        WorkerRoster roster = new WorkerRoster();
        WishQueue q = new WishQueue();
        roster.booted("wish-granter-1", 1_000L);
        roster.heard("wish-granter-1", "s-old", 1_500L);
        roster.forget("wish-granter-1");

        AtomicReference<Wish> ghost = new AtomicReference<>();
        AtomicReference<Wish> live = new AtomicReference<>();
        // The ghost parks FIRST — the 22:27:24 await — so it is the thread a FIFO wake would pick.
        Thread g = park(q, roster, "wish-granter-1", "s-ghost", 4000, ghost);
        waitFor(q, 1);
        // The pool boots a fresh wish-granter-1, which parks on its own session (22:28:03-10).
        roster.booted("wish-granter-1", 2_000L);
        roster.heard("wish-granter-1", "s-new", 2_100L);
        Thread l = park(q, roster, "wish-granter-1", "s-new", 4000, live);
        waitFor(q, 2);

        Wish w = wish("I need SUPER strong armor");
        q.offer(w);
        l.join(5000);
        assertSame(w, live.get(), "the live wish-granter-1 must be handed the wish");
        g.join(5000);
        assertNull(ghost.get(), "the stopped incarnation must never take it (05de5b59, #1548)");
        assertEquals(0, q.size(), "nothing left behind");
    }

    @Test
    @DisplayName("with no live worker, the ghost leaves the wish waiting for the next one")
    void aWishOfferedOnlyToAGhostWaits() throws Exception {
        WorkerRoster roster = new WorkerRoster();
        WishQueue q = new WishQueue();
        roster.booted("wish-granter-1", 1_000L);
        roster.heard("wish-granter-1", "s-old", 1_500L);
        roster.forget("wish-granter-1");
        AtomicReference<Wish> ghost = new AtomicReference<>();
        Thread g = park(q, roster, "wish-granter-1", "s-ghost", 2500, ghost);
        waitFor(q, 1);
        q.offer(wish("make my dino stomp enemies he walks by"));
        g.join(4000);
        assertNull(ghost.get());
        assertEquals(1, q.size(), "the wish is still there for the pool's next worker");
    }

    @Test
    @DisplayName("a worker that re-initialised its MCP session mid-wish is still busy")
    void aReinitialisedWorkerStillHoldsItsWish() {
        FakeClock clock = new FakeClock(1_000_000L);
        Marshal marshal = Marshal.forTest(clock, 3);
        WorkerRoster roster = new WorkerRoster();
        roster.booted("wish-granter-1", clock.nowMs());
        roster.heard("wish-granter-1", "s-first", clock.nowMs());
        UUID first = UUID.randomUUID();
        marshal.submit(first, UUID.randomUUID(), "a castle");
        marshal.assign("s-first", first);

        // The session TTL passes and the CLI re-initialises: same slot, new session id.
        roster.heard("wish-granter-1", "s-second", clock.nowMs() + 1);
        assertNull(AwaitWishTool.heldBy(marshal, "s-second", List.of()),
                "asked of the current session alone the worker looks free — the second-claim hole");
        var held = AwaitWishTool.heldBy(marshal, "s-second", roster.sessionsOf("wish-granter-1"));
        assertTrue(held != null && held.wishId().equals(first),
                "asked of every session its slot was heard on, it is still holding the castle");

        marshal.finish(first);
        assertNull(AwaitWishTool.heldBy(marshal, "s-second", roster.sessionsOf("wish-granter-1")),
                "once it finishes, it is free again");
    }
}
