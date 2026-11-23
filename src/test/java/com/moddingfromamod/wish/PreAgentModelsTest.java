package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** step 1: a pressed recipe starts no model; a wish in a child's own words does. */
class PreAgentModelsTest {

    @Test
    void aNamedRecipeStartsNoModel() {
        assertFalse(PreAgentModels.wanted(true));
    }

    @Test
    void aWishWithNoNamedRecipeStillStartsThem() {
        assertTrue(PreAgentModels.wanted(false));
    }
}
