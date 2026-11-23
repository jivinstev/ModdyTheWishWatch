package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** the pickup ratchet's state — what a parked call is told, and that nothing is told twice. */
class PickupHoldTest {

    private final PickupHold hold = PickupHold.forTest();
    private final UUID wish = UUID.randomUUID();

    @Test
    @DisplayName("nothing opened: no hold, and nothing parks")
    void noHold() throws Exception {
        assertEquals(PickupHold.State.NONE, hold.state(wish));
        assertEquals(PickupHold.State.NONE, hold.await(wish, 10));
    }

    @Test
    @DisplayName("a parked call waits for the verdict, then goes on with the note — once")
    void clearReleasesAParkedCall() throws Exception {
        hold.open(wish);
        AtomicReference<PickupHold.State> seen = new AtomicReference<>();
        Thread parked = new Thread(() -> {
            try {
                seen.set(hold.await(wish, 5_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        parked.start();
        Thread.sleep(100);
        assertEquals(null, seen.get(), "it must still be waiting before the verdict");
        hold.clear(wish, "a MAYBE's guidance");
        parked.join(2_000);
        assertEquals(PickupHold.State.CLEARED, seen.get());
        assertEquals("a MAYBE's guidance", hold.takeNote(wish));
        assertEquals("", hold.takeNote(wish), "the note rides ONE call, not every call after it");
    }

    @Test
    @DisplayName("a rescinded wish refuses its parked calls, and its session never acts again")
    void rescind() throws Exception {
        hold.open(wish);
        hold.rescind(wish, "session-a");
        assertEquals(PickupHold.State.RESCINDED, hold.await(wish, 10));
        assertTrue(hold.rescinded("session-a"));
        assertFalse(hold.rescinded("session-b"), "another worker is untouched");
        hold.clear(wish, "late");
        assertEquals(PickupHold.State.RESCINDED, hold.state(wish), "a decided hold never changes its mind");
    }

    @Test
    @DisplayName("a verdict that never comes leaves the call PENDING at the ceiling: the caller refuses")
    void ceilingFailsClosed() throws Exception {
        hold.open(wish);
        long t0 = System.currentTimeMillis();
        assertEquals(PickupHold.State.PENDING, hold.await(wish, 150));
        assertTrue(System.currentTimeMillis() - t0 >= 140, "it waited the ceiling out");
    }

    @Test
    @DisplayName("a refused PLAN answers every call with the grader's message until a new plan opens")
    void refusedPlan() throws Exception {
        hold.open(wish);
        hold.refuse(wish, "Plan step 2 again, gentler.");
        assertEquals(PickupHold.State.REFUSED, hold.await(wish, 10));
        assertEquals("Plan step 2 again, gentler.", hold.refusal(wish));
        assertEquals("Plan step 2 again, gentler.", hold.refusal(wish), "every call, not once");
        hold.open(wish);
        assertEquals(PickupHold.State.PENDING, hold.state(wish), "a re-plan opens a fresh hold");
        assertEquals("", hold.refusal(wish));
    }

    @Test
    @DisplayName("#1085: a stop wakes a parked call at once, and a verdict landing afterwards changes nothing")
    void stopWakesTheParkAndSticks() throws Exception {
        hold.open(wish);
        AtomicReference<PickupHold.State> seen = new AtomicReference<>();
        Thread parked = new Thread(() -> {
            try {
                seen.set(hold.await(wish, 30_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        parked.start();
        Thread.sleep(50);
        long t0 = System.currentTimeMillis();
        hold.stop(wish);
        parked.join(5_000);
        assertTrue(System.currentTimeMillis() - t0 < 5_000, "the park was not woken by the stop");
        assertEquals(PickupHold.State.STOPPED, seen.get());
        hold.clear(wish, "");
        assertEquals(PickupHold.State.STOPPED, hold.state(wish), "a grade landing later cannot un-stop it");
    }

    @Test
    @DisplayName("#1085: a stop on a hold already decided, or never opened, changes nothing")
    void stopLeavesADecidedHoldAlone() {
        hold.stop(wish);
        assertEquals(PickupHold.State.NONE, hold.state(wish));
        hold.open(wish);
        hold.clear(wish, "");
        hold.stop(wish);
        assertEquals(PickupHold.State.CLEARED, hold.state(wish));
    }

    @Test
    @DisplayName("a session flagged for retirement is answered once, then forgotten")
    void retireAfterWish() {
        hold.retireAfterWish("session-a");
        assertTrue(hold.takeRetire("session-a"));
        assertFalse(hold.takeRetire("session-a"));
        assertFalse(hold.takeRetire("session-b"));
    }

    @Test
    @DisplayName("the input holds and the plan holds are separate")
    void twoRegistries() {
        assertFalse(PickupHold.global() == PickupHold.plans());
        Set<PickupHold.State> all = Set.of(PickupHold.State.values());
        assertTrue(all.contains(PickupHold.State.REFUSED) && all.contains(PickupHold.State.RESCINDED));
    }
}
