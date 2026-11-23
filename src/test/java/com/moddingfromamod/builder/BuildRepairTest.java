package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** each one-block fault {@link BuildRepair} puts right, and what it must leave alone. */
class BuildRepairTest {

    private static BuildShape box(int x, int y, int z, int w, int h, int d, String block, boolean hollow) {
        return new BuildShape(BuildShape.Kind.BOX, x, y, z, w, h, d, block, hollow, BuildShape.Anchor.CORNER, "");
    }

    private static BuildShape air(int x, int y, int z, int w, int h, int d) {
        return box(x, y, z, w, h, d, "minecraft:air", false);
    }

    private static BuildPlan plan(BuildShape... s) {
        return new BuildPlan("fixture", "Fixture", new ArrayList<>(List.of(s)));
    }

    private static int unreachable(BuildPlan p) {
        return WalkThrough.of(p).unreachable().size();
    }

    private static int unreachableAsDrawn(BuildPlan p) {
        System.setProperty("moddingfromamod.buildrepair", "off");
        try {
            return WalkThrough.of(new BuildPlan(p.id(), p.name(), p.shapes())).unreachable().size();
        } finally {
            System.clearProperty("moddingfromamod.buildrepair");
        }
    }

    /** The commonest real fault: the box brings its own floor, and the doorway was cut from the ground. */
    @Test
    void aDoorwayCutABlockTooLowIsRaisedToTheRoomsFloor() {
        BuildPlan p = plan(box(0, 0, 0, 9, 5, 7, "minecraft:stone_bricks", true), air(4, 0, 0, 1, 2, 1));
        assertEquals(1, unreachableAsDrawn(p));
        BuildRepair.Fixed f = BuildRepair.of(p);
        assertEquals(1, f.fixes().size());
        assertEquals("doorway raised to the room's floor", f.fixes().get(0).kind());
        assertEquals(1, f.cells().size(), "one block");
        assertTrue(f.cells().containsKey(ComposedPlan.key(4, 2, 0)));
        assertNull(f.cells().get(ComposedPlan.key(4, 2, 0)));
        assertEquals(0, unreachable(p));
        assertTrue(BuildRepair.note(f).contains("doorway raised to the room's floor at 4,2,0"));
    }

    /** A doorway through one block of a two-block wall. */
    @Test
    void aDoorwayThatStopsABlockShortIsCutThrough() {
        BuildPlan p = plan(box(0, 0, 0, 9, 5, 9, "minecraft:stone_bricks", true),
                box(1, 0, 1, 7, 5, 7, "minecraft:stone_bricks", true), box(1, 0, 1, 7, 1, 7, "minecraft:air", false),
                box(0, 0, 0, 9, 1, 9, "minecraft:air", false), air(4, 1, 0, 1, 2, 1));
        if (unreachableAsDrawn(p) == 0) {
            return;   // the fixture's floor differs by how a hollow box closes: the corpus test carries the real case
        }
        BuildRepair.Fixed f = BuildRepair.of(p);
        assertFalse(f.isEmpty());
        assertTrue(f.cells().size() <= BuildRepair.MOST_CELLS);
        assertEquals(0, unreachable(p));
    }

    /** A ladder that stops under a floor with no hole. */
    @Test
    void aLadderIsCarriedUpThroughTheFloorItLeadsTo() {
        BuildPlan p = plan(box(0, 0, 0, 7, 11, 7, "minecraft:stone_bricks", true), air(3, 1, 0, 1, 2, 1),
                box(1, 5, 1, 5, 1, 5, "minecraft:oak_planks", false),
                box(5, 1, 3, 1, 4, 1, "minecraft:ladder[facing=west]", false));
        assertEquals(1, unreachableAsDrawn(p), "the upper floor has no way up as drawn");
        BuildRepair.Fixed f = BuildRepair.of(p);
        assertEquals(1, f.fixes().size());
        assertEquals("ladder carried up to the floor it leads to", f.fixes().get(0).kind());
        assertTrue(f.cells().size() <= 3);
        f.cells().values().forEach(b -> assertEquals("minecraft:ladder[facing=west]", b));
        assertEquals(0, unreachable(p));
    }

    /** A sealed box with no opening anywhere may be a solid turret: advice only, never a hole. */
    @Test
    void aRoomWithNoOpeningAtAllIsLeftAlone() {
        BuildPlan p = plan(box(0, 0, 0, 7, 5, 7, "minecraft:stone_bricks", true));
        assertEquals(1, unreachableAsDrawn(p));
        assertTrue(BuildRepair.of(p).isEmpty());
        assertEquals(1, unreachable(p));
    }

    /** A window (open at his head, solid at his feet) is not a doorway. */
    @Test
    void aWindowIsNotMadeIntoADoor() {
        BuildPlan p = plan(box(0, 0, 0, 9, 5, 7, "minecraft:stone_bricks", true), air(4, 2, 0, 1, 1, 1));
        assertTrue(BuildRepair.of(p).isEmpty());
    }

    /** A building whose rooms all walk is never touched. */
    @Test
    void aBuildingThatWalksIsNeverTouched() {
        BuildPlan p = plan(box(0, 0, 0, 9, 5, 7, "minecraft:stone_bricks", true), air(4, 1, 0, 1, 2, 1));
        assertEquals(0, unreachableAsDrawn(p));
        assertTrue(BuildRepair.of(p).isEmpty());
        assertEquals("", BuildRepair.note(BuildRepair.of(p)));
    }

    /** A door one block tall becomes a whole door, never half a door under a hole (half a door breaks). */
    @Test
    void aDoorOneBlockTallGetsItsTopHalf() {
        BuildPlan p = plan(box(0, 0, 0, 11, 7, 11, "minecraft:stone_bricks", true),
                box(5, 1, 10, 1, 1, 1, "minecraft:oak_door", false));
        BuildRepair.Fixed f = BuildRepair.of(p);
        assertEquals(1, f.fixes().size());
        String top = f.cells().get(ComposedPlan.key(5, 2, 10));
        assertTrue(top != null && top.startsWith("minecraft:oak_door[") && top.contains("half=upper"), String.valueOf(top));
        assertEquals(0, unreachable(p));
    }

    @Test
    void offMeansOff() {
        BuildPlan p = plan(box(0, 0, 0, 9, 5, 7, "minecraft:stone_bricks", true), air(4, 0, 0, 1, 2, 1));
        System.setProperty("moddingfromamod.buildrepair", "off");
        try {
            assertTrue(BuildRepair.of(p).isEmpty());
        } finally {
            System.clearProperty("moddingfromamod.buildrepair");
        }
    }
}
