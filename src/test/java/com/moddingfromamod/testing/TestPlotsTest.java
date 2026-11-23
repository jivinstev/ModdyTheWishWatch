package com.moddingfromamod.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The lane table is only worth anything if something checks it.
 *
 * <p>A collision here does not look like a collision: it looks like an unrelated Create test
 * confidently reporting a product bug that does not exist. Cheaper to fail here.
 */
class TestPlotsTest {

    @Test
    void noTwoTestClassesShareALane() {
        assertEquals(List.of(), TestPlots.clashes());
    }

    @Test
    void everyLaneIsWideEnoughForAnythingBuiltInIt() {
        // A machine spans tens of blocks and the biggest corpus build is a few hundred. If a lane
        // were ever narrow enough for a build to reach the next one, this whole scheme is theatre.
        assertTrue(TestPlots.laneWidth() >= 1000,
                "lanes are " + TestPlots.laneWidth() + " apart, close enough for one test's build "
                        + "to reach another's");
    }

    @Test
    void sitesAreFarEnoughApartForTwoConcurrentTests() {
        // The reason this exists: tests in a batch run AT THE SAME TIME, so separating classes is
        // not enough. Six tests of one class stacked in a single column twenty blocks apart in Y
        // was measurably worse than no separation at all — they built on top of each other.
        assertTrue(TestPlots.siteWidth() >= 256,
                "sites are " + TestPlots.siteWidth() + " apart, close enough for two tests of the "
                        + "same class to build into each other while running concurrently");
        assertTrue(TestPlots.laneWidth() > TestPlots.siteWidth(),
                "a lane (" + TestPlots.laneWidth() + ") must hold more than one site ("
                        + TestPlots.siteWidth() + "), or a class's sites spill into the next class");
    }

    @Test
    void everyRegionIsNamedAfterSomethingReal() {
        for (TestPlots.Region r : TestPlots.all()) {
            assertTrue(r.owner().endsWith("GameTest"),
                    r.owner() + " does not name a test class, so nobody can tell whose lane it is");
        }
    }
}
