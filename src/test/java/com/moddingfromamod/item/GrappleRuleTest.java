package com.moddingfromamod.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** the fling, flown through a player's own air physics, lands where the hook did. */
class GrappleRuleTest {

    @Test
    void theRooftopFromTheFilmIsReachedNotTheWallBelowIt() {
        // The first film: roof top 10.5 blocks ahead and 5 up. The drag-free rule reached 7.3.
        double[] v = GrappleRule.velocity(0, 0, 0, 0, 5, 10.5);
        assertNotNull(v);
        double[] at = GrappleRule.fly(v, GrappleRule.flightTicks(10.5));
        assertEquals(10.5, at[2], 0.05, "horizontal arrival");
        assertEquals(5 + GrappleRule.OVER, at[1], 0.05, "arrives just above the roof");
    }

    @Test
    void farAndLowAndHighTargetsAllArrive() {
        for (double[] target : new double[][]{{20, 0, 0}, {6, -3, 6}, {0, 12, 15}, {30, 2, 10}}) {
            double flat = Math.hypot(target[0], target[2]);
            double[] v = GrappleRule.velocity(0, 0, 0, target[0], target[1], target[2]);
            double speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            if (speed < GrappleRule.MAX_SPEED - 1e-9) {
                double[] at = GrappleRule.fly(v, GrappleRule.flightTicks(flat));
                assertEquals(target[0], at[0], 0.05);
                assertEquals(target[2], at[2], 0.05);
            }
            assertTrue(speed <= GrappleRule.MAX_SPEED + 1e-9, "capped");
        }
    }

    @Test
    void aStandingLaunchStillArrives() {
        double[] v = GrappleRule.velocity(0, 0, 0, 0, 5, 11.5, true);
        double[] at = GrappleRule.fly(v, GrappleRule.flightTicks(11.5), true);
        assertEquals(11.5, at[2], 0.05, "the ground's first-tick drag is paid for");
        assertEquals(5 + GrappleRule.OVER, at[1], 0.05);
    }

    @Test
    void nothingTooCloseOrTooFar() {
        assertNull(GrappleRule.velocity(0, 0, 0, 0.5, 0, 0.5));
        assertNull(GrappleRule.velocity(0, 0, 0, 60, 0, 0));
    }
}
