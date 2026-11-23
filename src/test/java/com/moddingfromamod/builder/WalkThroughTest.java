package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The walker's rules, one fixture per rule. The corpus calibration is {@link WalkThroughCorpusTest}. */
class WalkThroughTest {

    private static BuildShape box(int dx, int dy, int dz, int w, int h, int d, String block, boolean hollow) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, hollow);
    }

    /** A 7x5x7 stone room standing on the ground, floor at y 0, ceiling at y 4, plus whatever is added. */
    private static BuildPlan house(BuildShape... more) {
        List<BuildShape> s = new ArrayList<>();
        s.add(box(0, 0, 0, 7, 5, 7, "minecraft:stone", true));
        s.addAll(Arrays.asList(more));
        return new BuildPlan("t", "t", s);
    }

    private static WalkThrough.Room main(BuildPlan p) {
        WalkThrough.Report r = WalkThrough.of(p);
        assertFalse(r.tooBig());
        assertNotNull(r.main(), "no room found in " + r);
        return r.main();
    }

    @Test
    @DisplayName("a doorway two blocks tall lets him in; a closed box does not, and is named SEALED with a wall to cut")
    void doorwayOrSealed() {
        WalkThrough.Room sealed = main(house());
        assertFalse(sealed.reached());
        assertEquals(WalkThrough.Cause.SEALED, sealed.cause());
        assertNotNull(sealed.cut(), "a sealed ground room names the wall cell to open");
        assertEquals(1, sealed.cut()[1], "the cut is at his feet: the floor block is y 0, so feet are y 1");
        assertTrue(main(house(box(3, 1, 0, 1, 2, 1, "minecraft:air", false))).reached());
    }

    @Test
    @DisplayName("a one-block-tall hole is LOW_OPENING; a doorway raised two blocks is a step too high")
    void tooLowOrTooHigh() {
        // As drawn: as built, BuildRepair makes a doorway one block tall at the room's floor two tall.
        WalkThrough.Room low = WalkThrough.ofAsDrawn(house(box(3, 1, 0, 1, 1, 1, "minecraft:air", false))).main();
        assertFalse(low.reached());
        assertEquals(WalkThrough.Cause.LOW_OPENING, low.cause());
        // feet inside at y 1; a doorway at y 2..3 is reachable by air, by a crawl from nowhere, not on foot
        WalkThrough.Room high = main(house(box(3, 2, 0, 1, 2, 1, "minecraft:air", false)));
        assertFalse(high.reached());
        assertEquals(WalkThrough.Cause.UNREACHABLE_STEP, high.cause());
    }

    @Test
    @DisplayName("a wooden door is a way in; an iron door is a wall; a fence gate opens")
    void doors() {
        assertTrue(main(house(box(3, 1, 0, 1, 1, 1, "minecraft:oak_door[half=lower,facing=south]", false),
                box(3, 2, 0, 1, 1, 1, "minecraft:oak_door[half=upper,facing=south]", false))).reached());
        BuildPlan iron = house(box(3, 1, 0, 1, 1, 1, "minecraft:iron_door[half=lower,facing=south]", false),
                box(3, 2, 0, 1, 1, 1, "minecraft:iron_door[half=upper,facing=south]", false));
        assertFalse(WalkThrough.ofAsDrawn(iron).main().reached(), "as drawn, an iron door is a wall");
        assertTrue(main(iron).reached(), "as built, BuildDoors makes it wooden: no button is near it");
        assertTrue(main(house(box(3, 1, 0, 1, 1, 1, "minecraft:oak_fence_gate", false),
                box(3, 2, 0, 1, 1, 1, "minecraft:air", false))).reached());
    }

    @Test
    @DisplayName("an upper storey with no stairs is NO_WAY_UP; a ladder against the wall through its floor reaches it")
    void storeys() {
        // two storeys: ground floor y0..4 with a door, middle floor at y 4, upper room y 5..8
        BuildShape upper = box(0, 4, 0, 7, 5, 7, "minecraft:stone", true);
        BuildShape door = box(3, 1, 0, 1, 2, 1, "minecraft:air", false);
        BuildShape window = box(3, 6, 0, 1, 1, 1, "minecraft:glass", false);
        WalkThrough.Report none = WalkThrough.of(house(upper, door, window));
        WalkThrough.Room up = none.rooms().stream().filter(r -> r.floorY() >= 4).findFirst().orElseThrow();
        assertFalse(up.reached());
        assertEquals(WalkThrough.Cause.SEALED, up.cause(), "the glass window keeps the air out too");
        BuildShape openWindow = box(3, 6, 0, 1, 1, 1, "minecraft:air", false);
        WalkThrough.Room up2 = WalkThrough.of(house(upper, door, openWindow)).rooms().stream()
                .filter(r -> r.floorY() >= 4).findFirst().orElseThrow();
        assertEquals(WalkThrough.Cause.NO_WAY_UP, up2.cause());
        // a hole in the middle floor at (5,4,5) against the east wall, and a ladder up through it
        BuildShape hole = box(5, 4, 5, 1, 1, 1, "minecraft:air", false);
        BuildShape ladder = box(5, 1, 5, 1, 4, 1, "minecraft:ladder[facing=west]", false);
        WalkThrough.Report climbed = WalkThrough.of(house(upper, door, hole, ladder));
        assertTrue(climbed.rooms().stream().allMatch(WalkThrough.Room::reached), climbed.toString());
    }

    @Test
    @DisplayName("stairs up to a raised floor walk; a raised floor with a one-block step jumps; a 1.5 fence does not")
    void steps() {
        // a platform 1 high in front of the door: a jump with headroom outside
        BuildShape step = box(2, 0, -2, 3, 1, 2, "minecraft:stone", false);
        BuildShape door = box(3, 2, 0, 1, 2, 1, "minecraft:air", false);
        BuildShape raisedFloor = box(1, 1, 1, 5, 1, 5, "minecraft:stone", false);
        assertTrue(main(house(raisedFloor, step, door)).reached(), "one block up outside, then in");
        // the same with only a fence to stand on: a fence is a block and a half
        BuildShape fence = box(2, 0, -2, 3, 1, 2, "minecraft:oak_fence", false);
        assertFalse(main(house(raisedFloor, fence, door)).reached());
    }

    @Test
    @DisplayName("a bunker: through a closed trapdoor and down a ladder into a room dug out of the ground")
    void bunker() {
        List<BuildShape> s = List.of(
                box(0, -8, 0, 9, 7, 9, "minecraft:stone", true),        // shell, top at y -2
                box(1, -7, 1, 7, 5, 7, "minecraft:air", false),          // the room
                box(4, -7, 1, 1, 6, 1, "minecraft:ladder[facing=south]", false), // up to y -2, against the north wall
                box(4, -1, 1, 1, 1, 1, "minecraft:spruce_trapdoor[half=top]", false));
        WalkThrough.Report r = WalkThrough.of(new BuildPlan("b", "b", s));
        assertNotNull(r.main());
        assertTrue(r.main().reached(), "the hatch opens and the ladder goes down: " + r);
        // with the trapdoor replaced by stone, it is sealed
        List<BuildShape> shut = new ArrayList<>(s);
        shut.set(3, box(4, -1, 1, 1, 1, 1, "minecraft:stone", false));
        assertFalse(WalkThrough.of(new BuildPlan("b", "b", shut)).main().reached());
    }

    @Test
    @DisplayName("the note names each room and where, says it is fine if decoration, and leaves out what SEALED already said")
    void note() {
        WalkThrough.Report r = WalkThrough.ofAsDrawn(house(box(3, 1, 0, 1, 1, 1, "minecraft:air", false)));
        String note = WalkThrough.note(r, true);
        assertTrue(note.contains("WALK-THROUGH"), note);
        assertTrue(note.contains("under two blocks tall"), note);
        assertTrue(note.contains("decoration"), note);
        assertEquals("", WalkThrough.note(WalkThrough.of(house()), true), "SEALED already told");
        assertTrue(WalkThrough.note(WalkThrough.of(house()), false).contains("no way in at all"));
        assertEquals("", WalkThrough.note(WalkThrough.of(house(box(3, 1, 0, 1, 2, 1, "minecraft:air", false))), false));
    }

    @Test
    @DisplayName("a tour walks from outside through the door to the room's middle, one column at a time")
    void tour() {
        WalkThrough walk = WalkThrough.plan(house(box(3, 1, 0, 1, 2, 1, "minecraft:air", false)));
        List<WalkThrough.Step> t = walk.tour();
        assertFalse(t.isEmpty());
        WalkThrough.Step last = t.get(t.size() - 1);
        assertEquals(3, last.x());
        assertTrue(last.z() >= 2 && last.z() <= 3, "inside, in the middle: " + last);
        assertTrue(t.stream().anyMatch(st -> st.x() == 3 && st.z() == 0), "it goes through the doorway");
        for (int i = 1; i < t.size(); i++) {
            int dist = Math.abs(t.get(i).x() - t.get(i - 1).x()) + Math.abs(t.get(i).z() - t.get(i - 1).z());
            assertTrue(dist <= 1, "one column at a time: " + t.get(i - 1) + " -> " + t.get(i));
        }
    }
}
