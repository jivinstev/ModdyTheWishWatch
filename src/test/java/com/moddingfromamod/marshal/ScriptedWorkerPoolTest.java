package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Tests of the <b>instrument</b>, not of the code under test.
 *
 * <p>This exists because of a number this repo has already paid for: <i>instrument bugs outnumbered
 * agent bugs about five to one, and nearly all of them were silent.</i> Every scheduling conclusion
 * in {@code MarshalSchedulingTest} is only as good as the fake pool underneath it, so the fake is
 * asserted to do what it claims — deterministic order, honoured durations, a death that happens once
 * and not forever, and completions that genuinely cross.
 */
class ScriptedWorkerPoolTest {

    private static final UUID CHILD = UUID.nameUUIDFromBytes("steve".getBytes());

    private static UUID wish(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes());
    }

    @RepeatedTest(20)
    void twoIdenticalRunsProduceIdenticalHistories() {
        assertEquals(historyOfARun(), historyOfARun(),
                "a repeat that differs is an instrument bug, and it would read as flakiness in "
                        + "every test built on this");
    }

    private static List<String> historyOfARun() {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, 2);
        ScriptedWorkerPool pool = new ScriptedWorkerPool(m, clock, 3).defaultDuration(70).step(10);
        for (int i = 0; i < 8; i++) {
            String text = "wish " + i;
            m.submit(wish(text), CHILD, text);
            pool.takes(text, 30 + (i * 37) % 130);
        }
        pool.runToQuiet(4000);
        List<String> history = new ArrayList<>(pool.assignmentOrder());
        history.add("--");
        history.addAll(pool.completionOrder());
        return history;
    }

    @Test
    void aScriptedDurationIsActuallyHonoured() {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, 1);
        ScriptedWorkerPool pool = new ScriptedWorkerPool(m, clock, 1).step(10);
        m.submit(wish("A"), CHILD, "A");
        pool.takes("A", 200);

        pool.runUntil(190);
        assertTrue(pool.completionOrder().isEmpty(), "not finished a beat early");
        pool.runUntil(210);
        assertEquals(List.of("A"), pool.completionOrder(), "and finished by the beat it was due");
    }

    @Test
    void aDeathHappensOnceAndTheWishComesBack() {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, 1);
        ScriptedWorkerPool pool = new ScriptedWorkerPool(m, clock, 2).defaultDuration(50).step(10);
        m.submit(wish("A"), CHILD, "A");
        pool.diesAfter("A", 30);

        pool.runToQuiet(1000);

        assertEquals(1, pool.deathOrder().size(), "it dies exactly once");
        assertEquals(List.of("A"), pool.completionOrder(),
                "and the second worker finishes it — re-dispatched, not lost");
    }

    @Test
    void aWishStoppedMidFlightIsNotReportedAsCompletedWhenTheWorkerWindsDown() {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, 1);
        ScriptedWorkerPool pool = new ScriptedWorkerPool(m, clock, 1).step(10);
        m.submit(wish("A"), CHILD, "A");
        pool.takes("A", 200);
        pool.runUntil(50);

        m.cancel(wish("A"), CancelMode.STOP, "he stopped it", null);
        pool.runToQuiet(1000);

        assertTrue(pool.completionOrder().isEmpty(),
                "the visible stop and the worker's wind-down are decoupled; the wind-down must not "
                        + "resurrect the wish");
    }

    @Test
    void anIdleWorkerKeepsAskingRatherThanGivingUp() {
        FakeClock clock = new FakeClock(0);
        Marshal m = Marshal.forTest(clock, 1);
        ScriptedWorkerPool pool = new ScriptedWorkerPool(m, clock, 1).defaultDuration(20).step(10);
        pool.runUntil(100);
        assertNull(pool.workerOf("late"), "nothing to do yet");

        m.submit(wish("late"), CHILD, "late");
        pool.settle();
        assertNotNull(pool.workerOf("late"), "a parked worker takes it the moment it lands");
    }
}
