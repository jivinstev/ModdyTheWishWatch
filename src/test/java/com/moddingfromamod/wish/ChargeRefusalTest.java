package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/** What the core does with a world's charge's answer ({@link ChargeRefusal}), and what an older charge is told. */
class ChargeRefusalTest {

    private static final UUID HIM = UUID.randomUUID();
    private static final Predicate<String> PASSES = s -> true;
    private static final Predicate<String> FLAGS = s -> false;

    private static WishAsk typed() {
        return WishAsk.of(HIM, WishSource.TYPED, WishAsk.Magic.APPRENTICE).withText("a red dragon");
    }

    @Test
    void noAnswerLetsTheWishGoOn() {
        assertEquals(ChargeRefusal.ALLOWED, ChargeRefusal.decide(typed(), Optional.empty(), ChargeRefusal.Where.WISH, PASSES));
        assertEquals(ChargeRefusal.ALLOWED, ChargeRefusal.decide(typed(), null, ChargeRefusal.Where.MAKE, PASSES),
                "a charge that answered null is read as go on");
    }

    @Test
    void theChargesWordsAreTheOneLineHeReads() {
        ChargeRefusal r = ChargeRefusal.decide(typed(), Optional.of("  Only kept things can be wished in a round!  "),
                ChargeRefusal.Where.WISH, PASSES);
        assertTrue(r.refused());
        assertEquals("Only kept things can be wished in a round!", r.line());
        assertEquals("Not yet!", ChargeRefusal.decide(typed(), Optional.of("Not yet!"), ChargeRefusal.Where.MAKE, PASSES)
                .line(), "a Builder make reads the charge's words, not the wish bank's");
    }

    @Test
    void wordsTheSweepRefusesAreReplacedByTheFixedLine() {
        ChargeRefusal r = ChargeRefusal.decide(typed(), Optional.of("bad words"), ChargeRefusal.Where.WISH, FLAGS);
        assertTrue(r.refused(), "the refusal stands");
        assertEquals(ChargeRefusal.FALLBACK, r.line());
        ChargeRefusal threw = ChargeRefusal.decide(typed(), Optional.of("words"), ChargeRefusal.Where.MAKE, s -> {
            throw new IllegalStateException("no lexicon");
        });
        assertEquals(ChargeRefusal.FALLBACK, threw.line(), "a line that could not be swept is not said");
    }

    @Test
    void anOlderChargesRefusalSaysWhatItAlwaysSaid() {
        ChargeRefusal wish = ChargeRefusal.decide(typed(), Optional.of(""), ChargeRefusal.Where.WISH, PASSES);
        assertTrue(wish.refused());
        assertEquals("", wish.line(), "the charge said its own words: nothing more");
        ChargeRefusal make = ChargeRefusal.decide(typed(), Optional.of(""), ChargeRefusal.Where.MAKE, PASSES);
        assertTrue(make.refused());
        assertEquals(ChargeRefusal.BANK_EMPTY, make.line(), "a Builder make keeps its default line");
        assertEquals("Your wish bank is empty. It fills up again soon.", ChargeRefusal.BANK_EMPTY);
    }

    @Test
    void aChildInDistressIsNeverRefused() {
        WishAsk distress = typed().withDistress(true);
        assertFalse(ChargeRefusal.decide(distress, Optional.of("No."), ChargeRefusal.Where.WISH, PASSES).refused());
        assertFalse(ChargeRefusal.decide(distress, Optional.of(""), ChargeRefusal.Where.MAKE, PASSES).refused());
    }

    @Test
    void anOlderChargeIsPassedTheRecipeFlagItAlwaysWas() {
        // named by what started it: a button, an again, a hotbar fire, /moddy try, a Builder or Apprentice make
        assertTrue(WishAsk.replay(HIM, "fire_sword").namedRecipe(), "a hotbar fire or a recipe button");
        assertTrue(WishAsk.of(HIM, WishSource.COMMAND, WishAsk.Magic.REPLAY).namedRecipe(), "/moddy try");
        assertTrue(WishAsk.of(HIM, WishSource.BUILDER, WishAsk.Magic.BUILDER).namedRecipe(), "a Builder make");
        assertTrue(WishAsk.of(HIM, WishSource.BUILDER, WishAsk.Magic.APPRENTICE).namedRecipe(), "an Apprentice make");
        // typed words, whoever answers them
        assertFalse(WishAsk.of(HIM, WishSource.TYPED, WishAsk.Magic.APPRENTICE).namedRecipe());
        assertFalse(WishAsk.of(HIM, WishSource.TYPED, WishAsk.Magic.MAGICIAN).namedRecipe());
        assertFalse(WishAsk.of(HIM, WishSource.COMMAND, WishAsk.Magic.MAGICIAN).namedRecipe(), "/wish");
        assertFalse(WishAsk.of(HIM, WishSource.TYPED, WishAsk.Magic.REPLAY).withRecipe("fire_sword").namedRecipe(),
                "typed words a cached recipe matched");
        assertFalse(WishAsk.of(HIM, WishSource.COMMAND, WishAsk.Magic.REPLAY).withRecipe("fire_sword").namedRecipe(),
                "/wish words a cached recipe matched");
    }

    @Test
    void anAskCarriesItsWordsAndDistress() {
        WishAsk a = typed().withDistress(true).withRecipe("x").withMagic(WishAsk.Magic.MAGICIAN);
        assertEquals("a red dragon", a.text());
        assertTrue(a.distress());
        assertEquals("", WishAsk.of(HIM, WishSource.TYPED, WishAsk.Magic.MAGICIAN).withText(null).text());
        assertFalse(WishAsk.of(HIM, WishSource.TYPED, WishAsk.Magic.MAGICIAN).distress(), "no distress unless said");
    }
}
