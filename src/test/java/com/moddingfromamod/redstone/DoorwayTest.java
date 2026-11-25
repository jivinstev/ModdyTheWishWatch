package com.moddingfromamod.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The doorway finder on the fake world: a stone-brick wall along x at z=0, its 2x2 opening at x=0..1, y=-60. */
class DoorwayTest {

    static MechanismPlannerTest.FakeWorld wall() {
        return new MechanismPlannerTest.FakeWorld().wall(-10, 11);
    }

    @Test
    void findsTheExistingOpeningStraightAhead() {
        Doorway.Found d = Doorway.find(wall(), new RsPos(0, -60, -5), RsDir.SOUTH, 2);
        assertTrue(d.ok(), d.why());
        assertTrue(d.existing());
        assertEquals(2, d.width());
        assertEquals(RsDir.SOUTH, d.frame().in());
        // its two bottom cells are the opening's
        assertEquals(java.util.Set.of(new RsPos(0, -60, 0), new RsPos(1, -60, 0)),
                java.util.Set.of(d.frame().at(0, 0, 0), d.frame().at(1, 0, 0)));
        assertEquals("minecraft:stone_bricks", d.wallBlock());
    }

    @Test
    void findsAnOpeningOffToOneSide() {
        Doorway.Found d = Doorway.find(wall(), new RsPos(4, -60, -3), RsDir.SOUTH, 2);
        assertTrue(d.ok(), d.why());
        assertTrue(d.existing());
        assertEquals(2, d.width());
    }

    @Test
    void theGroundIsNeverTakenForAWall() {
        Doorway.Found d = Doorway.find(wall(), new RsPos(0, -60, -12), RsDir.SOUTH, 2);
        assertTrue(d.ok(), d.why());
        assertEquals(0, d.frame().origin().z());
        assertEquals(-60, d.frame().origin().y());
    }

    @Test
    void aWholeWallGetsANewOpening() {
        MechanismPlannerTest.FakeWorld w = wall();
        for (int x = 0; x <= 1; x++) {
            for (int y = -60; y <= -59; y++) {
                w.put(new RsPos(x, y, 0), WorldView.Kind.SOLID, "minecraft:stone_bricks");
            }
        }
        Doorway.Found d = Doorway.find(w, new RsPos(-5, -60, -4), RsDir.SOUTH, 2);
        assertTrue(d.ok(), d.why());
        assertFalse(d.existing());
        assertEquals(2, d.width());
        assertEquals(0, d.frame().origin().z());
    }

    @Test
    void aWideOpeningIsRefusedWithItsWidth() {
        MechanismPlannerTest.FakeWorld w = wall();
        for (int y = -60; y <= -59; y++) {
            w.kinds.remove(new RsPos(2, y, 0));
        }
        Doorway.Found d = Doorway.find(w, new RsPos(1, -60, -5), RsDir.SOUTH, 2);
        assertFalse(d.ok());
        assertTrue(d.why().contains("3 wide"), d.why());
    }

    @Test
    void noWallIsRefused() {
        Doorway.Found d = Doorway.find(new MechanismPlannerTest.FakeWorld(), new RsPos(0, -60, -5), RsDir.SOUTH, 2);
        assertFalse(d.ok());
        assertTrue(d.why().contains("no wall"), d.why());
    }
}
