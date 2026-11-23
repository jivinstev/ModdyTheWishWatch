package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Which shape is setting the building's height — an earlier issue's whole owner-vs-stranger gap.</b>
 *
 * <p>Every case here is taken from the real ladder cell that failed. Asked to <i>"make it much
 * taller"</i>, a worker that had not built the castle raised the keep from 28 to 56 and the towers
 * from 36 to 72, and the building's height did not move: 98 before, 98 after, in three cells of
 * four, byte-identical. The tallest thing was {@code glass_dome} at y=98, added by the previous
 * rung, and the stranger never touched it.
 */
class InspectBuildTallestTest {

    private static BuildShape shape(String name, int dy, int height, String block) {
        return new BuildShape(BuildShape.Kind.BOX, 0, dy, 0, 10, height, 10, block, false,
                BuildShape.Anchor.CORNER, name);
    }

    /** The Glimmerhold as the stranger found it, reduced to the shapes that matter. */
    private static BuildPlan theCastle() {
        return new BuildPlan("glimmerhold", "The Glimmerhold", List.of(
                shape("outer_wall", 2, 15, "minecraft:purple_concrete"),
                shape("keep", 2, 28, "minecraft:purple_concrete"),
                shape("keep_roof", 30, 12, "minecraft:gold_block"),
                shape("tower_nw", 2, 36, "minecraft:gold_block"),
                shape("tower_nw_roof", 38, 10, "minecraft:gold_block"),
                shape("glass_dome", 24, 74, "minecraft:glass")));
    }

    @Test
    @DisplayName("the dome is named, not the keep or the towers")
    void itFindsTheShapeThatOwnsTheHeight() {
        BuildShape t = InspectBuildTool.tallestShape(theCastle());
        assertNotNull(t);
        assertEquals("glass_dome", t.name(),
                "the tallest shape is the one 'make it taller' has to move; naming any other is "
                + "what let a real refinement raise the keep and towers and change nothing");
        assertEquals(98, t.dy() + t.height(), "and its top is the building's height");
    }

    @Test
    @DisplayName("raising everything EXCEPT the tallest leaves the building the same height")
    void theMeasuredFailure() {
        // Exactly what the stranger sent: keep 28 -> 56, towers 36 -> 72, dome untouched.
        BuildPlan after = new BuildPlan("glimmerhold", "The Glimmerhold", List.of(
                shape("outer_wall", 2, 30, "minecraft:purple_concrete"),
                shape("keep", 2, 56, "minecraft:purple_concrete"),
                shape("keep_roof", 58, 12, "minecraft:gold_block"),
                shape("tower_nw", 2, 72, "minecraft:gold_block"),
                shape("tower_nw_roof", 74, 10, "minecraft:gold_block"),
                shape("glass_dome", 24, 74, "minecraft:glass")));
        BuildShape before = InspectBuildTool.tallestShape(theCastle());
        BuildShape now = InspectBuildTool.tallestShape(after);
        assertEquals(before.dy() + before.height(), now.dy() + now.height(),
                "98 before and 98 after — a large, correct-looking edit that moved the height by "
                + "zero, which is the defect this note exists to prevent");
        assertEquals("glass_dome", now.name(), "and the dome is STILL the tallest");
    }

    @Test
    @DisplayName("an AIR shape is never the tallest — it carves, it does not build")
    void airDoesNotSetTheHeight() {
        BuildPlan withSky = new BuildPlan("x", "x", List.of(
                shape("keep", 0, 20, "minecraft:stone"),
                shape("sky_hole", 0, 200, "minecraft:air")));
        assertEquals("keep", InspectBuildTool.tallestShape(withSky).name(),
                "a tall air shape removes rather than fills, so it cannot be what makes a "
                + "building tall — naming it would send the agent to shrink a hole");
    }

    @Test
    @DisplayName("an empty plan names nothing rather than throwing")
    void nothingToName() {
        assertNull(InspectBuildTool.tallestShape(new BuildPlan("x", "x", List.of())));
    }
}
