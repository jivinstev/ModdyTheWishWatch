package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * §1.5 misspellings and near-misses, and §1.6 conflicting keywords.
 *
 * <p>The headline is not the misspellings — those are easy. It is the <b>near-miss collision</b>:
 * with both "Croker" and "Croker Jr" present, a fuzzy match must not silently pick one. The margin
 * rule applies to string distance exactly as it does to proximity, and a coin flip in front of a
 * child is the thing the whole guess-and-narrate policy is trying to avoid.
 */
class AliasIndexTest {

    private static AliasIndex crokerWorld() {
        return new AliasIndex()
                .add(new AliasIndex.Artifact("croker", "species", "Croker",
                        List.of("king kroak", "the kroak king", "king of the kroaks")))
                .add(new AliasIndex.Artifact("dinko", "species", "Dinko", List.of()))
                .add(new AliasIndex.Artifact("floating_castle", "build", "floating castle",
                        List.of("sky castle")));
    }

    // ---- rung 0 hits ------------------------------------------------------------------------

    @Test
    void theTextNamingItIsTheCheapestPossibleResolution() {
        var r = crokerWorld().resolve("make croker bigger");
        assertTrue(r.resolved());
        assertEquals("croker", r.artifact().id());
        assertEquals("exact", r.how());
    }

    @Test
    void anAliasResolvesWithNoModelCallAtAll() {
        // The case the whole alias idea exists for: no string match on "croker" anywhere.
        var r = crokerWorld().resolve("Now give King Kroak fire breath!");
        assertTrue(r.resolved());
        assertEquals("croker", r.artifact().id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Crkoer", "Corker", "croker", "CROKER", "Crokers", "crokr"})
    void heTypesItHoweverHeLikes(String typed) {
        var r = crokerWorld().resolve("make " + typed + " bigger");
        assertTrue(r.resolved(), typed + " did not resolve (" + r.verdict() + ")");
        assertEquals("croker", r.artifact().id(), typed + " resolved to the wrong thing");
    }

    @Test
    void punctuationAndPossessivesDoNotMatter() {
        assertEquals("croker", crokerWorld().resolve("make King Kroak's shell redder")
                .artifact().id());
    }

    @Test
    void aMultiWordNameIsMatchedWithoutItsSpaces() {
        assertEquals("floating_castle",
                crokerWorld().resolve("make the floatingcastle bigger").artifact().id());
    }

    // ---- the rule that matters: never silently pick ------------------------------------------

    @Test
    void aNearMissEquallyCloseToTwoNamesAsksRatherThanGuessing() {
        // A genuine tie: one edit from each. This is the margin rule applied to string distance
        // exactly as it is applied to proximity — nearest-wins without a margin is the heuristic
        // that produced the original wrong-mob bug.
        AliasIndex world = new AliasIndex()
                .add(new AliasIndex.Artifact("kroak", "species", "Kroak", List.of()))
                .add(new AliasIndex.Artifact("kroag", "species", "Kroag", List.of()));

        var r = world.resolve("make kroax bigger");
        assertEquals(AliasIndex.Verdict.AMBIGUOUS, r.verdict(),
                "equally close to both — picking one is a coin flip in front of a child");
        assertTrue(r.candidates().size() >= 2);
    }

    @Test
    void aClearWinnerIsStillPickedWhenARivalIsMerelyNearby() {
        AliasIndex world = new AliasIndex()
                .add(new AliasIndex.Artifact("croker", "species", "Croker", List.of()))
                .add(new AliasIndex.Artifact("croker_jr", "species", "Croker Jr", List.of()));

        var r = world.resolve("make crokr bigger");
        assertTrue(r.resolved(), "one edit from Croker and three from Croker Jr is not a tie");
        assertEquals("croker", r.artifact().id());
    }

    @Test
    void anExactHitStillWinsWhenAnotherNameIsMerelySimilar() {
        AliasIndex world = new AliasIndex()
                .add(new AliasIndex.Artifact("croker", "species", "Croker", List.of()))
                .add(new AliasIndex.Artifact("croker_jr", "species", "Croker Jr", List.of()));

        var r = world.resolve("make croker jr bigger");
        assertTrue(r.resolved(), "he typed one of them exactly");
        assertEquals("croker_jr", r.artifact().id(),
                "the longer exact name must win; being a superstring of another is not ambiguity");
    }

    @Test
    void twoThingsNamedInOneWishIsReportedNotGuessed() {
        var r = crokerWorld().resolve("make croker fight dinko");
        assertEquals(AliasIndex.Verdict.AMBIGUOUS, r.verdict(),
                "this is a two-artifact wish, and rung 0's job is to say so rather than pick one");
        assertEquals(2, r.candidates().size());
    }

    @Test
    void namingNothingResolvesToNothing() {
        assertEquals(AliasIndex.Verdict.NONE, crokerWorld().resolve("make it bigger").verdict(),
                "this is what rungs 1-5 are for; rung 0 must not invent an answer");
    }

    @Test
    void aWordNowhereNearANameIsLeftAlone() {
        assertEquals(AliasIndex.Verdict.NONE,
                crokerWorld().resolve("make the helicopter bigger").verdict());
    }

    /**
     * A judgement worth recording rather than hiding: "broker" is one edit from "croker" and DOES
     * resolve. That is the cost asymmetry the whole design turns on — the guess is narrated by name,
     * so he sees "Making <b>Croker</b> bigger!" within a second and stopping is one click, whereas
     * refusing to guess costs a question every time he mistypes. Tightening the budget to exclude
     * this would also exclude "Crkoer", which is the case that matters.
     */
    @Test
    void aRealWordOneEditFromANameIsAcceptedAsThatNameAndNarrated() {
        var r = crokerWorld().resolve("make the broker bigger");
        assertTrue(r.resolved());
        assertEquals("croker", r.artifact().id());
        assertEquals("fuzzy", r.how(),
                "…and it is flagged as a GUESS, so the pick is said out loud rather than silently made");
    }

    // ---- §1.6 alias hygiene -------------------------------------------------------------------

    @Test
    void anAliasCollisionIsResolvedAtWriteTimeNotReadTime() {
        AliasIndex existing = new AliasIndex()
                .add(new AliasIndex.Artifact("fire_dragon", "species", "fire dragon",
                        List.of("the fire one")));

        List<String> free = AliasBackfill.withoutCollisions(
                List.of("the fire one", "the burny castle"), existing);

        assertEquals(List.of("the burny castle"), free,
                "a collision discovered when he SAYS the word is a coin flip; discovered when the "
                        + "second artifact claims it, it is a decision nobody is waiting on");
    }

    @Test
    void aWordSharedByTwoThingsAsks() {
        AliasIndex world = new AliasIndex()
                .add(new AliasIndex.Artifact("fire_dragon", "species", "fire dragon", List.of()))
                .add(new AliasIndex.Artifact("fire_castle", "build", "fire castle", List.of()));
        assertEquals(AliasIndex.Verdict.NONE, world.resolve("make the fire one fly").verdict(),
                "'fire' names neither of them on its own");
    }

    // ---- kid typing ----------------------------------------------------------------------------

    @Test
    void theNormaliserMatchesHowAChildActuallyTypes() {
        assertEquals("big bun", KidTyping.normalise("Big  Bun!"));
        assertEquals("big bun", KidTyping.normalise("big_bun"));
        assertEquals("bigbun", KidTyping.squash("Big Bun"));
        assertEquals("king kroaks", KidTyping.normalise("King Kroak's"));
    }

    @Test
    void aShortWordGetsNoFuzzBudgetAtAll() {
        assertEquals(0, KidTyping.budgetFor("cat"), "'cat' and 'bat' are different animals");
        assertTrue(KidTyping.budgetFor("croker") >= 1);
        assertTrue(KidTyping.budgetFor("tyrannosaurus") <= 3, "a long name is never a wildcard");
    }

    @Test
    void theDistanceGivesUpEarlyRatherThanScoringAWholeSentence() {
        assertTrue(KidTyping.distance("croker", "a completely different sentence entirely", 2) > 2);
        assertEquals(1, KidTyping.distance("croker", "crokr", 2));
    }

    @Test
    void nearlySameIsSymmetricEnoughToTrust() {
        assertTrue(KidTyping.nearlySame("Crkoer", "Croker"));
        assertTrue(KidTyping.nearlySame("bigbun", "Big Bun"));
        assertFalse(KidTyping.nearlySame("dinko", "croker"));
    }

    @Test
    void wholeWordMatchingStopsCodMatchingInsideCocoa() {
        assertTrue(AliasIndex.containsWholePhrase("i want a cod", "cod"));
        assertFalse(AliasIndex.containsWholePhrase("i want cocoa beans", "cod"));
    }

    /**
     * The name a real agent really gave a real boss, on 2026-08-13.
     *
     * <p>It shipped {@code "§6CROKER, King of the Kroaks"} — display name with a gold colour code on
     * the front. A colour code is TWO characters, and normalisation keeps letters and digits, so the
     * section sign fell away on its own and the DIGIT survived glued to the name: his boss was filed
     * under {@code "6croker king of the kroaks"}.
     *
     * <p>It did not show up as a failure because the artifact's <i>id</i> happened to be a clean
     * "croker" and carried the match on its own. That is luck, not a mechanism — an id is generated,
     * not spoken, and the moment one is less tidy than the display name the child loses the ability
     * to name his own creature.
     *
     * <p>{@link com.moddingfromamod.entity.WishMobTags} learned this rule first and kept its own
     * copy of it; that copy is why the ladder went on matching "6croker" regardless.
     */
    @Test
    void aColourCodeInTheNameDoesNotBecomePartOfTheName() {
        assertEquals("croker king of the kroaks",
                KidTyping.normalise("\u00a76CROKER, King of the Kroaks"),
                "the colour code's digit must not survive as the first letter of his boss's name");
        assertEquals("croker king of the kroaks",
                KidTyping.normalise("\u00a7l\u00a76CROKER, King of the Kroaks"),
                "and two codes must not survive either");
    }

    @Test
    void heCanSayTheWholeNameOfABossWhoseNameCarriesAColourCode() {
        // The id deliberately is NOT something he could say, so the match has to come from the NAME.
        // He is quoting the boss back in full, which is the case the colour code broke outright:
        // "6croker king of the kroaks" is not a phrase any sentence of his contains.
        //
        // Framed on the whole name rather than on "croker" alone on purpose. A bare first word is
        // NOT this class's job — an artifact answers to its aliases, and AliasBackfill mines those
        // from the child's own wish text, where "CROKER" appears on its own. Asserting it here would
        // be testing the backfill through the wrong door.
        var world = new AliasIndex().add(new AliasIndex.Artifact(
                "wishmob_7f3a91", "species", "\u00a76CROKER, King of the Kroaks", List.of()));
        assertTrue(world.resolve("make CROKER, King of the Kroaks breathe ice").resolved(),
                "he said the name he was told; a formatting code is not part of what he heard");
    }
}
