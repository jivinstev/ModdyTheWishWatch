package com.moddingfromamod.test;

import net.minecraft.gametest.framework.GameTestHelper;

/**
 * <b>Wait for something that happens in REAL time, from a GameTest that counts TICKS.</b>
 *
 * <p>A GameTest server ticks as fast as it can (CLAUDE.md), so a timeout in ticks says nothing about
 * seconds. On 1.21.1 that was slow enough that a test waiting on a background thread, a debounce or a
 * one-second ceiling usually got there inside its tick budget. <b>1.21.4 is not</b>: measured on the
 * first 1.21.4 Gate B, a single-test batch ran 20,000 ticks inside ONE wall-clock second, so
 * five tests that wait on a thread or a clock failed before the thing they wait for had started.
 *
 * <p>So a test that waits on real time paces the server while it is NOT yet satisfied: each failed
 * check sleeps {@link #PACE_MS}, which turns the tick budget into a wall-clock one ({@code timeoutTicks}
 * x {@value #PACE_MS} ms) and costs nothing once the condition holds. It never sleeps on a check that
 * passes, so a test whose subject is already done finishes at full speed.
 */
public final class RealTime {

    /** Real milliseconds a tick is held back while the condition is still false. */
    public static final long PACE_MS = 5;

    private RealTime() {
    }

    /** {@link GameTestHelper#succeedWhen} whose budget is wall-clock, not tick count. */
    public static void succeedWhen(GameTestHelper helper, Runnable check) {
        helper.succeedWhen(() -> {
            try {
                check.run();
            } catch (RuntimeException e) {
                pace();
                throw e;
            }
        });
    }

    /** Hold this tick back a little, so the next check is later in real time. */
    public static void pace() {
        try {
            Thread.sleep(PACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
