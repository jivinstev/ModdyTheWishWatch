package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * the facts the agent decides a referent from are FACTS. The resolver they replace told the
 * agent "THE CHILD MEANS …" and "do not ask him which he meant", and was 0 for 4 live; nothing this
 * renders may read as an answer.
 */
class ReferentFactsTest {

    private static final ReferentFacts.Thing PEGASUS =
            new ReferentFacts.Thing("pegasus", "species", "Pegasus", 6.2);
    private static final ReferentFacts.Thing CASTLE =
            new ReferentFacts.Thing("sky_castle", "build", "", 40);

    @Test
    void noFactsIsNoBlock() {
        assertEquals("", ReferentFacts.render(List.of(), null, -1));
        assertEquals("", ReferentFacts.render(null, null, 5_000));
    }

    @Test
    void whatIsInHisViewIsSaidNearestFirstWithIdKindAndDistance() {
        String facts = ReferentFacts.render(List.of(CASTLE, PEGASUS), null, -1);
        assertTrue(facts.contains("his Pegasus ('pegasus', creature), 6 blocks away"), facts);
        assertTrue(facts.contains("his sky castle ('sky_castle', building), 40 blocks away"), facts);
        assertTrue(facts.indexOf("pegasus") < facts.indexOf("sky_castle"), "nearest first: " + facts);
    }

    @Test
    void onlyAFewThingsInViewAreNamed() {
        List<ReferentFacts.Thing> many = List.of(
                new ReferentFacts.Thing("a", "species", "A", 1),
                new ReferentFacts.Thing("b", "species", "B", 2),
                new ReferentFacts.Thing("c", "species", "C", 3),
                new ReferentFacts.Thing("d", "species", "D", 4));
        String facts = ReferentFacts.render(many, null, -1);
        assertFalse(facts.contains("'d'"), facts);
        assertTrue(facts.contains("and 1 more"), facts);
    }

    @Test
    void whatHeTouchedLastIsSaidWithItsAge() {
        String facts = ReferentFacts.render(List.of(), PEGASUS, 40_000);
        assertTrue(facts.contains("The last thing a wish of his made or changed: his Pegasus "
                + "('pegasus', creature), 40 s ago."), facts);
        assertTrue(ReferentFacts.render(List.of(), PEGASUS, 5 * 60_000).contains("5 minutes ago"));
    }

    /** The whole fix: the facts never tell the agent what the wish means. */
    @Test
    void theFactsNeverReadAsAResolution() {
        String facts = ReferentFacts.render(List.of(PEGASUS, CASTLE), PEGASUS, 1_000)
                .toLowerCase(Locale.ROOT);
        for (String claim : List.of("he means", "the child means", "said so", "do not ask",
                "this is the one", "working on")) {
            assertFalse(facts.contains(claim), "'" + claim + "' in the facts: " + facts);
        }
        assertTrue(facts.contains("not what this wish is about") && facts.contains("`about`"),
                "the block must say the decision is the agent's, in plan_wish's about: " + facts);
    }
    /** his last wish's words ride with what it made, marked as the likeliest "it". */
    @Test
    void theWishThatMadeTheLastThingIsSaidInHisWords() {
        String facts = ReferentFacts.render(List.of(), PEGASUS, 40_000, "a pegasus I can ride",
                "a pegasus I can ride", 40_000);
        assertTrue(facts.contains("his Pegasus ('pegasus', creature), 40 s ago, by his wish "
                + "\"a pegasus I can ride\"."), facts);
        assertTrue(facts.contains("most likely about this one"), facts);
        assertFalse(facts.contains("His previous wish"), "the same wish is not said twice: " + facts);
    }

    @Test
    void aLaterWishThatMadeNothingIsSaidOnItsOwn() {
        String facts = ReferentFacts.render(List.of(), PEGASUS, 300_000, "a pegasus I can ride",
                "make me fly", 30_000);
        assertTrue(facts.contains("His previous wish, 30 s ago: \"make me fly\" — it made or "
                + "changed none of his things."), facts);
        String alone = ReferentFacts.render(List.of(), null, -1, "", "make me fly", 30_000);
        assertTrue(alone.contains("make me fly"), "a previous wish alone is still a fact: " + alone);
    }

    @Test
    void daysAreSaidAsDays() {
        assertEquals("3 days ago", ReferentFacts.ago(3L * 24 * 3600_000));
    }

    @Test
    void theNewFactsStillNeverReadAsAResolution() {
        String facts = (ReferentFacts.render(List.of(PEGASUS), PEGASUS, 1_000, "a pegasus", "a pegasus",
                1_000) + ReferentFacts.NO_ANTECEDENT).toLowerCase(Locale.ROOT);
        for (String claim : List.of("he means", "the child means", "said so", "do not ask",
                "this is the one", "working on")) {
            assertFalse(facts.contains(claim), "'" + claim + "' in the facts: " + facts);
        }
    }
}
