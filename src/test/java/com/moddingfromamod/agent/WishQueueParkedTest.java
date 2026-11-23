package com.moddingfromamod.agent;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The pre-flight pool census</b>.
 *
 * <p>The point of this number is that it can be asked <em>before any wish is spent</em>.
 * {@code ArtifactAffinity.workersSeen()} counts workers a wish has been OFFERED to, so it reads 0
 * before the first rung and a harness waiting on it would deadlock — the pool only grows once the
 * work it was about to spend starts flowing.
 */
class WishQueueParkedTest {

    private static Thread park(WishQueue q, String worker, long ms, CountDownLatch in) {
        Thread t = new Thread(() -> {
            try {
                in.countDown();
                q.await(ms, worker);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "park-" + worker);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Parking is asynchronous; give the thread a moment to actually be inside await(). */
    private static void waitFor(WishQueue q, int n) throws InterruptedException {
        long until = System.currentTimeMillis() + 3000;
        while (q.waitingWorkers() < n && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
    }

    @Test
    @DisplayName("a worker parked on await_wish is counted, with no wish in the queue at all")
    void parkingIsWhatCounts() throws Exception {
        WishQueue q = new WishQueue();
        assertEquals(0, q.waitingWorkers(), "nobody has parked yet");
        CountDownLatch in = new CountDownLatch(1);
        park(q, "worker-a", 2000, in);
        waitFor(q, 1);
        assertEquals(1, q.waitingWorkers(),
                "the census must not require a wish to exist — that is the deadlock this replaces");
    }

    @Test
    @DisplayName("two workers parked is what arm B needs, and it reads 2")
    void twoWorkersRead2() throws Exception {
        WishQueue q = new WishQueue();
        park(q, "worker-a", 2000, new CountDownLatch(1));
        park(q, "worker-b", 2000, new CountDownLatch(1));
        waitFor(q, 2);
        assertEquals(2, q.waitingWorkers());
    }

    @Test
    @DisplayName("the same worker parking twice is still one worker")
    void theCensusIsOfDistinctWorkers() throws Exception {
        WishQueue q = new WishQueue();
        park(q, "worker-a", 1500, new CountDownLatch(1));
        park(q, "worker-a", 1500, new CountDownLatch(1));
        waitFor(q, 1);
        assertEquals(1, q.waitingWorkers(), "arm B needs a DIFFERENT worker, not another poll");
    }

    @Test
    @DisplayName("a worker that stops waiting is no longer counted")
    void leavingDecrementsIt() throws Exception {
        WishQueue q = new WishQueue();
        Thread t = park(q, "worker-a", 250, new CountDownLatch(1));
        waitFor(q, 1);
        t.join(3000);
        assertEquals(0, q.waitingWorkers(),
                "a stale census would report a pool that has gone home");
    }

    @Test
    @DisplayName("an unidentified caller is not counted as a worker")
    void anonymousPollsDoNotInflateIt() throws Exception {
        WishQueue q = new WishQueue();
        park(q, "", 800, new CountDownLatch(1));
        Thread.sleep(200);
        assertEquals(0, q.waitingWorkers(),
                "counting a blank id would report a second worker that arm B can never route to");
    }

    @Test
    @DisplayName("a parked worker still receives the wish — the census must not change behaviour")
    void parkingStillWorks() throws Exception {
        WishQueue q = new WishQueue();
        CountDownLatch in = new CountDownLatch(1);
        final Wish[] got = new Wish[1];
        Thread t = new Thread(() -> {
            try {
                in.countDown();
                got[0] = q.await(3000, "worker-a").wish();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.setDaemon(true);
        t.start();
        assertTrue(in.await(2, TimeUnit.SECONDS));
        waitFor(q, 1);
        q.offer(new Wish(UUID.randomUUID(), "build me a train", WishSource.TYPED,
                WishStatus.PENDING, 0L, UUID.randomUUID(), "kid", "minecraft:overworld",
                0, 0, 0, "", "", 0L));
        t.join(4000);
        assertTrue(got[0] != null, "the worker must still be handed the wish it parked for");
        assertEquals(0, q.waitingWorkers());
    }
}
