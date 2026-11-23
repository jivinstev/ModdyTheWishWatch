package com.moddingfromamod.agent;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
 * <b>A worker the pool stops while it is parked can never take a wish</b>.
 *
 * <p>{@code claude stop} ends the CLI, not the MCP handler thread blocked in its {@code await_wish}.
 * Without the retire, that thread would take the next wish and hand it to a process that no longer
 * exists — a wish the child waits on forever, with every count saying a worker has it.
 */
class WishQueueRetireTest {

    private static Wish wish(String text) {
        return new Wish(UUID.randomUUID(), text, WishSource.TYPED, WishStatus.PENDING, 0L,
                UUID.randomUUID(), "kid", "minecraft:overworld", 0, 0, 0, "", "", 0L);
    }

    private static Thread park(WishQueue q, String worker, long ms, AtomicReference<Wish> got) {
        Thread t = new Thread(() -> {
            try {
                got.set(q.await(ms, worker).wish());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "park-" + worker);
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
    @DisplayName("a retired worker's blocked await hands the wish to the live one, and returns empty")
    void theGhostNeverTakesTheWish() throws Exception {
        WishQueue q = new WishQueue();
        AtomicReference<Wish> ghost = new AtomicReference<>();
        AtomicReference<Wish> live = new AtomicReference<>();
        // The ghost parks FIRST, so it is the thread a FIFO wake-up would pick.
        Thread g = park(q, "mcp-ghost", 4000, ghost);
        waitFor(q, 1);
        Thread l = park(q, "mcp-live", 4000, live);
        waitFor(q, 2);

        assertTrue(q.retireIfParked("mcp-ghost"));
        assertEquals(1, q.waitingWorkers(), "a retired worker is no longer counted as listening");
        assertTrue(q.isRetired("mcp-ghost"));

        Wish w = wish("a dragon");
        q.offer(w);
        l.join(4000);
        assertSame(w, live.get(), "the live worker must be handed the wish");
        g.join(5000);
        assertNull(ghost.get(), "the stopped worker's await must never take a wish");
        assertFalse(q.isRetired("mcp-ghost"), "the entry goes when the ghost's await returns");
        assertEquals(0, q.size(), "nothing left behind");
    }

    @Test
    @DisplayName("with no live worker, a retired await leaves the wish waiting rather than losing it")
    void aWishOfferedToOnlyAGhostWaits() throws Exception {
        WishQueue q = new WishQueue();
        AtomicReference<Wish> ghost = new AtomicReference<>();
        Thread g = park(q, "mcp-ghost", 3000, ghost);
        waitFor(q, 1);
        assertTrue(q.retireIfParked("mcp-ghost"));
        q.offer(wish("a boat"));
        g.join(4000);
        assertNull(ghost.get());
        assertEquals(1, q.size(), "the wish is still there for the next worker (the pool's top-up)");
    }

    @Test
    @DisplayName("a worker that is not parked is not retired, so the pool does not stop it")
    void onlyAParkedWorkerCanBeRetired() {
        WishQueue q = new WishQueue();
        assertFalse(q.retireIfParked("mcp-nobody"), "not parked: between polls, or holding a wish");
        assertFalse(q.retireIfParked(""));
        assertFalse(q.retireIfParked(null));
    }

    @Test
    @DisplayName("a worker that has already taken its wish has left the census, so the retire refuses")
    void aWorkerThatTookAWishCannotBeRetired() throws Exception {
        WishQueue q = new WishQueue();
        AtomicReference<Wish> got = new AtomicReference<>();
        Thread t = park(q, "mcp-a", 3000, got);
        waitFor(q, 1);
        q.offer(wish("a castle"));
        t.join(3000);
        assertTrue(got.get() != null);
        assertFalse(q.retireIfParked("mcp-a"),
                "it holds a wish now — stopping it would strand the wish the child just made");
    }
}
