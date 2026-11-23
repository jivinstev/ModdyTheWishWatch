package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gate A for the "summon the real thing" rule.
 *
 * <p>The live version of this assertion is a GameTest against the real registry, but that one can
 * only be meaningful when the mod is actually installed. These tests pin the <b>ranking rule
 * itself</b> against synthetic ids, so the regression that motivated all of this — a child asking
 * for a wither storm and being handed a costumed spider — is caught on a clean checkout with no
 * mods, no game, and no download.
 */
class ContentSearchTest {

    /** The registry as it looks when the Wither Storm mod is installed. */
    private static final List<String> WITH_WITHERSTORM = List.of(
            "minecraft:wither",
            "minecraft:wither_skeleton",
            "minecraft:withering_effect_thing",
            "witherstormmod:wither_storm",
            "witherstormmod:withered_symbiont",
            "minecraft:zombie");

    @Test
    void theRealWitherStormOutranksTheVanillaWither() {
        List<String> hits = ContentSearch.rank(WITH_WITHERSTORM, "wither storm", 40);
        assertFalse(hits.isEmpty(), "'wither storm' should find something");
        assertEquals("witherstormmod:wither_storm", hits.get(0),
                "the modded boss must be the FIRST thing the agent sees — it acts on the first "
                        + "plausible hit, and minecraft:wither is not what the child means");
    }

    @Test
    void everyQueryWordMustAppearForAStrongHit() {
        // "withered_symbiont" contains 'wither' but not 'storm', so it must not outrank the boss.
        List<String> hits = ContentSearch.rank(WITH_WITHERSTORM, "wither storm", 40);
        assertTrue(hits.indexOf("witherstormmod:wither_storm")
                        < hits.indexOf("witherstormmod:withered_symbiont"),
                "a full-phrase match ranks above a partial one");
    }

    @Test
    void vanillaStillWinsWhenNoModProvidesIt() {
        List<String> hits = ContentSearch.rank(WITH_WITHERSTORM, "zombie", 40);
        assertEquals(List.of("minecraft:zombie"), hits);
    }

    @Test
    void nearMissesAreKeptSoTheAgentIsNeverToldNothingExists() {
        // Only vanilla withers here — the phrase can't match in full, but returning an empty list
        // would push the agent straight to inventing a lookalike.
        List<String> hits = ContentSearch.rank(
                List.of("minecraft:wither", "minecraft:zombie"), "wither storm", 40);
        assertEquals(List.of("minecraft:wither"), hits);
    }

    @Test
    void resultsAreStableAndCapped() {
        List<String> many = List.of("modb:dragon", "moda:dragon", "minecraft:ender_dragon");
        assertEquals(List.of("moda:dragon", "modb:dragon"), ContentSearch.rank(many, "dragon", 2),
                "modded first, alphabetical within a group, then truncated");
    }

    @Test
    void survivesRubbishInput() {
        assertTrue(ContentSearch.rank(null, "wither", 10).isEmpty());
        assertTrue(ContentSearch.rank(WITH_WITHERSTORM, null, 10).isEmpty());
        assertTrue(ContentSearch.rank(WITH_WITHERSTORM, "", 10).isEmpty());
        assertTrue(ContentSearch.rank(WITH_WITHERSTORM, "!!! ???", 10).isEmpty());
    }

    // ===================== matches() — used by modify_mob to pick a target =====================

    @Test
    void matchesSelectsTheSameMobTheSearchFound() {
        assertTrue(ContentSearch.matches("witherstormmod:wither_storm", "wither storm"));
        assertTrue(ContentSearch.matches("witherstormmod:wither_storm", "wither_storm"));
        assertTrue(ContentSearch.matches("witherstormmod:wither_storm", "storm"));
        assertFalse(ContentSearch.matches("minecraft:zombie", "storm"));
    }

    @Test
    void anEmptyTargetMatchesAnything() {
        // This is what makes modify_mob's 'target' optional: "just change the nearest thing".
        assertTrue(ContentSearch.matches("minecraft:zombie", ""));
        assertTrue(ContentSearch.matches("minecraft:zombie", null));
        assertFalse(ContentSearch.matches(null, "zombie"));
    }

    @Test
    @DisplayName("a three-word query is not answered with wooden buttons (#252)")
    void aMultiWordQueryDoesNotMatchAFragmentOfOneWord() {
        // MEASURED IN A LIVE WISH. The child asked for a machine that makes BUTT CHEEK WATER,
        // the mod that provides it was not installed, and this answered with thirteen wooden
        // buttons because every one of them contains "butt". The agent read that as a hit
        // list, built a lookalike out of a millstone, and told him it was his butt cheek water
        // machine. The metric then scored it working:true.
        List<String> hits = ContentSearch.rank(
                List.of("minecraft:acacia_button", "minecraft:birch_button",
                        "minecraft:stone_button", "minecraft:water_bucket"),
                "butt cheek water", 20);
        for (String hit : hits) {
            assertFalse(hit.endsWith("_button"),
                    "a button is not an answer to 'butt cheek water' — it matched a fragment of "
                            + "one word out of three, and was returned as confidently as a real hit: "
                            + hits);
        }
    }

    @Test
    @DisplayName("the forgiving single-word match still works — 'grind' finds a grinder")
    void aSingleWordQueryStaysFuzzy() {
        // The other half, and the reason the fix is narrow: whole-word matching everywhere would
        // break the fuzzy search this tool exists for. A child says 'grind', not 'grinder'.
        assertTrue(ContentSearch.rank(List.of("create:crushing_wheel", "mod:rock_grinder"),
                        "grind", 20).contains("mod:rock_grinder"),
                "a one-word query must still match inside a longer word");
    }

    @Test
    @DisplayName("a multi-word query still falls back on a REAL word — 'wither storm' finds a wither")
    void aMultiWordFallbackStillMatchesAWholeWord() {
        // The fallback is worth keeping; it is only the fragment matching that is not. A wither
        // skeleton is a fair near-miss for 'wither storm'. A button is not.
        assertTrue(ContentSearch.rank(List.of("minecraft:wither_skeleton", "minecraft:stone"),
                        "wither storm", 20).contains("minecraft:wither_skeleton"),
                "a genuine word match must survive the fix");
    }
}
