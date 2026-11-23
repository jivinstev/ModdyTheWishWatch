package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import com.moddingfromamod.runtime.SafeSpot.Body;
import com.moddingfromamod.runtime.SafeSpot.Cell;
import com.moddingfromamod.runtime.SafeSpot.Moves;
import com.moddingfromamod.runtime.SafeSpot.Problem;
import org.junit.jupiter.api.Test;

/**
 * The SafeSpot table: lava, leaves, a one-high gap, a flier, a swimmer, nowhere at all —
 * and what each failure does (a footing or a move), the follower hop, and what Moddy says.
 */
class SafeSpotTest {

    /** Ground (floor) up to y=63, so a creature stands at y=64; plus whatever a row adds. */
    static final class Fake implements SafeSpot.World {
        final Map<Long, Cell> cells = new HashMap<>();
        int groundTop = 63;
        int loaded = 100;

        static long key(int x, int y, int z) {
            return ((long) x & 0x3FFFFF) << 42 | ((long) y & 0xFFFFF) << 22 | ((long) z & 0x3FFFFF);
        }

        Fake box(int x0, int y0, int z0, int x1, int y1, int z1, Cell c) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        cells.put(key(x, y, z), c);
                    }
                }
            }
            return this;
        }

        @Override
        public Cell at(int x, int y, int z) {
            if (Math.abs(x) > loaded || Math.abs(z) > loaded) {
                return Cell.UNLOADED;
            }
            Cell c = cells.get(key(x, y, z));
            if (c != null) {
                return c;
            }
            return y <= groundTop ? Cell.FLOOR : Cell.OPEN;
        }
    }

    static final Body COW = new Body(0.9, 1.4, Moves.WALK);
    static final Body TALL = new Body(0.9, 1.9, Moves.WALK);
    static final Body BAT = new Body(0.5, 0.9, Moves.FLY);
    static final Body FISH = new Body(0.8, 0.8, Moves.SWIM);

    @Test
    void openGroundIsSafe() {
        assertEquals(Problem.NONE, SafeSpot.check(new Fake(), COW, 0.5, 64, 0.5));
        assertInstanceOf(SafeSpot.Ok.class, SafeSpot.fix(new Fake(), COW, 0.5, 64, 0.5));
    }

    @Test
    void lavaUnderAWalkerIsNotAFloorAndGetsAnObsidianFooting() {
        Fake w = new Fake().box(-5, 60, -5, 5, 63, 5, Cell.LAVA);
        assertEquals(Problem.OVER_LAVA, SafeSpot.check(w, COW, 0.5, 64, 0.5));
        SafeSpot.Fix f = SafeSpot.fix(w, COW, 0.5, 64, 0.5);
        var footing = assertInstanceOf(SafeSpot.Footing.class, f, "a walker over a lava moat is given a footing");
        assertEquals(Problem.OVER_LAVA, footing.why());
        assertEquals(9, footing.cells().size(), "a 3x3 footing under a one-block creature");
        for (int[] c : footing.cells()) {
            assertEquals(63, c[1], "the footing replaces the lava's surface");
        }
        assertEquals(Problem.NONE, SafeSpot.check(SafeSpot.overlay(w, footing.cells()), COW, 0.5, 64, 0.5));
    }

    @Test
    void lavaInTheBoxIsAHazardAndMoves() {
        Fake w = new Fake().box(0, 64, 0, 0, 64, 0, Cell.LAVA);
        assertEquals(Problem.HAZARD, SafeSpot.check(w, COW, 0.5, 64, 0.5));
        var m = assertInstanceOf(SafeSpot.Move.class, SafeSpot.fix(w, COW, 0.5, 64, 0.5));
        assertEquals(Problem.NONE, SafeSpot.check(w, COW, m.to().x(), m.to().y(), m.to().z()));
    }

    @Test
    void magmaUnderfootIsAHazard() {
        Fake w = new Fake().box(0, 63, 0, 0, 63, 0, Cell.HAZARD);
        assertEquals(Problem.HAZARD, SafeSpot.check(w, COW, 0.5, 64, 0.5));
    }

    @Test
    void leavesAreNoFloorForAWalkerButFineUnderAFlier() {
        Fake w = new Fake().box(-3, 64, -3, 3, 69, 3, Cell.LEAVES);
        assertEquals(Problem.NO_FLOOR, SafeSpot.check(w, COW, 0.5, 70, 0.5), "a walker on a canopy");
        assertEquals(Problem.NONE, SafeSpot.check(w, BAT, 0.5, 70, 0.5), "a flier needs only a free box");
        var m = assertInstanceOf(SafeSpot.Move.class, SafeSpot.fix(w, COW, 0.5, 70, 0.5),
                "leaves are not filled over: it moves to the ground");
        assertEquals(64, m.to().y(), 0.0, "off the tree, onto the ground beside it");
    }

    @Test
    void aOneHighGapDoesNotFitATallCreature() {
        // A slot one block high under a ceiling: a cow (1.4) does not fit, a bat (0.9) does.
        Fake w = new Fake().box(-12, 65, -12, 12, 70, 12, Cell.FLOOR);
        assertEquals(Problem.NO_ROOM, SafeSpot.check(w, COW, 0.5, 64, 0.5));
        assertEquals(Problem.NONE, SafeSpot.check(w, BAT, 0.5, 64, 0.5));
        var m = assertInstanceOf(SafeSpot.Move.class, SafeSpot.fix(w, COW, 0.5, 64, 0.5));
        assertEquals(71, m.to().y(), 0.0, "out of the gap, straight up onto the slab: " + m.to());
    }

    @Test
    void insideRockMovesStraightUpToTheSurface() {
        Fake w = new Fake().box(-10, 64, -10, 10, 69, 10, Cell.FLOOR);
        var m = assertInstanceOf(SafeSpot.Move.class, SafeSpot.fix(w, TALL, 0.5, 65, 0.5));
        assertEquals(Problem.NO_ROOM, m.why());
        assertEquals(0.5, m.to().x(), 0.0);
        assertEquals(0.5, m.to().z(), 0.0);
        assertEquals(70, m.to().y(), 0.0, "straight up onto the rock's top");
    }

    @Test
    void aFlierNeedsOnlyAFreeBoxNoFloorAndNoHazard() {
        Fake w = new Fake();
        assertEquals(Problem.NONE, SafeSpot.check(w, BAT, 0.5, 90, 0.5), "in mid-air");
        w.box(0, 90, 0, 0, 90, 0, Cell.HAZARD);
        assertEquals(Problem.HAZARD, SafeSpot.check(w, BAT, 0.5, 90, 0.5));
    }

    @Test
    void aSwimmerNeedsWaterAndAWalkerMustNotBeUnderIt() {
        Fake w = new Fake().box(-5, 58, -5, 5, 63, 5, Cell.WATER);
        assertEquals(Problem.NONE, SafeSpot.check(w, FISH, 0.5, 60, 0.5));
        assertEquals(Problem.NO_WATER, SafeSpot.check(w, FISH, 20.5, 64, 0.5));
        assertEquals(Problem.OVER_WATER, SafeSpot.check(w, COW, 0.5, 60, 0.5), "a walker under water");
        assertEquals(Problem.OVER_WATER, SafeSpot.check(w, COW, 0.5, 64, 0.5), "a walker on the surface");
        var f = assertInstanceOf(SafeSpot.Footing.class, SafeSpot.fix(w, COW, 0.5, 64, 0.5));
        assertEquals(Problem.OVER_WATER, f.why());
        var m = assertInstanceOf(SafeSpot.Move.class, SafeSpot.fix(w, FISH, 7.5, 64, 0.5),
                "a swimmer on the shore is moved into the water");
        assertEquals(Problem.NONE, SafeSpot.check(w, FISH, m.to().x(), m.to().y(), m.to().z()));
    }

    @Test
    void shallowWaterOverAFloorIsFine() {
        Fake w = new Fake().box(0, 64, 0, 0, 64, 0, Cell.WATER);
        assertEquals(Problem.NONE, SafeSpot.check(w, COW, 0.5, 64, 0.5));
    }

    @Test
    void nowhereToGoIsStuckNotAGuess() {
        Fake w = new Fake();
        w.groundTop = 400;      // solid all the way up, everywhere in reach
        assertInstanceOf(SafeSpot.Stuck.class, SafeSpot.fix(w, COW, 0.5, 64, 0.5));
        assertNull(SafeSpot.nearest(w, COW, 0.5, 64, 0.5));
    }

    @Test
    void anUnloadedSpotIsLeftAlone() {
        assertInstanceOf(SafeSpot.Stuck.class, SafeSpot.fix(new Fake(), COW, 500.5, 64, 0.5));
    }

    @Test
    void aFootingTooBigForACreatureMovesItInstead() {
        Fake w = new Fake().box(-30, 55, -30, 30, 63, 30, Cell.WATER);
        Body whale = new Body(9, 3, Moves.WALK);
        assertTrue(!(SafeSpot.fix(w, whale, 0.5, 64, 0.5) instanceof SafeSpot.Footing),
                "a 9-wide footing is not a small footing");
    }

    @Test
    void aNamedSpotWinsWhenItPasses() {
        Fake w = new Fake();
        var named = new SafeSpot.Spot(4.5, 64, 4.5);
        var m = assertInstanceOf(SafeSpot.Move.class, SafeSpot.fix(w, COW, 0.5, 64, 0.5, named));
        assertEquals(named, m.to());
        w.box(4, 64, 4, 4, 65, 4, Cell.FLOOR);
        assertInstanceOf(SafeSpot.Ok.class, SafeSpot.fix(w, COW, 0.5, 64, 0.5, named),
                "a named spot that fails the rule is ignored");
    }

    // ── the follower hop ─────────────────────────────────────────────────────────────────────

    @Test
    void noHopForAWalkerWhileHeIsAirborne() {
        assertNull(SafeSpot.hop(new Fake(), COW, 0.5, 90, 0.5, true, new Random(1)));
        assertNull(SafeSpot.hop(new Fake(), FISH, 0.5, 90, 0.5, true, new Random(1)));
    }

    @Test
    void aFlierMayHopToHisHeight() {
        var s = SafeSpot.hop(new Fake(), BAT, 0.5, 90, 0.5, true, new Random(1));
        assertNotNull(s);
        assertEquals(90, s.y(), 1.0);
    }

    @Test
    void aHopLandsNearHimOnTheGroundAndNeverOnTopOfHim() {
        int hopped = 0;
        for (int seed = 0; seed < 50; seed++) {
            var s = SafeSpot.hop(new Fake(), COW, 0.5, 64, 0.5, false, new Random(seed));
            if (s == null) {
                continue;       // ten unlucky tries: no hop this time, as vanilla
            }
            hopped++;
            double dx = Math.abs(s.x() - 0.5), dz = Math.abs(s.z() - 0.5);
            assertTrue(dx <= SafeSpot.HOP_REACH && dz <= SafeSpot.HOP_REACH, "within reach: " + s);
            assertTrue(dx >= 2 || dz >= 2, "not on top of him: " + s);
            assertEquals(64, s.y(), 0.0);
        }
        assertTrue(hopped >= 35, "open ground should nearly always find a spot: " + hopped + "/50");
    }

    @Test
    void aHopBesideLavaNeverLandsInIt() {
        // He stands on a one-block causeway through a lava lake.
        Fake w = new Fake().box(-10, 60, -10, 10, 63, 10, Cell.LAVA).box(-10, 63, 0, 10, 63, 0, Cell.FLOOR);
        for (int seed = 0; seed < 50; seed++) {
            var s = SafeSpot.hop(w, COW, 0.5, 64, 0.5, false, new Random(seed));
            if (s != null) {
                assertEquals(Problem.NONE, SafeSpot.check(w, COW, s.x(), s.y(), s.z()), "seed " + seed + ": " + s);
                assertEquals(0.5, s.z(), 0.0, "only the causeway is safe");
            }
        }
    }

    // ── what Moddy says ──────────────────────────────────────────────────────────────────────

    @Test
    void aSmallMoveIsSilentAndALargeOneSaysWhereAndWhy() {
        var from = new SafeSpot.Spot(0.5, 64, 0.5);
        assertNull(SafeSpot.told("dragon", from, new SafeSpot.Spot(0.5, 67, 0.5), SafeSpot.Why.PICKED_SPOT_SOLID),
                "three blocks is silent");
        String line = SafeSpot.told("dragon", from, new SafeSpot.Spot(0.5, 70, 0.5), SafeSpot.Why.PICKED_SPOT_SOLID);
        assertEquals("Your dragon is just above — the spot you picked was inside the rock!", line);
        String build = SafeSpot.told("Spikor", from, new SafeSpot.Spot(8.5, 64, 0.5), SafeSpot.Why.BUILDING);
        assertTrue(build.startsWith("Your Spikor is a few steps over"), build);
    }

    // ──: a PLAYER ───────────────────────────────────────────────────────────────────────

    static final Body PLAYER = new Body(0.6, 1.8, Moves.WALK);

    @Test
    void onlyInsideBlocksOrBurningCountsAsBuriedForAPlayer() {
        assertTrue(SafeSpot.buried(Problem.NO_ROOM));
        assertTrue(SafeSpot.buried(Problem.HAZARD));
        assertTrue(SafeSpot.buried(Problem.OVER_LAVA));
        assertTrue(!SafeSpot.buried(Problem.NO_FLOOR), "a ledge is where he chose to stand");
        assertTrue(!SafeSpot.buried(Problem.OVER_WATER), "swimming is where he chose to be");
        assertTrue(!SafeSpot.buried(Problem.UNLOADED), "an unread spot is not known to be buried");
        assertTrue(!SafeSpot.buried(Problem.NONE));
    }

    @Test
    void aPlayerInTheMiddleOfAWideTallMoundIsFoundByTheWiderPass() {
        // 41 wide and 40 tall: the ordinary reach (8 sideways, 32 up) finds nothing from the middle.
        Fake w = new Fake().box(-20, 64, -20, 20, 103, 20, Cell.FLOOR);
        assertNull(SafeSpot.nearest(w, PLAYER, 0.5, 64, 0.5));
        SafeSpot.Spot s = SafeSpot.nearestOrWider(w, PLAYER, 0.5, 64, 0.5);
        assertNotNull(s, "the wider pass must find a way out (off the side, here: nearer than the top)");
        assertEquals(Problem.NONE, SafeSpot.check(w, PLAYER, s.x(), s.y(), s.z()));
    }

    @Test
    void whatHeIsToldNamesWhy() {
        SafeSpot.Spot a = new SafeSpot.Spot(0.5, 64, 0.5);
        SafeSpot.Spot up = new SafeSpot.Spot(0.5, 70, 0.5);
        assertEquals("You're just above — your new building was going up right where you stood!",
                SafeSpot.toldYou(a, up, SafeSpot.YouWhy.BUILDING));
        assertTrue(SafeSpot.toldYou(a, up, SafeSpot.YouWhy.HOME_BURIED).contains("built on your home spot"));
        assertTrue(SafeSpot.toldYou(a, up, SafeSpot.YouWhy.STUCK).contains("stuck inside blocks"));
        assertNull(SafeSpot.toldYou(a, new SafeSpot.Spot(0.5, 66, 0.5), SafeSpot.YouWhy.STUCK),
                "a step is not worth a line");
        assertNotNull(SafeSpot.toldYou(a, new SafeSpot.Spot(0.5, 66, 0.5), SafeSpot.YouWhy.HOME_BURIED),
                "a buried home is always said, however near the step");
    }
}
