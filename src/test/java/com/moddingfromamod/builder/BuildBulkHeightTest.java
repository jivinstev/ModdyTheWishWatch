package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>How tall a building READS, against how tall its bounding box is.</b>
 *
 * <p>Every case is the real `glimmerhold` ladder cell that was scored as a failure. Asked to
 * <i>"make it much taller"</i>, a worker raised the towers from 0 to 72 and the roofs to 81 — the
 * castle more than doubled — and {@code grew:height} failed it, because rung 2 had put a
 * {@code glass_dome} overhead at 104 and the bounding box never moved.
 *
 * <p>Across 13 stored cells that scored the original builder's arm 4/4 and the stranger arm <b>2/9</b>, while the
 * stranger was raising the castle by <b>more</b> on average (+34.0 blocks against +22.5). The gap
 * was the oracle, not the agent, and it was reported as an agent defect twice before anyone asked
 * how the number was produced.
 */
class BuildBulkHeightTest {

    /** A solid box, sized and placed the way the real plans place them. */
    private static BuildShape box(String name, int dx, int dy, int dz,
                                  int w, int h, int d, String block) {
        return new BuildShape(BuildShape.Kind.BOX, dx, dy, dz, w, h, d, block, false,
                BuildShape.Anchor.CORNER, name);
    }

    /**
     * The castle as it stood before "make it much taller", with the dome already overhead.
     *
     * <p>Kept small enough to compose quickly while holding the shape that matters: a wide, low
     * castle under one narrow thing that is much taller than all of it.
     */
    private static BuildPlan before() {
        return new BuildPlan("glimmerhold", "The Glimmerhold", List.of(
                box("keep", 0, 0, 0, 20, 12, 20, "minecraft:purple_concrete"),
                box("tower_nw", 0, 0, 0, 4, 16, 4, "minecraft:gold_block"),
                box("tower_ne", 16, 0, 0, 4, 16, 4, "minecraft:gold_block"),
                box("spire", 9, 0, 9, 2, 60, 2, "minecraft:glass")));
    }

    /** The same castle after the refinement: everything raised EXCEPT the spire. */
    private static BuildPlan after() {
        return new BuildPlan("glimmerhold", "The Glimmerhold", List.of(
                box("keep", 0, 0, 0, 20, 30, 20, "minecraft:purple_concrete"),
                box("tower_nw", 0, 0, 0, 4, 40, 4, "minecraft:gold_block"),
                box("tower_ne", 16, 0, 0, 4, 40, 4, "minecraft:gold_block"),
                box("spire", 9, 0, 9, 2, 60, 2, "minecraft:glass")));
    }

    @Test
    @DisplayName("the bounding box misses a castle that more than doubled — the measured bug")
    void theBoundingBoxIsOwnedByTheDecoration() {
        int was = BuildFingerprint.of(before()).height();
        int now = BuildFingerprint.of(after()).height();
        assertEquals(was, now,
                "this is the defect, pinned so nobody 'fixes' it by changing height: the keep went "
                + "12 -> 30 and the towers 16 -> 40, and the bounding box did not move one block "
                + "because a 60-tall spire sat over all of it");
    }

    @Test
    @DisplayName("bulkHeight sees it, because a spire owns almost no columns")
    void theBulkHeightGrows() {
        int was = BuildFingerprint.of(before()).bulkHeight();
        int now = BuildFingerprint.of(after()).bulkHeight();
        assertTrue(now > was,
                "the median occupied column rose with the keep, which is what the child sees; "
                + "was=" + was + " now=" + now);
        assertTrue(now - was >= 10,
                "and it rose by a lot rather than by a token amount — a +1 clearance over the "
                + "decoration is exactly what the old oracle rewarded; was=" + was + " now=" + now);
    }

    @Test
    @DisplayName("a tall thin thing cannot lift bulkHeight on its own")
    void addingASpireIsNotMakingItTaller() {
        BuildPlan plain = new BuildPlan("x", "x", List.of(
                box("keep", 0, 0, 0, 20, 12, 20, "minecraft:stone")));
        List<BuildShape> withSpire = new ArrayList<>(plain.shapes());
        withSpire.add(box("spire", 9, 0, 9, 2, 90, 2, "minecraft:glass"));
        int plainBulk = BuildFingerprint.of(plain).bulkHeight();
        int spiredBulk = BuildFingerprint.of(new BuildPlan("x", "x", withSpire)).bulkHeight();
        assertEquals(plainBulk, spiredBulk,
                "a 90-block needle over a 12-block keep is not a taller building, and the two "
                + "ladders that DO ask for a thing on top (eiffel's flag, floating_castle's "
                + "lighthouse) deliberately still judge on the bounding box instead");
        assertTrue(BuildFingerprint.of(new BuildPlan("x", "x", withSpire)).height() > plainBulk,
                "…while the bounding box does rise, which is why both numbers are kept");
    }

    @Test
    @DisplayName("raising the whole building raises both")
    void anHonestGrowthMovesBothNumbers() {
        BuildPlan low = new BuildPlan("x", "x", List.of(
                box("keep", 0, 0, 0, 16, 10, 16, "minecraft:stone")));
        BuildPlan high = new BuildPlan("x", "x", List.of(
                box("keep", 0, 0, 0, 16, 40, 16, "minecraft:stone")));
        assertTrue(BuildFingerprint.of(high).height() > BuildFingerprint.of(low).height());
        assertTrue(BuildFingerprint.of(high).bulkHeight() > BuildFingerprint.of(low).bulkHeight(),
                "the simple case must still work, or the guard is only measuring its own cleverness");
    }

    @Test
    @DisplayName("an empty plan is 0 rather than a crash")
    void nothingIsZero() {
        assertEquals(0, BuildFingerprint.of(new BuildPlan("x", "x", List.of())).bulkHeight());
    }
}
