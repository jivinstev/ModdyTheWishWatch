package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** A camera for the wishes that need one, sized by their camera time. */
class CameraNeedTest {

    private static final long T0 = 1_000_000L;

    private static UUID id() {
        return UUID.randomUUID();
    }

    @Test
    void aSubmittedWishWantsACameraBeforeItsKindIsKnown() {
        var n = new CameraNeed();
        UUID w = id();
        n.open(w, T0);
        var d = n.demand(List.of(w), T0);
        assertEquals(1, d.wanting());
        assertEquals(1, d.pending());
        assertEquals(CameraNeed.PENDING_PRIOR_S, d.seconds());
        assertEquals(CameraNeed.Phase.PENDING, n.phase(w));
    }

    /** The long-planning case: a wish that takes minutes to declare its kind never lets the camera go. */
    @Test
    void aLongPlanNeverReleasesTheCameraByTimeAlone() {
        var n = new CameraNeed();
        UUID w = id();
        n.open(w, T0);
        for (long t = T0; t < T0 + 10 * 60_000L; t += 15_000L) {
            assertEquals(1, n.demand(List.of(w), t).wanting(), "still wanted at +" + (t - T0) / 1000 + " s");
        }
    }

    @Test
    void anItemOrAQuestionHoldsOneCameraButPricesNoCameraWork() {
        // it used to RELEASE the camera here, and then photograph at the
        // reveal anyway — a 10 s resume for the "teleport me to a far away plains" wish.
        for (String kind : List.of("item", "tool", "weapon", "other", "", "rule", "datapack")) {
            var n = new CameraNeed();
            UUID w = id();
            n.open(w, T0);
            n.declared(w, kind, T0 + 5_000);
            var d = n.demand(List.of(w), T0 + 5_000);
            assertEquals(1, d.wanting(), kind + ": a wish in flight keeps a camera awake");
            assertEquals(1, d.none(), kind);
            assertEquals(0.0, d.seconds(), kind + ": and prices no camera work");
            assertFalse(n.wantsCamera(w), kind + ": its photograph still takes no camera (an item is drawn)");
            assertEquals(1, CameraNeed.bodiesFor(d, 4), kind);
        }
    }

    @Test
    void eachCameraClassIsPricedByItsShots() {
        record Case(String kind, double s) { }
        for (Case c : List.of(new Case("creature", CameraNeed.CREATURE_S), new Case("mob", CameraNeed.CREATURE_S),
                new Case("building", CameraNeed.BUILDING_S), new Case("machine", CameraNeed.BUILDING_S),
                new Case("train", CameraNeed.BUILDING_S), new Case("picture", CameraNeed.PICTURE_S),
                new Case("event", CameraNeed.EVENT_S))) {
            var n = new CameraNeed();
            UUID w = id();
            n.open(w, T0);
            n.declared(w, c.kind(), T0);
            var d = n.demand(List.of(w), T0);
            assertEquals(1, d.needing(), c.kind());
            assertEquals(c.s(), d.seconds(), c.kind());
        }
    }

    @Test
    void aNoneWishThatMakesACreatureOrAsksForAPhotoNeedsACameraAfterAll() {
        var n = new CameraNeed();
        UUID a = id();
        n.open(a, T0);
        n.declared(a, "item", T0);
        n.made(a, "creature", T0 + 1);
        assertEquals(CameraNeed.Phase.NEEDS, n.phase(a));
        assertEquals("creature", n.cls(a));

        UUID b = id();
        n.open(b, T0);
        n.declared(b, "other", T0);
        n.photographing(b, T0 + 1);
        assertEquals(CameraNeed.Phase.NEEDS, n.phase(b));
        assertEquals(CameraNeed.PHOTO_S, n.demand(List.of(b), T0 + 1).seconds(), "one plain shot");
    }

    @Test
    void aWishThatNeedsACameraNeverFallsBackToNone() {
        var n = new CameraNeed();
        UUID w = id();
        n.open(w, T0);
        n.declared(w, "creature", T0);
        n.declared(w, "item", T0 + 1);           // a re-plan
        n.made(w, "item", T0 + 2);                // it also made an item
        n.made(w, "datapack", T0 + 3);
        assertEquals(CameraNeed.Phase.NEEDS, n.phase(w));
        assertEquals("creature", n.cls(w));
        n.made(w, "build", T0 + 4);               // the dearer class wins
        assertEquals("building", n.cls(w));
        n.declared(w, "picture", T0 + 5);         // and is never cheapened
        assertEquals("building", n.cls(w));
    }

    @Test
    void madeKindsThatSayNothingAboutTheCameraLeaveAPendingWishPending() {
        var n = new CameraNeed();
        UUID w = id();
        n.open(w, T0);
        n.made(w, "datapack", T0);
        n.made(w, null, T0);
        assertEquals(CameraNeed.Phase.PENDING, n.phase(w));
        n.made(w, "item", T0);
        assertEquals(CameraNeed.Phase.NONE, n.phase(w), "a wish whose first thing is an item");
    }

    @Test
    void aWedgedWishStopsHoldingTheCameraAfterTheCap() {
        var n = new CameraNeed();
        UUID w = id();
        n.open(w, T0);
        assertEquals(1, n.demand(List.of(w), T0 + CameraNeed.STALE_MS).wanting());
        var d = n.demand(List.of(w), T0 + CameraNeed.STALE_MS + 1);
        assertEquals(0, d.wanting());
        assertEquals(1, d.stale());
    }

    @Test
    void theWishBarsAreTheTruthAboutInFlight() {
        var n = new CameraNeed();
        UUID ended = id();
        UUID neverOpened = id();
        n.open(ended, T0);
        n.declared(ended, "building", T0);
        var d = n.demand(Set.of(neverOpened), T0);
        assertEquals(1, d.wanting(), "a wish in flight that nobody opened is PENDING, as before");
        assertEquals(1, d.pending());
        assertNull(n.phase(ended), "an entry whose wish ended by another path is pruned, not leaked");
        assertEquals(CameraNeed.Demand.NONE, n.demand(List.of(), T0));
    }

    @Test
    void closeForgetsTheWish() {
        var n = new CameraNeed();
        UUID w = id();
        n.open(w, T0);
        n.close(w);
        assertNull(n.phase(w));
        assertTrue(n.wantsCamera(w), "unknown means a camera may be taken — the old behaviour");
        n.open(null, T0);
        n.close(null);
        n.declared(null, "creature", T0);
    }

    @Test
    void openIsIdempotentAndKeepsTheFirstClock() {
        var n = new CameraNeed();
        UUID w = id();
        n.open(w, T0);
        n.declared(w, "creature", T0 + 1);
        n.open(w, T0 + 2);
        assertEquals(CameraNeed.Phase.NEEDS, n.phase(w), "a second open never resets a known kind");
        assertEquals(0, n.demand(List.of(w), T0 + CameraNeed.STALE_MS + 1).wanting(), "clock from the first open");
    }

    /** The calibration point: one body kept up with three creature wishes at their old 14 s (CAPACITY §4.2). */
    @Test
    void bodiesAreCameraSecondsOverOneBodysShare() {
        assertEquals(42, CameraNeed.PER_BODY_S, 1e-9, "the measured share does not move with the costs");
        assertEquals(0, CameraNeed.bodiesFor(CameraNeed.Demand.NONE, 4));
        assertEquals(1, CameraNeed.bodiesFor(demandOf("creature", 1), 4));
        assertEquals(1, CameraNeed.bodiesFor(demandOf("creature", 5), 4), "a faster shot fits five on a body");
        assertEquals(2, CameraNeed.bodiesFor(demandOf("creature", 6), 4));
        assertEquals(2, CameraNeed.bodiesFor(demandOf("creature", 10), 4), "10 creatures: 2 bodies (4 before #1293)");
        assertEquals(3, CameraNeed.bodiesFor(demandOf("building", 10), 4), "a building is dearer");
        assertEquals(3, CameraNeed.bodiesFor(demandOf("event", 10), 16));
        assertEquals(1, CameraNeed.bodiesFor(demandOf("picture", 14), 4));
        assertEquals(1, CameraNeed.bodiesFor(demandOf("item", 10), 4), "ten item wishes keep one camera awake and no more (#1604)");
        assertEquals(1, CameraNeed.bodiesFor(demandOf("creature", 1), 0), "a cap below one is one");
        assertEquals(4, CameraNeed.bodiesFor(demandOf("building", 30), 4), "capped");
    }

    @Test
    void aMixIsTheSumOfItsParts() {
        var n = new CameraNeed();
        var ids = new java.util.ArrayList<UUID>();
        for (String k : List.of("creature", "building", "item", "item", "picture")) {
            UUID w = id();
            n.open(w, T0);
            n.declared(w, k, T0);
            ids.add(w);
        }
        UUID pending = id();
        n.open(pending, T0);
        ids.add(pending);
        var d = n.demand(ids, T0);
        assertEquals(6, d.wanting(), "every wish in flight holds the camera (#1604)");
        assertEquals(2, d.none());
        assertEquals(CameraNeed.CREATURE_S + CameraNeed.BUILDING_S + CameraNeed.PICTURE_S
                + CameraNeed.PENDING_PRIOR_S, d.seconds());
        assertEquals(1, CameraNeed.bodiesFor(d, 4), "29 s of camera work: one body");
    }

    @Test
    void concurrentCallsNeverLoseAWish() throws Exception {
        var n = new CameraNeed();
        var ids = new java.util.concurrent.CopyOnWriteArrayList<UUID>();
        var threads = new java.util.ArrayList<Thread>();
        for (int t = 0; t < 8; t++) {
            threads.add(new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    UUID w = UUID.randomUUID();
                    n.open(w, T0);
                    n.declared(w, i % 2 == 0 ? "creature" : "item", T0);
                    n.made(w, "creature", T0);
                    ids.add(w);
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        var d = n.demand(ids, T0);
        assertEquals(4000, d.wanting(), "every wish ended up NEEDS");
        assertEquals(4000 * CameraNeed.CREATURE_S, d.seconds());
    }

    private static CameraNeed.Demand demandOf(String kind, int count) {
        var n = new CameraNeed();
        var ids = new java.util.ArrayList<UUID>();
        for (int i = 0; i < count; i++) {
            UUID w = id();
            n.open(w, T0);
            n.declared(w, kind, T0);
            ids.add(w);
        }
        return n.demand(ids, T0);
    }

    // ── a wish that puts back something kept takes no camera and no photograph ──

    @Test
    void aReplayLetsTheCameraGoEvenAfterItsPlanAskedForOne() {
        var n = new CameraNeed();
        UUID w = id();
        n.open(w, T0);
        n.declared(w, "creature", T0 + 1);
        assertEquals(CameraNeed.Phase.NEEDS, n.phase(w));
        n.reused(w, null, null, T0 + 2);         // run_command: wishmob spawn pet_fox
        assertEquals(CameraNeed.Phase.NONE, n.phase(w));
        assertTrue(n.reuseOnly(w));
        n.photographing(w, T0 + 3);
        assertEquals(CameraNeed.Phase.NONE, n.phase(w), "its photograph does not wake one either");
        var d = n.demand(List.of(w), T0 + 3);
        assertEquals(1, d.wanting(), "in flight, so it keeps the camera awake (#1604)");
        assertEquals(0.0, d.seconds(), "…but asks for no camera work");
    }

    /** NONE wishes keep ONE body awake and never a second; a wedged one stops holding it. */
    @Test
    void wishesThatNeedNoCameraNeverAskForASecondBodyAndAWedgedOneLetsGo() {
        var n = new CameraNeed();
        var ids = new java.util.ArrayList<UUID>();
        for (int i = 0; i < 20; i++) {
            UUID w = id();
            n.open(w, T0);
            n.declared(w, i % 2 == 0 ? "item" : "other", T0);
            ids.add(w);
        }
        var d = n.demand(ids, T0 + 1);
        assertEquals(20, d.wanting());
        assertEquals(1, CameraNeed.bodiesFor(d, 4), "twenty item wishes: one camera awake, never four");
        var late = n.demand(ids, T0 + CameraNeed.STALE_MS + 1);
        assertEquals(0, late.wanting(), "wedged past the cap, they stop holding it");
        assertEquals(20, late.stale());
        assertEquals(0, CameraNeed.bodiesFor(late, 4));
    }

    @Test
    void theKeptThingItselfIsNotNewWhenItsToolRecordsIt() {
        var n = new CameraNeed();
        UUID w = id();
        n.open(w, T0);
        n.reused(w, "event", "volcano_6_1", T0 + 1);  // stage_event, the same design again
        n.made(w, "event", "volcano_6_1", T0 + 2);    // McpProtocol's record of it
        assertTrue(n.reuseOnly(w));
        assertEquals(CameraNeed.Phase.NONE, n.phase(w));
    }

    @Test
    void somethingNewBesideItStillGetsItsCamera() {
        var n = new CameraNeed();
        UUID w = id();
        n.open(w, T0);
        n.reused(w, "contraption", "flour_mill", T0 + 1);
        n.made(w, "contraption", "flour_mill", T0 + 2);
        n.made(w, "creature", "miller_cat", T0 + 3);   // and a new pet beside it
        assertFalse(n.reuseOnly(w));
        assertEquals(CameraNeed.Phase.NEEDS, n.phase(w));
        // a replay AFTER something new never takes its camera away
        UUID v = id();
        n.open(v, T0);
        n.made(v, "creature", "dragon", T0 + 1);
        n.reused(v, null, null, T0 + 2);
        assertFalse(n.reuseOnly(v));
        assertEquals(CameraNeed.Phase.NEEDS, n.phase(v));
    }

    @Test
    void closingForgetsTheReuse() {
        var n = new CameraNeed();
        UUID w = id();
        n.reused(w, null, null, T0);
        n.close(w);
        assertFalse(n.reuseOnly(w));
    }

    // ── 2026-10-29: what "in flight" means for the camera ──────────────────────────────────────

    @Test
    void aLiveWishWithNoBarIsStillInFlight() {
        UUID canary = id();
        assertEquals(Set.of(canary), CameraNeed.inFlight(Set.of(), List.of(canary), w -> false),
                "the canary's bar was taken down by the ticker (its stand-in is not in the player list), "
                        + "but Marshal still holds the wish live, so a camera is still wanted");
    }

    @Test
    void barsAndLiveWishesAreUnitedOnce() {
        UUID a = id();
        UUID b = id();
        assertEquals(Set.of(a, b), CameraNeed.inFlight(Set.of(a), List.of(a, b), w -> false));
    }

    @Test
    void aWishOverForTheChildHoldsNoCameraThroughItsGrading() {
        UUID done = id();
        assertTrue(CameraNeed.inFlight(Set.of(), List.of(done), w -> w.equals(done)).isEmpty(),
                "its bar came down at \"All done\"; being live in Marshal while graded must not hold a camera");
    }

    @Test
    void nothingAtAllIsNothing() {
        assertTrue(CameraNeed.inFlight(null, null, null).isEmpty());
    }
}
