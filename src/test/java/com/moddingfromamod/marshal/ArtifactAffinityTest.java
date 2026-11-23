package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.marshal.ArtifactAffinity.Routing;
import org.junit.jupiter.api.Test;

/**
 * The picker: same/different/off actually route that way, and neither forcing mode can wedge a wish.
 *
 * <p>Gate A, in milliseconds, against a pure class — which is the whole reason the ownership map and
 * the hold rule live outside the queue that uses them. The same questions asked with real agents cost
 * ~117 s a wish and would be asked once.
 */
class ArtifactAffinityTest {

    private static ArtifactAffinity owned(String artifact, String worker) {
        ArtifactAffinity a = ArtifactAffinity.forTest();
        a.remember(artifact, worker);
        return a;
    }

    // ================= off: today's behaviour, untouched =================

    @Test
    void withNoModeForcingEveryoneMayTakeEverything() {
        ArtifactAffinity a = owned("zippy", "w1");
        for (String worker : new String[] {"w1", "w2", "w3", ""}) {
            Routing r = a.decide(WishAffinity.OFF, worker, "zippy", 0);
            assertEquals(Routing.ANYONE, r);
            assertTrue(r.take());
            assertTrue(r.held(), "OFF is not an arm that can fail to hold");
        }
    }

    /** A null mode is OFF, not a forcing mode. Defaults must never be a surprise treatment. */
    @Test
    void aNullModeIsTreatedAsOff() {
        assertEquals(Routing.ANYONE, owned("zippy", "w1").decide(null, "w2", "zippy", 0));
    }

    // ================= same =================

    @Test
    void underSameOnlyTheWorkerThatMadeItMayTakeIt() {
        ArtifactAffinity a = owned("zippy", "w1");
        assertEquals(Routing.OWNER, a.decide(WishAffinity.SAME, "w1", "zippy", 0));
        Routing stranger = a.decide(WishAffinity.SAME, "w2", "zippy", 0);
        assertEquals(Routing.WAIT_FOR_OWNER, stranger);
        assertFalse(stranger.take());
        assertTrue(stranger.held(), "waiting IS the arm holding, not failing");
    }

    // ================= different =================

    @Test
    void underDifferentAnybodyButTheWorkerThatMadeItMayTakeIt() {
        ArtifactAffinity a = owned("zippy", "w1");
        assertEquals(Routing.STRANGER, a.decide(WishAffinity.DIFFERENT, "w2", "zippy", 0));
        Routing maker = a.decide(WishAffinity.DIFFERENT, "w1", "zippy", 0);
        assertEquals(Routing.WAIT_FOR_STRANGER, maker);
        assertFalse(maker.take());
    }

    // ================= an unowned thing =================

    /**
     * Both arms agree on a creation, and they have to.
     *
     * <p>Every ladder starts with "make me Zippy", which names nothing anybody has worked on. If a
     * forcing mode refused that, arm B could not start a ladder at all and the floor would be
     * unmeasurable.
     */
    @Test
    void nothingOwnsANewThingSoBothArmsLetItThrough() {
        ArtifactAffinity a = ArtifactAffinity.forTest();
        assertEquals(Routing.ANYONE, a.decide(WishAffinity.SAME, "w1", "zippy", 0));
        assertEquals(Routing.ANYONE, a.decide(WishAffinity.DIFFERENT, "w1", "zippy", 0));
        assertEquals(Routing.ANYONE, a.decide(WishAffinity.SAME, "w1", "", 0),
                "a wish that names nothing at all is a creation too");
    }

    // ================= the starvation rule =================

    /**
     * A preference is a HOLD, not a lock — and it says so when it gives up.
     *
     * <p>Forcing SAME makes a wish wait for one specific worker; forcing DIFFERENT with a one-worker
     * pool makes it wait for a worker that does not exist. Both wedge, and a wedged wish is a child
     * staring at sparkles that never resolve.
     */
    @Test
    void aHeldWishIsEventuallyGivenToWhoeverIsFree() {
        ArtifactAffinity a = owned("zippy", "w1");
        assertFalse(a.decide(WishAffinity.SAME, "w2", "zippy", ArtifactAffinity.HOLD_MS - 1).take(),
                "still inside the hold");
        Routing fell = a.decide(WishAffinity.SAME, "w2", "zippy", ArtifactAffinity.HOLD_MS);
        assertEquals(Routing.FALLBACK, fell);
        assertTrue(fell.take(), "a control may never wedge a child's wish");
    }

    /**
     * The honest half. A wish that fell back was NOT granted under the arm it is labelled with, and
     * averaging it in would report a result the run never measured.
     */
    @Test
    void aFallbackAdmitsTheArmDidNotHold() {
        ArtifactAffinity a = owned("zippy", "w1");
        assertFalse(a.decide(WishAffinity.SAME, "w2", "zippy", ArtifactAffinity.HOLD_MS).held());
        assertFalse(a.decide(WishAffinity.DIFFERENT, "w1", "zippy", ArtifactAffinity.HOLD_MS).held());
        // Every other outcome DID honour the mode it was asked for.
        assertTrue(a.decide(WishAffinity.SAME, "w1", "zippy", 0).held());
        assertTrue(a.decide(WishAffinity.DIFFERENT, "w2", "zippy", 0).held());
        assertTrue(a.decide(WishAffinity.SAME, "w2", "zippy", 0).held());
    }

    /**
     * A caller with no identity is taken AND flagged. Refusing it would wedge the paths that have no
     * worker bound (a direct call, a unit test); letting it through silently would mislabel the wish
     * as evidence for an arm it was never routed by.
     */
    @Test
    void anAnonymousCallerIsLetThroughButNeverCountsAsEvidence() {
        ArtifactAffinity a = owned("zippy", "w1");
        Routing r = a.decide(WishAffinity.SAME, "", "zippy", 0);
        assertTrue(r.take());
        assertFalse(r.held());
        assertFalse(a.decide(WishAffinity.DIFFERENT, null, "zippy", 0).held());
    }

    // ================= the ownership map =================

    @Test
    void ownershipIsRememberedCaseAndWhitespaceInsensitively() {
        ArtifactAffinity a = owned("Zippy", "w1");
        assertEquals("w1", a.ownerOf(" zippy "));
        assertEquals("w1", a.ownerOf("ZIPPY"));
        assertEquals("", a.ownerOf("spikor"));
        assertEquals("", a.ownerOf(""));
    }

    @Test
    void theMostRecentWorkerOwnsIt() {
        ArtifactAffinity a = owned("zippy", "w1");
        a.remember("zippy", "w2");
        assertEquals("w2", a.ownerOf("zippy"));
        assertEquals(Routing.OWNER, a.decide(WishAffinity.SAME, "w2", "zippy", 0));
        assertEquals(Routing.WAIT_FOR_OWNER, a.decide(WishAffinity.SAME, "w1", "zippy", 0));
    }

    @Test
    void aBlankArtifactOrWorkerIsNotRemembered() {
        ArtifactAffinity a = ArtifactAffinity.forTest();
        a.remember("", "w1");
        a.remember("zippy", "");
        a.remember("zippy", null);
        assertEquals(0, a.size());
    }

    /**
     * Eviction is oldest-FIRST, and re-remembering counts as use.
     *
     * <p>A plain {@code put} on a LinkedHashMap keeps the original insertion position, so the thing
     * being actively refined — the only artifact whose ownership anyone cares about — would be the
     * one evicted.
     */
    @Test
    void theMapIsBoundedAndEvictsWhatNobodyHasTouched() {
        // THE ORDER OF THESE THREE STEPS IS THE TEST. An earlier cut touched Zippy on the last
        // statement of the filling loop, which re-inserted it AFTER the only eviction had already
        // happened — so it passed with the recency fix removed and was not a gate at all. Zippy must
        // be touched in the MIDDLE and then buried, or nothing distinguishes "kept because it was
        // used" from "kept because it was added last".
        int half = ArtifactAffinity.MAX_REMEMBERED / 2;
        int overshoot = 8;
        ArtifactAffinity a = ArtifactAffinity.forTest();
        a.remember("zippy", "w1");
        for (int i = 0; i < half; i++) {
            a.remember("filler" + i, "w9");
        }
        a.remember("zippy", "w1");            // still being worked on: now the most recent thing
        for (int i = half; i < ArtifactAffinity.MAX_REMEMBERED + overshoot; i++) {
            a.remember("filler" + i, "w9");   // …and now buried well past the cap
        }

        assertEquals(ArtifactAffinity.MAX_REMEMBERED, a.size(), "the map is bounded");
        assertEquals("w1", a.ownerOf("zippy"),
                "the actively-refined thing must survive — it is the only artifact whose ownership "
                        + "anyone cares about");
        assertEquals("", a.ownerOf("filler0"), "and the oldest untouched ones are what went");
    }

    @Test
    void clearingForgetsEverything() {
        ArtifactAffinity a = owned("zippy", "w1");
        a.clear();
        assertEquals("", a.ownerOf("zippy"));
        assertEquals(Routing.ANYONE, a.decide(WishAffinity.SAME, "w2", "zippy", 0));
    }

    // ───: was the arm ANSWERABLE AT ALL? ──────────────────────────────────────────────────

    @Test
    void aPoolOfOneCannotSatisfyTheDifferentArm() {
        // The measured failure: two class-5 arm-B cells came up with one worker and routed
        // `anyone x5` and `fallback x4`, and both reported PASS 5/5 with perfect continuity. With
        // one worker the only worker IS the owner, so STRANGER is unreachable by construction.
        ArtifactAffinity a = owned("zippy", "w1");
        assertEquals(Routing.WAIT_FOR_STRANGER, a.decide(WishAffinity.DIFFERENT, "w1", "zippy", 0));
        assertEquals(Routing.FALLBACK,
                a.decide(WishAffinity.DIFFERENT, "w1", "zippy", ArtifactAffinity.HOLD_MS),
                "…and after the hold it is simply given away");
        assertEquals(1, a.workersSeen(), "one worker: arm B could never have been answered");
    }

    @Test
    void theCensusCountsEveryWorkerThatASKED_notOnlyTheOnesThatOwned() {
        // Counting owners would report 1 for exactly the pool this is meant to detect: a refused
        // worker is still a worker that EXISTED, and arm B needs nothing more than that.
        ArtifactAffinity a = owned("zippy", "w1");
        a.decide(WishAffinity.SAME, "w2", "zippy", 0);        // refused — but it is out there
        assertEquals("w1", a.ownerOf("zippy"), "w2 never owned anything");
        assertEquals(2, a.workersSeen(), "and yet the pool is two, which is what arm B needs");
    }

    @Test
    void theCensusIgnoresAWorkerWithNoIdentity() {
        // A direct call or a unit test has no worker id and is already handled as FALLBACK. Letting
        // it inflate the pool would report an answerable arm on a world with one real agent.
        ArtifactAffinity a = owned("zippy", "w1");
        a.decide(WishAffinity.DIFFERENT, "", "zippy", 0);
        a.decide(WishAffinity.DIFFERENT, null, "zippy", 0);
        assertEquals(1, a.workersSeen());
    }

    @Test
    void theCensusIsClearedBetweenWorlds() {
        // Same rule as ownership: one world's pool must never vouch for another world's arm.
        ArtifactAffinity a = owned("zippy", "w1");
        a.decide(WishAffinity.DIFFERENT, "w2", "zippy", 0);
        assertEquals(2, a.workersSeen());
        a.clear();
        assertEquals(0, a.workersSeen());
    }
}
