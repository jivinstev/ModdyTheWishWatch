package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * a hollow box one or two tall is a RING. The old rule kept every cell on the top or bottom face, so a
 * 16x1x12 "hollow" fence round the playground was 192 fence posts filling the sand. Air keeps its old meaning —
 * a short hollow air box clears its whole area, which builds rely on.
 */
class ShortHollowBoxIsARingTest {

    private static BuildShape box(String block, int w, int h, int d, boolean hollow) {
        return new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, w, h, d, block, hollow);
    }

    @Test
    @DisplayName("a hollow fence one tall is its edge, not the whole floor")
    void aOneTallHollowFenceIsARing() {
        assertEquals(2 * 16 + 2 * 10, box("minecraft:oak_fence", 16, 1, 12, true).volume());
    }

    @Test
    @DisplayName("a hollow wall two tall is two rings")
    void aTwoTallHollowWallIsTwoRings() {
        assertEquals(2 * (2 * 15 + 2 * 13), box("minecraft:stone_brick_wall", 15, 2, 15, true).volume());
    }

    @Test
    @DisplayName("three tall or more keeps its floor and ceiling, as before")
    void threeTallKeepsItsCaps() {
        // 5x3x5: the full 75 minus the 3x1x3 inside.
        assertEquals(75 - 9, box("minecraft:stone", 5, 3, 5, true).volume());
    }

    @Test
    @DisplayName("a short hollow AIR box still clears its whole area")
    void shortHollowAirStillClearsEverything() {
        assertEquals(15 * 15, box("minecraft:air", 15, 1, 15, true).volume());
    }

    @Test
    @DisplayName("a solid short box is untouched")
    void solidIsUntouched() {
        assertEquals(16 * 12, box("minecraft:oak_fence", 16, 1, 12, false).volume());
    }
}
