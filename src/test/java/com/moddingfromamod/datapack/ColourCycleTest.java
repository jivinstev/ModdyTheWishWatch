package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The recolour part: which mobs, which field, and a template that renders whole. */
class ColourCycleTest {

    @Test
    void aChildsWordForTheMobFindsItsPalette() {
        assertEquals("Variant", ColourCycle.palette("axolotl").field());
        assertEquals("Variant", ColourCycle.palette("Axolotls").field());
        assertEquals("Color", ColourCycle.palette("minecraft:sheep").field());
        assertEquals(ColourCycle.palette("fox"), ColourCycle.palette("foxes"));
        assertNull(ColourCycle.palette("zombie"), "a mob with no colours of its own has no palette");
    }

    @Test
    void everyPaletteIsARealCycle() {
        for (String mob : ColourCycle.mobs()) {
            var p = ColourCycle.palette(mob);
            assertTrue(p.values().size() >= 2, mob + " has nothing to cycle between");
            assertEquals(p.values().size(), p.values().stream().distinct().count(),
                    mob + " repeats a colour");
        }
    }

    @Test
    void oneStepPerColourAndEachAimsAtTheTaggedMob() {
        var p = ColourCycle.palette("sheep");
        String steps = ColourCycle.steps("rainbow_sheep", p, 20);
        assertEquals(p.values().size(), steps.split("\n").length);
        for (String line : steps.split("\n")) {
            assertTrue(line.strip().startsWith("/execute if score #t_rainbow_sheep"), line);
            assertTrue(line.contains("as @e[tag=rainbow_sheep] run data modify entity @s Color"),
                    line);
        }
    }

    @Test
    void theCatalogueEntryRendersWithNoPlaceholderLeft() {
        EffectCatalogue cat = EffectCatalogue.load();
        assertNotNull(cat.get("colour_cycle"), "the recolour part is in the catalogue");
        var p = ColourCycle.palette("axolotl");
        Map<String, String> params = new HashMap<>();
        params.put("TAG", "rainbow_axolotl");
        params.put("PERIOD_TICKS", "20");
        params.put("KEY", ColourCycle.keyFor("rainbow_axolotl"));
        params.put("STEPS", ColourCycle.steps("rainbow_axolotl", p, 20));
        params.put("COUNT", String.valueOf(p.values().size()));
        String src = cat.render("colour_cycle", params);
        assertFalse(src.contains("${"), src);
        assertTrue(src.contains("set value 3"), "the cyan axolotl step is in the program");
    }

    @Test
    void aLongTagStillMakesAShortScoreHolder() {
        assertTrue(ColourCycle.keyFor("wishmob_a_very_long_species_name_indeed_it_is").length()
                <= 28);
        assertEquals("rainbow_axolotl", ColourCycle.keyFor("Rainbow-Axolotl"));
    }
}
