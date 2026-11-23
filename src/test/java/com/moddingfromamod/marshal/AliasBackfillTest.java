package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Recovering aliases from what he already said — and, mostly, from NOT inventing ones he didn't.
 *
 * <p>The whole file is shaped by one bug the offline replay found on its first run. See
 * {@link #aPlacePhraseIsNeverAName()}.
 */
class AliasBackfillTest {

    /** A Spikor manifest in the shape of the real one (paraphrased: a child's own words stay out of the public tree). */
    private static final String SPIKOR_INTENT =
            "can you build a HUGE castle right here? A Spikor castle! And then make "
            + "a new boss mob Spikor here, he's the king of the kroaks!";

    private static final List<String> SPIKOR_REFINEMENTS = List.of(
            "Bring me SPIKOR king of the kroaks! And his fire breath has to come out of his MOUTH!",
            "Create SPIKOR KING of the KROAKS! And let him breathe FIRE!",
            "Bring Spikor back, and give him a real chance against Dinko! Dinko beat him!",
            "Make SPIKOR king of the Kroaks! He can breathe FIRE!");

    @Test
    void spikorsOwnHistoryYieldsTheNameHeAnswersTo() {
        List<String> aliases = AliasBackfill.from("spikor", SPIKOR_INTENT, SPIKOR_REFINEMENTS);
        assertTrue(aliases.stream().anyMatch(a -> a.contains("king of the kroaks")),
                "the alias list §'record what a thing answers to' wants is already on disk, in his "
                        + "own words: " + aliases);
    }

    @Test
    void thoseAliasesActuallyResolveTheWishThatMotivatedThem() {
        AliasIndex world = new AliasIndex().add(new AliasIndex.Artifact("spikor", "species",
                "spikor", AliasBackfill.from("spikor", SPIKOR_INTENT, SPIKOR_REFINEMENTS)));
        var r = world.resolve("Now give King Kroak fire breath!");
        assertTrue(r.resolved(), "backfilled from real refinements, this must resolve: " + r.verdict());
        assertTrue(r.artifact().id().equals("spikor"));
    }

    /**
     * <b>The bug the offline replay found, pinned.</b>
     *
     * <p>"Please build a giant airplane <b>in the sky</b>" produced the alias
     * <i>"in the sky"</i>, which then confidently resolved <i>"there's a cool castle in the sky, can
     * you add a lighthouse on top of it?"</i> to the aeroplane. A false positive at rung 0 is worse
     * than a miss: a miss costs one rung, this costs the child watching the wrong thing change while
     * being told the right name.
     */
    @Test
    void aPlacePhraseIsNeverAName() {
        List<String> aliases =
                AliasBackfill.from("giant_airplane", "Please build a giant airplane in the sky!",
                        List.of());
        assertFalse(aliases.contains("in the sky"), "aliases were " + aliases);
        for (String a : aliases) {
            assertFalse(a.startsWith("in ") || a.startsWith("on ") || a.startsWith("at "),
                    "'" + a + "' describes where it is, not what he calls it");
        }
    }

    @Test
    void andTheCastleWishNoLongerResolvesToTheAeroplane() {
        AliasIndex world = new AliasIndex()
                .add(new AliasIndex.Artifact("giant_airplane", "build", "giant airplane",
                        AliasBackfill.from("giant_airplane",
                                "Please build a giant airplane in the sky!", List.of())));
        var r = world.resolve(
                "look, a cool castle in the sky here, can you put a lighthouse on top of it?");
        assertFalse(r.resolved() && "giant_airplane".equals(r.artifact().id()),
                "this is the exact false positive the replay caught");
    }

    @Test
    void shoutingIsASurprisinglyGoodNameSignalInThisCorpus() {
        // A seven-year-old capitalises the thing he cares about, and it needs no model at all.
        assertTrue(AliasBackfill.shoutedPhrases("Bring me SPIKOR king of the kroaks!")
                .contains("spikor"));
        assertTrue(AliasBackfill.shoutedPhrases("Create SPIKOR KING of the KROAKS!")
                .contains("spikor king"));
    }

    @Test
    void aSentenceWithNoShoutingYieldsNoShoutedAliases() {
        assertTrue(AliasBackfill.shoutedPhrases("please make me a nice small house").isEmpty());
    }

    @Test
    void theAliasListIsBoundedSoAChattyHistoryCannotDiluteMatching() {
        List<String> lots = List.of(
                "AAA one", "BBB two", "CCC three", "DDD four", "EEE five", "FFF six",
                "GGG seven", "HHH eight", "III nine", "JJJ ten");
        assertTrue(AliasBackfill.from("thing", "make a THING", lots).size()
                <= AliasBackfill.MAX_ALIASES);
    }

    @Test
    void theIdItselfIsNotRepeatedAsAnAlias() {
        assertFalse(AliasBackfill.from("spikor", "make SPIKOR", List.of()).contains("spikor"),
                "the id is already a name; repeating it wastes one of the bounded alias slots");
    }
}
