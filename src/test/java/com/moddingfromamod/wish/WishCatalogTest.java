package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Gate A for the shipped wish set.
 *
 * <p><b>The jar ships no hard-coded wishes</b>. The ~18 built-ins were
 * untuned, and the loose no-agent lane matched them on substrings — "breakfast" was
 * {@code super_fast}. What a child taps is the starter library, and what the watch learns is the
 * agent's {@code add_template}; both reach {@code WishCatalog} only through
 * {@code LearnedTemplates.rebuild}. This test is what stops a built-in quietly coming back.
 */
class WishCatalogTest {

    /** Every id the deleted catalogue carried. */
    private static final List<String> DELETED = List.of(
            "rock_crusher", "cobble_factory_wish", "windmill_wish", "conveyor_wish",
            "carousel_wish", "water_mill_wish", "rain_chickens", "treasure", "super_fast",
            "night_spooky", "tower", "fly", "zombies", "cats", "rainbow_sheep", "sunny",
            "diamonds", "cake");

    @Test
    void theCatalogueShipsNoTemplatesOfItsOwn() {
        WishTemplates before = WishCatalog.templates();
        try {
            WishCatalog.resetToDefaults();
            assertEquals(0, WishCatalog.templates().size(),
                    "the shipped default is EMPTY; a template here answers a child with no agent "
                            + "and no curator: " + WishCatalog.templates().ids());
        } finally {
            WishCatalog.setTemplates(before);
        }
    }

    @Test
    void noOldPresetWordsAreAnsweredOnAnyLane() {
        WishTemplates before = WishCatalog.templates();
        try {
            WishCatalog.resetToDefaults();
            for (String said : new String[]{
                    "make me super fast", "make it night", "give me diamonds", "give me treasure",
                    "build me a tower", "i want to fly", "spawn 10 zombies", "i want cats",
                    "make it sunny", "cake party", "rainbow sheep", "build me a factory",
                    "can we have breakfast at a big table", "build me a knight"}) {
                String clean = WishText.sanitize(said);
                assertTrue(WishCatalog.templates().matchConfident(clean).isEmpty(),
                        "the confident lane answered: \"" + said + "\"");
                assertTrue(WishCatalog.templates().matchDetailed(clean).isEmpty(),
                        "the loose no-agent lane answered: \"" + said + "\"");
                assertTrue(WishCatalog.templates().hint(clean).isEmpty(),
                        "the agent's hint still names a deleted built-in for: \"" + said + "\"");
            }
        } finally {
            WishCatalog.setTemplates(before);
        }
    }

    /** The starter library must not adopt a deleted id: that would be the built-in coming back. */
    @Test
    void theStarterLibraryCarriesNoneOfTheDeletedIds() throws java.io.IOException {
        String starters;
        try (var in = getClass().getClassLoader().getResourceAsStream(
                com.moddingfromamod.library.StarterLibrary.ROOT + "templates.json")) {
            assertTrue(in != null, "the jar ships no starter templates.json");
            starters = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        List<String> ids = WishTemplates.fromJson(starters).ids();
        assertFalse(ids.isEmpty(), "the starter library parsed to nothing");
        for (String id : DELETED) {
            assertFalse(ids.contains(id), "the starter library ships the deleted built-in " + id);
        }
    }

    @Test
    void volumeIsAlwaysBounded() {
        // Even an enthusiastic request must not turn into thousands of commands. Measured on the
        // old built-ins' countable chickens, kept as a fixture: the matcher is unchanged.
        WishTemplates t = OldBuiltInsFixture.templates();
        CommandPlan plan = t.match(WishText.sanitize("spawn 99999 chickens")).orElseThrow();
        assertTrue(plan.commands().size() <= CommandPlan.MAX_COMMANDS,
                "runaway count, got " + plan.commands().size());
    }
}
