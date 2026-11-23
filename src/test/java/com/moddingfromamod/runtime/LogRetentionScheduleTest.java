package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * the daily pass runs at start and then every period, and a pass that throws does not stop the next. Timed in
 * real milliseconds, so it lives here and not in a GameTest (CLAUDE.md: GameTest ticks are not seconds).
 */
class LogRetentionScheduleTest {

    @Test
    void theFirstPassRunsAtOnceAndThenEveryPeriod() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch four = new CountDownLatch(4);
        Thread t = LogRetention.daily("test-retention", Duration.ofMillis(50), () -> {
            first.countDown();
            four.countDown();
        });
        try {
            assertTrue(first.await(500, TimeUnit.MILLISECONDS), "the first pass runs at start, not after a period");
            assertTrue(four.await(5, TimeUnit.SECONDS), "then once per period");
            assertTrue(t.isDaemon(), "it never keeps the JVM alive");
        } finally {
            t.interrupt();
        }
    }

    @Test
    void aPassThatThrowsDoesNotStopTheNext() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch three = new CountDownLatch(3);
        Thread t = LogRetention.daily("test-retention-throws", Duration.ofMillis(30), () -> {
            runs.incrementAndGet();
            three.countDown();
            throw new IllegalStateException("boom");
        });
        try {
            assertTrue(three.await(5, TimeUnit.SECONDS), "passes kept running after one threw: " + runs.get());
        } finally {
            t.interrupt();
        }
    }
}
