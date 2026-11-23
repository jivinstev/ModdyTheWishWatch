package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

/**
 * <b>The Secret Treehouse's canopy sits on and over its roof.</b> The leaf sphere started at
 * dy 27 while the roof's apex is dy 24, so every child who wished for it got a ball of leaves
 * hovering two blocks over the house. Lowering it onto the apex alone was tried and photographed: a
 * ball balanced on a point. So the canopy now comes down over the top of the roof — and none of it
 * may be UNDER the roof, because a hollow roof does not clear what is inside it and the attic would
 * fill with leaves. Asked of the FINISHED blocks (later shapes overwrite earlier ones, as the builder
 * places them), never of the shape numbers, so a later edit to the roof cannot reopen either.
 */
class TreehouseCanopyTest {

    private static final Path PLAN =
            Path.of("src/main/resources/starter-library/buildings/secret_treehouse/build.json");

    @Test
    void theLeavesRestOnTheRoofAndReachOverIt() throws IOException {
        BuildPlan plan = BuildPlan.fromJson(JsonParser.parseString(Files.readString(PLAN)).getAsJsonObject());
        Map<Long, String> world = new HashMap<>();
        plan.forEachBlock((x, y, z, block) -> world.put(key(x, y, z), block));
        String roof = "minecraft:dark_oak_planks";
        String leaves = "minecraft:oak_leaves";
        int roofTop = Integer.MIN_VALUE;
        int leafBottom = Integer.MAX_VALUE;
        int resting = 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int houseMinX = Integer.MAX_VALUE, houseMaxX = Integer.MIN_VALUE;
        for (var e : world.entrySet()) {
            int x = (int) (e.getKey() << 4 >> 44), y = (int) (e.getKey() << 24 >> 44), z = (int) (e.getKey() << 44 >> 44);
            if (roof.equals(e.getValue())) {
                roofTop = Math.max(roofTop, y);
            }
            if ("minecraft:spruce_planks".equals(e.getValue())) {
                houseMinX = Math.min(houseMinX, x);
                houseMaxX = Math.max(houseMaxX, x);
            }
            if (leaves.equals(e.getValue())) {
                leafBottom = Math.min(leafBottom, y);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                if (roof.equals(world.get(key(x, y - 1, z)))) {
                    resting++;
                }
            }
        }
        assertTrue(leafBottom < roofTop, "the canopy's lowest leaves are at dy " + leafBottom
                + " and the roof's apex at dy " + roofTop + " — it must come down over the roof, not perch on it");
        int under = 0;
        for (var e : world.entrySet()) {
            if (!leaves.equals(e.getValue())) {
                continue;
            }
            int x = (int) (e.getKey() << 4 >> 44), y = (int) (e.getKey() << 24 >> 44), z = (int) (e.getKey() << 44 >> 44);
            for (int up = y + 1; up <= roofTop; up++) {
                if (roof.equals(world.get(key(x, up, z)))) {
                    under++;
                    break;
                }
            }
        }
        assertEquals(0, under, under + " leaf blocks are under the roof — inside the attic");
        assertTrue(resting >= 4, "only " + resting + " leaf blocks rest on a roof block");
        assertTrue(minX <= houseMinX && maxX >= houseMaxX, "the canopy (dx " + minX + ".." + maxX
                + ") does not reach over the house (dx " + houseMinX + ".." + houseMaxX + ")");
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
    }
}
