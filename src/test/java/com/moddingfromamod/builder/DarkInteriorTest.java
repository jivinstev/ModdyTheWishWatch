package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** a roofed room the building's own lights do not reach is reported dark before the reveal. */
class DarkInteriorTest {

    static BuildShape box(int dx, int dy, int dz, int w, int h, int d, String block,
                                  boolean hollow) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, hollow);
    }

    private static BuildPlan plan(BuildShape... shapes) {
        return new BuildPlan("room", "Room", List.of(shapes));
    }

    static BuildPlan with(BuildPlan p, BuildShape... more) {
        List<BuildShape> all = new ArrayList<>(p.shapes());
        all.addAll(List.of(more));
        return p.withShapes(all);
    }

    /** An 11×6×11 stone room with a door: floor at y=0, a child stands at y=1, roof at y=5. */
    private static final BuildPlan ROOM = plan(
            box(0, 0, 0, 11, 6, 11, "minecraft:stone_bricks", true),
            box(5, 1, 0, 1, 2, 1, "minecraft:air", false));

    @Test
    @DisplayName("an unlit roofed room is reported, with where to put the light")
    void anUnlitRoomIsDark() {
        DarkInterior.Report r = DarkInterior.of(ROOM);
        assertEquals(82, r.roofedFloor(), "the 9×9 floor inside the walls, and the doorway under the lintel");
        assertTrue(r.darkFloor() > 60, "most of it: " + r);
        assertEquals(1, r.areas().size());
        DarkInterior.Area a = r.areas().get(0);
        assertEquals(1, a.y(), "at the height a child stands");
        assertTrue(a.x() >= 3 && a.x() <= 7 && a.z() >= 3 && a.z() <= 7, "near its middle: " + a);
    }

    @Test
    @DisplayName("a lantern on the floor lights an 11-wide room")
    void aLanternLightsIt() {
        DarkInterior.Report r = DarkInterior.of(with(ROOM, box(5, 1, 5, 1, 1, 1, "minecraft:lantern", false)));
        assertFalse(r.dark(), r.toString());
        assertEquals("", DarkInterior.note(r), "silent when nothing is dark");
    }

    @Test
    @DisplayName("glowstone IN the ceiling lights it: a light block that stops light still gives it")
    void glowstoneInTheCeiling() {
        assertFalse(DarkInterior.of(with(ROOM, box(5, 5, 5, 1, 1, 1, "minecraft:glowstone", false))).dark());
    }

    @Test
    @DisplayName("a lantern ON the roof does not reach the room under it")
    void aLanternAboveTheCeiling() {
        DarkInterior.Report r = DarkInterior.of(with(ROOM, box(5, 6, 5, 1, 1, 1, "minecraft:lantern", false)));
        assertTrue(r.dark(), "the #1355 mechanism, in miniature: " + r);
    }

    @Test
    @DisplayName("light falls off: a long hall lit at one end is dark at the other")
    void lightFallsOff() {
        BuildPlan hall = plan(box(0, 0, 0, 7, 6, 40, "minecraft:stone_bricks", true),
                box(3, 1, 2, 1, 1, 1, "minecraft:lantern", false));
        DarkInterior.Report r = DarkInterior.of(hall);
        assertTrue(r.dark());
        DarkInterior.Area a = r.areas().get(0);
        assertTrue(a.z() > 20, "the far end, not the lantern's end: " + a);
    }

    @Test
    @DisplayName("an open courtyard is not an inside")
    void aCourtyardIsNotInside() {
        BuildPlan yard = plan(box(0, 0, 0, 15, 1, 15, "minecraft:stone", false),
                box(0, 1, 0, 15, 4, 1, "minecraft:stone", false),
                box(0, 1, 14, 15, 4, 1, "minecraft:stone", false),
                box(0, 1, 0, 1, 4, 15, "minecraft:stone", false),
                box(14, 1, 0, 1, 4, 15, "minecraft:stone", false));
        DarkInterior.Report r = DarkInterior.of(yard);
        assertEquals(0, r.roofedFloor());
        assertFalse(r.dark());
    }

    @Test
    @DisplayName("a lava moat the island overwrites is not a light")
    void overwrittenLavaIsNotALight() {
        BuildPlan moated = plan(box(-3, -2, -3, 17, 2, 17, "minecraft:lava", false),
                box(-1, -2, -1, 13, 2, 13, "minecraft:stone", false),
                box(0, 0, 0, 11, 6, 11, "minecraft:stone_bricks", true));
        assertTrue(DarkInterior.of(moated).dark(), "the ring left over is outside the walls");
    }

    @Test
    @DisplayName("light passes glass: a lantern behind a window lights the room")
    void lightPassesGlass() {
        BuildPlan behindGlass = with(ROOM,
                box(1, 1, 5, 9, 4, 1, "minecraft:glass", false),
                box(5, 1, 7, 1, 1, 1, "minecraft:lantern", false));
        DarkInterior.Report r = DarkInterior.of(behindGlass);
        assertTrue(r.areas().stream().allMatch(a -> a.z() >= 6), "the far side of the glass is lit: " + r);
    }

    @Test
    @DisplayName("a candle is a light only when the plan lights it")
    void candlesAreUnlitUnlessSaid() {
        assertEquals(0, DarkInterior.emission("minecraft:candle"));
        assertEquals(3, DarkInterior.emission("minecraft:candle[lit=true]"));
        assertEquals(12, DarkInterior.emission("minecraft:red_candle[candles=4,lit=true]"));
        assertEquals(0, DarkInterior.emission("minecraft:redstone_lamp"));
        assertEquals(15, DarkInterior.emission("minecraft:campfire"));
        assertEquals(15, DarkInterior.emission("minecraft:lantern[hanging=true]"));
        assertEquals(0, DarkInterior.emission("minecraft:stone"));
        assertTrue(DarkInterior.of(with(ROOM, box(5, 1, 5, 1, 1, 1, "minecraft:candle", false))).dark());
    }

    @Test
    @DisplayName("what passes light and what stops it")
    void passesLight() {
        assertTrue(DarkInterior.passesLight("minecraft:air"));
        assertTrue(DarkInterior.passesLight("minecraft:red_carpet"));
        assertTrue(DarkInterior.passesLight("minecraft:white_stained_glass_pane"));
        assertTrue(DarkInterior.passesLight("minecraft:oak_door[half=lower]"));
        assertFalse(DarkInterior.passesLight("minecraft:stone_bricks"));
        assertFalse(DarkInterior.passesLight("minecraft:gold_block"));
        assertFalse(DarkInterior.passesLight("minecraft:light_gray_wool"));
        assertFalse(DarkInterior.passesLight("minecraft:glowstone"));
        assertFalse(DarkInterior.passesLight("minecraft:bedrock"));
        assertFalse(DarkInterior.passesLight("minecraft:grass_block"));
    }

    @Test
    @DisplayName("a dark alcove too small to matter is not reported")
    void aSmallPocketIsQuiet() {
        // A lit room with a 1×2 dead-end alcove behind a pillar run: under MIN_DARK_FLOOR.
        BuildPlan p = with(ROOM, box(5, 1, 5, 1, 1, 1, "minecraft:lantern", false));
        assertFalse(DarkInterior.of(p).dark());
        assertTrue(DarkInterior.MIN_DARK_FLOOR > 2);
    }

    @Test
    @DisplayName("the note tells the agent not to judge lighting from the camera's photograph")
    void theNoteIsHonestAboutTheCamera() {
        String note = DarkInterior.note(DarkInterior.of(ROOM));
        assertTrue(note.contains("lanterns"), note);
        assertTrue(note.contains("camera client sees in the dark"), note);
    }

    @Test
    @DisplayName("a named spot outside the roof is not a dark interior")
    void aSpotOutsideIsNotReported() {
        BuildPlan p = ROOM.withSpots(List.of(new BuildSpot("door", 5, 1, -2, "north")));
        DarkInterior.Report r = DarkInterior.of(p);
        assertTrue(r.spots().isEmpty(), r.spots().toString());
        assertNotNull(r);
    }
}
