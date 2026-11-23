package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** a ladder shaft standing in water gets its rim at the waterline, from above and from below. */
class BuildWaterEntryTest {

    /** A pool 9x9, water to y=4; a glass tube 3x3 in its middle with a ladder inside, its rim at {@code rimTop}. */
    static BuildPlan pool(int rimTop, boolean outsideLadder) {
        List<BuildShape> s = new ArrayList<>();
        s.add(new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 9, 5, 9, "minecraft:stone", true));
        s.add(new BuildShape(BuildShape.Kind.BOX, 1, 1, 1, 7, 4, 7, "minecraft:water", false));
        s.add(new BuildShape(BuildShape.Kind.BOX, 3, 1, 3, 3, rimTop, 3, "minecraft:glass", false));
        s.add(new BuildShape(BuildShape.Kind.BOX, 4, 1, 4, 1, rimTop, 1, "minecraft:ladder[facing=south]", false));
        if (outsideLadder) {
            s.add(new BuildShape(BuildShape.Kind.BOX, 4, 3, 6, 1, 4, 1, "minecraft:ladder[facing=south]", false));
        }
        return new BuildPlan("pool", "Pool", s);
    }

    static Map<Long, String> after(BuildPlan p) {
        Map<Long, String> c = BuildDoors.applied(p, BuildDoors.of(p));
        BuildWaterEntry.apply(c, BuildWaterEntry.compute(p));
        c.values().removeIf(Objects::isNull);
        return c;
    }

    @Test
    void aRimABlockOverTheWaterIsCutDownToIt() {
        BuildPlan p = pool(5, false);   // rim top at y=5, water top at y=4
        BuildWaterEntry.Fixed f = BuildWaterEntry.compute(p);
        assertEquals(1, f.lowered());
        Map<Long, String> c = after(p);
        assertFalse(c.containsKey(ComposedPlan.key(3, 5, 3)), "the rim over the water is gone");
        assertEquals("minecraft:glass", c.get(ComposedPlan.key(3, 4, 3)), "the rim in the water's top layer stays");
        assertTrue(c.get(ComposedPlan.key(4, 4, 4)).contains("ladder"), "the ladder reaches the waterline");
        assertFalse(c.containsKey(ComposedPlan.key(4, 5, 4)), "and no higher");
    }

    @Test
    void aRimUnderTheWaterIsCarriedUpToIt() {
        BuildPlan p = pool(3, false);   // rim top at y=3: one under the surface, the shaft floods
        BuildWaterEntry.Fixed f = BuildWaterEntry.compute(p);
        assertEquals(1, f.raised());
        Map<Long, String> c = after(p);
        assertEquals("minecraft:glass", c.get(ComposedPlan.key(3, 4, 3)));
        assertTrue(c.get(ComposedPlan.key(4, 4, 4)).contains("ladder"));
        assertFalse(c.getOrDefault(ComposedPlan.key(4, 3, 4), "").contains("water"), "no water in the shaft");
    }

    @Test
    void aRimAtTheWaterlineIsLeft() {
        assertTrue(BuildWaterEntry.compute(pool(4, false)).isEmpty());
    }

    @Test
    void aTowerWithItsOwnLadderOutsideIsLeft() {
        assertTrue(BuildWaterEntry.compute(pool(6, true)).isEmpty());
    }

    @Test
    void aLadderOnDryLandIsLeft() {
        List<BuildShape> s = new ArrayList<>();
        s.add(new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 3, 8, 3, "minecraft:stone", false));
        s.add(new BuildShape(BuildShape.Kind.BOX, 1, 0, 1, 1, 8, 1, "minecraft:ladder[facing=south]", false));
        assertTrue(BuildWaterEntry.compute(new BuildPlan("dry", "Dry", s)).isEmpty());
    }

    /** From the archive: a lifeguard's ladder standing on a pool's deck is not a shaft. */
    @Test
    void aLadderOnAPoolDeckIsLeft() {
        List<BuildShape> s = new ArrayList<>();
        s.add(new BuildShape(BuildShape.Kind.BOX, -11, 0, -11, 22, 1, 22, "minecraft:quartz_block", false));
        s.add(new BuildShape(BuildShape.Kind.BOX, -6, 1, -4, 12, 1, 8, "minecraft:water", false));
        s.add(new BuildShape(BuildShape.Kind.BOX, 7, 0, -5, 1, 7, 1, "minecraft:ladder[facing=south]", false));
        assertTrue(BuildWaterEntry.compute(new BuildPlan("lifeguard", "Lifeguard", s)).isEmpty());
    }
}
