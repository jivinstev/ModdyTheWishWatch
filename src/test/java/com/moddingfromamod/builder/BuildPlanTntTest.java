package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * a wish built a volcano "PACKED with TNT — over 47,000 blocks", the child lit it, and the
 * live server crash-looped (a 60-second tick, the watchdog, the lit TNT saved and going off again).
 * One building may hold at most {@link BuildPlan#MAX_TNT} TNT, counted on the FINISHED building.
 */
class BuildPlanTntTest {

    private static BuildShape box(int w, int h, int d, String block, boolean hollow) {
        return new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, w, h, d, block, hollow);
    }

    @Test
    void theVolcanoThatCrashLoopedTheServerIsRefused() {
        // 36 x 36 x 36 solid TNT = 46,656 — the size of the live wish.
        BuildPlan volcano = new BuildPlan("tnt_volcano", "TNT Volcano",
                List.of(box(40, 40, 40, "minecraft:stone", true), box(36, 36, 36, "minecraft:tnt", false)));
        assertEquals(46_656, volcano.tntCount());
        assertFalse(volcano.fitsTntBudget());
        assertTrue(volcano.tntRefusal().contains("46656") && volcano.tntRefusal().contains("2000"),
                "the refusal names the count and the limit, so the agent can act on it in one edit");
    }

    @Test
    void aBigButSafeBangIsBuilt() {
        BuildPlan ok = new BuildPlan("tnt_volcano", "TNT Volcano",
                List.of(box(40, 40, 40, "minecraft:stone", true), box(12, 12, 12, "minecraft:tnt", false)));
        assertEquals(1728, ok.tntCount());
        assertTrue(ok.fitsTntBudget());
    }

    @Test
    void theFinishedBuildingIsWhatCounts_airCarvedThroughTntIsGone() {
        // A 14x14x14 TNT cube (2,744 — over the budget as shapes) with an air core carved through it
        // (12x12x12 = 1,728) is 1,016 TNT once built: within the budget, so it is built.
        BuildShape carve = new BuildShape(BuildShape.Kind.BOX, 1, 1, 1, 12, 12, 12, "minecraft:air", false);
        BuildPlan p = new BuildPlan("carved", "Carved", List.of(box(14, 14, 14, "minecraft:tnt", false), carve));
        assertEquals(1016, p.tntCount());
        assertTrue(p.fitsTntBudget());
    }

    @Test
    void anyTntCountsAndNothingElseDoes() {
        assertTrue(BuildPlan.isTnt("minecraft:tnt"));
        assertTrue(BuildPlan.isTnt("somemod:mega_tnt"));
        assertTrue(BuildPlan.isTnt("TNT"));
        assertFalse(BuildPlan.isTnt("minecraft:stone"));
        assertFalse(BuildPlan.isTnt(null));
    }
}
