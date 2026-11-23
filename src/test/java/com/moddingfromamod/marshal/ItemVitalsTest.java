package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.moddingfromamod.datapack.ItemCatalogue;
import org.junit.jupiter.api.Test;

/** The item card's words and numbers (2026-10-12): what the watch and the account page both show. */
class ItemVitalsTest {

    private static final ItemCatalogue CATALOGUE = ItemCatalogue.load();

    private static String line(String effect) {
        var e = CATALOGUE.get(effect);
        return e == null ? "" : e.childWhen();
    }

    @Test
    void everyEffectInTheCatalogueSaysWhatItDoes() {
        for (var e : CATALOGUE.all()) {
            String does = ItemVitals.whatItDoes(List.of(e.id()), Map.of(), ItemVitalsTest::line);
            assertFalse(does.isBlank(), e.id() + " has no words for a child");
            assertFalse(does.contains("${"), e.id() + " leaks a placeholder: " + does);
        }
    }

    @Test
    void everyShapeHasANoun() {
        for (String shape : ItemCatalogue.SHAPES) {
            assertNotEquals("a thing you can hold", ItemVitals.noun(shape, ""),
                    shape + " falls through to the default noun");
        }
        assertEquals("boots", ItemVitals.noun("worn_feet", ""));
        assertEquals("a drink", ItemVitals.noun("on_consume", "minecraft:potion"));
        assertEquals("food — 8 of them", ItemVitals.whatItIs("on_consume", "minecraft:cookie", 8));
    }

    @Test
    void aShrinkingCookieIsNeverDescribedAsMakingYouHuge() {
        // The starter Tiny Cookie: grow, SCALE 0.3 — the catalogue's line says HUGE.
        String does = ItemVitals.whatItDoes(List.of("grow"), Map.of("SCALE", "0.3", "SECONDS", "60"),
                ItemVitalsTest::line);
        assertTrue(does.contains("TINY"), does);
        assertFalse(does.contains("HUGE"), does);
        String big = ItemVitals.whatItDoes(List.of("grow"), Map.of("SCALE", "4"), ItemVitalsTest::line);
        assertTrue(big.contains("HUGE"), big);
        assertEquals(List.of("size ×0.3", "lasts 60 seconds"),
                ItemVitals.stats(Map.of("SCALE", "0.3", "SECONDS", "60")));
    }

    @Test
    void numbersReadAsWords() {
        assertEquals(List.of("5 hearts of damage"), ItemVitals.stats(Map.of("HEARTS", "5")));
        assertEquals(List.of("1 heart of damage"), ItemVitals.stats(Map.of("HEARTS", "1")));
        assertEquals(List.of("makes zombie", "3 at a time"),
                ItemVitals.stats(Map.of("COUNT", "3", "MOB", "minecraft:zombie")));
        assertEquals(List.of("level 4", "lasts 30 seconds"),
                ItemVitals.stats(Map.of("LEVEL", "3", "SECONDS", "30")));
        assertEquals(List.of("takes ice, sugar"),
                ItemVitals.stats(Map.of("INGREDIENTS", "minecraft:ice, minecraft:sugar")));
        // a switch or a design name has no phrase, and is never shown raw
        assertEquals(List.of(), ItemVitals.stats(Map.of("SAFE", "yes", "DESIGN", "tidal_wave")));
        // a number that is not one is left out rather than printed as garbage
        assertEquals(List.of(), ItemVitals.stats(Map.of("HEARTS", "lots")));
    }

    @Test
    void everyParameterTheCatalogueOffersEitherHasAPhraseOrIsDeliberatelyOmitted() {
        var omitted = java.util.Set.of("SAFE", "BOOM", "BREAKS_BLOCKS", "DESIGN", "EXTRAS", "MAX",
                "SURPRISES", "WHERE");
        for (var e : CATALOGUE.all()) {
            for (String p : e.params()) {
                assertTrue(ItemVitals.ORDER.contains(p) || omitted.contains(p),
                        e.id() + "'s " + p + " has no phrase and is not listed as omitted");
            }
        }
    }

    @Test
    void colourIsAWordNeverAHexCode() {
        assertEquals("purple", ItemVitals.colour("purple", rgb -> "x"));
        assertEquals("light blue", ItemVitals.colour("#4FC3F7", rgb -> "light_blue"));
        assertEquals(null, ItemVitals.colour("", rgb -> "x"));
        assertEquals("made once", ItemVitals.made(1));
        assertEquals("made 3 times", ItemVitals.made(3));
    }
}
