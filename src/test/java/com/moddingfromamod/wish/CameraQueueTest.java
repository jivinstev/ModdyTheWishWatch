package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The ORDER two wishes take one camera in — the part was missing, tested without a world.
 *
 * <p>Before the queue there was a lease and a six-attempt retry, so which of two children got a
 * properly framed wish card was decided by scheduling. These pin the properties that make the wait
 * fair and, more importantly, BOUNDED: a queue that can wedge is worse than the spin it replaced.
 */
class CameraQueueTest {

    private static final UUID A = UUID.nameUUIDFromBytes("wish-a".getBytes());
    private static final UUID B = UUID.nameUUIDFromBytes("wish-b".getBytes());
    private static final UUID C = UUID.nameUUIDFromBytes("wish-c".getBytes());

    @Test
    void anIdleCameraIsGrantedImmediately() {
        CameraQueue q = new CameraQueue();
        assertEquals(CameraQueue.Outcome.GRANTED, q.claim(A, 0));
        assertEquals(A, q.holder());
        assertEquals(0, q.depth());
    }

    @Test
    void theHolderReclaimingIsHowABurstStaysOpen() {
        // A wish takes several shots in a row. Refusing the second would end the session between
        // every frame, which is the freeze/unfreeze/teleport churn WishCapture exists to avoid.
        CameraQueue q = new CameraQueue();
        q.claim(A, 0);
        assertEquals(CameraQueue.Outcome.GRANTED, q.claim(A, 100));
        assertEquals(CameraQueue.Outcome.GRANTED, q.claim(A, 200));
        assertEquals(0, q.depth());
    }

    @Test
    void aSecondWishIsQueuedRatherThanRefused() {
        CameraQueue q = new CameraQueue();
        q.claim(A, 0);
        assertEquals(CameraQueue.Outcome.QUEUED, q.claim(B, 10));
        assertEquals(1, q.depth());
        assertEquals(1, q.positionOf(B));
    }

    @Test
    void askingTwiceDoesNotTakeTwoPlacesInLine() {
        CameraQueue q = new CameraQueue();
        q.claim(A, 0);
        q.claim(B, 10);
        q.claim(B, 20);
        q.claim(B, 30);
        assertEquals(1, q.depth(), "B queued three times and should hold one place");
    }

    @Test
    void releaseHandsItToTheNextInLineInOrder() {
        CameraQueue q = new CameraQueue();
        q.claim(A, 0);
        q.claim(B, 10);
        q.claim(C, 20);
        q.release(A, false, 0);
        assertEquals(B, q.holder(), "B waited first and must go first");
        q.release(B, false, 0);
        assertEquals(C, q.holder());
        q.release(C, false, 0);
        assertTrue(q.idle());
    }

    @Test
    void onlyTheHolderMayPutItDown() {
        // Otherwise a second wish's tidy-up ends the first one's session while it is still shooting
        // -- which is the freeze being released out from under a child mid-photograph.
        CameraQueue q = new CameraQueue();
        q.claim(A, 0);
        q.claim(B, 10);
        q.release(B, false, 0);
        assertEquals(A, q.holder(), "B does not hold it and must not be able to release it");
    }

    @Test
    void theWatchdogMayAlwaysReleaseIt() {
        // WishCapture's existing contract: the freeze must ALWAYS end however wedged things get,
        // so the watchdog releases WITHOUT being the holder -- the one exemption to the rule above.
        CameraQueue q = new CameraQueue();
        q.claim(A, 0);
        q.release(B, true, 0);          // B does not hold it; the watchdog does not care
        assertTrue(q.idle(), "a forced release must clear the camera even from a non-holder");
    }

    @Test
    void aForcedReleaseStillHandsOverToWhoeverIsWaiting() {
        // The watchdog freeing a wedged holder must not also throw away the queue behind it.
        CameraQueue q = new CameraQueue();
        q.claim(A, 0);
        q.claim(B, 10);
        q.release(A, true, 0);
        assertEquals(B, q.holder());
    }

    @Test
    void aPromotedWaitersHoldClockStartsWhenItTAKESIt() {
        // THE HANDOVER IS AN OFFER, NOT A HOLD. If promotion started the clock, the new holder
        // would be charged for however long the previous wish took and could be expired before it
        // ever fired a shutter; if promotion started NO clock, a waiter that gave up would hold the
        // camera for the life of the server. Both are wedges, in opposite directions.
        CameraQueue q = new CameraQueue();
        q.claim(A, 0);
        q.claim(B, 0);
        q.release(A, false, 1000);            // B is offered it at t=1000
        q.claim(B, 1500);                     // ...and picks it up at t=1500
        assertFalse(q.expire(1500 + 9000, 10_000, 60_000),
                "B has held it for 9s of its own 10s budget, not 9.5s of A's");
        assertTrue(q.expire(1500 + 11_000, 10_000, 60_000));
        assertTrue(q.idle());
    }

    @Test
    void aWaiterOfferedTheCameraThatNeverComesBackIsStillExpired() {
        CameraQueue q = new CameraQueue();
        q.claim(A, 0);
        q.claim(B, 0);
        q.release(A, false, 1000);
        assertEquals(B, q.holder());
        assertTrue(q.expire(1000 + 11_000, 10_000, 60_000),
                "B never claimed it and must not hold it forever");
        assertTrue(q.idle());
    }

    @Test
    void aBurstCannotRefreshItsWayPastTheHardCap() {
        // The hold clock is set once, at the take. Re-claiming keeps the session open (that is what
        // a burst is) but must not push the cap out, or the watchdog can never fire.
        CameraQueue q = new CameraQueue();
        q.claim(A, 1000);
        for (int t = 2000; t <= 10_000; t += 1000) {
            q.claim(A, t);
        }
        assertTrue(q.expire(12_000, 10_000, 60_000), "A took it at t=1000 and the cap is 10s");
    }

    @Test
    void anAnonymousCallerCannotReleaseARealWishesCamera() {
        // A headless probe SHARES the camera rather than taking it, so its tidy-up releasing the
        // holder would end a real burst mid-photograph -- the exact failure ownership exists for.
        CameraQueue q = new CameraQueue();
        q.claim(A, 0);
        q.claim(null, 10);
        q.release(null, false, 20);
        assertEquals(A, q.holder());
    }

    @Test
    void aWaiterThatGivesUpDoesNotBlockTheOnesBehindIt() {
        CameraQueue q = new CameraQueue();
        q.claim(A, 0);
        q.claim(B, 10);
        q.claim(C, 20);
        q.abandon(B);
        q.release(A, false, 0);
        assertEquals(C, q.holder(), "B gave up, so C is next");
    }

    @Test
    void aHolderThatDiesMidBurstIsExpired() {
        // THE QUEUE MUST NOT BE ABLE TO WEDGE. A wish that dies holding the camera would otherwise
        // hold it for the life of the server and everything behind it would wait forever -- which
        // is strictly worse than the spin-and-give-up this replaced.
        CameraQueue q = new CameraQueue();
        q.claim(A, 1000);
        q.claim(B, 1000);
        assertTrue(q.expire(1000 + 30_000, 10_000, 60_000));
        assertEquals(B, q.holder(), "A held too long; B is promoted");
    }

    @Test
    void aWaiterThatWaitedForeverIsDropped() {
        CameraQueue q = new CameraQueue();
        q.claim(A, 1000);
        q.claim(B, 1000);
        q.expire(1000 + 120_000, 300_000, 60_000);
        assertEquals(0, q.depth(), "B waited past the cap and should be dropped");
        assertEquals(A, q.holder(), "A is still within its hold budget");
    }

    @Test
    void expireReportsWhetherItChangedAnything() {
        CameraQueue q = new CameraQueue();
        q.claim(A, 1000);
        assertFalse(q.expire(1500, 10_000, 60_000), "nothing is stale yet");
    }

    // ── the anonymous case, which is most of the existing callers ───────────────────────────────

    @Test
    void anAnonymousCaptureNeverContends() {
        // A headless probe, a manual command and several gates photograph with no AgentContext run
        // in scope and arrive with a null owner. The pre-queue lease let those share; making them
        // contend would turn "two unrelated tests took a photo" into a stall.
        CameraQueue q = new CameraQueue();
        assertEquals(CameraQueue.Outcome.GRANTED, q.claim(null, 0));
        assertEquals(CameraQueue.Outcome.GRANTED, q.claim(A, 10),
                "a real wish must not be queued behind an anonymous holder");
        assertEquals(CameraQueue.Outcome.GRANTED, q.claim(null, 20),
                "and an anonymous caller must not be queued behind a real wish");
        assertEquals(0, q.depth());
    }

    @Test
    void somebodyNotInLineHasNoPosition() {
        CameraQueue q = new CameraQueue();
        q.claim(A, 0);
        assertEquals(-1, q.positionOf(C));
    }

    @Test
    void anEmptyQueueHasNoHolder() {
        CameraQueue q = new CameraQueue();
        assertTrue(q.idle());
        assertNull(q.holder());
    }
}
