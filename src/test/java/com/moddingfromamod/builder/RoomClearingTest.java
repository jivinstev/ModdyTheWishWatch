package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * a building in a forest clears the natural blocks out of its ROOMS — and only its rooms, so an open
 * courtyard keeps its tree — and never a block somebody placed.
 */
class RoomClearingTest {

    static BuildPlan roomAndCourtyard() {
        return RoomClearingFixture.plan();
    }

    @Test
    @DisplayName("roomSpace holds a roofed room's air and none of an open courtyard's")
    void roomSpaceIsRoofedRoomsOnly() {
        Set<Long> space = WalkThrough.plan(roomAndCourtyard()).roomSpace();
        assertTrue(space.contains(ComposedPlan.key(3, 1, 3)), "the room's middle, at his feet");
        assertTrue(space.contains(ComposedPlan.key(3, 3, 3)), "the room's middle, up to its ceiling");
        assertFalse(space.contains(ComposedPlan.key(3, 4, 3)), "the roof is not air");
        assertFalse(space.contains(ComposedPlan.key(11, 2, 3)), "the courtyard has no roof: it is not a room");
    }

    @Test
    @DisplayName("only natural blocks in the rooms go; a placed block, the plan's own cells and the cut's are left")
    void onlyPlantsInRooms() {
        Map<String, BuildClearing.Kind> world = new HashMap<>();
        world.put("103,51,203", BuildClearing.Kind.PLANT);   // a trunk in the room
        world.put("103,53,203", BuildClearing.Kind.PLANT);   // leaves in the room, higher
        world.put("102,51,203", BuildClearing.Kind.OTHER);   // his chest in the room
        world.put("111,52,203", BuildClearing.Kind.PLANT);   // a tree in the courtyard
        world.put("104,51,204", BuildClearing.Kind.PLANT);   // already taken by the hillside cut
        BuildClearing.World w = (x, y, z) -> world.getOrDefault(x + "," + y + "," + z, BuildClearing.Kind.AIR);
        BuildPlan p = roomAndCourtyard();
        Set<Long> plan = new java.util.HashSet<>();
        p.forEachBlock((x, y, z, b) -> plan.add(ComposedPlan.key(x, y, z)));
        BuildClearing.Cut c = BuildClearing.interiors(w, 100, 50, 200, WalkThrough.plan(p).roomSpace(), plan,
                Set.of("104,51,204"));
        assertEquals(List.of("103,53,203", "103,51,203"),
                c.cells().stream().map(a -> a[0] + "," + a[1] + "," + a[2]).toList(), "top first, room plants only");
        assertTrue(BuildClearing.roomsForTheAgent(c).contains("2 natural block(s)"));
        assertTrue(BuildClearing.roomsForTheAgent(c).contains("courtyard"));
        assertEquals("", BuildClearing.roomsForTheAgent(BuildClearing.Cut.NONE));
    }
}
