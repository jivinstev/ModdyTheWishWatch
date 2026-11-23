package com.moddingfromamod.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Gate A for the creature selector.
 *
 * <p>These strings are handed to the agent in tool results and end up baked into datapacks it
 * writes, so the format is a published contract — changing one silently would break every wish that
 * ever used it. That is what these tests pin.
 */
class WishMobTagsTest {

    @Test
    void aSpeciesGetsItsOwnTag() {
        assertEquals("wishmob_spikor", WishMobTags.forSpecies("spikor"));
        assertEquals("@e[tag=wishmob_spikor]", WishMobTags.selector("spikor"));
        assertEquals("@e[tag=wishmob_spikor,limit=1,sort=nearest]",
                WishMobTags.nearestSelector("spikor"));
    }

    @Test
    void colourCodesAndPunctuationAreStrippedNotEscaped() {
        // The real failure: the only handle the agent had was "§6SPIKOR, King of the Kroaks", and
        // deriving anything command-shaped from it broke. A tag must never carry that.
        String tag = WishMobTags.forSpecies("§6SPIKOR, King of the Kroaks!");
        assertEquals("wishmob_spikor_king_of_the_kroaks", tag);
        assertFalse(tag.contains("§"), "a colour code must never reach a selector");
        assertFalse(tag.contains(","), "a comma would terminate the selector argument early");
        assertFalse(tag.contains(" "), "a space would split the command");
    }

    @Test
    void aTagIsAlwaysUsableInASelector() {
        // Anything that survives must be [a-z0-9_-] — the set a command selector accepts unquoted.
        for (String awkward : new String[]{"Ünïcödé", "a b c", "UPPER", "trailing...", "123",
                                           "mixed-Case_9", "emoji🐢here"}) {
            String tag = WishMobTags.forSpecies(awkward);
            assertTrue(tag.matches("[a-z0-9_-]+"),
                    "tag for '" + awkward + "' was not selector-safe: " + tag);
        }
    }

    @Test
    void anUnusableIdFallsBackToTheGenericTagRatherThanAnEmptyOne() {
        // "wishmob_" alone, or "@e[tag=]", would silently match EVERYTHING — far worse than
        // matching nothing. Degrade to the honest generic tag instead.
        assertEquals(WishMobTags.ALL, WishMobTags.forSpecies("§§§"));
        assertEquals(WishMobTags.ALL, WishMobTags.forSpecies(""));
        assertEquals(WishMobTags.ALL, WishMobTags.forSpecies(null));
    }

    @Test
    void speciesTagsAreDistinguishableFromEveryOtherTag() {
        // applySpecies uses this to clear a STALE species tag on a re-sculpt without touching tags
        // that other systems (or other datapacks) put on the same entity.
        assertTrue(WishMobTags.isSpeciesTag("wishmob_spikor"));
        assertFalse(WishMobTags.isSpeciesTag(WishMobTags.ALL), "the generic tag is not a species tag");
        assertFalse(WishMobTags.isSpeciesTag("bwf"), "a datapack's own tag must survive untouched");
        assertFalse(WishMobTags.isSpeciesTag("othermod_boss_castle"));
        assertFalse(WishMobTags.isSpeciesTag(null));
    }

    @Test
    void differentSpeciesNeverCollide() {
        assertFalse(WishMobTags.forSpecies("spikor").equals(WishMobTags.forSpecies("spikor2")));
        assertFalse(WishMobTags.forSpecies("dragon").equals(WishMobTags.forSpecies("dragon_baby")));
    }
}
