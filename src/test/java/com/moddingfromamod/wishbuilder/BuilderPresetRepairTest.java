package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRepair;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * what {@link BuildRepair} does to the buildings WE ship, at every size each is offered. Three starters really
 * have the fault it fixes (the floating island, the treehouse and the mushroom village each have a doorway one block tall at a
 * room's floor), so they are repaired as they are placed; no other Builder building is touched, and a fix stays small.
 */
class BuilderPresetRepairTest {

    @Test
    void noBuilderBuildingIsTouchedAtAnySizeItIsOffered() {
        List<String> touched = new ArrayList<>();
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            for (int size : BuilderSizes.offered(t.id(), true)) {
                BuildPlan p = BuilderBuildingComposableTest.plan(
                        BuilderBuildingComposableTest.composed(t.preset(), BuilderBuildings.NOTHING, "", size, "X"));
                BuildRepair.Fixed f = BuildRepair.of(p);
                if (!f.isEmpty()) {
                    touched.add(t.id());
                    org.junit.jupiter.api.Assertions.assertTrue(f.cells().size() <= 6, t.id() + ":" + size + " " + f.fixes());
                }
            }
        }
        assertEquals(List.of("floating_island", "mushroom_village", "secret_treehouse"), touched.stream().distinct().sorted().toList(),
                "which Builder buildings a repair touches changed");
    }
}
