package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.datapack.ItemCatalogue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * make_item's refusal of an effect that does not exist names the one it most likely meant —
 * by spelling first (the old rule), then by meaning — and says nothing when it cannot tell.
 * The full list still follows in the refusal either way; this only says which line to read first.
 */
class MakeItemNearestTest {

    private static final ItemCatalogue CAT = ItemCatalogue.load();

    @Test
    @DisplayName("spelling first: a guess that starts like a real id is pointed at it, as before")
    void spellingFirst() {
        assertTrue(MakeItemTool.nearest(CAT, "speed_boost").contains("'speed'"),
                MakeItemTool.nearest(CAT, "speed_boost"));
    }

    @Test
    @DisplayName("then meaning: Spark's spawn / anger / random_item are summon / aggro / outcome_table")
    void thenMeaning() {
        assertTrue(MakeItemTool.nearest(CAT, "spawn").contains("Did you mean 'summon'"));
        assertTrue(MakeItemTool.nearest(CAT, "anger").contains("Did you mean 'aggro'"));
        assertTrue(MakeItemTool.nearest(CAT, "random_item").contains("Did you mean 'outcome_table'"));
    }

    @Test
    @DisplayName("silent when unsure: a near-miss suggestion is worse than none")
    void silentWhenUnsure() {
        assertEquals("", MakeItemTool.nearest(CAT, "weather_clear"));
        assertEquals("", MakeItemTool.nearest(CAT, "polymorph"));
    }
}
