package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** #114: the rule for a confident gaze hit, and what an unsure one does to the spot. */
class AimConfidenceTest {

    private static List<AimConfidence.Look> held(float yaw, float pitch, int ticks) {
        List<AimConfidence.Look> out = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            out.add(new AimConfidence.Look(yaw, pitch));
        }
        return out;
    }

    @Test
    void aSteadySquareHitOnSolidGroundWithinReachIsConfident() {
        assertTrue(AimConfidence.confident(true, 0.5, 0.5, 60, 40, held(10, 60, 6)));
    }

    @Test
    void eachRuleCanSayNo() {
        assertFalse(AimConfidence.confident(false, 0.5, 0.5, 60, 40, held(10, 60, 6)), "not a solid face");
        assertFalse(AimConfidence.confident(true, 0.02, 0.5, 60, 40, held(10, 60, 6)), "grazing an edge");
        assertFalse(AimConfidence.confident(true, 0.5, 0.97, 60, 40, held(10, 60, 6)), "grazing the other edge");
        assertFalse(AimConfidence.confident(true, 0.5, 0.5, 5, 40, held(10, 60, 6)), "glancing along the face");
        assertFalse(AimConfidence.confident(true, 0.5, 0.5, 60, WishAim.REACH + 1, held(10, 60, 6)), "out of reach");
        assertFalse(AimConfidence.confident(true, 0.5, 0.5, 60, 40, held(10, 60, 2)), "too short a look");
    }

    @Test
    void aOneTickFlickIsNotAGaze() {
        List<AimConfidence.Look> looks = held(10, 20, 6);
        looks.add(new AimConfidence.Look(10, 65));   // swung down to the floor on the very tick of the press
        assertFalse(AimConfidence.steady(looks));
        List<AimConfidence.Look> wobble = held(179, 60, 5);
        wobble.add(new AimConfidence.Look(-179.5f, 61));   // across the yaw seam: two degrees, not 358
        assertTrue(AimConfidence.steady(wobble));
        assertEquals(-2f, AimConfidence.wrap(358f), 1e-6);
    }

    /** The world under a sky island's edge: a floor at y=10, the island at y=63 for x < 3. */
    private static final WishAim.World ISLAND_OVER_FLOOR = new WishAim.World() {
        @Override public WishAim.Cell at(int x, int y, int z) {
            if (y == 9 || (x < 3 && y == 62)) {
                return WishAim.Cell.SOLID;
            }
            return WishAim.Cell.AIR;
        }

        @Override public int bottom() {
            return 0;
        }

        @Override public int top() {
            return 200;
        }
    };

    @Test
    void aSureHitFarBelowSettlesThereAndAnUnsureOneIsHeldLevel() {
        // he stands on the island at its edge, looking down past it at the floor 53 blocks below
        WishAim.Snapshot me = new WishAim.Snapshot(1.5, 63, 0.5, 64.6, -90f, 70f);
        WishAim.Hit floor = new WishAim.Hit(20, 9, 0, 0, 1, 0);
        WishAim.Spot sure = WishAim.aim(me, floor, ISLAND_OVER_FLOOR, true);
        assertEquals(10, sure.y(), "confident: where he looked, however far down: " + sure);
        WishAim.Spot unsure = WishAim.aim(me, floor, ISLAND_OVER_FLOOR, false);
        assertEquals(63, unsure.y(), "unsure: held level with the ground under him, as a miss: " + unsure);
        assertTrue(unsure.how().startsWith("an unsure hit"), unsure.how());
        // an unsure hit close below is still a hit: nothing to hold level
        WishAim.Spot near = WishAim.aim(me, new WishAim.Hit(2, 62, 0, 0, 1, 0), ISLAND_OVER_FLOOR, false);
        assertEquals(63, near.y());
        assertEquals(2, near.x());
    }
}
