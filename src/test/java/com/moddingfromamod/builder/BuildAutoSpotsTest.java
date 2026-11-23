package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Gate A 's automatic spots: top, inside, the sides, around, and the words for them. */
class BuildAutoSpotsTest {

    private static BuildShape box(int x, int y, int z, int w, int h, int d, String b, boolean hollow) {
        return new BuildShape(BuildShape.Kind.BOX, x, y, z, w, h, d, b, hollow);
    }

    private static BuildSpot named(List<BuildSpot> spots, String name) {
        return spots.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }

    @Test
    void theHallHasATopOnItsRoofAnInsideOnItsFloorFourSidesAndAround() {
        List<BuildSpot> auto = BuildAutoSpots.of(BuildSpotsTest.hall(true));
        assertEquals(new BuildSpot("top", 4, 6, 4, ""), named(auto, "top"), "standing on the roof's middle");
        assertEquals(new BuildSpot("inside", 4, 1, 4, ""), named(auto, "inside"),
                "the middle of the hall's floor, feet one above the floor block");
        assertEquals(new BuildSpot("north", 4, 0, -BuildSpots.FRONT_GAP, "north"), named(auto, "north"));
        assertEquals(new BuildSpot("east", 8 + BuildSpots.FRONT_GAP, 0, 4, "east"), named(auto, "east"));
        assertEquals(new BuildSpot("south", 4, 0, 8 + BuildSpots.FRONT_GAP, "south"), named(auto, "south"));
        assertEquals(new BuildSpot("west", -BuildSpots.FRONT_GAP, 0, 4, "west"), named(auto, "west"));
        assertEquals(new BuildSpot("around", 4, 0, 4, ""), named(auto, "around"));
        assertEquals(9.0, BuildAutoSpots.aroundRadius(BuildSpotsTest.hall(true)),
                "just outside the corners (half-diagonal 6.4 → 7) plus the gap");
        assertEquals(1, auto.stream().filter(s -> s.name().startsWith("inside")).count(),
                "the throne's own top is one cell, not a room");
    }

    @Test
    void aGarageOpenAtTheFrontIsARoomAndAPavilionIsNot() {
        List<BuildShape> garage = new ArrayList<>();
        garage.add(box(0, 0, 0, 7, 1, 9, "minecraft:stone", false));          // floor
        garage.add(box(0, 1, 0, 1, 4, 9, "minecraft:bricks", false));         // west wall
        garage.add(box(6, 1, 0, 1, 4, 9, "minecraft:bricks", false));         // east wall
        garage.add(box(0, 1, 0, 7, 4, 1, "minecraft:bricks", false));         // back wall
        garage.add(box(0, 5, 0, 7, 1, 9, "minecraft:oak_planks", false));     // roof; the south is open
        BuildSpot inside = BuildSpots.spot(new BuildPlan("garage", "Garage", garage), "inside", 0, 0);
        assertNotNull(inside, "a garage with its door open is still IN the garage");
        assertEquals(1, inside.dy());
        assertTrue(inside.dx() >= 1 && inside.dx() <= 5 && inside.dz() >= 1 && inside.dz() <= 8, inside.toString());

        List<BuildShape> pavilion = new ArrayList<>();
        pavilion.add(box(0, 0, 0, 7, 1, 7, "minecraft:stone", false));
        for (int[] p : new int[][]{{0, 0}, {6, 0}, {0, 6}, {6, 6}}) {
            pavilion.add(box(p[0], 1, p[1], 1, 3, 1, "minecraft:oak_log", false));
        }
        pavilion.add(box(0, 4, 0, 7, 1, 7, "minecraft:oak_planks", false));
        assertEquals(null, BuildSpots.spot(new BuildPlan("pav", "Pavilion", pavilion), "inside", 0, 0),
                "a roof on posts has no inside");
    }

    @Test
    void aCarwashOpenAtBothEndsIsStillARoom() {
        List<BuildShape> wash = new ArrayList<>();
        wash.add(box(0, 0, 0, 5, 1, 9, "minecraft:stone", false));
        wash.add(box(0, 1, 0, 1, 3, 9, "minecraft:quartz_block", false));
        wash.add(box(4, 1, 0, 1, 3, 9, "minecraft:quartz_block", false));
        wash.add(box(0, 4, 0, 5, 1, 9, "minecraft:quartz_block", false));
        assertNotNull(BuildSpots.spot(new BuildPlan("wash", "Wash", wash), "inside", 0, 0));
    }

    @Test
    void aTwoStoreyHouseHasAnInsideOnEachFloorGroundFirst() {
        List<BuildShape> house = new ArrayList<>();
        house.add(box(0, 0, 0, 9, 10, 9, "minecraft:stone_bricks", true));
        house.add(box(1, 5, 1, 7, 1, 7, "minecraft:oak_planks", false));      // the upstairs floor
        List<BuildSpot> auto = BuildAutoSpots.of(new BuildPlan("house", "House", house));
        assertEquals(1, named(auto, "inside").dy(), "downstairs first");
        assertEquals(6, named(auto, "inside_2").dy(), "upstairs second, on its floor");
    }

    @Test
    void aCastleTopIsOnItsWallsOrTowersNeverItsCourtyard() {
        List<BuildShape> castle = new ArrayList<>();
        castle.add(box(0, 0, 0, 21, 1, 21, "minecraft:stone", false));          // courtyard ground
        castle.add(box(0, 1, 0, 21, 8, 1, "minecraft:stone_bricks", false));
        castle.add(box(0, 1, 20, 21, 8, 1, "minecraft:stone_bricks", false));
        castle.add(box(0, 1, 0, 1, 8, 21, "minecraft:stone_bricks", false));
        castle.add(box(20, 1, 0, 1, 8, 21, "minecraft:stone_bricks", false));
        castle.add(box(0, 1, 0, 4, 12, 4, "minecraft:stone_bricks", false));    // a tower
        BuildSpot top = BuildSpots.spot(new BuildPlan("castle", "Castle", castle), "roof", 0, 0);
        assertNotNull(top);
        assertTrue(top.dy() >= 9, "on a wall or the tower, never down in the courtyard: " + top);
    }

    @Test
    void theWordsAChildUsesMapOntoTheSpots() {
        BuildPlan hall = BuildSpotsTest.hall(true);
        assertEquals(6, BuildSpots.spot(hall, "roof", 0, 0).dy(), "roof → top");
        assertEquals(6, BuildSpots.spot(hall, "on_top", 0, 0).dy());
        assertEquals(1, BuildSpots.spot(hall, "in", 0, 0).dy(), "in → inside");
        // The door is in the south wall, so the front faces south: beside is clockwise (west), behind north.
        assertEquals("west", BuildSpots.spot(hall, "beside", 0, 0).facing());
        assertEquals("west", BuildSpots.spot(hall, "next_to", 0, 0).facing());
        assertEquals("north", BuildSpots.spot(hall, "behind", 0, 0).facing());
        assertEquals("south", BuildSpots.spot(hall, "door", 0, 0).facing(), "door → front");
        // A building that marked its OWN roof keeps it.
        BuildPlan marked = hall.withSpots(List.of(new BuildSpot("roof", 1, 6, 1, "east")));
        assertEquals(new BuildSpot("roof", 1, 6, 1, "east"), BuildSpots.spot(marked, "roof", 0, 0));
        assertEquals(4, BuildSpots.spot(marked, "top", 0, 0).dx(), "top is still the automatic one");
    }

    @Test
    void aPictureHangsOnAFlatPatchOfTheWallAtHisEyeHeightFacingOut() {
        BuildPlan hall = BuildSpotsTest.hall(true);   // 9 wide, 6 tall; a door at 4,1..2,8 in the south wall
        BuildSpot north = BuildAutoSpots.wallFace(hall, "north", 3, 2);
        assertEquals(new BuildSpot("wall_north", 5, 1, -1, "north"), north,
                "in front of the north wall (z 0), bottom-left as he sees it is its EAST end (x 3..5)");
        BuildSpot south = BuildAutoSpots.wallFace(hall, "south", 3, 2);
        assertNotNull(south);
        assertTrue(south.dx() + 2 < 4 || south.dx() > 4,
                "never over the doorway, which is not flat wall: " + south);
        assertEquals(9, south.dz());
        assertEquals(null, BuildAutoSpots.wallFace(hall, "east", 12, 2), "wider than the wall");
    }

    @Test
    void onlyAThroneOrARoofKeepsItsSeat() {
        assertTrue(BuildSpots.keepsItsSeat("throne"));
        assertTrue(BuildSpots.keepsItsSeat("top"));
        assertTrue(BuildSpots.keepsItsSeat("roof"));
        assertTrue(BuildSpots.keepsItsSeat("inside"));
        for (String free : List.of("front", "beside", "behind", "around", "north", "next_to")) {
            assertTrue(!BuildSpots.keepsItsSeat(free), free);
        }
    }

    @Test
    void aHugeBuildingIsAnsweredFastAndSkipsTheRoomScanOnlyPastTheCap() {
        List<BuildShape> big = new ArrayList<>();
        big.add(box(0, 0, 0, 120, 40, 120, "minecraft:stone", true));
        long t0 = System.nanoTime();
        List<BuildSpot> auto = BuildAutoSpots.of(new BuildPlan("big", "Big", big));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertNotNull(named(auto, "inside"), "576,000 cells is under the cap");
        assertTrue(ms < 5_000, "took " + ms + " ms");
    }
}
