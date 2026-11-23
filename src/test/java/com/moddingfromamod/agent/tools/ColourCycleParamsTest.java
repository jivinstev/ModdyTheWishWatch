package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** {@code apply_effect} turns colour_cycle's MOB into its steps, and refuses a mob with no colours. */
class ColourCycleParamsTest {

    @Test
    void aKnownMobBecomesItsStepsAndMobIsConsumed() {
        Map<String, String> p = new HashMap<>(Map.of("TAG", "rainbow_axolotl", "MOB", "axolotl",
                "PERIOD_TICKS", "20"));
        assertNull(ApplyEffectTool.fillColourCycle(p));
        assertFalse(p.containsKey("MOB"), "MOB is consumed, so render() never sees it");
        assertEquals("4", p.get("COUNT"));
        assertTrue(p.get("STEPS").contains("Variant set value 4"));
    }

    @Test
    void aMobWithNoColoursIsRefusedAndTheRefusalNamesTheOnesThatWork() {
        Map<String, String> p = new HashMap<>(Map.of("TAG", "z", "MOB", "zombie",
                "PERIOD_TICKS", "20"));
        String err = ApplyEffectTool.fillColourCycle(p);
        assertNotNull(err);
        assertTrue(err.contains("axolotl") && err.contains("sculpt"), err);
    }
}
