package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.builder.BuildGrounding;
import com.moddingfromamod.builder.BuildPlan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>Nothing in a building floats</b>. The mushroom village's back mushroom was seen
 * "floating": every stem had been drawn from where its author meant its MIDDLE ({@code dy} is a shape's base, centre
 * anchor or not), so all three stood 4–7 blocks above the plaza on nothing. A defect found is a class found, so this
 * sweeps every building type at the size it was made (size 2, the default, the size that is graded), alone and with
 * everything outside it: each lump of blocks (blocks that touch, a corner counting, as a stepped roof's layers and a
 * spiral stair's steps do) must reach the ground (dy ≤ 0; the world's own ground is at −1). It found three more: a
 * glowstone in mid-air in the temple's shrine, the well's chain under a hollow roof, and the ship's masts and wheel a
 * block above its deck.
 *
 * <p><b>Known gap: the other sizes.</b> Scaling rounds each shape's offset and size on its own, so at sizes 1, 3 and 4
 * parts that touched can come apart (a lantern off its ceiling, a roof's tip off its roof). Scaling by edges fixed
 * those and changed far more (a maze's walls alternating 1 and 2 thick, a castle's moat gone at size 1), so it was
 * taken out for its own issue,: deterministic grounding and scaling. Until then this sweep holds size 2 only.
 */
class BuilderBuildingsGroundedTest {

    @Test
    void nothingInAnyBuildingFloatsAtTheSizeItWasMade() {
        List<String> bad = new ArrayList<>();
        String all = BuilderBuildings.join(OutsideZones.OPTIONS);   // everything outside
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            for (int size = 2; size <= 2; size++) {   // the made size only: the other sizes are the known gap above
                for (String outside : t.takesOutside() ? List.of(BuilderBuildings.NOTHING, all) : List.of(BuilderBuildings.NOTHING)) {
                    BuildPlan p = BuilderBuildingComposableTest.plan(BuilderBuildingComposableTest.composed(t.preset(), outside, "", size, "X"));
                    for (String f : floating(p)) {
                        bad.add(t.id() + " size " + size + (outside.equals(BuilderBuildings.NOTHING) ? "" : " + " + outside) + ": " + f);
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), "parts that stand on nothing:\n" + String.join("\n", bad));
    }

    /** Each lump that stands on nothing, as it stands (doors hung): {@link BuildGrounding}, the one definition. */
    static List<String> floating(BuildPlan p) {
        List<String> out = new ArrayList<>();
        BuildGrounding.of(p).floating().forEach(f -> out.add(f.describe()));
        return out;
    }
}
