package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** a prefix names the SAME source its tool validates against, so offered and taken cannot drift. */
class CatalogueTest {

    @Test
    void realIdsFromEveryCataloguePass() {
        for (String id : List.of("power:flies", "power:rideable", "shape:held", "shape:machine", "shape:magic_block",
                "motion:falling_body", "when:jumps", "preset:species/pet_dragon", "preset:buildings/secret_treehouse",
                "preset:pictures/castle", "preset:events/meteor", "preset:items/lightning_sword_lightning",
                "mob:minecraft:cow", "scheme:ocean")) {
            assertNull(Catalogue.problem(id), id);
        }
    }

    @Test
    void effectAndAbilityIdsComeFromTheirCatalogues() {
        String effect = com.moddingfromamod.datapack.ItemCatalogue.load().all().get(0).id();
        assertNull(Catalogue.problem("effect:" + effect));
        String ability = com.moddingfromamod.datapack.EffectCatalogue.load().all().get(0).id();
        assertNull(Catalogue.problem("ability:" + ability));
    }

    @Test
    void madeUpIdsAreRefusedByName() {
        for (String id : List.of("power:teleports_to_mars", "shape:spinning", "motion:wobble", "when:sings",
                "preset:species/unicorn_x", "scheme:plaid", "effect:no_such", "ability:no_such", "mob:Cow!",
                "flies", "power:", "colour:red")) {
            String p = Catalogue.problem(id);
            assertNotNull(p, id + " must be refused");
        }
    }

    @Test
    void presetsComeFromTheShippedIndex() {
        assertTrue(Catalogue.presets().contains("species/cat"));
        assertTrue(Catalogue.presets().stream().noneMatch(p -> p.startsWith("datapacks/")),
                "a datapack is not something the Builder starts from");
    }
}
