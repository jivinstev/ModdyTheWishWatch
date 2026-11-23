package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.health.WishTimeoutPolicy;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>: a wish a worker has taken off the queue is claimed from that moment.</b>
 *
 * <p>The live case: the worker took a wish naming a character at 0 s and waited for its brief before binding it
 * ({@code Marshal.take}); the claim ceiling (120 s) cancelled it as "never claimed" at 128 s, and the
 * supervisor stopped the worker as deaf. Here the seconds are asserted against a fake clock, as the claim
 * ceiling's own test does (a GameTest cannot assert real seconds).
 */
class PickedUpClaimTest {

    private static final UUID WISH = UUID.nameUUIDFromBytes("picked-up".getBytes());
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("child".getBytes());

    @Test
    void aPickedUpWishIsNeverCancelledAsNeverClaimed() {
        FakeClock clock = new FakeClock(1_000_000L);
        Marshal m = Marshal.forTest(clock, 2);
        m.submit(WISH, PLAYER, "Give Scorch lava colours");
        WishTimeoutPolicy policy = new WishTimeoutPolicy(120, 900, 300, clock);

        clock.advance(5_000L);
        WishRun run = m.pickedUp("worker-1", WISH);
        assertTrue(run != null, "a live, unbound wish can be picked up");
        assertEquals("worker-1", run.pickedUpBy());
        assertEquals(1, m.pickingUp().size());
        assertTrue(WishTimeoutPolicy.Candidate.of(run).claimed(), "picked up is claimed");

        clock.advance(200_000L);   // well past the claim ceiling: the brief is slow
        assertTrue(policy.judge(WishTimeoutPolicy.Candidate.of(run), clock.nowMs()).isEmpty(),
                "a wish a worker holds is not 'never claimed' at 205 s");

        clock.advance(100_000L);   // ...but the no-progress ceiling still bounds a worker stuck before the bind
        var expiry = policy.judge(WishTimeoutPolicy.Candidate.of(run), clock.nowMs());
        assertTrue(expiry.isPresent());
        assertEquals(WishTimeoutPolicy.Reason.NO_PROGRESS, expiry.get().reason());
    }

    @Test
    void theBindEndsThePickupAndTheRunCountsFromTheBind() {
        FakeClock clock = new FakeClock(1_000_000L);
        Marshal m = Marshal.forTest(clock, 2);
        m.submit(WISH, PLAYER, "Give Scorch lava colours");
        m.pickedUp("worker-1", WISH);
        clock.advance(50_000L);
        WishRun bound = m.take("worker-1", WISH, PLAYER, "Give Scorch lava colours");
        assertEquals("worker-1", bound.workerId());
        assertNull(bound.pickedUpBy(), "bound, so no longer merely picked up");
        assertEquals(-1L, bound.pickedUpMs());
        assertEquals(0, m.pickingUp().size());
        assertEquals(1_050_000L, WishTimeoutPolicy.Candidate.of(bound).startedMs());
    }

    @Test
    void aWorkerThatDiesLetsItsPickupGoAndTheClaimClockRunsAgain() {
        FakeClock clock = new FakeClock(1_000_000L);
        Marshal m = Marshal.forTest(clock, 2);
        m.submit(WISH, PLAYER, "Give Scorch lava colours");
        WishTimeoutPolicy policy = new WishTimeoutPolicy(120, 900, 300, clock);
        WishRun run = m.pickedUp("worker-1", WISH);
        clock.advance(130_000L);
        m.workerDied("worker-1");
        assertNull(run.pickedUpBy());
        var expiry = policy.judge(WishTimeoutPolicy.Candidate.of(run), clock.nowMs());
        assertTrue(expiry.isPresent(), "nobody holds it any more: the child is told, as before");
        assertEquals(WishTimeoutPolicy.Reason.NEVER_CLAIMED, expiry.get().reason());
    }

    @Test
    void aWorkerPicksUpOneWishAtATime() {
        FakeClock clock = new FakeClock(1_000_000L);
        Marshal m = Marshal.forTest(clock, 2);
        UUID other = UUID.nameUUIDFromBytes("other".getBytes());
        m.submit(WISH, PLAYER, "one");
        m.submit(other, PLAYER, "two");
        WishRun first = m.pickedUp("worker-1", WISH);
        m.pickedUp("worker-1", other);
        assertNull(first.pickedUpBy(), "the earlier pickup, never bound, is nobody's again");
        assertEquals(1, m.pickingUp().size());
    }

    @Test
    void aStoppedOrUnknownWishIsNotPickedUp() {
        FakeClock clock = new FakeClock(1_000_000L);
        Marshal m = Marshal.forTest(clock, 2);
        assertNull(m.pickedUp("worker-1", WISH), "no run");
        m.submit(WISH, PLAYER, "one");
        m.cancel(WISH, CancelMode.STOP, "", "");
        assertNull(m.pickedUp("worker-1", WISH), "stopped");
        assertNull(m.pickedUp("", WISH), "no worker");
    }
}
