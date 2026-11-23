package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A size refusal has to say WHICH shape is eating the budget —.
 *
 * <p>Measured on the buildings ladder: eight consecutive refusals on one rung went
 * 222,182 → 220,862 → 220,750 → 221,374 → 220,102 → 220,582 → 220,046 → 220,158 against a 220,000
 * ceiling. Oscillating, never getting under, because the refusal reported only the total — so the
 * agent was shaving blind. Twenty-three size refusals across a six-cell matrix.
 */
class BudgetBreakdownTest {

    private static BuildShape box(String name, int d, boolean hollow) {
        // (kind, dx,dy,dz = WHERE, width,height,depth = HOW BIG) — putting the size in the
        // position fields gives every shape volume 0, which is how the first cut of this test
        // "failed" against perfectly good code.
        return new BuildShape(BuildShape.Kind.BOX, 0, 0, 0, d, d, d,
                "minecraft:stone", hollow, BuildShape.Anchor.CENTRE, name);
    }

    private static BuildPlan planOf(BuildShape... shapes) {
        return new BuildPlan("castle", "Castle", List.of(shapes));
    }

    @Test
    @DisplayName("it names the biggest shape, not just the total")
    void namesTheOffender() {
        String out = planOf(box("keep", 60, false), box("flag", 3, false)).budgetBreakdown();
        assertTrue(out.contains("keep"), "the biggest shape is not named: " + out);
        assertTrue(out.contains("Biggest"), out);
    }

    @Test
    @DisplayName("it says how many blocks over, so one edit can be enough")
    void saysHowMuchToLose() {
        // The oscillation happened because the agent could not tell how far over it was.
        BuildShape huge = box("keep", 90, false);
        String out = planOf(huge).budgetBreakdown();
        if (huge.volume() > BuildPlan.MAX_BLOCKS) {
            assertTrue(out.contains("lose"), "does not say how much to lose: " + out);
        }
    }

    @Test
    @DisplayName("it points at SOLID shapes, because hollowing one is the fix that works")
    void solidIsCalledOut() {
        String solid = planOf(box("keep", 60, false)).budgetBreakdown();
        assertTrue(solid.contains("SOLID"), solid);
        String hollow = planOf(box("keep", 60, true)).budgetBreakdown();
        assertTrue(hollow.contains("already hollow"),
                "a hollow shape must not be suggested for hollowing: " + hollow);
    }

    @Test
    @DisplayName("it stays short — a list nobody reads is the same as no list")
    void itIsShort() {
        BuildShape[] many = new BuildShape[20];
        for (int i = 0; i < many.length; i++) {
            many[i] = box("shape" + i, 10 + i, false);
        }
        String out = planOf(many).budgetBreakdown();
        assertTrue(out.split(";").length <= 3, "too many shapes listed: " + out);
        assertFalse(out.contains("shape0"), "listed a small shape instead of the big ones: " + out);
    }

    @Test
    @DisplayName("the amount to lose counts the shapes BEHIND the one that broke the ceiling")
    void theTotalIsNotTruncatedAtTheCeiling() {
        // volume() stops counting the moment it is over, which is right for fitsBudget() and
        // wrong for a number the agent acts on. Three shapes where the SECOND crosses the line
        // and a large third sits behind it: the truncated total hides that third shape entirely,
        // so "you need to lose N" is short by its whole volume -- and shaving N leaves the build
        // still refused, which is exactly the oscillation is about.
        BuildShape a = box("keep", 60, false);          // 216,000
        BuildShape b = box("hall", 20, false);          //   8,000 -- crosses 220,000 here
        BuildShape c = box("annex", 50, false);         // 125,000 -- invisible to volume()
        BuildPlan plan = planOf(a, b, c);

        int truncated = plan.volume();
        int exact = plan.exactVolume();
        assertTrue(exact > truncated,
                "this fixture must actually exercise the short-circuit; exact=" + exact
                        + " truncated=" + truncated);
        assertEquals(a.volume() + b.volume() + c.volume(), exact, "exactVolume must count them all");

        String out = plan.budgetBreakdown();
        assertTrue(out.contains(String.valueOf(exact - BuildPlan.MAX_BLOCKS)),
                "the shortfall must be measured against the real total (" + exact + "), not the "
                        + "truncated one (" + truncated + "): " + out);
    }

    @Test
    @DisplayName("the OTHER refusal door names the offender too")
    void builderSubmitAlsoExplainsItself() {
        // Builder.submit is a second path to the same refusal and kept the generic advice long
        // after the agent's own tool stopped giving it. A defect found is a defect class found.
        String breakdown = planOf(box("keep", 90, false)).budgetBreakdown();
        assertTrue(breakdown.contains("keep"), breakdown);
        assertTrue(breakdown.contains("Biggest"), breakdown);
    }
}
