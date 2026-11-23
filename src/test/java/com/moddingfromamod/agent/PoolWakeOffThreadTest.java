package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.health.AgentWarnings;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Starting the wish pool never holds up its caller </b> — the timing half, which a GameTest
 * cannot assert (it ticks as fast as it can; CLAUDE.md). {@code PoolStartOffThreadGameTest} drives the
 * real join; this pins {@link AgentSession#wake} itself: off the caller's thread, once at a time, and a
 * failure said and retried.
 *
 * <p>🔴 Verified red: with {@code wakeStart.run()} called inline in {@code wake} the budget test fails
 * at the fake's full sleep; with the {@code WAKING} guard removed the race test counts 8 starts; with the
 * catch narrowed back to {@code RuntimeException} the Error test finds no warning noted.
 */
class PoolWakeOffThreadTest {

    private static final long SLOW_MS = 1500;
    private static final long BUDGET_MS = 100;

    private BooleanSupplier realWanted;
    private Runnable realStart;

    @BeforeEach
    void seams() throws InterruptedException {
        realWanted = AgentSession.wakeWanted;
        realStart = AgentSession.wakeStart;
        awaitIdle();
        AgentWarnings.clear();
    }

    @AfterEach
    void restore() throws InterruptedException {
        awaitIdle();
        AgentSession.wakeWanted = realWanted;
        AgentSession.wakeStart = realStart;
        AgentWarnings.clear();
    }

    private static void awaitIdle() throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (AgentSession.waking() && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        assertFalse(AgentSession.waking(), "a wake from an earlier test never finished");
    }

    @Test
    void theCallerNeverWaitsForASlowStart() throws InterruptedException {
        AtomicReference<String> on = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AgentSession.wakeWanted = () -> done.getCount() > 0;
        AgentSession.wakeStart = () -> {
            on.set(Thread.currentThread().getName());
            sleep(SLOW_MS);
            done.countDown();
        };
        long t0 = System.nanoTime();
        AgentSession.wake("a join");
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(ms < BUDGET_MS, "wake held its caller (the server thread) for " + ms + " ms behind a "
                + SLOW_MS + " ms start — the world would freeze for the child joining");
        assertTrue(done.await(10, TimeUnit.SECONDS), "the start never ran");
        assertEquals("wish-pool-wake", on.get(), "the start must run on its own daemon thread");
    }

    @Test
    void manyJoinsAtOnceStartThePoolOnce() throws InterruptedException {
        AtomicInteger starts = new AtomicInteger();
        AtomicBoolean up = new AtomicBoolean();
        AgentSession.wakeWanted = () -> !up.get();
        AgentSession.wakeStart = () -> {
            starts.incrementAndGet();
            sleep(300);
            up.set(true);
        };
        CountDownLatch go = new CountDownLatch(1);
        Thread[] joins = new Thread[8];
        for (int i = 0; i < joins.length; i++) {
            joins[i] = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                AgentSession.wake("a join");
            });
            joins[i].start();
        }
        go.countDown();
        for (Thread t : joins) {
            t.join(5000);
        }
        awaitIdle();
        AgentSession.wake("a late join");     // the pool is up: nothing to do
        awaitIdle();
        assertEquals(1, starts.get(), "eight joins at once must start ONE pool");
    }

    @Test
    void aFailedStartIsSaidAndTheNextWakeTriesAgain() throws InterruptedException {
        AtomicInteger tries = new AtomicInteger();
        AgentSession.wakeWanted = () -> true;
        AgentSession.wakeStart = () -> {
            tries.incrementAndGet();
            throw new IllegalStateException("the CLI would not launch");
        };
        AgentSession.wake("a join");
        awaitIdle();
        assertTrue(String.valueOf(AgentWarnings.last()).contains("the CLI would not launch"),
                "a failed off-thread start must be noted for the watchdog, not swallowed: " + AgentWarnings.last());
        AgentSession.wake("a wish");
        awaitIdle();
        assertEquals(2, tries.get(), "the next wake must try again after a failure");
    }

    @Test
    void anErrorInTheStartIsSaidToo() throws InterruptedException {
        AgentSession.wakeWanted = () -> true;
        AgentSession.wakeStart = () -> {
            throw new NoClassDefFoundError("a linkage error in a launch");
        };
        AgentSession.wake("a join");
        awaitIdle();
        assertTrue(String.valueOf(AgentWarnings.last()).contains("a linkage error in a launch"),
                "an Error must not end the wake thread silently: " + AgentWarnings.last());
    }

    @Test
    void nothingStartsWhereThePoolMayNotRun() throws InterruptedException {
        AtomicInteger starts = new AtomicInteger();
        AgentSession.wakeWanted = () -> false;
        AgentSession.wakeStart = starts::incrementAndGet;
        AgentSession.wake("a join");
        Thread.sleep(100);
        assertEquals(0, starts.get());
        assertFalse(AgentSession.waking());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
