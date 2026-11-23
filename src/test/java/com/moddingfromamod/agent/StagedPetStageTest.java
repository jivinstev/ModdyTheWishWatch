package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.lite.StockWalkStage;
import org.junit.jupiter.api.Test;

/** The pure halves of the staged scenes were measured with. */
class StagedPetStageTest {

    @Test
    void overheadIsStraightAboveAndBarelyMoves() {
        for (int t = 0; t < 400; t++) {
            double[] o = StagedPetStage.overheadOffset(t);
            assertEquals(6.0, o[1], 1e-9);
            assertEquals(0.4, Math.hypot(o[0], o[2]), 1e-9, "he drifts round a circle 0.4 of a block out");
        }
        double[] a = StagedPetStage.overheadOffset(0);
        double[] b = StagedPetStage.overheadOffset(20);
        assertEquals(-a[0], b[0], 1e-9, "half a lap in a second: the direction to him has swung right round");
    }

    @Test
    void hoverGoesOutToTheSideAndPassesStraightOver() {
        double least = 99;
        double most = 0;
        for (int t = 0; t < 480; t++) {
            double[] o = StagedPetStage.hoverOffset(t);
            double r = Math.hypot(o[0], o[2]);
            least = Math.min(least, r);
            most = Math.max(most, r);
            assertEquals(5.0, o[1], 1e-9);
        }
        assertTrue(least < 0.1, "he passes straight over it, was " + least);
        assertEquals(6.0, most, 1e-6);
    }

    @Test
    void theRecordedRecipeIsInTheJar() {
        assertNotNull(StagedPetStage.class.getResourceAsStream("/stages/pet_dragon_spider.json"));
    }

    @Test
    void aFrenzyStepSetsHowHardItTurns() {
        for (int t = 2; t < 200; t += 2) {
            float d = wrap(StockWalkStage.frenzyYaw(t, 45f) - StockWalkStage.frenzyYaw(t - 2, 45f));
            assertTrue(d >= 44.9f && d <= 63.1f, "a 45-degree frenzy turns 45 to 63 degrees a step, was " + d);
        }
        assertEquals(StockWalkStage.frenzyYaw(40), StockWalkStage.frenzyYaw(40, 150f), 1e-4f);
    }

    private static float wrap(float d) {
        float w = d % 360f;
        return w < 0 ? w + 360f : w;
    }
}
