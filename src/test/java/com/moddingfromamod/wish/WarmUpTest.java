package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A server booted only to check that it boots stopped while the Apprentice's warm-up thread was still loading
 * classes, and the thread died with an uncaught {@code NoClassDefFoundError}, which a platform smoke reads as a
 * crash. A stop during the warm-up is expected and quiet; the same failure while the server runs is still a fault.
 */
class WarmUpTest {

    /** Records what a warm-up said, by level. */
    static final class Said implements WarmUp.Log {
        final List<String> debug = new ArrayList<>();
        final List<String> warn = new ArrayList<>();
        final List<Throwable> error = new ArrayList<>();

        @Override
        public synchronized void debug(String message) {
            debug.add(message);
        }

        @Override
        public synchronized void warn(String message) {
            warn.add(message);
        }

        @Override
        public synchronized void error(String message, Throwable cause) {
            error.add(cause);
        }
    }

    @Test
    @DisplayName("a normal warm-up runs the job once and says nothing")
    void normalWarmBuilds() {
        Said said = new Said();
        AtomicInteger built = new AtomicInteger();
        assertEquals(WarmUp.Outcome.BUILT, WarmUp.run("the matcher", () -> true, built::incrementAndGet, said));
        assertEquals(1, built.get());
        assertTrue(said.debug.isEmpty() && said.warn.isEmpty() && said.error.isEmpty());
    }

    @Test
    @DisplayName("a server already stopped: the job never starts")
    void stoppedBeforeStart() {
        Said said = new Said();
        AtomicInteger built = new AtomicInteger();
        assertEquals(WarmUp.Outcome.NOT_STARTED, WarmUp.run("the matcher", () -> false, built::incrementAndGet, said));
        assertEquals(0, built.get());
        assertEquals(1, said.debug.size());
        assertTrue(said.error.isEmpty());
    }

    @Test
    @DisplayName("the server stops during the warm-up, then a class fails to load: quiet, not a fault")
    void stopDuringWarmIsQuiet() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch inJob = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<WarmUp.Outcome> outcome = new AtomicReference<>();
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Said said = new Said();
        Thread t = new Thread(() -> outcome.set(WarmUp.run("the matcher", running::get, () -> {
            inJob.countDown();
            try {
                assertTrue(stopped.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            throw new NoClassDefFoundError("com/moddingfromamod/wishbuilder/English");
        }, said)), "warm-under-test");
        t.setUncaughtExceptionHandler((th, e) -> uncaught.set(e));
        t.start();
        assertTrue(inJob.await(10, TimeUnit.SECONDS));
        running.set(false); // the server stops while the job runs
        stopped.countDown();
        t.join(10_000);
        assertFalse(t.isAlive());
        assertEquals(WarmUp.Outcome.STOPPED, outcome.get());
        assertEquals(null, uncaught.get(), "nothing escapes the thread");
        assertTrue(said.error.isEmpty() && said.warn.isEmpty(), "a stop is not a fault");
        assertEquals(1, said.debug.size());
    }

    @Test
    @DisplayName("a class that fails to load while the server runs is logged at ERROR, with its cause")
    void linkageErrorWhileRunningIsAFault() {
        Said said = new Said();
        NoClassDefFoundError e = new NoClassDefFoundError("com/moddingfromamod/wishbuilder/English");
        assertEquals(WarmUp.Outcome.FAILED, WarmUp.run("the matcher", () -> true, () -> {
            throw e;
        }, said));
        assertEquals(1, said.error.size());
        assertSame(e, said.error.get(0));
    }

    @Test
    @DisplayName("any other Error while the server runs is logged at ERROR too")
    void otherErrorWhileRunningIsAFault() {
        Said said = new Said();
        AssertionError e = new AssertionError("broken tree");
        assertEquals(WarmUp.Outcome.FAILED, WarmUp.run("the matcher", () -> true, () -> {
            throw e;
        }, said));
        assertSame(e, said.error.get(0));
    }

    @Test
    @DisplayName("a RuntimeException while the server runs is a warning, as before")
    void runtimeExceptionWhileRunningWarns() {
        Said said = new Said();
        assertEquals(WarmUp.Outcome.FAILED, WarmUp.run("the matcher", () -> true, () -> {
            throw new IllegalStateException("no tree");
        }, said));
        assertEquals(1, said.warn.size());
        assertTrue(said.error.isEmpty());
    }
}
