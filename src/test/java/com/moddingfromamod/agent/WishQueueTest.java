package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import org.junit.jupiter.api.Test;

/** Gate A for the wake channel: blocking, timeout, overflow, and the handoff being genuinely fast. */
class WishQueueTest {

    private static Wish wish(String text) {
        return new Wish(UUID.randomUUID(), text, WishSource.TYPED, WishStatus.PENDING,
                System.currentTimeMillis(), UUID.randomUUID(), "Steve",
                "minecraft:overworld", 0, 64, 0, "", "", 0L);
    }

    @Test
    void aWaitingWishIsReturnedImmediately() throws Exception {
        WishQueue q = new WishQueue();
        q.offer(wish("make it rain chickens"));
        Wish got = q.await(1000);
        assertNotNull(got);
        assertEquals("make it rain chickens", got.text());
    }

    @Test
    void timesOutCleanlyWhenNothingArrives() throws Exception {
        WishQueue q = new WishQueue();
        long start = System.nanoTime();
        assertNull(q.await(150), "should return null, not throw");
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms >= 120, "must actually wait, waited " + ms + "ms");
    }

    /**
     * The headline property. A parked waiter must be handed a wish the moment it lands — this is
     * the entire reason the tool exists, replacing a 5s poll behind a 45s rate limit.
     */
    @Test
    void aParkedWaiterIsWokenAlmostInstantly() throws Exception {
        WishQueue q = new WishQueue();
        CountDownLatch parked = new CountDownLatch(1);
        AtomicReference<Wish> got = new AtomicReference<>();
        AtomicLong wokeAt = new AtomicLong();

        Thread waiter = new Thread(() -> {
            parked.countDown();
            try {
                got.set(q.await(5000));
                wokeAt.set(System.nanoTime());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "test-waiter");
        waiter.setDaemon(true);
        waiter.start();

        assertTrue(parked.await(2, TimeUnit.SECONDS), "waiter should have started");
        Thread.sleep(50);                       // make sure it is genuinely blocked, not racing
        long offeredAt = System.nanoTime();
        q.offer(wish("a wither storm"));
        waiter.join(3000);

        assertNotNull(got.get(), "the parked waiter should have been handed the wish");
        long wakeMs = (wokeAt.get() - offeredAt) / 1_000_000;
        assertTrue(wakeMs < 250, "wake should be near-instant, took " + wakeMs + "ms");
    }

    @Test
    void wishesArrivingWhileNobodyWaitsAreKeptNotDropped() throws Exception {
        // There is a ~1.8s gap between an agent finishing one wish and parking again (measured).
        // Anything wished in that window must still be there when it comes back.
        WishQueue q = new WishQueue();
        q.offer(wish("first"));
        q.offer(wish("second"));
        assertEquals("first", q.await(100).text(), "oldest first");
        assertEquals("second", q.await(100).text());
    }

    @Test
    void overflowDropsTheOldestAndSaysSo() {
        WishQueue q = new WishQueue();
        for (int i = 0; i < WishQueue.MAX_WAITING + 5; i++) {
            q.offer(wish("wish " + i));
        }
        assertEquals(WishQueue.MAX_WAITING, q.size(), "must stay bounded");
        assertEquals(5, q.droppedCount(), "overflow must be counted, never silent");
    }

    @Test
    void peekRestReportsABacklogWithoutTakingIt() {
        WishQueue q = new WishQueue();
        for (int i = 0; i < 4; i++) {
            q.offer(wish("w" + i));
        }
        assertEquals(3, q.peekRest(3).size());
        assertEquals(4, q.size(),
                "LOOKED AT, NOT TAKEN. This used to poll them off, which was right with one agent "
                        + "and is total starvation with a pool: the first worker to wake empties "
                        + "the queue and every other one long-polls nothing forever.");
        assertTrue(new WishQueue().peekRest(5).isEmpty(), "an empty queue has no backlog");
    }

    @Test
    void twoWorkersEachGetTheirOwnWish() {
        // The regression this file exists to hold from here on. A pool that grants strictly one
        // wish at a time looks perfectly healthy from every other angle -- both agents alive, both
        // parked, no errors anywhere -- and only a run with two REAL agents ever showed it.
        WishQueue q = new WishQueue();
        q.offer(wish("spikor"));
        q.offer(wish("a javelin"));

        Wish first;
        Wish second;
        try {
            first = q.await(50);
            q.peekRest(5);            // what the first worker does with the rest
            second = q.await(50);     // the second worker, a moment later
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        assertTrue(first != null && second != null, "both workers must get work");
        assertFalse(first.id().equals(second.id()), "and it must be DIFFERENT work");
    }

    @Test
    void clearResetsForANewWorld() {
        WishQueue q = new WishQueue();
        q.offer(wish("stale"));
        q.clear();
        assertEquals(0, q.size());
        assertEquals(0, q.droppedCount());
    }

    @Test
    void survivesRubbish() {
        WishQueue q = new WishQueue();
        q.offer(null);
        assertEquals(0, q.size(), "a null wish is ignored, not queued");
    }
}
