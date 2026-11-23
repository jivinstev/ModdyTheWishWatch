package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The corpus has to stay broad and stay honest about what it is testing. */
class CreatureCorpusTest {

    @Test
    @DisplayName("the corpus loads and stays broad")
    void itLoadsAndIsBroad() {
        List<CreatureCorpus.Case> all = CreatureCorpus.load();
        assertTrue(all.size() >= 20,
                "earlier evals used three creatures and kept reaching conclusions that did not "
                        + "survive a fourth. Found " + all.size());
    }

    @Test
    @DisplayName("every tier is represented — each grades differently, so a missing tier is a blind spot")
    void everyTierIsCovered() {
        for (String tier : List.of("named", "archetype", "variant", "invented")) {
            assertFalse(CreatureCorpus.ofTier(tier).isEmpty(),
                    "no '" + tier + "' cases. Tiers differ in WHERE correctness comes from, so a "
                            + "missing tier is a whole class of wish going unmeasured.");
        }
    }

    /**
     * The awkward cases are the point. A corpus of well-documented characters would say nothing
     * about the ones where the reference is a meme, or absent entirely.
     */
    @Test
    @DisplayName("the hard-to-source cases are present and flagged as hard")
    void hardSourcingIsRepresented() {
        List<CreatureCorpus.Case> named = CreatureCorpus.ofTier("named");
        long hard = named.stream()
                .filter(c -> c.sourcing() != null && c.sourcing().contains("HARD")).count();
        assertTrue(hard >= 2,
                "at least two named cases must be genuinely hard to source, or the reference "
                        + "guidance is only ever exercised on easy mode. Found " + hard);
        assertTrue(named.stream().anyMatch(c -> "sigmaboy".equals(c.id())),
                "the case that may have NO canonical design is the one that tests whether the "
                        + "agent notices instead of inventing one");
    }

    @Test
    @DisplayName("invented creatures state checkable properties; named ones need a reference")
    void tiersCarryWhatTheirGradingNeeds() {
        for (CreatureCorpus.Case c : CreatureCorpus.ofTier("invented")) {
            assertFalse(c.named().isEmpty(),
                    "an invented creature is graded ONLY by the child's own words, so '" + c.id()
                            + "' must state some: " + c.prompt());
            assertFalse(c.needsAReference(),
                    "'" + c.id() + "' is invented; there is nothing to look up");
        }
        for (CreatureCorpus.Case c : CreatureCorpus.ofTier("named")) {
            assertTrue(c.needsAReference(),
                    "'" + c.id() + "' is a named character and must name its reference subject");
        }
    }

    @Test
    @DisplayName("selecting a subset is explicit, never a silent sample")
    void selectionIsExplicit() {
        assertEquals(1, CreatureCorpus.select("sonic", 99).size());
        assertEquals(2, CreatureCorpus.select("sonic,yoshi", 99).size());
        assertEquals(3, CreatureCorpus.select(null, 3).size());
        assertEquals(CreatureCorpus.load().size(), CreatureCorpus.select(null, 999).size());
    }

    @Test
    @DisplayName("every case carries the words a child would type, not a tidy paraphrase")
    void promptsAreInTheChildsVoice() {
        for (CreatureCorpus.Case c : CreatureCorpus.load()) {
            assertTrue(c.prompt() != null && c.prompt().length() > 8,
                    c.id() + " has no usable prompt");
            assertTrue(c.tier() != null && c.sourcing() != null,
                    c.id() + " must say what tier it is and how hard its reference is to find");
        }
    }
}
