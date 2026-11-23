package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * a building on a hillside is cut into the hill as if it stood on flat ground. The rule is pure, so each
 * case is a world of a few columns: a slope, a block somebody placed, water, a tree on the slope, a hill higher
 * than the cap.
 */
class BuildClearingTest {

    /** A tiny world: everything not set is AIR. */
    private static final class Grid implements BuildClearing.World {
        final Map<String, BuildClearing.Kind> cells = new HashMap<>();

        void set(int x, int y, int z, BuildClearing.Kind k) {
            cells.put(x + "," + y + "," + z, k);
        }

        /** Ground from far below up to and including {@code top}. */
        void column(int x, int z, int top) {
            for (int y = -5; y <= top; y++) {
                set(x, y, z, BuildClearing.Kind.GROUND);
            }
        }

        @Override
        public BuildClearing.Kind at(int x, int y, int z) {
            return cells.getOrDefault(x + "," + y + "," + z, BuildClearing.Kind.AIR);
        }
    }

    private static List<int[]> footprint(int w, int d) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                out.add(new int[]{x, z, 0});
            }
        }
        return out;
    }

    private static Set<String> asSet(List<int[]> cells) {
        Set<String> out = new HashSet<>();
        for (int[] c : cells) {
            out.add(c[0] + "," + c[1] + "," + c[2]);
        }
        return out;
    }

    @Test
    @DisplayName("on flat ground nothing is cut")
    void flatGroundCutsNothing() {
        Grid g = new Grid();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                g.column(x, z, -1);
            }
        }
        BuildClearing.Cut cut = BuildClearing.hillside(g, 0, 0, 0, footprint(5, 5), Set.of());
        assertTrue(cut.isEmpty());
        assertEquals("", BuildClearing.forTheAgent(cut));
    }

    @Test
    @DisplayName("a slope: the uphill ground above the floor is cut, top first, and the agent is told")
    void aSlopeIsCutDownToTheFloor() {
        Grid g = new Grid();
        // ground rises one block per column along x: column x has its top at y = x - 1
        for (int x = 0; x < 4; x++) {
            g.column(x, 0, x - 1);
        }
        BuildClearing.Cut cut = BuildClearing.hillside(g, 0, 0, 0, footprint(4, 1), Set.of());
        // x=1: y0; x=2: y0,1; x=3: y0,1,2
        assertEquals(Set.of("1,0,0", "2,0,0", "2,1,0", "3,0,0", "3,1,0", "3,2,0"), asSet(cut.cells()));
        assertEquals(3, cut.columns());
        for (int i = 1; i < cut.cells().size(); i++) {
            assertTrue(cut.cells().get(i - 1)[1] >= cut.cells().get(i)[1], "the cut goes top first");
        }
        assertTrue(BuildClearing.forTheAgent(cut).contains("6 block(s) of hill out of"));
        // nothing BELOW the floor is ever cut
        assertTrue(cut.cells().stream().allMatch(c -> c[1] >= 0));
    }

    @Test
    @DisplayName("a cell the plan places is never cut: its own block goes there")
    void planCellsAreLeftToThePlan() {
        Grid g = new Grid();
        g.column(0, 0, 3);
        BuildClearing.Cut cut = BuildClearing.hillside(g, 10, 64, 10, List.of(new int[]{0, 0, 0}),
                Set.of(ComposedPlan.key(0, 0, 0), ComposedPlan.key(0, 2, 0)));
        // absolute: the column is at 10..; we asked the world at ax+x — so build the grid in absolute terms
        assertTrue(cut.isEmpty(), "the grid is at the origin, the building at (10,64,10): nothing there");
        Grid h = new Grid();
        for (int y = 60; y <= 67; y++) {
            h.set(10, y, 10, BuildClearing.Kind.GROUND);
        }
        BuildClearing.Cut c2 = BuildClearing.hillside(h, 10, 64, 10, List.of(new int[]{0, 0, 0}),
                Set.of(ComposedPlan.key(0, 0, 0), ComposedPlan.key(0, 2, 0)));
        assertEquals(Set.of("10,65,10", "10,67,10"), asSet(c2.cells()));
    }

    @Test
    @DisplayName("a block somebody placed, and water, are never cut")
    void othersAreLeft() {
        Grid g = new Grid();
        g.column(0, 0, 2);
        g.set(0, 1, 0, BuildClearing.Kind.OTHER);      // his cobblestone, or water, in the hillside
        BuildClearing.Cut cut = BuildClearing.hillside(g, 0, 0, 0, List.of(new int[]{0, 0, 0}), Set.of());
        assertEquals(Set.of("0,0,0", "0,2,0"), asSet(cut.cells()));
    }

    @Test
    @DisplayName("what grew on the cut ground goes with it, so a tree on the slope is not left hanging")
    void plantsOnTheCutGroundGoToo() {
        Grid g = new Grid();
        g.column(0, 0, 1);
        for (int y = 2; y <= 6; y++) {
            g.set(0, y, 0, BuildClearing.Kind.PLANT);   // a trunk and its crown
        }
        g.set(0, 8, 0, BuildClearing.Kind.PLANT);       // a leaf above a gap: not standing on it, left
        BuildClearing.Cut cut = BuildClearing.hillside(g, 0, 0, 0, List.of(new int[]{0, 0, 0}), Set.of());
        assertEquals(Set.of("0,0,0", "0,1,0", "0,2,0", "0,3,0", "0,4,0", "0,5,0", "0,6,0"), asSet(cut.cells()));
    }

    @Test
    @DisplayName("on flat ground a tree inside the footprint is NOT cut: only ground above the floor triggers a cut")
    void aTreeOnFlatGroundIsNotTheHillsQuestion() {
        Grid g = new Grid();
        g.column(0, 0, -1);
        for (int y = 0; y <= 5; y++) {
            g.set(0, y, 0, BuildClearing.Kind.PLANT);
        }
        assertTrue(BuildClearing.hillside(g, 0, 0, 0, List.of(new int[]{0, 0, 0}), Set.of()).isEmpty());
    }

    @Test
    @DisplayName("a hill taller than the cap is cut up to the cap, and the agent is warned")
    void aCliffAboveTheCapIsReported() {
        Grid g = new Grid();
        g.column(0, 0, BuildClearing.CUT_CAP + 10);
        BuildClearing.Cut cut = BuildClearing.hillside(g, 0, 0, 0, List.of(new int[]{0, 0, 0}), Set.of());
        assertEquals(BuildClearing.CUT_CAP + 1, cut.cells().size());
        assertEquals(1, cut.over());
        assertTrue(BuildClearing.forTheAgent(cut).contains("rises more than"));
        assertFalse(cut.cells().stream().anyMatch(c -> c[1] > BuildClearing.CUT_CAP));
    }

    @Test
    @DisplayName("no footprint (a sky castle), nothing cut")
    void noFootprintNoCut() {
        Grid g = new Grid();
        g.column(0, 0, 5);
        assertTrue(BuildClearing.hillside(g, 0, 0, 0, List.of(), Set.of()).isEmpty());
    }

    @Test
    @DisplayName("the footing's sentence carries the cut, and says removing it puts the ground back")
    void theAgentHearsItWithTheFooting() {
        Grid g = new Grid();
        g.column(0, 0, 1);
        BuildClearing.Cut cut = BuildClearing.hillside(g, 0, 0, 0, List.of(new int[]{0, 0, 0}), Set.of());
        String said = BuildFooting.forTheAgent(null, 0, 0, 0, List.of(BuildClearing.forTheAgent(cut)));
        assertTrue(said.contains("of hill out of"), said);
        assertTrue(said.contains("puts the ground back"), said);
    }
}
