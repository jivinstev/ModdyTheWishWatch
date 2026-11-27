package com.moddingfromamod.wishbuilder;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A world's own colours travel inline, and the player's Builder never changes</b> (#178). A world's make carries
 * {@code opts.palette}; no scheme is registered, so the player's scheme list, the Builder tree and the Apprentice's
 * recorded fingerprint stay exactly as they are; a player's spec has the palette taken off.
 */
class WorldPaletteTest {

    private static BuilderSpec golem(Map<String, String> opts) {
        return new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:bodies/rock_golem", "", 2,
                List.of(), "", 1, "", "", opts);
    }

    @Test
    void aPaletteIsExactlyThreeColoursAndOptionallyThreeReviewedBlocks() {
        Schemes.Scheme s = Schemes.parsePalette("#16131a, #B3121B,#FF3B3B");
        assertNotNull(s);
        assertEquals(List.of("#16131A", "#B3121B", "#FF3B3B"), List.of(s.main(), s.accent(), s.trim()));
        assertNull(s.mainBlock(), "colours only: a building keeps its own blocks");
        List<String> blocks = List.copyOf(Schemes.reviewedBlocks()).subList(0, 3);
        Schemes.Scheme b = Schemes.parsePalette("#16131A,#B3121B,#FF3B3B;" + String.join(",", blocks));
        assertNotNull(b);
        assertEquals(blocks, List.of(b.mainBlock(), b.accentBlock(), b.trimBlock()));
        for (String bad : List.of("", "#16131A,#B3121B", "#16131A,#B3121B,#FF3B3B,#000000", "red,black,white",
                "#16131A,#B3121B,#FF3B3", "#16131A,#B3121B,#FF3B3B;minecraft:tnt,minecraft:stone,minecraft:dirt",
                "#16131A,#B3121B,#FF3B3B;" + blocks.get(0), "#16131A,#B3121B,#FF3B3B;a;b")) {
            assertNull(Schemes.parsePalette(bad), bad);
        }
        assertTrue(!Schemes.reviewedBlocks().contains("minecraft:tnt") && !Schemes.reviewedBlocks().contains("minecraft:magma_block"));
    }

    @Test
    void theLoweringReadsTheInlinePaletteBeforeANamedScheme() {
        Schemes.Scheme s = Schemes.resolve(golem(Map.of(Schemes.PALETTE_OPT, "#16131A,#B3121B,#FF3B3B")));
        assertEquals("#16131A", s.main());
        assertNull(Schemes.resolve(golem(Map.of())));
        assertNull(Schemes.resolve(golem(Map.of(Schemes.PALETTE_OPT, "not a palette"))), "a bad palette paints nothing");
    }

    @Test
    void aPlayersSpecHasThePaletteTakenOffAndOnlyAWorldsKeepsIt() {
        BuilderSpec withPalette = golem(Map.of(Schemes.PALETTE_OPT, "#16131A,#B3121B,#FF3B3B", "strength", "2"));
        BuilderSpec player = BuilderAllowlist.REVIEWED.capped(withPalette);
        assertEquals("", player.opt(Schemes.PALETTE_OPT), "a player's spec carried a world's palette");
        assertEquals("2", player.opt("strength"), "only the palette is taken off");
        assertEquals("#16131A,#B3121B,#FF3B3B", BuilderAllowlist.FOR_WORLDS.capped(withPalette).opt(Schemes.PALETTE_OPT));
    }

    @Test
    void noWorldsColoursAreACoreScheme() {
        assertNull(Schemes.byId("shadow"), "a world's colours (a Shadow) were registered as a core scheme");
        for (Schemes.Scheme s : Schemes.ALL) {
            assertTrue(!s.words().contains("evil") && !s.words().contains("villain"), s.id() + " offers a world's words");
        }
    }

    @Test
    void thePlayersSchemesAreMainsExactly() {
        // the three-tone schemes a player picks from, as main has them: a world's colours never join this list
        assertEquals(List.of("ocean", "sunset", "forest", "candy", "royal", "ice", "lava", "crystal", "slime", "cloud",
                "gold"), Schemes.THREE_TONE.stream().map(Schemes.Scheme::id).toList());
        JsonObject tree = ApprenticeTreeFingerprintDiffTest.tree();
        String t = tree.toString();
        assertTrue(!t.contains("scheme:shadow") && !t.contains("\"palette\""), "the player's Builder tree offers a world's colours");
    }
}
