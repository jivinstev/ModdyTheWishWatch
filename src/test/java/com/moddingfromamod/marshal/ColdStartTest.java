package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The arithmetic behind "is the pool up before he gets to his watch?". */
class ColdStartTest {

    private static ColdStart pool(long... readyAt) {
        ColdStart c = new ColdStart();
        c.poolStarting(0);
        for (int i = 0; i < readyAt.length; i++) {
            c.workerReady("session-" + i, readyAt[i]);
        }
        return c;
    }

    @Test
    void oneWorkerIsJustHowLongItTook() {
        var c = pool(40_000);
        assertEquals(40_000, c.firstReadyMs());
        assertEquals(40_000, c.allReadyMs());
        assertEquals(0, c.extraForTheRestMs(), "there is no rest");
    }

    @Test
    void bootingInParallelCostsAlmostNothingExtra() {
        // The good case, and the one the design is betting on: four workers up within a few seconds
        // of each other, so more slots are close to free.
        var c = pool(40_000, 41_000, 42_000, 43_000);
        assertEquals(40_000, c.firstReadyMs());
        assertEquals(3_000, c.extraForTheRestMs());
        assertTrue(c.comfortable());
    }

    @Test
    void bootingInSeriesIsTheFindingThatWouldChangeSomething() {
        // The bad case: each worker costs a full start-up, so a child's SECOND wish waits on a boot
        // rather than on an agent — and "we start the pool early so he never notices" stops holding.
        var c = pool(40_000, 80_000, 120_000, 160_000);
        assertEquals(40_000, c.firstReadyMs());
        assertEquals(120_000, c.extraForTheRestMs());
        assertFalse(c.comfortable(),
                "four serialised boots must not be reported as a comfortable start-up");
    }

    @Test
    void aWorkerSpeakingTwiceIsStillOneWorker() {
        // workerReady is called on EVERY message that worker sends, because it is wired at the seam
        // they all pass through. Only the first can be its readiness.
        ColdStart c = new ColdStart();
        c.poolStarting(0);
        c.workerReady("a", 40_000);
        c.workerReady("a", 90_000);
        assertEquals(1, c.ready());
        assertEquals(40_000, c.allReadyMs(), "a later message must not re-date the boot");
    }

    @Test
    void nothingIsReportedBeforeThePoolIsAskedToStart() {
        // Otherwise a session from a previous world would be measured against a start that never
        // happened, and produce a negative or enormous wait.
        ColdStart c = new ColdStart();
        c.workerReady("stale", 5_000);
        assertEquals(0, c.ready());
        assertEquals(-1, c.firstReadyMs());
        assertFalse(c.comfortable(), "no data is not a comfortable start-up");
    }

    @Test
    void restartingThePoolForgetsTheOldOne() {
        var c = pool(40_000);
        c.poolStarting(100_000);
        c.workerReady("new", 130_000);
        assertEquals(1, c.ready());
        assertEquals(30_000, c.firstReadyMs());
    }

    @Test
    void theReportSaysWhatAGrownUpNeedsToKnow() {
        String r = pool(40_000, 43_000).report();
        assertTrue(r.contains("2 worker"), r);
        assertTrue(r.contains("40s") && r.contains("43s"), r);
        assertTrue(r.contains("3s"), "the cost of the extra workers is the point: " + r);
    }
}
