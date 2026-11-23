package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>An ability has to select the creature it was given to.</b>
 *
 * <p>A wish creature carries exactly two tags — {@code wishmob} and {@code wishmob_<species>} —
 * and never a bare {@code spikor}. {@code apply_effect}'s schema asks the agent for "the same id
 * you gave sculpt_creature, e.g. spikor", which is the right thing to ask for, and for a long
 * while that bare id went straight into the ability's command selectors.
 *
 * <p>Nothing complained. The CBScript compiled, the datapack loaded, {@code verify_datapack}
 * passed, the tool reported <i>"Gave 'spikor' the ability"</i>, and the clock it installed
 * selected {@code @e[tag=spikor]} — which matches nothing in the world, for ever. Measured on a
 * real wish: <i>"make me a Spikor that breathes fire"</i> produced a verified fire-breath datapack
 * and a Spikor who never breathed, and a 195-second screen recording of that world contains
 * <b>zero</b> frames with a flame particle in them.
 *
 * <p>The obvious fix — always prefix — is also wrong, and the second half of these tests is why:
 * this tool is called with a LITERAL tag too. {@code AbilityLiveClientTest} puts a bare tag on an
 * ordinary pig, and rewriting that would break it exactly as silently.
 */
class ApplyEffectTagTest {

    /** Stands in for the live entity list: these tags exist in the world, nothing else does. */
    private static Predicate<String> worldWearing(String... tags) {
        return Set.of(tags)::contains;
    }

    @Test
    @DisplayName("a species id becomes the tag the creature really carries")
    void speciesIdIsPrefixed() {
        assertEquals("wishmob_spikor",
                ApplyEffectTool.selectorTagFor("spikor", worldWearing("wishmob", "wishmob_spikor")),
                "the ability's selectors must match WishMobEntity's own species tag; a bare "
                + "'spikor' selects nothing, and does so silently");
        assertEquals("wishmob_ice_dragon",
                ApplyEffectTool.selectorTagFor("Ice Dragon", worldWearing("wishmob_ice_dragon")),
                "and it must be sanitised exactly as the entity's own tag was, or the two "
                + "disagree about a creature they both name");
    }

    @Test
    @DisplayName("a literal tag on an ordinary entity is honoured, not rewritten")
    void literalTagOnAPlainEntity() {
        assertEquals("livetestmob",
                ApplyEffectTool.selectorTagFor("livetestmob", worldWearing("livetestmob")),
                "AbilityLiveClientTest tags a PIG with a bare literal and applies an effect to "
                + "it. Always prefixing would point that ability at wishmob_livetestmob, which "
                + "nothing wears — the same silent miss, introduced by the fix for it.");
    }

    @Test
    @DisplayName("with nothing wearing either, prefer the shape a wish creature will have")
    void unknownPrefersTheSpeciesTag() {
        assertEquals("wishmob_spikor", ApplyEffectTool.selectorTagFor("spikor", worldWearing()),
                "an ability written before its creature arrives should be aimed at the tag that "
                + "creature is going to carry");
    }

    @Test
    @DisplayName("a tag that is already a tag is left alone")
    void idempotent() {
        assertEquals("wishmob_spikor",
                ApplyEffectTool.selectorTagFor("wishmob_spikor", worldWearing("wishmob_spikor")),
                "a caller passing the full tag must not be double-prefixed into "
                + "wishmob_wishmob_spikor, which would select nothing exactly like the bug this "
                + "guards against");
        assertEquals("wishmob", ApplyEffectTool.selectorTagFor("wishmob", worldWearing("wishmob")),
                "the catch-all tag is a legitimate target: 'every creature I made'");
    }

    @Test
    @DisplayName("nothing sensible to select means the catch-all, never an empty selector")
    void blankFallsBackToAll() {
        assertEquals("wishmob", ApplyEffectTool.selectorTagFor("", worldWearing()));
        assertEquals("wishmob", ApplyEffectTool.selectorTagFor(null, worldWearing()));
    }
}
