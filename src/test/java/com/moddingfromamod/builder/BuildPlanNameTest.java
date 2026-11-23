package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** a building the agent did not name is called what its id says, never "Building". */
class BuildPlanNameTest {

    @Test
    void anUnnamedBuildingIsNamedFromItsId() {
        assertEquals("Giant Volcano", new BuildPlan("giant_volcano", null, List.of()).name());
        assertEquals("Giant Ocean Volcano", new BuildPlan("giant_ocean_volcano", "  ", List.of()).name());
    }

    @Test
    void theOldPlaceholderSavedOnAShelfCountsAsUnnamed() {
        assertEquals("Giant Volcano", new BuildPlan("giant_volcano", "Building", List.of()).name());
    }

    @Test
    void aRealNameIsKept() {
        assertEquals("Blaze's Lair", new BuildPlan("giant_volcano", "Blaze's Lair", List.of()).name());
    }

    @Test
    void aForkSuffixAndSeparatorsAreNotPartOfTheName() {
        assertEquals("Sky Island", BuildPlan.nameFromId("sky_island~1a2b3c4d"));
        assertEquals("Moat Castle", BuildPlan.nameFromId("moat-castle"));
        assertEquals(BuildPlan.UNNAMED, BuildPlan.nameFromId(""));
    }
}
