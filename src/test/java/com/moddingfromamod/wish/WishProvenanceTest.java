package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Provenance is only useful if every case has one and nobody can quietly count fixtures as demand.
 *
 * <p>The mistake being guarded is concrete: ranked by raw case count, {@code machines} is FIRST with
 * 119 cases; ranked by demand evidence it is FOURTEENTH with 2. Almost all of that difference is
 * fixtures written while building the machines subsystem — the subsystem arguing for itself, using
 * its own test data as the argument.
 */
class WishProvenanceTest {

    @Test
    void everyCaseKnowsWhereItCameFrom() {
        List<String> anonymous = WishCorpus.load().stream()
                .filter(c -> c.provenance() == null)
                .map(WishCorpus.Case::id).toList();
        assertEquals(List.of(), anonymous,
                "these cases declare no provenance, so any ranking that includes them is guessing "
                        + "whether they are demand or fixtures: " + anonymous);
    }

    @Test
    void fixturesAreNotDemandEvidence() {
        assertFalse(WishProvenance.GENERATED.isDemandEvidence(),
                "a case written to exercise a subsystem must never count as somebody wanting it");
        assertFalse(WishProvenance.OBSERVED_OUTPUT.isDemandEvidence(),
                "something the agent BUILT is evidence pointing the other way from a request");
        assertTrue(WishProvenance.WISHED.isDemandEvidence());
        assertTrue(WishProvenance.INFERRED.isDemandEvidence());
    }

    @Test
    void aGeneratedCaseNeverCountsAsDemand() {
        // The specific circularity this whole taxonomy exists to prevent. (It was asked of the
        // generated machines corpus until that went with Create; the rule is general.)
        var generated = WishCorpus.load().stream()
                .filter(c -> c.provenance() == WishProvenance.GENERATED).toList();
        assertTrue(generated.size() > 50,
                "expected a large generated corpus, found " + generated.size());
        var demand = generated.stream().filter(WishCorpus.Case::isDemandEvidence)
                .map(WishCorpus.Case::id).toList();
        assertTrue(demand.isEmpty(),
                "generated cases are counting as demand evidence: " + demand + ". A fixture we "
                        + "wrote is not a child asking for anything.");
    }

    @Test
    void anUnrecognisedSourceIsTreatedAsAFixtureNotAsDemand() {
        // Conservative on purpose: mistaking a fixture for demand skews a roadmap, while the
        // opposite merely under-counts.
        assertEquals(WishProvenance.GENERATED, WishProvenance.fromSource("something new"));
        assertEquals(WishProvenance.GENERATED, WishProvenance.fromSource(null));
        assertEquals(WishProvenance.WISHED, WishProvenance.fromSource("real play 2026-08-05"));
        assertEquals(WishProvenance.OBSERVED_OUTPUT,
                WishProvenance.fromSource("real agent output, mined from a world"));
    }

    @Test
    void aDeclaredProvenanceBeatsTheGuessFromSource() {
        WishCorpus.Case c = WishCorpus.parse(
                "{\"id\":\"x\",\"kind\":\"machines\",\"source\":\"mod-repo feature inventory\","
                        + "\"provenance\":\"GENERATED\"}").get(0);
        assertEquals(WishProvenance.GENERATED, c.provenance(),
                "the declared field must win, or stamping a case can never correct a bad guess");
    }
}
