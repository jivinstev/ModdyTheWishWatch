package com.moddingfromamod.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.agent.Surroundings;
import com.moddingfromamod.wishbuilder.BuilderSuggest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** §3: find_content's around: true, and the brief's "In view when he wished", over the committed index snapshot. */
class AroundSearchTest {

    static final BuilderSuggest.Seen SEEN = new BuilderSuggest.Seen("minecraft:andesite",
            Map.of("minecraft:andesite", 30, "minecraft:grass_block", 9), Map.of("minecraft:sand", 12, "minecraft:raw_iron", 3));

    @Test
    void onlyWhatHeSawAndCarriedRankedWithCounts() {
        String all = AroundSearch.answer(SEEN, "", Found.index(), 40);
        assertTrue(all.indexOf("minecraft:andesite") < all.indexOf("minecraft:grass_block"), all);
        assertTrue(all.contains("minecraft:andesite — Andesite — at his crosshair — 30 of 45"), all);
        assertTrue(all.indexOf("minecraft:sand — Sand — ×12") < all.indexOf("minecraft:raw_iron"), all);
        String grass = AroundSearch.answer(SEEN, "grass", Found.index(), 40);
        assertTrue(grass.contains("minecraft:grass_block") && !grass.contains("andesite") && !grass.contains("sand"), grass);
        String stone = AroundSearch.answer(SEEN, "stone", Found.index(), 40);
        assertTrue(stone.contains("minecraft:andesite") && !stone.contains("minecraft:stone "), stone);
        assertEquals(AroundSearch.NO_VIEW, AroundSearch.answer(null, "x", Found.index(), 40));
    }

    @Test
    void theBriefSaysWhatHeSawAndNothingWithoutAReading() {
        assertEquals("", Surroundings.inView(null));
        String s = Surroundings.inView(SEEN);
        assertTrue(s.startsWith("  IN VIEW WHEN HE WISHED") && s.contains("at his crosshair: minecraft:andesite (Andesite)")
                && s.contains("in view: minecraft:andesite (Andesite) ×30, minecraft:grass_block (Grass Block) ×9")
                && s.contains("he carries: minecraft:sand (Sand) ×12, minecraft:raw_iron (Raw Iron) ×3"), s);
        assertTrue(Surroundings.inView(BuilderSuggest.Seen.NOTHING).contains("looking at: nothing"));
        assertFalse(Surroundings.inView(BuilderSuggest.Seen.NOTHING).contains("at his crosshair"));
    }
}
