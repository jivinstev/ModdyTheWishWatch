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
    /**
     * Loads com.moddingfromamod classes itself, from the test classpath, until it is closed; then every such load fails,
     * as a closed mod jar does when the server has stopped.
     */
    static final class ClosingLoader extends ClassLoader {
        volatile boolean closed;

        ClosingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("com.moddingfromamod.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    if (closed) {
                        throw new ClassNotFoundException(name + " (the jar is closed)");
                    }
                    String res = name.replace('.', '/') + ".class";
                    try (java.io.InputStream in = getParent().getResourceAsStream(res)) {
                        if (in == null) {
                            throw new ClassNotFoundException(name);
                        }
                        byte[] b = in.readAllBytes();
                        c = defineClass(name, b, 0, b.length);
                    } catch (java.io.IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
    }

    @Test
    @DisplayName("the jar closes during the warm-up: judging the failure loads no class from it, nothing escapes")
    @SuppressWarnings("unchecked")
    void closedJarDuringWarmIsQuiet() throws Exception {
        ClosingLoader loader = new ClosingLoader(WarmUpTest.class.getClassLoader());
        AtomicBoolean running = new AtomicBoolean(true);
        Runnable job = () -> {
            running.set(false);   // the server stops...
            loader.closed = true; // ...and its jar closes, while the job is still loading classes
            throw new NoClassDefFoundError("com/moddingfromamod/wishbuilder/English");
        };
        // the caller does what ApprenticeService.warm does: WarmUp.load() while the jar is open, then a direct call
        java.util.function.Supplier<String> caller = (java.util.function.Supplier<String>) Class
                .forName("com.moddingfromamod.wish.WarmUpCaller", true, loader)
                .getDeclaredConstructor(java.util.function.BooleanSupplier.class, Runnable.class)
                .newInstance((java.util.function.BooleanSupplier) running::get, job);
        String got;
        try {
            got = caller.get();
        } catch (LinkageError e) {
            throw new AssertionError("a failure escaped the warm-up: " + e, e);
        }
        assertEquals("STOPPED [debug]", got, "a stop is quiet: one debug line, no warning or error");
    }
}
