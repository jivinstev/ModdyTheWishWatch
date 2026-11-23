package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link BuildLighting#forDark} lights what {@link DarkInterior} calls dark, and takes the place of no thing. */
class BuildDarkLightsTest {

    private static BuildShape box(int x, int y, int z, int w, int h, int d, String block, boolean hollow) {
        return new BuildShape(BuildShape.Kind.BOX, x, y, z, w, h, d, block, hollow, BuildShape.Anchor.CORNER, "");
    }

    /** A sealed stone room on a floor: dark until lit. One torch far away in another wing does not excuse it. */
    private static BuildPlan room(String floor, BuildShape... more) {
        List<BuildShape> s = new ArrayList<>();
        s.add(box(0, 0, 0, 12, 6, 12, "minecraft:stone_bricks", true));
        s.add(box(1, 0, 1, 10, 1, 10, floor, false));
        s.addAll(List.of(more));
        return new BuildPlan("room", "Room", s);
    }

    @Test
    void aDarkRoomIsLitThroughItsFloorAndStaysWalkable() {
        BuildPlan p = room("minecraft:oak_planks");
        assertTrue(DarkInterior.of(p).dark());
        List<BuildShape> lights = BuildLighting.forDark(p);
        assertFalse(lights.isEmpty());
        List<BuildShape> all = new ArrayList<>(p.shapes());
        all.addAll(lights);
        assertFalse(DarkInterior.of(p.withShapes(all)).dark(), "still dark after the lights");
        for (BuildShape l : lights) {
            assertEquals(0, l.dy(), "a light is set INTO the floor, never stood on it");
            assertEquals("minecraft:shroomlight", l.block(), "warm light in a wooden floor");
            assertTrue(l.gen(), "generated: outside the 64-shape cap");
        }
    }

    @Test
    void aRoomThatLightsItselfGetsNothing() {
        BuildPlan p = room("minecraft:stone", box(5, 1, 5, 1, 1, 1, "minecraft:lantern", false), box(2, 1, 2, 1, 1, 1,
                "minecraft:lantern", false), box(9, 1, 9, 1, 1, 1, "minecraft:lantern", false));
        assertFalse(DarkInterior.of(p).dark());
        assertEquals(List.of(), BuildLighting.forDark(p));
    }

    @Test
    void aLightNeverTakesThePlaceOfAThing() {
        assertFalse(BuildLighting.plainFloor("minecraft:red_bed[part=head]"));
        assertFalse(BuildLighting.plainFloor("minecraft:chest"));
        assertFalse(BuildLighting.plainFloor("minecraft:bookshelf"));
        assertFalse(BuildLighting.plainFloor("minecraft:glass_pane"));
        assertTrue(BuildLighting.plainFloor("minecraft:stone_bricks"));
        assertEquals("minecraft:glowstone", BuildLighting.floorLight("minecraft:stone_bricks"));
        assertEquals("minecraft:sea_lantern", BuildLighting.floorLight("minecraft:quartz_bricks"));
    }
}
