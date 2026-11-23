package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Gate A: a creature is spawned where the child can see it, never around him. */
class SpawnSpotTest {

    private static double flat(SpawnSpot.Spot s, double px, double pz) {
        return Math.hypot(s.x() - px, s.z() - pz);
    }

    @Test
    void theGiantSeaLeaperStandsBeyondItsOwnReach() {
        // scale 4, a Spikor-sized sculpt reaches ~10 blocks end to end.
        double stand = SpawnSpot.standOff(8.0, 10.2);
        assertTrue(stand >= 10.2 + SpawnSpot.CLEARANCE);
        for (int i = 0; i < 5; i++) {
            SpawnSpot.Spot s = SpawnSpot.choose(0, 64, 0, 0f, null, stand, 10.2, i);
            assertTrue(flat(s, 0, 0) >= stand - 1e-9,
                    "creature " + i + " stands " + flat(s, 0, 0) + " from him, inside its reach");
        }
    }

    @Test
    void aSmallCreatureStillStandsFarEnoughToBeFramed() {
        assertEquals(Framing.distanceFor(1.0), SpawnSpot.standOff(1.0, 0.5), 1e-9);
        assertTrue(SpawnSpot.standOff(1.0, 0.5) >= Framing.MIN_DISTANCE);
    }

    @Test
    void itStandsWhereHeIsLooking() {
        SpawnSpot.Spot south = SpawnSpot.choose(0, 64, 0, 0f, null, 5, 1, 0);
        assertEquals(5, south.z(), 1e-9);
        assertEquals(0, south.x(), 1e-9);
        SpawnSpot.Spot east = SpawnSpot.choose(0, 64, 0, -90f, null, 5, 1, 0);
        assertEquals(5, east.x(), 1e-9);
    }

    @Test
    void anAimedSpotWinsWhenItIsFarEnough() {
        SpawnSpot.Spot aimed = new SpawnSpot.Spot(0, 63, 16);
        SpawnSpot.Spot s = SpawnSpot.choose(0, 69, 0, 90f, aimed, 5, 1, 0);
        assertEquals(aimed, s, "the wish manifests where it was aimed");
    }

    @Test
    void anAimedSpotInsideItsReachIsPushedBack() {
        // The child aimed two blocks away at a creature that reaches eight.
        SpawnSpot.Spot aimed = new SpawnSpot.Spot(0, 63, 2);
        SpawnSpot.Spot s = SpawnSpot.choose(0, 69, 0, 0f, aimed, 9.5, 8, 0);
        assertTrue(flat(s, 0, 0) >= 9.5 - 1e-9);
        assertEquals(63, s.y(), 1e-9, "at the aimed spot's height, not his");
    }

    @Test
    void aHerdStandsInDifferentPlaces() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            SpawnSpot.Spot s = SpawnSpot.choose(0, 64, 0, 0f, null, 5, 2, i);
            seen.add(Math.round(s.x() * 10) + "," + Math.round(s.z() * 10));
        }
        assertEquals(6, seen.size());
    }
}
