package com.moddingfromamod.builder;

import java.util.List;

import com.moddingfromamod.marshal.WatchIcons;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** one composition of a plan, the building as it will stand, shared by every check. */
class ComposedPlanTest {

    private static BuildShape box(int dx, int dy, int dz, int w, int h, int d, String block) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, false);
    }

    private static final BuildPlan MOAT = new BuildPlan("m", "Moat", List.of(
            box(0, 0, 0, 10, 1, 10, "minecraft:lava"),
            box(1, 0, 1, 8, 1, 8, "minecraft:stone"),
            box(4, 0, 4, 2, 1, 2, "minecraft:air")));

    @Test
    @DisplayName("later shapes win and air empties, exactly as BuildPlan.forEachBlock places them")
    void composesLikeTheBuilder() {
        ComposedPlan c = ComposedPlan.of(MOAT);
        assertEquals("minecraft:lava", c.blockAt(0, 0, 0));
        assertEquals("minecraft:stone", c.blockAt(1, 0, 1));
        assertNull(c.blockAt(4, 0, 4), "carved");
        assertEquals(100 - 4, c.size());
        assertEquals(36, c.lights().size(), "only the ring of lava the stone left");
    }

    @Test
    @DisplayName("plus() lays shapes on top without recomposing; as() reuses only for the same shapes")
    void sharing() {
        ComposedPlan c = ComposedPlan.of(MOAT);
        BuildShape lantern = box(4, 0, 4, 1, 1, 1, "minecraft:lantern");
        BuildPlan more = MOAT.withShapes(new java.util.ArrayList<>(MOAT.shapes()) {{
            add(lantern);
        }});
        ComposedPlan lit = c.plus(more, List.of(lantern));
        assertEquals(ComposedPlan.of(more).size(), lit.size());
        assertEquals(ComposedPlan.of(more).lights().size(), lit.lights().size());
        assertNull(c.blockAt(4, 0, 4), "the original is untouched");
        // Same shapes, different spots: the same blocks, the new plan.
        BuildPlan spotted = more.withSpots(List.of(new BuildSpot("seat", 4, 1, 4, "north")));
        ComposedPlan same = lit.as(spotted);
        assertSame(spotted, same.plan());
        assertEquals(lit.size(), same.size());
        // Different shapes: composed afresh, never answered for the wrong building.
        assertEquals(ComposedPlan.of(MOAT).size(), lit.as(MOAT).size());
    }

    @Test
    @DisplayName("#1386: the lava-moat castle's watch icon is what it is made of, not a lava bucket")
    void theIconIsTheBuildingNotTheDrawing() throws Exception {
        BuildPlan castle = ComposedLightCensusTest.starter("lava_moat_castle");
        assertIconIsTheBuilding(castle);
        assertSame(ComposedPlan.blockCounts(castle), ComposedPlan.blockCounts(castle),
                "remembered: the watch asks on every open");
    }

    /** The control (summed shape volumes, what the watch used to ask) says lava; the building as built says stone. */
    static void assertIconIsTheBuilding(BuildPlan castle) {
        java.util.Map<String, Integer> drawn = new java.util.LinkedHashMap<>();
        for (BuildShape s : castle.shapes()) {
            drawn.merge(s.block(), s.volume(), Integer::sum);
        }
        assertEquals("minecraft:lava_bucket", WatchIcons.forBuild(drawn),
                "as drawn the moat outweighs the walls");
        assertEquals("minecraft:stone_bricks",
                WatchIcons.forBuild(ComposedPlan.blockCounts(castle)),
                "as built most of the moat is under the island");
    }

    @Test
    @DisplayName("a null plan composes to nothing, and every reader says so")
    void nothing() {
        ComposedPlan none = ComposedPlan.of(null);
        assertTrue(none.isEmpty() && none.lights().isEmpty());
        assertTrue(BuildGeometry.of(none).isEmpty());
        assertNull(BuildFraming.bounds(none));
        assertTrue(BuildLighting.lightsFor(none).isEmpty());
        assertTrue(!DarkInterior.of(none).dark());
    }
}
