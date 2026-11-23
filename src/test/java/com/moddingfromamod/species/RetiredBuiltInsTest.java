package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * the six built-in creatures are RETIRED — never given to a world, dropped when a saved copy
 * is loaded, and summoned by nothing the jar ships. (This replaces {@code BuiltInHonestyTest},
 * whose subject — the shipped built-ins — no longer exists.)
 */
class RetiredBuiltInsTest {

    @Test
    void theSixAreRetiredAndASavedCopyIsDropped() {
        assertEquals(6, SpeciesRegistry.RETIRED_BUILT_INS.size());
        for (String id : SpeciesRegistry.RETIRED_BUILT_INS) {
            assertTrue(SpeciesRegistry.isSuperseded(id), id + " would survive a world load");
        }
        assertTrue(SpeciesRegistry.isSuperseded("Guard_Bot"), "an id matches whatever its case");
        assertFalse(SpeciesRegistry.isSuperseded("pet_dragon"), "the starter dragon is not retired");
    }

    /** What ships is the starter library's templates (the hard-coded ones were deleted). */
    @Test
    void noShippedTemplateSummonsOneOfThem() throws java.io.IOException {
        String catalogue;
        try (var in = getClass().getClassLoader().getResourceAsStream(
                com.moddingfromamod.library.StarterLibrary.ROOT + "templates.json")) {
            assertTrue(in != null, "the jar ships no starter templates.json");
            catalogue = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String id : SpeciesRegistry.RETIRED_BUILT_INS) {
            assertFalse(catalogue.contains("'" + id + "'"), "a built-in template still summons " + id);
            assertFalse(catalogue.contains("\"" + id + "_creature\""), "template " + id + "_creature ships");
        }
    }
}
