package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link BuildGrounding}: what floats, what stands, and how far it hangs. */
class BuildGroundingTest {

    static BuildShape box(String name, int dx, int dy, int dz, int w, int h, int d, String block, boolean hollow) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, hollow, BuildShape.Anchor.CORNER, name);
    }

    static BuildPlan plan(BuildShape... shapes) {
        return new BuildPlan("t", "T", List.of(shapes));
    }

    /** A 7×5×7 room: floor at 0, walls and a roof (a hollow box), a doorway. */
    static List<BuildShape> room() {
        List<BuildShape> s = new ArrayList<>();
        s.add(box("room", 0, 0, 0, 7, 5, 7, "minecraft:stone_bricks", true));
        s.add(box("door", 3, 1, 0, 1, 2, 1, "minecraft:air", false));
        return s;
    }

    @Test
    void aRoomStands() {
        assertTrue(BuildGrounding.of(new BuildPlan("t", "T", room())).clean());
    }

    @Test
    void aLightInMidAirFloatsAndSaysHowFarItHangs() {
        List<BuildShape> s = room();
        s.add(box("light", 3, 2, 3, 1, 1, 1, "minecraft:glowstone", false));   // floor at 0, roof at 4: touching neither
        BuildGrounding.Report r = BuildGrounding.of(new BuildPlan("t", "T", s));
        assertEquals(1, r.floating().size(), r.toString());
        BuildGrounding.Floating f = r.floating().get(0);
        assertEquals(2, f.y());
        assertEquals(1, f.gap());
        assertTrue(f.describe().contains("glowstone"), f.describe());
    }

    @Test
    void aLightTouchingTheCeilingHangsFromIt() {
        List<BuildShape> s = room();
        s.add(box("light", 3, 3, 3, 1, 1, 1, "minecraft:lantern[hanging=true]", false));   // the roof is at 4
        assertTrue(BuildGrounding.of(new BuildPlan("t", "T", s)).clean());
    }

    @Test
    void stepsThatTouchOnlyAtTheirEdgesStand() {
        BuildGrounding.Report r = BuildGrounding.of(plan(
                box("s0", 0, 0, 0, 1, 1, 1, "minecraft:oak_planks", false),
                box("s1", 1, 1, 1, 1, 1, 1, "minecraft:oak_planks", false),
                box("s2", 2, 2, 2, 1, 1, 1, "minecraft:oak_planks", false)));
        assertTrue(r.clean(), r.toString());
    }

    @Test
    void aStemDrawnFromWhereItsMiddleWasMeantFloats() {
        // an earlier issue's mushroom village: the plaza at -1, a stem starting at dy 5, its cap round it.
        BuildGrounding.Report r = BuildGrounding.of(plan(
                box("plaza", -5, -1, -5, 11, 1, 11, "minecraft:dirt_path", false),
                box("stem", -1, 5, -1, 3, 8, 3, "minecraft:mushroom_stem", false)));
        assertEquals(1, r.floating().size());
        assertEquals(5, r.floating().get(0).gap(), "the plaza is at -1, the stem's foot at 5");
    }

    @Test
    void anAirborneBuildIsExemptButAPartFloatingOffItIsNot() {
        BuildGrounding.Report island = BuildGrounding.of(plan(
                box("island", 0, 20, 0, 9, 3, 9, "minecraft:grass_block", false)));
        assertTrue(island.airborne());
        assertTrue(island.clean(), "a floating island floats on purpose");
        BuildGrounding.Report cloud = BuildGrounding.of(plan(
                box("island", 0, 20, 0, 9, 3, 9, "minecraft:grass_block", false),
                box("cloud", 2, 30, 2, 3, 1, 3, "minecraft:white_wool", false)));
        assertTrue(cloud.airborne());
        assertEquals(1, cloud.floating().size());
        assertEquals("minecraft:white_wool", cloud.floating().get(0).lowest());
        assertEquals(7, cloud.floating().get(0).gap(), "the island's top is at 22, the cloud at 30");
    }

    @Test
    void anOpenAirDoorwayCountsTheDoorBuildDoorsHangs() {
        // A door in its doorway is a block of the build: a lantern standing on a door's top stands.
        List<BuildShape> s = room();
        s.add(box("above_door", 3, 3, -1, 1, 1, 1, "minecraft:lantern", false));
        BuildGrounding.Report r = BuildGrounding.of(new BuildPlan("t", "T", s));
        assertTrue(r.clean(), "it touches the wall above the doorway: " + r);
    }

    @Test
    void theAdviceNamesEachPartAndTheRule() {
        List<BuildShape> s = room();
        s.add(box("light", 3, 2, 3, 1, 1, 1, "minecraft:glowstone", false));
        String note = BuildGrounding.note(BuildGrounding.of(new BuildPlan("t", "T", s)));
        assertTrue(note.contains("1 part of this build stands on nothing"), note);
        assertTrue(note.contains("glowstone") && note.contains("dy2"), note);
        assertTrue(note.contains("dy is its BASE"), note);
        assertFalse(BuildGrounding.note(BuildGrounding.of(new BuildPlan("t", "T", room()))).length() > 0);
    }

    // ── the fix (PR 2) ───────────────────────────────────────────────────────────────────────────────────────────

    static java.util.Map<Long, String> standing(BuildPlan p, BuildGrounding.Fixed fx) {
        java.util.Map<Long, String> c = BuildDoors.applied(p, BuildDoors.of(p));
        c.values().removeIf(java.util.Objects::isNull);
        return BuildGrounding.applied(c, fx);
    }

    @Test
    void aLampInMidAirIsHungFromTheCeilingOnAChain() {
        List<BuildShape> s = room();
        s.add(box("light", 3, 2, 3, 1, 1, 1, "minecraft:lantern", false));   // the roof is at 4
        BuildPlan p = new BuildPlan("t", "T", s);
        BuildGrounding.Fixed fx = BuildGrounding.compute(p);
        assertTrue(fx.after().clean(), fx.toString());
        var w = standing(p, fx);
        assertEquals("minecraft:iron_chain", w.get(ComposedPlan.key(3, 3, 3)));
        assertEquals("minecraft:lantern[hanging=true]", w.get(ComposedPlan.key(3, 2, 3)));
        assertEquals(2, fx.cells().size(), "the chain and the lantern turned to hang: " + fx.cells());
        assertTrue(fx.actions().get(0).startsWith("hung the lantern"), fx.actions().toString());
    }

    @Test
    void aStemDrawnTooHighIsCarriedDownAndItsHollowIsLeftAlone() {
        BuildPlan p = plan(
                box("plaza", -5, -1, -5, 11, 1, 11, "minecraft:dirt_path", false),
                box("stem", -2, 5, -2, 5, 8, 5, "minecraft:mushroom_stem", true));   // a hollow tube, floor at 5
        BuildGrounding.Fixed fx = BuildGrounding.compute(p);
        assertTrue(fx.after().clean(), fx.actions().toString());
        var w = standing(p, fx);
        assertEquals("minecraft:mushroom_stem", w.get(ComposedPlan.key(-2, 0, 0)), "the wall reaches the plaza");
        assertFalse(w.containsKey(ComposedPlan.key(0, 0, 0)), "the tube's floor is not carried down: its room stays");
        assertTrue(fx.actions().get(0).startsWith("carried the mushroom stem"), fx.actions().toString());
    }

    @Test
    void aSmallLumpABlockOrTwoUpIsSetDown() {
        BuildPlan p = plan(
                box("floor", 0, 0, 0, 5, 1, 5, "minecraft:stone", false),
                box("crate", 2, 3, 2, 1, 1, 1, "minecraft:barrel", false));   // two above the floor at 0
        BuildGrounding.Fixed fx = BuildGrounding.compute(p);
        assertTrue(fx.after().clean());
        var w = standing(p, fx);
        assertEquals("minecraft:barrel", w.get(ComposedPlan.key(2, 1, 2)));
        assertFalse(w.containsKey(ComposedPlan.key(2, 3, 2)));
    }

    @Test
    void aBigLumpOrABigGapIsLeftAndReported() {
        BuildPlan tower = plan(
                box("floor", 0, 0, 0, 20, 1, 20, "minecraft:stone", false),
                box("tower", 0, 3, 0, 10, 10, 10, "minecraft:stone_bricks", false));   // 1000 blocks, two above
        BuildGrounding.Fixed big = BuildGrounding.compute(tower);
        assertTrue(big.cells().isEmpty(), "a tower is a design, never moved");
        assertEquals(1, big.after().floating().size());
        assertTrue(big.actions().get(0).startsWith("left "), big.actions().toString());
        BuildPlan sky = plan(
                box("floor", 0, 0, 0, 5, 1, 5, "minecraft:stone", false),
                box("cube", 2, 9, 2, 1, 1, 1, "minecraft:stone", false));   // eight above, nothing over it
        assertTrue(BuildGrounding.compute(sky).cells().isEmpty());
    }

    @Test
    void theFixNeverFillsADoorwayThePlanCut() {
        BuildPlan p = plan(
                box("ground", -3, -1, -3, 7, 1, 7, "minecraft:grass_block", false),
                box("post", 0, 3, 0, 1, 4, 1, "minecraft:oak_log", false),
                box("doorway", 0, 0, 0, 1, 2, 1, "minecraft:air", false));
        BuildGrounding.Fixed fx = BuildGrounding.compute(p);
        var w = standing(p, fx);
        assertFalse(w.containsKey(ComposedPlan.key(0, 0, 0)), "the doorway stays open");
        assertFalse(w.containsKey(ComposedPlan.key(0, 1, 0)));
    }

    @Test
    void aBuildThatStandsIsNeverTouched() {
        assertTrue(BuildGrounding.compute(new BuildPlan("t", "T", room())).cells().isEmpty());
    }

    @Test
    void theAgentIsToldWhatWasGroundedAndWhatStillFloats() {
        List<BuildShape> s = room();
        s.add(box("light", 3, 2, 3, 1, 1, 1, "minecraft:lantern", false));
        s.add(box("cloud", 0, 15, 0, 3, 1, 3, "minecraft:white_wool", false));
        String note = BuildGrounding.note(BuildGrounding.compute(new BuildPlan("t", "T", s)));
        assertTrue(note.contains("were grounded: hung the lantern"), note);
        assertTrue(note.contains("1 part of this build stands on nothing") && note.contains("white wool"), note);
    }

    @Test
    void theRewriteIsOffUntilSwitchedOn() {
        // the fix changes what players get, so it runs only with groundfix=on; the advice is on regardless.
        List<BuildShape> s = room();
        s.add(box("light", 3, 2, 3, 1, 1, 1, "minecraft:lantern", false));
        BuildPlan p = new BuildPlan("t", "T", s);
        String was = System.getProperty("moddingfromamod.groundfix");
        try {
            System.clearProperty("moddingfromamod.groundfix");
            assertTrue(BuildGrounding.fix(p).cells().isEmpty(), "off by default");
            assertFalse(BuildGrounding.compute(p).cells().isEmpty(), "compute is the same rewrite regardless");
            assertFalse(BuildGrounding.of(p).clean(), "the advice still sees it");
            System.setProperty("moddingfromamod.groundfix", "on");
            assertFalse(BuildGrounding.fix(p).cells().isEmpty());
        } finally {
            if (was == null) {
                System.clearProperty("moddingfromamod.groundfix");
            } else {
                System.setProperty("moddingfromamod.groundfix", was);
            }
        }
    }
}
