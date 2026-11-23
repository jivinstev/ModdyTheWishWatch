package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** a ladder needs a wall, sand and scaffolding need something under them. */
class BuildSupportTest {

    private static BuildShape box(String name, int dx, int dy, int dz, int w, int h, int d,
                                  String block, boolean hollow) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, hollow,
                BuildShape.Anchor.CORNER, name);
    }

    private static BuildPlan plan(BuildShape... shapes) {
        return new BuildPlan("t", "T", List.of(shapes));
    }

    /** A 5x10x5 hollow stone tower whose inner west wall is at x=0 (the room is x1..3). */
    private static final BuildShape TOWER = box("tower", 0, 0, 0, 5, 10, 5, "minecraft:stone", true);

    @Test
    @DisplayName("a ladder against a wall, faced away from it, stands")
    void aBackedLadderStands() {
        BuildPlan p = plan(TOWER, box("ladder", 1, 1, 2, 1, 9, 1, "minecraft:ladder[facing=east]", false));
        assertEquals(List.of(), BuildSupport.check(p));
    }

    @Test
    @DisplayName("a ladder in the middle of the room has nothing behind it — every cell is reported")
    void aLadderInTheMiddleHangsOnNothing() {
        BuildPlan p = plan(TOWER, box("ladder", 2, 1, 2, 1, 9, 1, "minecraft:ladder[facing=east]", false));
        List<BuildSupport.Problem> got = BuildSupport.check(p);
        assertEquals(8, got.size());
        assertTrue(got.stream().allMatch(x -> x.need() == BuildSupport.Need.WALL));
        assertTrue(got.get(0).describe().contains("ladder") && got.get(0).describe().contains("(ladder)"),
                got.get(0).describe());
    }

    @Test
    @DisplayName("a ladder FACING the wall (backwards) is caught: the facing is where it points, not where it hangs")
    void aBackwardsLadderIsCaught() {
        BuildPlan p = plan(TOWER, box("ladder", 1, 1, 2, 1, 9, 1, "minecraft:ladder[facing=west]", false));
        assertEquals(8, BuildSupport.check(p).size());
    }

    @Test
    @DisplayName("a bare minecraft:ladder faces north, so it needs the wall to its SOUTH")
    void aBareLadderFacesNorth() {
        // Against the tower's inner south wall (z=4): backed. Against the west wall: not.
        assertEquals(List.of(), BuildSupport.check(
                plan(TOWER, box("l", 2, 1, 3, 1, 9, 1, "minecraft:ladder", false))));
        assertEquals(8, BuildSupport.check(
                plan(TOWER, box("l", 1, 1, 2, 1, 9, 1, "minecraft:ladder", false))).size());
    }

    @Test
    @DisplayName("glass is a wall, a pane, leaves and a fence are not")
    void whatCountsAsAWall() {
        assertTrue(BuildSupport.isFullFace("minecraft:glass"));
        assertTrue(BuildSupport.isFullFace("minecraft:oak_log[axis=y]"));
        assertTrue(BuildSupport.isFullFace("minecraft:stone_slab[type=double]"));
        assertFalse(BuildSupport.isFullFace("minecraft:stone_slab"));
        assertFalse(BuildSupport.isFullFace("minecraft:glass_pane"));
        assertFalse(BuildSupport.isFullFace("minecraft:oak_leaves"));
        assertFalse(BuildSupport.isFullFace("minecraft:oak_fence"));
        assertFalse(BuildSupport.isFullFace("minecraft:water"));
        assertFalse(BuildSupport.isFullFace(null));
    }

    @Test
    @DisplayName("a gap in the wall behind a ladder is a gap in the ladder")
    void aGapBehindIsReported() {
        BuildPlan p = plan(TOWER,
                box("window", 0, 4, 2, 1, 2, 1, "minecraft:air", false),
                box("ladder", 1, 1, 2, 1, 9, 1, "minecraft:ladder[facing=east]", false));
        List<BuildSupport.Problem> got = BuildSupport.check(p);
        assertEquals(2, got.size());
        assertEquals(4, got.get(0).y());
        assertEquals(5, got.get(1).y());
    }

    @Test
    @DisplayName("sand over air falls; sand on stone and sand on the lowest layer stand")
    void gravity() {
        BuildPlan floating = plan(box("deck", 0, 0, 0, 5, 1, 5, "minecraft:stone", false),
                box("sand", 0, 5, 0, 3, 1, 3, "minecraft:red_sand", false));
        assertEquals(9, BuildSupport.check(floating).size());
        BuildPlan onStone = plan(box("deck", 0, 0, 0, 5, 1, 5, "minecraft:stone", false),
                box("sand", 0, 1, 0, 3, 3, 3, "minecraft:sand", false));
        assertEquals(List.of(), BuildSupport.check(onStone));
        // The lowest layer rests on whatever ground the build is put on — an earlier issue's question.
        assertEquals(List.of(), BuildSupport.check(plan(box("beach", 0, 0, 0, 9, 1, 9,
                "minecraft:sand", false))));
        BuildPlan onWater = plan(box("sea", 0, 0, 0, 5, 1, 5, "minecraft:water", false),
                box("sand", 0, 1, 0, 1, 1, 1, "minecraft:gravel", false));
        assertEquals(1, BuildSupport.check(onWater).size(), "gravel on water sinks");
        assertEquals(1, BuildSupport.check(plan(box("deck", 0, 0, 0, 5, 1, 5, "minecraft:stone", false),
                box("powder", 2, 3, 2, 1, 1, 1, "minecraft:red_concrete_powder", false))).size());
    }

    @Test
    @DisplayName("scaffolding: a column from a floor stands, one in mid-air falls, a 6-wide bridge holds, 7 does not")
    void scaffolding() {
        BuildShape deck = box("deck", 0, 0, 0, 20, 1, 3, "minecraft:stone", false);
        assertEquals(List.of(), BuildSupport.check(plan(deck,
                box("col", 1, 1, 1, 1, 12, 1, "minecraft:scaffolding", false))));
        assertEquals(6, BuildSupport.check(plan(deck,
                box("col", 1, 10, 1, 1, 6, 1, "minecraft:scaffolding", false))).size());
        // A post, then a bridge out sideways at the top: 6 steps hold, the 7th falls.
        BuildShape post = box("post", 1, 1, 1, 1, 4, 1, "minecraft:scaffolding", false);
        assertEquals(List.of(), BuildSupport.check(plan(deck, post,
                box("bridge", 2, 4, 1, 6, 1, 1, "minecraft:scaffolding", false))));
        List<BuildSupport.Problem> seven = BuildSupport.check(plan(deck, post,
                box("bridge", 2, 4, 1, 7, 1, 1, "minecraft:scaffolding", false)));
        assertEquals(1, seven.size());
        assertEquals(8, seven.get(0).x());
    }

    @Test
    @DisplayName("scaffolding that stands only on the ground under the build is counted and named; on a floor it is not")
    void groundOnlyScaffolding() {
        BuildPlan fromGround = plan(box("deck", 0, 6, 0, 5, 1, 5, "minecraft:stone", false),
                box("col", 2, 0, -1, 1, 7, 1, "minecraft:scaffolding", false));
        assertEquals(List.of(), BuildSupport.check(fromGround));
        assertEquals(7, BuildSupport.groundOnlyScaffolding(fromGround));
        String note = BuildSupport.note(null, List.of(), 7);
        assertTrue(note.contains("7 scaffolding blocks stand only") && note.contains("ladder"), note);
        assertEquals("", BuildSupport.note(null, List.of(), 2), "one or two is not a climbing column");
        BuildPlan onFloor = plan(box("deck", 0, 0, 0, 5, 1, 5, "minecraft:stone", false),
                box("col", 2, 1, 2, 1, 7, 1, "minecraft:scaffolding", false));
        assertEquals(0, BuildSupport.groundOnlyScaffolding(onFloor));
    }

    @Test
    @DisplayName("a ladder that runs up into a ceiling with no hole cannot be climbed off; through the hole it can")
    void aLadderMustEndWhereHeCanStepOff() {
        // The tower's lid is at y=9. A ladder to y=8 ends right under it.
        BuildPlan capped = plan(TOWER, box("ladder", 1, 1, 2, 1, 8, 1, "minecraft:ladder[facing=east]", false));
        List<BuildSupport.Problem> got = BuildSupport.check(capped);
        assertEquals(1, got.size());
        assertEquals(BuildSupport.Need.EXIT, got.get(0).need());
        assertEquals(8, got.get(0).y());
        String note = BuildSupport.note(null, got);
        assertTrue(note.contains("THROUGH the floor or roof") && !note.contains("AGAINST a solid wall"), note);
        // Two below the lid still hits his head: he is nearly two blocks tall.
        assertEquals(1, BuildSupport.check(plan(TOWER,
                box("ladder", 1, 1, 2, 1, 7, 1, "minecraft:ladder[facing=east]", false))).size());
        // Carried up through the lid (the ladder replaces that block, still against the wall).
        assertEquals(List.of(), BuildSupport.check(plan(TOWER,
                box("ladder", 1, 1, 2, 1, 9, 1, "minecraft:ladder[facing=east]", false))));
        // A door or a trapdoor overhead is a way through, not a ceiling.
        assertEquals(List.of(), BuildSupport.check(plan(TOWER,
                box("ladder", 1, 1, 2, 1, 8, 1, "minecraft:ladder[facing=east]", false),
                box("hatch", 1, 9, 2, 1, 1, 1, "minecraft:oak_trapdoor", false))));
    }

    @Test
    @DisplayName("finish_building's hold names each unsound building and says nothing for sound ones")
    void holdReveal() {
        BuildPlan sound = plan(TOWER, box("ladder", 1, 1, 2, 1, 9, 1, "minecraft:ladder[facing=east]", false));
        BuildPlan capped = plan(TOWER, box("ladder", 1, 1, 2, 1, 8, 1, "minecraft:ladder[facing=east]", false));
        assertEquals(null, BuildSupport.holdReveal(java.util.Map.of("tower", sound)));
        String hold = BuildSupport.holdReveal(new java.util.LinkedHashMap<>(java.util.Map.of("tower", capped)));
        assertTrue(hold.startsWith("Not yet") && hold.contains("'tower'") && hold.contains("TOP of a ladder"), hold);
    }

    @Test
    @DisplayName("a torch, a door and a carpet need a floor; an upper door half stands on its lower half")
    void floors() {
        BuildShape deck = box("deck", 0, 0, 0, 9, 1, 9, "minecraft:stone", false);
        assertEquals(List.of(), BuildSupport.check(plan(deck,
                box("t", 1, 1, 1, 1, 1, 1, "minecraft:torch", false),
                box("d", 3, 1, 3, 1, 1, 1, "minecraft:oak_door", false),
                box("u", 3, 2, 3, 1, 1, 1, "minecraft:oak_door[half=upper]", false),
                box("c", 5, 1, 5, 2, 1, 2, "minecraft:red_carpet", false))));
        List<BuildSupport.Problem> floating = BuildSupport.check(plan(deck,
                box("lamp", 4, 6, 4, 1, 1, 1, "minecraft:torch", false)));
        assertEquals(1, floating.size());
        assertEquals(BuildSupport.Need.FLOOR, floating.get(0).need());
    }

    @Test
    @DisplayName("a wall torch and a lever hang by their facing; a floor lever needs a floor")
    void wallThings() {
        assertEquals(List.of(), BuildSupport.check(plan(TOWER,
                box("wt", 1, 5, 2, 1, 1, 1, "minecraft:wall_torch[facing=east]", false),
                box("lv", 2, 1, 2, 1, 1, 1, "minecraft:lever[face=floor]", false))));
        assertEquals(2, BuildSupport.check(plan(TOWER,
                box("wt", 2, 5, 2, 1, 1, 1, "minecraft:wall_torch[facing=east]", false),
                box("lv", 2, 3, 2, 1, 1, 1, "minecraft:lever", false))).size());
    }

    @Test
    @DisplayName("a ladder with no facing turns toward the wall beside it, and says so")
    void autoFaceTurnsTowardTheWall() {
        BuildPlan p = plan(TOWER, box("ladder", 1, 1, 2, 1, 9, 1, "minecraft:ladder", false));
        assertEquals(8, BuildSupport.check(p).size(), "faces north by default: no wall south of it");
        BuildSupport.Faced faced = BuildSupport.autoFace(p);
        assertEquals("minecraft:ladder[facing=east]", faced.plan().shapes().get(1).block());
        assertEquals(List.of("ladder → facing=east"), faced.turned());
        assertEquals(List.of(), BuildSupport.check(faced.plan()));
        String note = BuildSupport.note(faced, BuildSupport.check(faced.plan()));
        assertTrue(note.contains("turned 1 ladder") && note.contains("facing=east"), note);
        assertFalse(note.contains("CANNOT STAND"), note);
    }

    @Test
    @DisplayName("auto-face never touches a ladder the agent faced itself, and reports one with no wall anywhere")
    void autoFaceIsTimid() {
        BuildPlan faced = plan(TOWER, box("l", 1, 1, 2, 1, 9, 1, "minecraft:ladder[facing=west]", false));
        assertEquals(faced, BuildSupport.autoFace(faced).plan());
        BuildPlan alone = plan(box("deck", 0, 0, 0, 9, 1, 9, "minecraft:stone", false),
                box("l", 4, 1, 4, 1, 6, 1, "minecraft:ladder", false));
        BuildSupport.Faced f = BuildSupport.autoFace(alone);
        assertEquals(alone, f.plan());
        assertEquals(List.of("l"), f.unbacked());
        String note = BuildSupport.note(f, BuildSupport.check(f.plan()));
        assertTrue(note.contains("CANNOT STAND") && note.contains("AGAINST a solid wall"), note);
    }

    @Test
    @DisplayName("a wall torch keeps its other state when it is turned")
    void autoFaceKeepsState() {
        BuildPlan p = plan(TOWER, box("wt", 1, 5, 2, 1, 1, 1, "minecraft:redstone_wall_torch[lit=true]", false));
        assertEquals("minecraft:redstone_wall_torch[facing=east,lit=true]",
                BuildSupport.autoFace(p).plan().shapes().get(1).block());
    }

    @Test
    @DisplayName("the note counts everything and spells out at most MAX_SHOWN, and says nothing for a sound building")
    void noteShape() {
        assertEquals("", BuildSupport.note(BuildSupport.autoFace(plan(TOWER)),
                BuildSupport.check(plan(TOWER))));
        BuildPlan p = plan(TOWER, box("ladder", 2, 1, 2, 1, 9, 1, "minecraft:ladder[facing=east]", false));
        String note = BuildSupport.note(null, BuildSupport.check(p));
        assertTrue(note.contains("8 blocks") && note.contains("… and 2 more"), note);
    }

    @Test
    @DisplayName("negative offsets survive the position key (an underwater build goes down)")
    void negativeOffsets() {
        BuildPlan p = plan(box("tube", -1, -22, -1, 3, 23, 3, "minecraft:glass", true),
                box("air", 0, -21, 0, 1, 23, 1, "minecraft:air", false),
                box("ladder", 0, -21, 0, 1, 22, 1, "minecraft:ladder[facing=north]", false));
        assertEquals(List.of(), BuildSupport.check(p));
        long k = BuildSupport.key(-17, -31, 5);
        assertEquals(-17, BuildSupport.kx(k));
        assertEquals(-31, BuildSupport.ky(k));
        assertEquals(5, BuildSupport.kz(k));
    }
}
