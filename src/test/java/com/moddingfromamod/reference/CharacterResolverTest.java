package com.moddingfromamod.reference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The property that matters is <b>key identity</b>: whatever the prefetch writes, the lookup must
 * read. Everything else here is about not looking up things that should not be looked up.
 *
 * <p>These exercise the HEURISTIC path directly, so they run without a model. The model path is
 * exercised live by the smoke run.
 */
class CharacterResolverTest {

    @Test
    @DisplayName("THE property: prefetch and lookup derive the same key from the same wish")
    void keysCannotDiverge() {
        // The bug this replaces: prefetch keyed on its own regex output ("Spikormake"), the agent
        // keyed on its own phrasing ("Spikor"), and they matched only by luck.
        String wish = "Make SPIKOR, king of the Kroaks! He can breathe FIRE!";
        var a = CharacterResolver.keyFor(wish);
        var b = CharacterResolver.keyFor(wish);
        assertEquals(a, b, "the same wish must always give the same key");
    }

    @Test
    @DisplayName("an invented creature is NOT looked up")
    void inventedCreaturesAreLeftAlone() {
        // Looking these up returns somebody else's Zizzle and quietly replaces the child's design.
        for (String w : new String[] {
                "invent me a creature called Zizzle, a spiky orange lizard with three tails",
                "invent a new creature called Glorp, a squishy purple four-eyed slug",
                "make a big troll called Grumbnar with a club"}) {
            assertTrue(CharacterResolver.heuristic(w).isEmpty(),
                    "should not look up an invented creature: " + w);
        }
    }

    @Test
    @DisplayName("an archetype is NOT looked up")
    void archetypesAreLeftAlone() {
        for (String w : new String[] {"an icy mage that makes a blizzerd",
                "a fire ghost thing that explodes in fire",
                "a huge walking tree monster"}) {
            assertTrue(CharacterResolver.heuristic(w).isEmpty(),
                    "should not look up an archetype: " + w);
        }
    }

    @Test
    @DisplayName("REAL BUG: no leading verbs, no cross-clause runs")
    void junkCandidatesAreGone() {
        // All three were actually prefetched in a live run and cached under keys nothing could ask
        // for: "Make SPIKOR", "Spikormake", "Fun Mealsmake Tralalelo Tralalala".
        var b = CharacterResolver.heuristic("Make SPIKOR king of the Kroaks!");
        assertTrue(b.isPresent(), "should find a character");
        assertTrue(b.get().toLowerCase().startsWith("spikor"), "got: " + b.get());

        var t = CharacterResolver.heuristic(
                "make chucko mcburger with Fun Meals! make Tralalelo Tralalala the shark");
        assertTrue(t.isEmpty() || !t.get().contains("Mealsmake"), "got: " + t);
    }

    @Test
    @DisplayName("the key is stable across the casings a child actually types")
    void keyIsStableAcrossCasing() {
        assertEquals(CharacterResolver.normalise("Chucko McBurger"),
                CharacterResolver.normalise("chucko mcburger"));
        assertEquals(CharacterResolver.normalise("Chucko McBurger"),
                CharacterResolver.normalise("CHUCKO  McBurger"));
    }

    @Test
    @DisplayName("#1219: the reply names a KIND, and anything else is no lookup at all")
    void theReplyCarriesAKind() {
        var b = CharacterResolver.parse("CHARACTER: Spikor", "make spikor");
        assertEquals(new CharacterResolver.Resolved("Spikor", SubjectKind.CHARACTER), b.get());
        var a = CharacterResolver.parse("thinking...\ncreature: Axolotl.", "an axolotl");
        assertEquals(new CharacterResolver.Resolved("Axolotl", SubjectKind.CREATURE), a.get());
        assertTrue(CharacterResolver.parse("NONE", "build me a Treehouse").isEmpty(),
                "an object is not looked up");
        // MEASURED on the live wishes: the model once answered with a question. An unprefixed
        // or garbled reply used to be taken as a name, and the heuristic would have looked up
        // the capitalised word in "build me a Treehouse".
        assertTrue(CharacterResolver.parse("What's the wish?", "a fork").isEmpty());
        assertTrue(CharacterResolver.parse("Treehouse", "build me a Treehouse").isEmpty());
        assertTrue(CharacterResolver.parse("CHARACTER: What's the wish?", "a fork").isEmpty());
        assertTrue(CharacterResolver.parse("CHARACTER: a very long explanation of why this is "
                + "not a character at all", "x").isEmpty());
    }
}
