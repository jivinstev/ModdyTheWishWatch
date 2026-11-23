package com.moddingfromamod.provenance;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixtures here are in the shape of REAL ones — the four Chucko manifests and an accepted
 * invented creature recorded in {@code origin/moddy/wishes} and quoted — paraphrased, because a
 * child's own words stay out of the public tree. The words a detector keys on are kept.
 *
 * <p>That matters more than usual: a lineage detector tuned on prose someone wrote to demonstrate
 * it would pass its own tests and then find nothing, which is precisely how a fixture-tuned gate
 * once caught 0 of 14 real creatures on its first live run.
 *
 * <p>Glorp (the accepted invented creature) is the negative control and the one that keeps this honest. It was accepted first time,
 * and a detector that chains it has replaced an optimistic bias with a pessimistic one.
 */
class WishLineageTest {

    private static final long MIN = 60_000L;
    private static final long WINDOW = 30 * MIN;

    // The real chain: one wish, then three complaints about what it produced.
    private static final WishLineage.Entry CHUCKO = new WishLineage.Entry(
            "chucko_mcburger", "species", "make me a chucko mcburger mob please!", 0);
    private static final WishLineage.Entry CLOWN_FRIEND = new WishLineage.Entry(
            "clown_friend", "species",
            "You just made chucko mcburger but he looks scary! he has a pumpkin head! "
                    + "Also you spawned two different chucko mcburgers", 5 * MIN);
    private static final WishLineage.Entry CHUCKO_CLOWN = new WishLineage.Entry(
            "chucko_clown", "species",
            "You just made chucko mcburger but he looks scary! he has a pumpkin head!", 9 * MIN);
    private static final WishLineage.Entry KILL_IT = new WishLineage.Entry(
            "chucko_mcburger_wish", "template", "that chucko mcburger is terrible, kill it",
            14 * MIN);

    // Accepted first time, no complaint. Must never be chained to anything.
    private static final WishLineage.Entry GLORP = new WishLineage.Entry(
            "glorp", "species", "make me a purple slug called glorp", 40 * MIN);

    private static List<WishLineage.Entry> theRealRun() {
        return List.of(CHUCKO, CLOWN_FRIEND, CHUCKO_CLOWN, KILL_IT, GLORP);
    }

    @Test
    @DisplayName("Chucko's four recorded 'successes' are one chain of four attempts")
    void theChuckoChainIsReconstructed() {
        var links = WishLineage.reconstruct(theRealRun(), WINDOW);
        assertEquals(4, WishLineage.attemptsFor("chucko_mcburger_wish", links),
                "four manifests, each recorded with 0 refinements, are one artifact retried three "
                        + "times — that is the whole finding in #18: " + links);
    }

    @Test
    @DisplayName("THE NEGATIVE CONTROL: a first-time success stays a first-time success")
    void glorpIsNotChainedToAnything() {
        var links = WishLineage.reconstruct(theRealRun(), WINDOW);
        assertEquals(1, WishLineage.attemptsFor("glorp", links),
                "glorp was accepted first time. A detector that chains it has swapped an "
                        + "optimistic bias for a pessimistic one, which is not an improvement: "
                        + links);
        assertTrue(links.stream().noneMatch(l -> l.laterId().equals("glorp")), links.toString());
    }

    @Test
    @DisplayName("every link says WHY, so a reconstruction can be argued with")
    void everyLinkCarriesItsEvidence() {
        for (var l : WishLineage.reconstruct(theRealRun(), WINDOW)) {
            assertTrue(l.evidence() != null && l.because() != null && !l.because().isBlank(),
                    "a link with no stated reason is a guess presented as a fact: " + l);
        }
    }

    @Test
    @DisplayName("naming the prior artifact needs TWO shared words, not one")
    void oneSharedWordIsACoincidenceNotAReference() {
        var dragonA = new WishLineage.Entry("fire_dragon", "species", "make me a fire dragon", 0);
        var dragonB = new WishLineage.Entry("ice_dragon", "species", "make me an ice dragon", MIN);
        assertFalse(WishLineage.namesPrior(dragonA, dragonB),
                "two unrelated dragon wishes share exactly one significant word. Chaining on one "
                        + "word would make every creature wish a refinement of the last one.");
    }

    @Test
    @DisplayName("a complaint long afterwards is not a refinement of whatever preceded it")
    void backReferenceAloneNeedsToBeCloseInTime() {
        var earlier = new WishLineage.Entry("a_train", "train", "build me a long train", 0);
        var muchLater = new WishLineage.Entry("b_castle", "buildings",
                "that is terrible, get rid of it", 5 * 60 * MIN);
        assertTrue(WishLineage.reconstruct(List.of(earlier, muchLater), WINDOW).isEmpty(),
                "five hours later, with no shared words, is a new session — not a refinement");
    }

    @Test
    @DisplayName("the same id is left alone — the publisher already chains those")
    void anAlreadyChainedIdIsNotRelinked() {
        var one = new WishLineage.Entry("spikor", "species", "make spikor", 0);
        var two = new WishLineage.Entry("spikor", "species", "you just made spikor, make him bigger",
                MIN);
        assertTrue(WishLineage.reconstruct(List.of(one, two), WINDOW).isEmpty(),
                "a reused id is already one chain; relinking it would double-count the attempt");
    }

    @Test
    @DisplayName("stop-words carry no identity")
    void commonWordsAreNotEvidence() {
        var words = WishLineage.significantWords("You just made the new mob and it has more");
        assertTrue(words.isEmpty(),
                "every one of those appears in most wishes; treating them as identity would chain "
                        + "everything to everything: " + words);
    }

    @Test
    @DisplayName("an artifact with no parent reports one attempt, not zero")
    void aRootIsOneAttempt() {
        assertEquals(1, WishLineage.attemptsFor("anything", List.of()));
    }

    @Test
    @DisplayName("a cycle in the links cannot hang the walk")
    void aCycleTerminates() {
        var cyclic = List.of(
                new WishLineage.Link("a", "b", WishLineage.Evidence.NAMES_PRIOR, "x"),
                new WishLineage.Link("b", "a", WishLineage.Evidence.NAMES_PRIOR, "x"));
        assertTrue(WishLineage.attemptsFor("a", cyclic) <= 2,
                "reconstruction is heuristic, so it can produce a cycle; the walk must still stop");
    }
}
