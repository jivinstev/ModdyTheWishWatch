package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * a hollow cylinder one or two tall is a RING, as made a short hollow box. The old rule kept both
 * caps, and on a cylinder one or two tall every layer is a cap, so the lighthouse's 11-wide iron-bar railing was a
 * plate of bars over its balcony. Air keeps its old meaning — a short hollow air cylinder clears its whole disk.
 */
class ShortHollowCylinderIsARingTest {

    private static BuildShape cyl(String block, int w, int h, int d, boolean hollow) {
        return new BuildShape(BuildShape.Kind.CYLINDER, 0, 0, 0, w, h, d, block, hollow);
    }

    private static Set<String> layer(BuildShape s, int y) {
        Set<String> out = new HashSet<>();
        s.forEachBlock((x, yy, z) -> {
            if (yy == y) {
                out.add(x + "," + z);
            }
        });
        return out;
    }

    @Test
    @DisplayName("a one-tall hollow railing is the ring a three-tall wall has in its middle layer")
    void aOneTallHollowCylinderIsItsWall() {
        Set<String> ring = layer(cyl("minecraft:iron_bars", 11, 1, 11, true), 0);
        Set<String> wall = layer(cyl("minecraft:iron_bars", 11, 3, 11, true), 1);
        assertEquals(wall, ring);
        assertTrue(ring.size() < layer(cyl("minecraft:iron_bars", 11, 1, 11, false), 0).size(),
                "the ring must be smaller than the disk");
        assertTrue(!ring.contains("5,5"), "the centre of a railing is open");
    }

    @Test
    @DisplayName("a two-tall hollow battlement is two rings")
    void aTwoTallHollowCylinderIsTwoRings() {
        BuildShape two = cyl("minecraft:stone_bricks", 9, 2, 9, true);
        assertEquals(layer(two, 0), layer(two, 1));
        assertEquals(layer(cyl("minecraft:stone_bricks", 9, 3, 9, true), 1), layer(two, 0));
    }

    @Test
    @DisplayName("three tall or more keeps its floor and lid, as before (a tower)")
    void threeTallKeepsItsCaps() {
        BuildShape tower = cyl("minecraft:stone", 7, 3, 7, true);
        Set<String> disk = layer(cyl("minecraft:stone", 7, 1, 7, false), 0);
        assertEquals(disk, layer(tower, 0));
        assertEquals(disk, layer(tower, 2));
    }

    @Test
    @DisplayName("a short hollow AIR cylinder still clears its whole disk")
    void shortHollowAirStillClearsTheDisk() {
        assertEquals(layer(cyl("minecraft:air", 9, 1, 9, false), 0), layer(cyl("minecraft:air", 9, 1, 9, true), 0));
        assertEquals(cyl("air", 9, 2, 9, false).volume(), cyl("air", 9, 2, 9, true).volume());
    }

    @Test
    @DisplayName("a solid short cylinder is untouched")
    void solidIsUntouched() {
        assertEquals(layer(cyl("minecraft:oak_planks", 11, 1, 11, false), 0).size(),
                cyl("minecraft:oak_planks", 11, 1, 11, false).volume());
    }
}
