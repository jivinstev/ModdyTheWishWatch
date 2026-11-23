package com.moddingfromamod.builder;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The buildings refinement metric, tested the way the creature one wasn't at first.
 *
 * <p>Every assertion here is one a broken metric would fail. The two that matter most are
 * {@link #anEditThatAddsATowerKeepsTheCastle()} and {@link #aRebuildFromScratchLosesTheCastle()} —
 * between them they are the whole measurement, and a metric that cannot tell those two apart would
 * report the defect and the fix as the same number.
 */
class BuildFingerprintTest {

    private static BuildShape box(int dx, int dy, int dz, int w, int h, int d, String block) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, false);
    }

    private static BuildPlan plan(BuildShape... shapes) {
        return new BuildPlan("keep", "The Keep", List.of(shapes));
    }

    // ───────────────────────── the fingerprint ─────────────────────────

    @Test
    @DisplayName("the palette is weighted by what is standing, not by how many shapes name a block")
    void thePaletteIsWeightedByWhatIsStanding() {
        // A big stone keep and a one-block gold doorstep. A distinct-ids list would call these
        // equals; a child looking at it sees a stone castle.
        BuildPlan p = plan(box(0, 0, 0, 10, 10, 10, "minecraft:stone"),
                           box(20, 0, 0, 1, 1, 1, "minecraft:gold_block"));
        List<String> palette = BuildFingerprint.palette(p);
        assertTrue(palette.get(0).startsWith("minecraft:stone:"),
                "the thing it mainly IS comes first: " + palette);
        assertTrue(palette.stream().anyMatch(e -> e.startsWith("minecraft:gold_block:")),
                "…and the doorstep is still present: " + palette);
    }

    @Test
    @DisplayName("a block painted over by a later shape leaves the palette")
    void aBlockPaintedOverLeavesThePalette() {
        // Shapes are placed in order and later ones overwrite. A palette read off the shape list
        // would still claim this building is stone; nothing stone is left standing.
        BuildPlan p = plan(box(0, 0, 0, 5, 5, 5, "minecraft:stone"),
                           box(0, 0, 0, 5, 5, 5, "minecraft:purple_concrete"));
        List<String> palette = BuildFingerprint.palette(p);
        assertEquals(1, palette.size(), "only one block is actually standing: " + palette);
        assertTrue(palette.get(0).startsWith("minecraft:purple_concrete:"), palette.toString());
    }

    @Test
    @DisplayName("air carves rather than fills, so the carved blocks are gone")
    void airCarvesRatherThanFills() {
        BuildPlan solid = plan(box(0, 0, 0, 5, 5, 5, "minecraft:stone"));
        BuildPlan carved = plan(box(0, 0, 0, 5, 5, 5, "minecraft:stone"),
                                box(2, 0, 2, 1, 5, 1, "minecraft:air"));
        List<String> palette = BuildFingerprint.palette(carved);
        assertEquals(1, palette.size(), "the doorway is a hole, not a block: " + palette);
        assertEquals(5 * 5 * 5 - 5, BuildFingerprint.of(carved).volume(),
                "and the carved blocks are gone from the volume too");
        // Asserted through survival as well, because that is the ONLY form of this check that
        // exercises this class's own composition on both sides. The first cut asserted only the
        // volume, which came from BuildGeometry — so a composition here that treated air as a
        // fill passed the "air carves" test. A gate that green-lights the bug it is named for is
        // worse than no gate.
        BuildFingerprint.Survival s = BuildFingerprint.survival(solid, carved);
        assertEquals(125, s.before());
        assertEquals(120, s.kept(), "five blocks of the solid keep did not survive the doorway");
    }

    @Test
    @DisplayName("air never appears in the palette, however it is composed")
    void airNeverAppearsInThePalette() {
        // The other half of the same rule, and it fails under a different mutation: a composition
        // that writes every shape's block unconditionally puts `minecraft:air` in the palette, and
        // then `palette_top:` can be answered by a hole.
        BuildPlan p = plan(box(0, 0, 0, 4, 4, 4, "minecraft:stone"),
                           box(0, 0, 0, 4, 4, 4, "minecraft:air"),
                           box(10, 0, 0, 2, 2, 2, "minecraft:gold_block"));
        assertTrue(BuildFingerprint.palette(p).stream().noneMatch(e -> e.startsWith("minecraft:air")),
                "air is absence: " + BuildFingerprint.palette(p));
    }

    @Test
    @DisplayName("the volume matches the canonical BuildGeometry, so the two compositions agree")
    void volumeMatchesTheCanonicalGeometry() {
        // This class composes a plan itself, because it needs to know WHICH block ends up at each
        // position and BuildGeometry keeps only a boolean. Two compositions of one plan is a
        // divergence waiting to happen — a metric describing a building the builder never puts up.
        // So they are pinned to each other here, over the cases that differ: overwrites, carving,
        // hollow shapes and round ones.
        List<BuildPlan> plans = List.of(
                plan(box(0, 0, 0, 9, 9, 9, "minecraft:stone")),
                plan(box(0, 0, 0, 9, 9, 9, "minecraft:stone"),
                     box(3, 0, 3, 3, 9, 3, "minecraft:air")),
                plan(box(0, 0, 0, 9, 9, 9, "minecraft:stone"),
                     box(0, 0, 0, 9, 9, 9, "minecraft:purple_concrete")),
                plan(new BuildShape(BuildShape.Kind.CYLINDER, 0, 0, 0, 11, 14, 11,
                        "minecraft:stone", true)),
                plan(new BuildShape(BuildShape.Kind.SPHERE, 0, 0, 0, 12, 12, 12,
                        "minecraft:glass", true)),
                plan(new BuildShape(BuildShape.Kind.PYRAMID, 0, 0, 0, 13, 8, 13,
                        "minecraft:oak_planks", false)));
        for (BuildPlan p : plans) {
            assertEquals(BuildGeometry.of(p).solidCount(), BuildFingerprint.of(p).volume(),
                    "the fingerprint and the geometry must compose the same building: " + p);
            assertEquals(BuildGeometry.of(p).height(), BuildFingerprint.of(p).height(), "height");
            assertEquals(BuildGeometry.of(p).span(), BuildFingerprint.of(p).span(), "span");
        }
    }

    @Test
    @DisplayName("the palette order is stable, so two reads of one building agree")
    void thePaletteOrderIsStable() {
        // Ties are broken by id. Without that, a HashMap iteration order can reorder equal shares
        // between two reads and `palette_top:` becomes a coin toss.
        BuildPlan p = plan(box(0, 0, 0, 4, 4, 4, "minecraft:stone"),
                           box(10, 0, 0, 4, 4, 4, "minecraft:oak_planks"));
        assertEquals(BuildFingerprint.palette(p), BuildFingerprint.palette(p));
        assertTrue(BuildFingerprint.palette(p).get(0).startsWith("minecraft:oak_planks:"),
                "equal shares tie-break by id, so the answer is at least deterministic");
    }

    @Test
    @DisplayName("height, span and volume come from what is standing")
    void theDimensionsComeFromWhatIsStanding() {
        BuildFingerprint.Fingerprint f = BuildFingerprint.of(
                plan(box(0, 0, 0, 8, 20, 6, "minecraft:stone")));
        assertEquals(20, f.height());
        assertEquals(8, f.span(), "the LONGER horizontal extent");
        assertEquals(8 * 20 * 6, f.volume());
        assertEquals(1, f.shapes());
    }

    @Test
    @DisplayName("a building that was never made fingerprints as nothing, not as an error")
    void anAbsentBuildingFingerprintsAsNothing() {
        BuildFingerprint.Fingerprint f = BuildFingerprint.of(null);
        assertEquals(0, f.volume());
        assertEquals(List.of(), f.palette());
    }

    // ───────────────────────── survival ─────────────────────────

    @Test
    @DisplayName("an edit that adds a tower keeps the castle")
    void anEditThatAddsATowerKeepsTheCastle() {
        BuildPlan castle = plan(box(0, 0, 0, 20, 10, 20, "minecraft:stone"));
        BuildPlan withTower = plan(box(0, 0, 0, 20, 10, 20, "minecraft:stone"),
                                   box(4, 10, 4, 6, 12, 6, "minecraft:stone"));
        assertEquals(1.0, BuildFingerprint.survival(castle, withTower).fraction(), 1e-9,
                "every block of the original is still standing");
        assertEquals(1.0, BuildFingerprint.footprintSurvival(castle, withTower).fraction(), 1e-9);
    }

    @Test
    @DisplayName("a rebuild from scratch loses the castle")
    void aRebuildFromScratchLosesTheCastle() {
        // The failure this whole measurement exists for: a different worker re-invents the
        // building from the words alone and puts something else on the same anchor.
        BuildPlan castle = plan(box(0, 0, 0, 20, 10, 20, "minecraft:stone"));
        BuildPlan tower = plan(box(0, 0, 0, 6, 30, 6, "minecraft:stone"));
        double kept = BuildFingerprint.survival(castle, tower).fraction();
        assertTrue(kept < 0.2, "a 6-wide tower cannot preserve a 20-wide keep, got " + kept);
        double ground = BuildFingerprint.footprintSurvival(castle, tower).fraction();
        assertTrue(ground < 0.2, "and the ground plan is gone too, got " + ground);
    }

    @Test
    @DisplayName("footprint survival forgives a re-roof and does not forgive a relocation")
    void footprintSurvivalForgivesAReroof() {
        // The R4 question names for buildings: the footprint and the room layout, not
        // the palette. A new roof is a legitimate refinement; a building moved off its own plot
        // is the thing that must never read as preserved.
        BuildPlan house = plan(box(0, 0, 0, 12, 6, 12, "minecraft:stone"));
        BuildPlan reroofed = plan(box(0, 0, 0, 12, 6, 12, "minecraft:stone"),
                                  new BuildShape(BuildShape.Kind.PYRAMID, 0, 6, 0, 12, 6, 12,
                                          "minecraft:oak_planks", false));
        assertEquals(1.0, BuildFingerprint.footprintSurvival(house, reroofed).fraction(), 1e-9);

        BuildPlan moved = plan(box(40, 0, 40, 12, 6, 12, "minecraft:stone"));
        assertEquals(0.0, BuildFingerprint.footprintSurvival(house, moved).fraction(), 1e-9,
                "same building, different plot — the child's castle is gone from where it stood");
    }

    @Test
    @DisplayName("the footprint is a DIFFERENT number from the block count, not a second name for it")
    void theFootprintIsADifferentNumberFromTheBlockCount() {
        // The case the re-roof test above cannot make: hollowing a solid keep out so the child can
        // walk inside is a legitimate refinement that keeps the whole ground plan and destroys half
        // the blocks. Without this, a footprint metric that had quietly forgotten to drop the Y
        // coordinate — i.e. one that was just `survival` under another name — passed every test.
        BuildPlan solid = plan(box(0, 0, 0, 12, 6, 12, "minecraft:stone"));
        BuildPlan hollowed = plan(new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 12, 6, 12,
                "minecraft:stone", true));
        assertEquals(1.0, BuildFingerprint.footprintSurvival(solid, hollowed).fraction(), 1e-9,
                "it stands on exactly the same ground");
        double blocks = BuildFingerprint.survival(solid, hollowed).fraction();
        assertTrue(blocks < 0.7,
                "…while most of its blocks are gone, which is what makes these two metrics worth "
                        + "having separately, got " + blocks);
    }

    @Test
    @DisplayName("survival counts blocks, not shapes, so an equivalent re-description scores high")
    void survivalCountsBlocksNotShapes() {
        // One 20-wide wall, versus two 10-wide walls describing exactly the same wall. A
        // shape-identity metric scores this 0 and would report a rebuild that changed nothing.
        BuildPlan oneShape = plan(box(0, 0, 0, 20, 4, 4, "minecraft:stone"));
        BuildPlan twoShapes = plan(box(0, 0, 0, 10, 4, 4, "minecraft:stone"),
                                   box(10, 0, 0, 10, 4, 4, "minecraft:stone"));
        assertEquals(1.0, BuildFingerprint.survival(oneShape, twoShapes).fraction(), 1e-9);
    }

    @Test
    @DisplayName("a repaint is NOT a structural loss")
    void aRepaintIsNotAStructuralLoss() {
        // Deliberate, and it is the entire point of grading structure separately (R4): the colour
        // judge already reports repaints. If survival fell when the paint changed, the two metrics
        // would move together and the class would learn nothing the colour gate did not know.
        BuildPlan stone = plan(box(0, 0, 0, 10, 10, 10, "minecraft:stone"));
        BuildPlan gold = plan(box(0, 0, 0, 10, 10, 10, "minecraft:gold_block"));
        assertEquals(1.0, BuildFingerprint.survival(stone, gold).fraction(), 1e-9,
                "the building is untouched; only its colour changed");
        assertTrue(BuildFingerprint.palette(stone).get(0)
                        .startsWith("minecraft:stone:"),
                "…and the palette is where the repaint shows up instead");
        assertTrue(BuildFingerprint.palette(gold).get(0).startsWith("minecraft:gold_block:"));
    }

    @Test
    @DisplayName("nothing to survive is null, never a score")
    void nothingToSurviveIsNullNeverAScore() {
        BuildPlan empty = new BuildPlan("x", "x", List.of());
        assertNull(BuildFingerprint.survival(empty, plan(box(0, 0, 0, 4, 4, 4, "minecraft:stone")))
                .fraction(), "a building that never existed has not been measured");
        assertNotNull(BuildFingerprint.survival(plan(box(0, 0, 0, 4, 4, 4, "minecraft:stone")), empty)
                .fraction(), "…but a building that was DELETED very much has been");
        assertEquals(0.0, BuildFingerprint.survival(
                plan(box(0, 0, 0, 4, 4, 4, "minecraft:stone")), empty).fraction(), 1e-9);
    }

    @Test
    @DisplayName("survival is measured against the anchor, so a rebuild in place is comparable")
    void survivalIsAnchorRelative() {
        // BuildStructureTool rebuilds an existing id at the anchor it already stands on, so
        // anchor-relative occupancy is the same patch of world between two rungs. This test is the
        // reason the metric may be a plain set intersection rather than a registration.
        BuildPlan a = plan(box(0, 0, 0, 10, 10, 10, "minecraft:stone"));
        BuildPlan shiftedByOne = plan(box(1, 0, 0, 10, 10, 10, "minecraft:stone"));
        double kept = BuildFingerprint.survival(a, shiftedByOne).fraction();
        assertTrue(kept > 0.85 && kept < 1.0,
                "a one-block nudge is a small loss, not a total one, got " + kept);
    }
}
