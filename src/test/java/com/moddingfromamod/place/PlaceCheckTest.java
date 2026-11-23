package com.moddingfromamod.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Gate A 's placement check: IN / ON / BY a building, and how two buildings sit. */
class PlaceCheckTest {

    /** A hall: blocks x 100..108, y 64..69, z 200..208. */
    private static final int[] HALL = {100, 64, 200, 108, 69, 208};

    @Test
    void aPointIsInOnOrByTheBuilding() {
        assertEquals("IN it", PlaceCheck.relationOfPoint(HALL, 104.5, 65, 204.5));
        assertEquals("ON it", PlaceCheck.relationOfPoint(HALL, 104.5, 70, 204.5), "standing on the roof");
        assertEquals("ON it", PlaceCheck.relationOfPoint(HALL, 104.5, 69, 204.5),
                "on a deck between battlements: feet level with the top layer");
        assertEquals("IN it", PlaceCheck.relationOfPoint(HALL, 104.5, 67, 204.5), "a room under the roof");
        assertEquals("BY it, 2.0 from its walls", PlaceCheck.relationOfPoint(HALL, 111, 64, 204.5));
        assertTrue(PlaceCheck.relationOfPoint(HALL, 104.5, 64, 230).startsWith("BY it"));
    }

    @Test
    void aSecondBuildingIsBesideAroundOnOrOverlapping() {
        assertEquals("stands BESIDE it, 1 block(s) of air between",
                PlaceCheck.relation(HALL, new int[]{110, 64, 202, 114, 67, 206}));
        assertTrue(PlaceCheck.relation(HALL, new int[]{95, 64, 195, 113, 66, 213}).startsWith("stands AROUND it"));
        assertTrue(PlaceCheck.relation(HALL, new int[]{102, 70, 202, 106, 80, 206}).startsWith("stands ON it"));
        assertEquals("OVERLAPS it", PlaceCheck.relation(HALL, new int[]{105, 64, 205, 112, 66, 212}));
    }

    @Test
    void eachToolLooksForItsOwnDefaultSpot() {
        assertEquals("top", PlaceCheck.defaultWhere("stage_event"));
        assertEquals("beside", PlaceCheck.defaultWhere("build_structure"));
        assertEquals("front", PlaceCheck.defaultWhere("define_creature"));
    }

    @Test
    void aThingBehindTheBuildingIsShotFromBeyondItNotThroughTheBuilding() {
        int[] barn = {0, 64, 0, 9, 72, 9};
        double[] from = PlaceCheck.viewFrom(barn, new double[] {5, 64, 14}, 5, -30, null);   // he is at the front
        org.junit.jupiter.api.Assertions.assertTrue(from[1] > 14, "from beyond the shed, not his side: " + from[1]);
        double[] onTop = PlaceCheck.viewFrom(barn, new double[] {5, 73, 5}, 5, -30, "top");
        assertEquals(-30, onTop[1], 1e-9, "a thing on top is shot from his side");
        int[] barnAndShed = {0, 64, 0, 9, 72, 20};          // the shed's centre is inside the union
        double[] shed = PlaceCheck.viewFrom(barnAndShed, new double[] {5, 64, 17}, 5, -30, "behind");
        org.junit.jupiter.api.Assertions.assertTrue(shed[1] > 17, "behind is shot from beyond it: " + shed[1]);
    }

    @Test
    void aNewBuildingBesideAnotherIsShotAcrossBothFromHisSide() {
        int[] both = {0, 64, 0, 20, 72, 9};                 // cottage west, stable east
        double[] from = PlaceCheck.across(both, new double[] {16, 64, 5}, 10, -30);   // he is south (z -30)
        org.junit.jupiter.api.Assertions.assertTrue(from[1] < 0, "from his side of the pair: " + from[1]);
        org.junit.jupiter.api.Assertions.assertEquals(10.5, from[0], 1e-9, "square on to the pair, not end-on");
    }
}
