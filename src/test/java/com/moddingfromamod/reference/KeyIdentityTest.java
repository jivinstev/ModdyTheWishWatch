package com.moddingfromamod.reference;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The same character must key the same way at fetch time and at grading time.</b>
 *
 * <p>This is the link that has broken most often and always silently. Three separate incidents:
 *
 * <ul>
 *   <li>prefetch keyed on a regex ({@code Spikormake}, {@code Kroaks}) while the agent keyed on its
 *       own phrasing ({@code Spikor}) — two independent guesses at one string</li>
 *   <li>{@code ReferenceUse} recorded under the agent's argument while the harness read by corpus
 *       id, so a run that DID fetch references reported "0 lookups"</li>
 *   <li>captured sculpts were named by species id while the corpus used another, so
 *       {@code chucko} and {@code chucko_mcburger} never matched</li>
 * </ul>
 *
 * <p>Every one of those produced a plausible-looking result with the feature silently disabled,
 * which is why this is tested rather than reasoned about.
 */
class KeyIdentityTest {

    @BeforeEach
    void reset() {
        CharacterResolver.clear();
        CharacterLineage.clear();
    }

    @Test
    @DisplayName("FETCH and GRADE derive the same key from one wish")
    void fetchAndGradeAgree() {
        String wish = "Make SPIKOR, king of the Kroaks! He can breathe FIRE!";
        // What prefetch would write...
        Optional<String> atFetch = CharacterResolver.resolve(wish);
        // ...and what the grading path reads back, from the same wish.
        Optional<String> atGrade = CharacterResolver.resolve(wish);
        assertEquals(atFetch, atGrade);
        assertEquals(CharacterResolver.keyFor(wish), CharacterResolver.keyFor(wish));
    }

    @Test
    @DisplayName("the agent's OWN phrasing cannot change the key")
    void agentPhrasingIsOnlyAHint() {
        // The agent may ask for "Spikor", "SPIKOR" or "Spikor, King of the Kroaks". None of those
        // may decide where the cache is read, or it can miss a fetch already sitting there.
        String wish = "Make SPIKOR king of the Kroaks!";
        String canonical = CharacterResolver.resolve(wish).orElse("");
        for (String asked : new String[] {"Spikor", "SPIKOR", "Spikor, King of the Kroaks"}) {
            assertEquals(canonical, CharacterResolver.resolve(wish).orElse(""),
                    "the key must come from the wish, not from '" + asked + "'");
        }
    }

    @Test
    @DisplayName("THE follow-up case: a second wish that names nobody still finds the character")
    void followUpKeepsTheKey() {
        // The whole reason the reference is worth fetching is the SECOND pass, when it is warm.
        // A follow-up almost never repeats the name, so re-resolving its text returns nothing --
        // and the cache would be missed at exactly the moment it finally has something.
        CharacterLineage.remember("omega_lad", "Omega Lad");

        for (String followUp : new String[] {"make him bigger", "give him a friendlier face",
                "make it look cooler"}) {
            assertTrue(CharacterResolver.resolve(followUp).isEmpty(),
                    "the follow-up names nobody, as expected: " + followUp);
            assertEquals(Optional.of("Omega Lad"),
                    CharacterLineage.resolve(followUp, "omega_lad"),
                    "must fall back to the creature being edited: " + followUp);
        }
    }

    @Test
    @DisplayName("a follow-up that DOES name someone else wins over the lineage")
    void namingSomeoneElseWins() {
        // A child may genuinely change the subject; the creature's history must not override that.
        CharacterLineage.remember("omega_lad", "Omega Lad");
        var r = CharacterLineage.resolve("now make Tralalelo Tralalala the shark", "omega_lad");
        assertTrue(r.isPresent());
        assertTrue(r.get().toLowerCase().contains("tralal"),
                "the wish named someone new: " + r.get());
    }

    @Test
    @DisplayName("normalisation is stable across the casings a child types")
    void normalisationStable() {
        String a = CharacterResolver.normalise("Chucko McBurger");
        assertEquals(a, CharacterResolver.normalise("chucko mcburger"));
        assertEquals(a, CharacterResolver.normalise("CHUCKO  McBurger"));
        assertEquals(a, CharacterResolver.normalise("Chucko-McBurger"));
    }

    @Test
    @DisplayName("nothing is remembered for a creature the child invented")
    void inventedCreaturesGetNoLineage() {
        // Remembering one would make a later follow-up fetch somebody else's Zizzle.
        assertTrue(CharacterLineage.forSpecies("zizzle").isEmpty());
        assertTrue(CharacterLineage.resolve("make it bigger", "zizzle").isEmpty());
    }

    /**
     * <b>The provenance metric must be asked under the name the researcher RECORDED.</b>
     *
     * <p>The A/B harness asked {@code ReferenceUse.forCharacter(corpusCase.id())} while the
     * researcher records against the RESOLVED character. Spikor matched by luck — its corpus id
     * and its name slugify identically. Omega Lad did not: the corpus id is {@code omegalad}, the
     * resolved name slugifies to {@code omega_lad}, and a run that demonstrably used a reference
     * (three cache hits in the log, the tool call in the action log) recorded
     * {@code referenceLookedUp=false}.
     *
     * <p>That is the worst possible direction for the error. An arm that USED a reference,
     * recorded as one that did not, makes an A/B read as a null result — and a creature built
     * without a reference looks exactly like one built with a bad one.
     */
    @Test
    @DisplayName("REAL BUG: a corpus id and its resolved name must agree, or provenance lies")
    void aCorpusIdIsNotTheKeyTheResearcherUsed() {
        // The two spellings that diverged in the field.
        assertEquals("omega_lad", ReferenceUse.keyForTest("Omega Lad"));
        assertNotEquals(ReferenceUse.keyForTest("omegalad"), ReferenceUse.keyForTest("Omega Lad"),
                "if these ever agree the bug is hidden rather than fixed, and the next character "
                        + "whose id omits a space will silently record as having had no reference");
        // Spikor is the case that masked it: id and name slugify the same, so it looked fine.
        assertEquals(ReferenceUse.keyForTest("spikor"), ReferenceUse.keyForTest("Spikor"));
    }
}
