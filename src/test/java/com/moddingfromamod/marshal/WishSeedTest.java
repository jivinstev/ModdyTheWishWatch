package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Seeding the box with words that cannot be mis-matched.
 *
 * <p>Every case here is from real play: "make my crushing plant" failing to hit the recipe that
 * built it, and one Spikor owning four different recipes.
 */
class WishSeedTest {

    private static WishSeed.Template t(String id, String... terms) {
        return new WishSeed.Template(id, List.of(terms));
    }

    @Test
    void theRecipeThatBuiltItIsFoundByItsGuaranteedTerms() {
        var found = WishSeed.bestMatch(List.of("crushing plant"),
                List.of(t("crushing_plant", "crushing", "plant")));
        assertEquals("crushing_plant", found.id());
    }

    @Test
    void everyTermMustMatchNotJustOne() {
        // Sharing one word would make every recipe mentioning "spikor" a candidate for anything
        // Spikor-ish he owns — which is how a re-wish quietly produces the wrong thing.
        assertNull(WishSeed.bestMatch(List.of("crushing plant"),
                List.of(t("crushing_wheels", "crushing", "wheels"))));
    }

    @Test
    void theMostSpecificRecipeWinsWhenOneThingHasSeveral() {
        // His real Spikor has spikor_fire_breath, spikor_boss and spikor_king.
        var names = List.of("SPIKOR, King of the Kroaks", "king of the kroaks", "the kroak king");
        var found = WishSeed.bestMatch(names, List.of(
                t("spikor_king", "spikor"),
                t("spikor_fire_breath", "spikor", "kroak", "king")));
        assertEquals("spikor_fire_breath", found.id(), "the recipe that had to satisfy the most");
    }

    @Test
    void aTieGoesToTheMostRecentlyLearned() {
        // Same specificity, so the tiebreak is which version of the thing he last cared about.
        var found = WishSeed.bestMatch(List.of("spikor"), List.of(
                t("spikor_old", "spikor"),
                t("spikor_new", "spikor")));
        assertEquals("spikor_new", found.id());
    }

    @Test
    void aRecipeWithNoGuaranteedTermsIsNeverOffered() {
        // No `all` array means no guarantee, and the whole point is that this cannot miss.
        assertNull(WishSeed.bestMatch(List.of("spikor"), List.of(t("mystery"))));
    }

    @Test
    void punctuationAndCaseInHisNamesDoNotStopAMatch() {
        var found = WishSeed.bestMatch(List.of("SPIKOR, King of the Kroaks!"),
                List.of(t("spikor_king", "spikor", "king")));
        assertEquals("spikor_king", found.id());
    }

    @Test
    void theSeededSentenceIsAnInvitationRatherThanAnInstruction() {
        // "make another crushing plant" is complete: press enter and you get a duplicate nobody
        // asked for. An unfinished sentence asks him what he wants it to DO.
        assertEquals("I want my crushing plant to ", WishSeed.sentence("crushing plant"));
        assertTrue(WishSeed.sentence("x").endsWith(" "), "his first word must not run into the name");
    }

    @Test
    void anUnfinishedSeedIsRecognisedAsSayingNothing() {
        // Not blank, so an is-it-empty check waves it straight through and wakes an agent to grant
        // a sentence with no verb in it.
        assertTrue(WishSeed.isUnfinished("I want my crushing plant to "));
        assertTrue(WishSeed.isUnfinished("i want my spikor to"));
        assertFalse(WishSeed.isUnfinished("I want my crushing plant to go faster"));
    }

    @Test
    void anEmptyNameStillProducesSomethingTypeable() {
        assertEquals("I want my ", WishSeed.sentence(null));
    }
}
