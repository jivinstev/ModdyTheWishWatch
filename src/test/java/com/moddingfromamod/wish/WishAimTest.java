package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The aim rule's table: sky, under reach, beyond reach, water, cave, wall, looking up — and
 * the one slide by kind. Each row is a world small enough to read, built from {@link Fake}.
 */
class WishAimTest {

    /** Ground at y=63 everywhere (stand at 64), loaded within ±200, plus whatever a row adds. */
    static final class Fake implements WishAim.World {
        final Map<Long, WishAim.Cell> cells = new HashMap<>();
        int groundTop = 63;
        int loaded = 200;
        int waterTop = Integer.MIN_VALUE;   // a sea over the whole world, when set

        static long key(int x, int y, int z) {
            return ((long) x & 0x3FFFFF) << 42 | ((long) y & 0xFFFFF) << 22 | ((long) z & 0x3FFFFF);
        }

        Fake put(int x, int y, int z, WishAim.Cell c) {
            cells.put(key(x, y, z), c);
            return this;
        }

        Fake box(int x0, int y0, int z0, int x1, int y1, int z1, WishAim.Cell c) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        put(x, y, z, c);
                    }
                }
            }
            return this;
        }

        @Override
        public WishAim.Cell at(int x, int y, int z) {
            if (Math.abs(x) > loaded || Math.abs(z) > loaded) {
                return WishAim.Cell.UNLOADED;
            }
            WishAim.Cell c = cells.get(key(x, y, z));
            if (c != null) {
                return c;
            }
            if (y <= groundTop) {
                return WishAim.Cell.SOLID;
            }
            return y <= waterTop ? WishAim.Cell.FLUID : WishAim.Cell.AIR;
        }

        @Override
        public int bottom() {
            return -64;
        }

        @Override
        public int top() {
            return 320;
        }
    }

    /** Standing (or flying) at (0.5, y, 0.5) looking south (+z), pitch as given. */
    static WishAim.Snapshot at(double y, float pitch) {
        return new WishAim.Snapshot(0.5, y, 0.5, y + 1.62, 0f, pitch);
    }

    @Test
    void flyingHighAndLookingAtTheSkyLandsAsFarOutAsHeIsHighNotUnderHim() {
        // The measured case: 50 blocks up, gazing at the horizon, the ray hits nothing.
        WishAim.Spot s = WishAim.aim(at(114, 0f), null, new Fake());
        assertEquals(64, s.y(), "a miss is settled DOWN onto the ground, never left in the air");
        assertEquals(50, s.z(), 1, "50 up → 50 out, so it is ~45° below his eye line: " + s);
        assertEquals(0, s.x());
        double below = Math.toDegrees(Math.atan2(114 + 1.62 - s.y(), s.z() - 0.5));
        assertTrue(below < 50, "it should be near his eye line, not under his feet: " + below + "°");
    }

    @Test
    void higherThanReachIsClampedToReach() {
        WishAim.Spot s = WishAim.aim(at(64 + 200, 0f), null, new Fake());
        assertEquals(WishAim.REACH, s.z(), 1);
        assertEquals(64, s.y());
    }

    @Test
    void onTheGroundLookingAtNothingLandsTheMinimumOut() {
        WishAim.Spot s = WishAim.aim(at(64, 0f), null, new Fake());
        assertEquals(WishAim.MIN_MISS, s.z(), 1);
        assertEquals(64, s.y());
    }

    @Test
    void lookingUpUsesTheDefaultDistanceEvenFromHigh() {
        WishAim.Spot s = WishAim.aim(at(114, -40f), null, new Fake());
        assertEquals(WishAim.MIN_MISS, s.z(), 1);
        assertEquals(64, s.y(), "looking up never floats it: only the agent's `up` does");
    }

    @Test
    void beyondTheLoadedWorldItStopsAtTheLastLoadedPoint() {
        Fake w = new Fake();
        w.loaded = 20;
        WishAim.Spot s = WishAim.aim(at(114, 0f), null, w);
        assertEquals(20, s.z());
        assertEquals(64, s.y());
        assertTrue(s.how().contains("last loaded"), s.how());
    }

    @Test
    void aHitLandsOnTheAirBlockInFrontOfTheFaceNotInsideTheBlock() {
        // A hill face at z=30 (x any), facing north toward him (normal -z), hit at y=70.
        Fake w = new Fake();
        w.box(-5, 64, 30, 5, 80, 40, WishAim.Cell.SOLID);
        WishAim.Spot s = WishAim.aim(at(64, 0f), new WishAim.Hit(0, 70, 30, 0, 0, -1), w);
        assertEquals(29, s.z(), "in front of the face, not in the hill");
        assertEquals(64, s.y(), "and settled down to the ground at the hill's foot");
    }

    @Test
    void aHitOnTheGroundUnderReachIsThatSpot() {
        WishAim.Spot s = WishAim.aim(at(64, 30f), new WishAim.Hit(0, 63, 20, 0, 1, 0), new Fake());
        assertEquals(new WishAim.Spot(0, 64, 20, "hit"), s);
    }

    @Test
    void aBuildingAimedAtWaterFloatsOnIt() {
        // A sea: water from the sea bed (y=50) to y=62, the ray stops at the surface.
        Fake w = new Fake();
        w.groundTop = 49;
        w.waterTop = 62;
        WishAim.Spot s = WishAim.aim(at(70, 20f), new WishAim.Hit(0, 62, 25, 0, 1, 0), w);
        assertEquals(63, s.y(), "on the water surface, not on the sea bed: " + s);
    }

    @Test
    void aMissOverTheSeaFloatsToo() {
        Fake w = new Fake();
        w.groundTop = 49;
        w.waterTop = 62;
        WishAim.Spot s = WishAim.aim(at(90, 0f), null, w);
        assertEquals(63, s.y());
    }

    @Test
    void inACaveItIsNeverRaisedToTheSurface() {
        // A cave: a pocket of air y=20..25 under 40 blocks of rock, floor at y=19.
        Fake w = new Fake();
        w.box(-10, 20, -10, 10, 25, 40, WishAim.Cell.AIR);
        // He looks at the cave wall at z=41 from inside.
        w.box(-10, 20, 41, 10, 25, 41, WishAim.Cell.SOLID);
        WishAim.Snapshot me = new WishAim.Snapshot(0.5, 20, 0.5, 21.62, 0f, 0f);
        WishAim.Spot s = WishAim.aim(me, new WishAim.Hit(0, 21, 41, 0, 0, -1), w);
        assertEquals(20, s.y(), "the cave floor, not the surface 40 blocks up: " + s);
        assertEquals(40, s.z());
    }

    @Test
    void aWallHitInTheAirSettlesDown() {
        // A tower face at z=10 hit 30 blocks up (he is flying): stand at the tower's foot.
        Fake w = new Fake();
        w.box(-2, 64, 10, 2, 120, 12, WishAim.Cell.SOLID);
        WishAim.Spot s = WishAim.aim(at(94, 0f), new WishAim.Hit(0, 95, 10, 0, 0, -1), w);
        assertEquals(64, s.y());
        assertEquals(9, s.z());
    }

    // ---- the one slide by kind ---------------------------------------------------------------

    @Test
    void aCreatureAimedFarAwaySlidesInAlongTheSameGazeLine() {
        WishAim.Spot s = WishAim.slide(at(64, 0f), 0, 64, 50, WishAim.Kind.CREATURE, new Fake());
        assertNotNull(s);
        assertEquals(0, s.x(), "the same line, never sideways");
        assertEquals(16, s.z(), 1);
        assertEquals(64, s.y());
    }

    @Test
    void aSlideFollowsADiagonalGazeLineNotTheAxes() {
        WishAim.Spot s = WishAim.slide(at(64, 0f), 30, 64, 30, WishAim.Kind.PICTURE, new Fake());
        assertNotNull(s);
        assertEquals(s.x(), s.z(), 1, "still on the diagonal: " + s);
        double d = Math.hypot(s.x() + 0.5 - 0.5, s.z() + 0.5 - 0.5);
        assertTrue(d <= 16.8, "slid to the picture's far limit: " + d);
    }

    @Test
    void aBuildingRightOnTopOfHimSlidesOut() {
        WishAim.Spot s = WishAim.slide(at(64, 0f), 0, 64, 3, WishAim.Kind.BUILDING, new Fake());
        assertNotNull(s);
        assertEquals(WishAim.MIN_MISS, s.z(), 1);
    }

    @Test
    void aSpotAlreadyInRangeDoesNotMove() {
        assertNull(WishAim.slide(at(64, 0f), 0, 64, 10, WishAim.Kind.CREATURE, new Fake()));
        assertNull(WishAim.slide(at(64, 0f), 0, 64, 60, WishAim.Kind.ITEM, new Fake()));
        assertNull(WishAim.slide(at(64, 0f), 0, 64, 60, WishAim.Kind.OTHER, new Fake()));
    }

    @Test
    void aSlideOntoAHillClimbsOutOfItRatherThanBurying() {
        Fake w = new Fake();
        w.box(-5, 64, 14, 5, 70, 20, WishAim.Cell.SOLID);
        WishAim.Spot s = WishAim.slide(at(64, 0f), 0, 64, 40, WishAim.Kind.CREATURE, w);
        assertNotNull(s);
        assertEquals(71, s.y(), "on top of the hill: " + s);
    }

    @Test
    void kindsParseFromPlainWords() {
        assertEquals(WishAim.Kind.BUILDING, WishAim.Kind.parse("Building"));
        assertEquals(WishAim.Kind.CREATURE, WishAim.Kind.parse("creature"));
        assertEquals(WishAim.Kind.PICTURE, WishAim.Kind.parse("painting"));
        assertEquals(WishAim.Kind.OTHER, WishAim.Kind.parse(null));
        assertEquals(WishAim.Kind.OTHER, WishAim.Kind.parse("??"));
    }

    /**
     * <b>: lava is not a floor.</b> An aim at a lava moat used to stand a creature on the lava,
     * where it burned. The spot stays where he aimed (so the creature can be given a footing there),
     * but it is reported {@code over lava} and {@link WishAim#floored} says nothing may stand on it
     * as it is — unlike water, which a wish floats on.
     */
    @Test
    void anAimAtALavaMoatIsOverLavaAndNotFloored() {
        Fake w = new Fake();
        w.box(-3, 60, 10, 3, 63, 20, WishAim.Cell.LAVA);
        WishAim.Spot s = WishAim.aim(at(64, 20f), new WishAim.Hit(0, 63, 14, 0, 1, 0), w);
        assertEquals(64, s.y(), "the spot stays on the moat's surface, where he aimed: " + s);
        assertEquals(14, s.z());
        assertTrue(s.how().contains("over lava"), "the log must say it is over lava: " + s.how());
        assertTrue(!WishAim.floored(w, s.x(), s.y(), s.z()), "lava was treated as a floor");

        Fake sea = new Fake();
        sea.box(-3, 60, 10, 3, 63, 20, WishAim.Cell.FLUID);
        WishAim.Spot onWater = WishAim.aim(at(64, 20f), new WishAim.Hit(0, 63, 14, 0, 1, 0), sea);
        assertTrue(WishAim.floored(sea, onWater.x(), onWater.y(), onWater.z()),
                "water still floats a wish (#1268)");
        assertTrue(!onWater.how().contains("lava"), onWater.how());
    }

    /** A miss whose settle lands on lava says so too. */
    @Test
    void aMissThatSettlesOnLavaSaysSo() {
        Fake w = new Fake();
        w.box(-5, 55, 5, 5, 63, 20, WishAim.Cell.LAVA);
        WishAim.Spot s = WishAim.aim(at(64, 0f), null, w);
        assertEquals(64, s.y());
        assertTrue(s.how().contains("over lava"), s.how());
    }
}
