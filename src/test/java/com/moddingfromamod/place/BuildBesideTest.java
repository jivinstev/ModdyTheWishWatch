package com.moddingfromamod.place;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Gate A: a new building put beside, around or on a thing. */
class BuildBesideTest {

    /** A 5x4x7 stable: x 0..4, y 0..3, z 0..6. */
    private static final int[] STABLE = {0, 0, 0, 4, 3, 6};

    @Test
    void besideTheEastWallItStandsOutsideItWithAGap() {
        // The hall's east spot is at x 110 (its wall at 108, two out); its middle at 104.5.
        int[] a = BuildBeside.anchor(STABLE, 110.5, 64, 204.5, 104.5, 204.5, true);
        assertArrayEquals(new int[]{110, 64, 201}, a, "its west edge on the spot, centred along the wall");
    }

    @Test
    void northAndWestPutItsFarEdgeOnTheSpot() {
        assertArrayEquals(new int[]{102, 64, 192}, BuildBeside.anchor(STABLE, 104.5, 64, 198.5, 104.5, 204.5, true),
                "north: its south edge (z 6) on the spot at z 198");
        assertArrayEquals(new int[]{94, 64, 201}, BuildBeside.anchor(STABLE, 98.5, 64, 204.5, 104.5, 204.5, true),
                "west: its east edge (x 4) on the spot at x 98");
    }

    @Test
    void aroundAndTopCentreIt() {
        assertArrayEquals(new int[]{102, 70, 201}, BuildBeside.anchor(STABLE, 104.5, 70, 204.5, 104.5, 204.5, false));
        assertTrue(BuildBeside.outward("beside") && BuildBeside.outward("north") && BuildBeside.outward("front"));
        assertFalse(BuildBeside.outward("around") || BuildBeside.outward("top") || BuildBeside.outward("inside"));
    }

    @Test
    void inFrontOfHimIsTheWayHeLooks() {
        // He stands at 0,64,0; the front spot is 1.8 south of him.
        int[] a = BuildBeside.anchor(STABLE, 0.5, 64, 1.8, 0.5, 0, true);
        assertArrayEquals(new int[]{-2, 64, 1}, a, "south of him, its north edge on the spot");
    }
}
