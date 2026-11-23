package com.moddingfromamod.reference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.runtime.CreatureSculpt;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** idea 2: the main colour of a named character, checked before the reveal. */
class MainColourCheckTest {

    private static ReferenceSpec ref(ReferenceSpec.Confidence c, Object... shares) {
        java.util.List<ReferenceSpec.ColourShare> cs = new java.util.ArrayList<>();
        for (int i = 0; i < shares.length; i += 2) {
            cs.add(new ReferenceSpec.ColourShare((String) shares[i], (Integer) shares[i + 1]));
        }
        return new ReferenceSpec(cs, List.of("body"), ReferenceSpec.Size.SMALL, c, List.of("a page"));
    }

    @Test
    @DisplayName("the lavender Puffo of #2429's A/B: told, in words that cannot be read as 'make it purple'")
    void theLavenderPuffoIsCaught() {
        var puffo = ref(ReferenceSpec.Confidence.HIGH, "purple/pink", 80, "red", 12, "blue", 3);
        String why = MainColourCheck.forAgent(Map.of("blue", 0.90, "cyan", 0.08), puffo, "Puffo",
                "make me Puffo", "Re-sculpt the same id").orElseThrow();
        assertTrue(why.startsWith("⚠ MAIN COLOUR WRONG"), why);
        assertTrue(why.contains("mostly pink or purple (about 80%)") && why.contains("mostly blue (90%)"), why);
        assertTrue(why.contains("Re-sculpt the same id with pink or purple as the main colour"), why);
    }

    @Test
    @DisplayName("a build that keeps the main colour, a child's own colour, and an unsure reference say nothing")
    void quietWhenRight() {
        var sparkit = ref(ReferenceSpec.Confidence.HIGH, "yellow", 75, "black", 8, "red", 6);
        assertFalse(MainColourCheck.forAgent(Map.of("yellow", 0.86, "black", 0.05), sparkit, "Sparkit", "", "x")
                .isPresent());
        assertFalse(MainColourCheck.forAgent(Map.of("blue", 0.85), sparkit, "Sparkit",
                "make me a BLUE sparkit", "x").isPresent(), "his own words win");
        assertFalse(MainColourCheck.forAgent(Map.of("blue", 0.85), ref(ReferenceSpec.Confidence.LOW, "yellow", 75),
                "Sparkit", "", "x").isPresent(), "a guess is not a reference");
        assertTrue(MainColourCheck.forAgent(Map.of("blue", 0.85), sparkit, "Sparkit", "", "Repaint the same id")
                .isPresent(), "and the blue Sparkit is caught");
    }

    @Test
    @DisplayName("a picture's colours are counted by what shows, painted in order, transparent left out")
    void pictureSharesCountWhatShows() {
        var rects = List.of(new CreatureSculpt.PaintRect(0, 0, 32, 32, 0x2850E0),      // blue base
                new CreatureSculpt.PaintRect(0, 0, 32, 24, 0xF0D228));                   // yellow over 3/4
        Map<String, Double> s = MainColourCheck.pictureShares(32, rects);
        assertTrue(Math.abs(s.get("yellow") - 0.75) < 0.01 && Math.abs(s.get("blue") - 0.25) < 0.01, s.toString());
    }
}
