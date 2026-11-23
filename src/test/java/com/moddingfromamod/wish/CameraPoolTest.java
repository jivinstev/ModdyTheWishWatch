package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Which camera a wish gets, and when the pool grows or shrinks. */
class CameraPoolTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    private static CameraPool.Cam cam(UUID holder, int depth, int pos) {
        return new CameraPool.Cam(UUID.randomUUID(), holder, depth, pos);
    }

    @Test
    void namesShareOneStemAndFitMinecraftsLimit() {
        assertEquals(List.of("Moddy-Camera", "Moddy-Camera-2", "Moddy-Camera-3"),
                CameraPool.names("Moddy-Camera", 3));
        for (String n : CameraPool.names("AVeryLongCamName", 4)) {
            assertTrue(n.length() <= CameraPool.MAX_NAME, n);
        }
        assertEquals(List.of(), CameraPool.names("", 2), "no camera name means no pool");
        assertEquals(CameraPool.MAX, CameraPool.names("c", 99).size(), "capped at MAX");
    }

    /** The per-world cap is a setting (1–16, default 4), never a recompile. */
    @Test
    void theCapIsASettingWithinHardBounds() {
        assertEquals(CameraPool.DEFAULT_MAX, CameraPool.maxFromProperty(null));
        assertEquals(CameraPool.DEFAULT_MAX, CameraPool.maxFromProperty(" "));
        assertEquals(CameraPool.DEFAULT_MAX, CameraPool.maxFromProperty("lots"));
        assertEquals(10, CameraPool.maxFromProperty("10"));
        assertEquals(CameraPool.HARD_MAX, CameraPool.maxFromProperty("99"));
        assertEquals(1, CameraPool.maxFromProperty("0"));
        assertEquals(expectedNames(10), CameraPool.names("Moddy-Camera", 10, 10));
        assertEquals(16, CameraPool.names("c", 99, CameraPool.HARD_MAX).size());
        for (String n : CameraPool.names("AVeryLongCamName", 16, CameraPool.HARD_MAX)) {
            assertTrue(n.length() <= CameraPool.MAX_NAME, n);
        }
    }

    private static List<String> expectedNames(int n) {
        List<String> out = new java.util.ArrayList<>(List.of("Moddy-Camera"));
        for (int i = 2; i <= n; i++) {
            out.add("Moddy-Camera-" + i);
        }
        return out;
    }

    /** The bug the pool exists for: before it, the second wish queued on body one while two idled. */
    @Test
    void aSecondWishGetsTheIdleBodyNotALine() {
        var cams = List.of(cam(A, 0, -1), cam(null, 0, -1));
        assertEquals(1, CameraPool.choose(B, cams));
    }

    @Test
    void aBurstStaysOnTheBodyItHolds() {
        var cams = List.of(cam(null, 0, -1), cam(A, 0, -1));
        assertEquals(1, CameraPool.choose(A, cams), "an orbit must not hop bodies mid-burst");
    }

    @Test
    void aWaiterLeavesItsLineOnlyForAnIdleBody() {
        var busy = List.of(cam(A, 1, 1), cam(C, 0, -1));
        assertEquals(0, CameraPool.choose(B, busy), "both busy: keep its place");
        var freed = List.of(cam(A, 1, 1), cam(null, 0, -1));
        assertEquals(1, CameraPool.choose(B, freed), "an idle body beats second in line");
    }

    @Test
    void newWaitersSpreadToTheShortestLine() {
        var cams = List.of(cam(A, 2, -1), cam(B, 0, -1));
        assertEquals(1, CameraPool.choose(C, cams));
    }

    @Test
    void anAnonymousCaptureAlwaysSharesTheFirstBody() {
        var cams = List.of(cam(A, 1, -1), cam(null, 0, -1));
        assertEquals(0, CameraPool.choose(null, cams));
        assertEquals(-1, CameraPool.choose(A, List.of()));
    }

    private static CameraPool.State state(int running, int joined, boolean starting, int waiting,
                                          long waitingSince, long idleSince, int max) {
        return new CameraPool.State(running, joined, starting, waiting, waitingSince, idleSince, max);
    }

    @Test
    void itGrowsOnlyAfterAWishHasWaited() {
        long now = 100_000;
        assertEquals(CameraPool.Change.HOLD,
                CameraPool.decide(state(1, 1, false, 1, now - 1_000, 0, 2), now),
                "a moment of depth is not a measurement");
        assertEquals(CameraPool.Change.GROW,
                CameraPool.decide(state(1, 1, false, 1, now - CameraPool.GROW_AFTER_MS, 0, 2), now));
        assertEquals(CameraPool.Change.HOLD,
                CameraPool.decide(state(2, 2, false, 3, now - 60_000, 0, 2), now),
                "never past the cap, however long the line");
        assertEquals(CameraPool.Change.HOLD,
                CameraPool.decide(state(2, 1, true, 3, now - 60_000, 0, 3), now),
                "one body joining at a time");
        assertEquals(CameraPool.Change.HOLD,
                CameraPool.decide(state(1, 0, false, 3, now - 60_000, 0, 3), now),
                "body one is CameraLifecycle's, not the pool's");
    }

    @Test
    void itShrinksAnIdleExtraBodyButNeverTheFirst() {
        long now = 1_000_000;
        assertEquals(CameraPool.Change.SHRINK,
                CameraPool.decide(state(2, 2, false, 0, 0, now - CameraPool.SHRINK_AFTER_MS, 2), now));
        assertEquals(CameraPool.Change.HOLD,
                CameraPool.decide(state(2, 2, false, 0, 0, now - 10_000, 2), now));
        assertEquals(CameraPool.Change.HOLD,
                CameraPool.decide(state(1, 1, false, 0, 0, now - 10 * CameraPool.SHRINK_AFTER_MS, 2), now));
    }

    @Test
    void aLoweredCapShedsABodyOnlyWhenNobodyWaits() {
        long now = 1_000;
        assertEquals(CameraPool.Change.SHRINK,
                CameraPool.decide(state(3, 3, false, 0, 0, 0, 1), now));
        assertEquals(CameraPool.Change.HOLD,
                CameraPool.decide(state(3, 3, false, 1, now, 0, 1), now));
    }

    private static CameraPool.State want(int running, int joined, boolean starting, int waiting,
                                         long idleSince, int max, int wanted) {
        return new CameraPool.State(running, joined, starting, waiting, 0, idleSince, max, wanted);
    }

    /** Grow AHEAD of the queue, from the wishes' camera time (CameraNeed). */
    @Test
    void itGrowsWhenTheForecastAsksBeforeAnybodyHasQueued() {
        long now = 100_000;
        assertEquals(CameraPool.Change.GROW, CameraPool.decide(want(1, 1, false, 0, 0, 4, 2), now),
                "two bodies' worth of camera time, one body: grow now, not 3 s into a queue");
        assertEquals(CameraPool.Change.HOLD, CameraPool.decide(want(2, 2, false, 0, 0, 4, 2), now),
                "enough bodies");
        assertEquals(CameraPool.Change.GROW, CameraPool.decide(want(1, 1, true, 0, 0, 4, 3), now),
                "a body still joining does NOT hold the next the forecast wants (bench, 2026-10-03)");
        assertEquals(CameraPool.Change.GROW, CameraPool.decide(want(1, 0, true, 0, 0, 4, 2), now),
                "nor does body one still booting: it took 68 s on the bench, and body two waited it out");
        assertEquals(CameraPool.Change.HOLD, CameraPool.decide(want(2, 0, true, 0, 0, 4, 2), now),
                "two starting for two wanted: enough");
        assertEquals(CameraPool.Change.HOLD, CameraPool.decide(want(1, 0, true, 3, 0, 4, 0), now),
                "the queue's safety net still waits for a body to join");
        assertEquals(CameraPool.Change.HOLD, CameraPool.decide(want(0, 0, false, 0, 0, 4, 2), now),
                "body one is CameraLifecycle's, never the pool's");
        assertEquals(CameraPool.Change.HOLD, CameraPool.decide(want(4, 4, false, 0, 0, 4, 9), now),
                "never past the cap");
        assertEquals(CameraPool.Change.GROW, CameraPool.decide(want(2, 2, false, 0, 0, 3, 9), now),
                "up to the cap");
    }

    @Test
    void aBodyTheForecastStillWantsIsNeverShrunk() {
        long now = 1_000_000;
        long longIdle = now - CameraPool.SHRINK_AFTER_MS - 1;
        assertEquals(CameraPool.Change.HOLD, CameraPool.decide(want(2, 2, false, 0, longIdle, 4, 2), now),
                "nobody queued, but two bodies of work in flight");
        assertEquals(CameraPool.Change.SHRINK, CameraPool.decide(want(3, 3, false, 0, longIdle, 4, 2), now),
                "a body past the forecast, idle long enough");
        assertEquals(CameraPool.Change.HOLD,
                CameraPool.decide(want(3, 3, false, 0, now - 1_000, 4, 2), now), "not idle long enough");
        assertEquals(CameraPool.Change.SHRINK, CameraPool.decide(want(2, 2, false, 0, longIdle, 4, 0), now),
                "nothing wanted: back to body one, as before");
    }

    @Test
    void theQueueIsStillTheSafetyNetWhenTheForecastIsLow() {
        long now = 100_000;
        assertEquals(CameraPool.Change.GROW, CameraPool.decide(
                new CameraPool.State(1, 1, false, 2, now - CameraPool.GROW_AFTER_MS, 0, 4, 1), now));
        assertEquals(CameraPool.Change.HOLD, CameraPool.decide(
                new CameraPool.State(1, 1, false, 2, now - 1, 0, 4, 1), now));
    }
}
