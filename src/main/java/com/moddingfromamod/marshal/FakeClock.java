package com.moddingfromamod.marshal;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A clock a test drives by hand.
 *
 * <p>Lives in {@code src/main} rather than {@code src/test} on purpose: Gate-B {@code @GameTest}s
 * are compiled into the mod, so a fake that only existed in the unit-test source set would be
 * unavailable to exactly the tests that most need to avoid wall-clock.
 *
 * <p>Thread-safe, because the marshal is driven from the server thread, HTTP handler threads and
 * the narrator thread, and a fake that is only safe on one of them turns a real race into a test
 * artefact.
 */
public final class FakeClock implements Clock {

    private final AtomicLong now;

    public FakeClock() {
        this(0L);
    }

    public FakeClock(long startMs) {
        this.now = new AtomicLong(startMs);
    }

    @Override
    public long nowMs() {
        return now.get();
    }

    /** Move time forward. Negative is refused — time going backwards is never the thing under test. */
    public FakeClock advance(long ms) {
        if (ms > 0) {
            now.addAndGet(ms);
        }
        return this;
    }

    public FakeClock set(long ms) {
        now.set(ms);
        return this;
    }
}
