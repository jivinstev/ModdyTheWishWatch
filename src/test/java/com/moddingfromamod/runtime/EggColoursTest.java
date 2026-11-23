package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Gate A for spawn-egg colours. The property that matters to a child is that he can <b>find his
 * dragon's egg</b> on a shelf of eggs — so no two creatures may look the same, and the same creature
 * must never change colour between sessions.
 */
class EggColoursTest {

    @Test
    void aColouredSpeciesUsesItsOwnColour() {
        assertEquals(0xFF00FF, EggColours.base(0xFF00FF, "anything"));
    }

    @Test
    void anUncolouredSpeciesStillGetsADistinctiveEgg() {
        int c = EggColours.base(EggColours.NO_TINT, "baby_dragon");
        assertNotEquals(EggColours.NO_TINT, c, "a white egg would be indistinguishable");
        // Vivid, not muddy: at least one channel is strong.
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        assertTrue(Math.max(r, Math.max(g, b)) > 150, "derived egg colours should be vivid");
    }

    @Test
    void theSameCreatureAlwaysGetsTheSameEgg() {
        // Stable across calls — and, because it's a pure function of the id, across sessions.
        assertEquals(EggColours.base(EggColours.NO_TINT, "baby_dragon"),
                EggColours.base(EggColours.NO_TINT, "baby_dragon"));
        // Case shouldn't matter; ids are lowercased everywhere else too.
        assertEquals(EggColours.base(EggColours.NO_TINT, "Baby_Dragon"),
                EggColours.base(EggColours.NO_TINT, "baby_dragon"));
    }

    @Test
    void differentCreaturesGetDifferentEggs() {
        String[] ids = {"baby_dragon", "giant_slime", "lava_beast", "mega_chicken", "guard_bot",
                        "pink_spider_queen", "tiny_friend", "sparkle_pony", "doom_worm"};
        Set<Integer> seen = new HashSet<>();
        for (String id : ids) {
            seen.add(EggColours.base(EggColours.NO_TINT, id));
        }
        assertEquals(ids.length, seen.size(), "every creature should get its own egg colour");
    }

    @Test
    void spotsAreLighterThanTheBaseSoTheSpecklesShow() {
        int base = EggColours.base(0x203040, "x");
        int spots = EggColours.spots(0x203040, "x");
        assertNotEquals(base, spots);
        for (int shift : new int[]{16, 8, 0}) {
            assertTrue(((spots >> shift) & 0xFF) >= ((base >> shift) & 0xFF),
                    "spot colour should be lighter on every channel");
        }
    }

    @Test
    void tintsMustBeOpaqueOrTheItemRendersInvisible() {
        // The bug this guards: a bare 0xRRGGBB has alpha 0x00, and Minecraft multiplies the item's
        // quads by the tint INCLUDING alpha — so the egg rendered as nothing at all.
        int bare = EggColours.base(EggColours.NO_TINT, "baby_dragon");
        assertEquals(0, bare >>> 24, "a plain colour has no alpha — that is what broke");

        int tint = EggColours.opaque(bare);
        assertEquals(0xFF, tint >>> 24, "an item tint must be fully opaque");
        assertEquals(bare, tint & 0xFFFFFF, "opaque() must not disturb the colour itself");
        // Idempotent, so double-applying can't corrupt it.
        assertEquals(tint, EggColours.opaque(tint));
    }

    @Test
    void handlesNothingGracefully() {
        // A missing/blank id must still yield a usable colour rather than throwing.
        assertTrue(EggColours.base(EggColours.NO_TINT, null) >= 0);
        assertTrue(EggColours.base(EggColours.NO_TINT, "") >= 0);
        assertTrue(EggColours.spots(EggColours.NO_TINT, null) >= 0);
    }
}
