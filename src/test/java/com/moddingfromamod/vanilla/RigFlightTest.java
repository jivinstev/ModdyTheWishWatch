package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The pure half 's flight: a velocity towards where it is going. */
class RigFlightTest {

    @Test
    void fromRestItTurnsAThirdOfTheWayToFullSpeedTowardsTheTarget() {
        double[] v = RigFlight.velocity(0, 0, 0, 10, 0, 0, 0, 0, 0, 1.0);
        assertEquals(RigFlight.BASE_SPEED * RigFlight.TURN, v[0], 1e-9);
        assertEquals(0, v[1], 1e-9);
        assertEquals(0, v[2], 1e-9);
    }

    @Test
    void heldLongEnoughItReachesTheSpeedItWasAskedFor() {
        double[] v = {0, 0, 0};
        for (int i = 0; i < 60; i++) {
            v = RigFlight.velocity(0, 0, 0, 0, 10, 0, v[0], v[1], v[2], 1.2);
        }
        assertEquals(RigFlight.BASE_SPEED * 1.2, v[1], 1e-6);
    }

    @Test
    void itOutweighsTheBatsOwnNudge() {
        // The bat's own AI moves velocity a tenth of the way to its random spot; ours a third
        // of the way to where it was sent. Flying straight away from ours, ours still wins.
        double[] v = {-RigFlight.BASE_SPEED, 0, 0};
        v = RigFlight.velocity(0, 0, 0, 10, 0, 0, v[0], v[1], v[2], 1.0);
        assertTrue(v[0] > -RigFlight.BASE_SPEED, "no turn towards the target");
    }

    @Test
    void arrivedItSlowsToAHover() {
        double[] v = RigFlight.velocity(0, 0, 0, 1, 0, 0, 0.3, 0, 0, 1.0);
        assertTrue(v[0] < 0.3 && v[0] > 0, "it should ease off, not stop dead or keep going");
        assertTrue(RigFlight.arrived(0, 0, 0, 1, 0.5, 0.5));
        assertFalse(RigFlight.arrived(0, 0, 0, 2, 0, 0));
    }

    @Test
    void aNegativeSpeedIsNoSpeed() {
        double[] v = RigFlight.velocity(0, 0, 0, 10, 0, 0, 0, 0, 0, -3);
        assertEquals(0, v[0], 1e-9);
    }
}
