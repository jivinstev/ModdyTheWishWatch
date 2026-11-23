package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** a fence ring's posts join; a pane in a wall joins the wall; a lone post stays a post. */
class BuildConnectTest {

    @Test
    void aFenceRingJoinsAndAPaneJoinsItsWall() {
        BuildPlan p = new BuildPlan("yard", "Yard", List.of(
                new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, 5, 1, 5, "minecraft:oak_fence", true),
                new BuildShape(BuildShape.Kind.BOX, 10, 0, 0, 3, 3, 1, "minecraft:stone", false),
                new BuildShape(BuildShape.Kind.BOX, 11, 1, 0, 1, 1, 1, "minecraft:glass_pane", false),
                new BuildShape(BuildShape.Kind.BOX, 20, 0, 20, 1, 1, 1, "minecraft:oak_fence", false)));
        Map<Long, String> m = BuildConnect.of(p);
        assertEquals("minecraft:oak_fence[east=true,north=false,south=true,west=false]", m.get(ComposedPlan.key(0, 0, 0)), "a corner");
        assertEquals("minecraft:oak_fence[east=true,north=false,south=false,west=true]", m.get(ComposedPlan.key(2, 0, 0)), "a run");
        assertEquals("minecraft:glass_pane[east=true,north=false,south=false,west=true]", m.get(ComposedPlan.key(11, 1, 0)));
        assertFalse(m.containsKey(ComposedPlan.key(20, 0, 20)), "a lone post is left as drawn");
    }
}
