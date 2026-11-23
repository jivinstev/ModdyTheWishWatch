package com.moddingfromamod.wish;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Did the thing the child asked for happen?</b>
 *
 * <p>The wish these came from, verbatim: <i>"now make a machine over here that GRINDS UP the trees,
 * and MAGICALLY transforms them into BUTT CHEEK WATER! It needs to bottle up the BUTT CHEEK WATER
 * too so that I can drink it later!"</i>
 *
 * <p>It was finished five times, and each time the child was told it worked. Nothing ground
 * anything. The agent was not lying — it verified what was easy to verify (a chest count, a shaft
 * rpm) and then described what it had intended.
 */
class WishClaimsTest {

    private static final List<String> GRINDER = List.of(
            "it grinds up the trees",
            "it turns them into butt cheek water",
            "it puts the water in bottles");

    @BeforeEach
    void reset() {
        WishClaims.clear();
    }

    /**
     * <b>The bound is his sentence, not the machine.</b>
     *
     * <p>The design decision this whole class turns on. "What should we verify about a machine?" has
     * no end — rpm, stress, every link, every port — and any list built that way grows until nobody
     * finishes it. "What did the child ask for?" is naturally small: his grinder wish contains
     * exactly three things.
     */
    @Test
    void aWishesClaimsAreCountedInThingsAChildSaidNotThingsAMachineHas() {
        assertEquals(3, WishClaims.state(GRINDER));
        assertEquals(3, WishClaims.MAX,
                "the cap is meant to be what a child's sentence holds; raising it turns this into "
                        + "the artifact's checklist wearing the wish's clothes, which is the thing "
                        + "that makes a verification pass open-ended and therefore skipped");
    }

    /** Over the cap, extras are dropped and SAID so — never silently truncated. */
    @Test
    void tooManyClaimsAreDroppedVisibly() {
        int kept = WishClaims.state(List.of("one", "two", "three", "four", "five"));
        assertEquals(WishClaims.MAX, kept,
                "the caller is told how many were kept, so 'I claimed five' and 'three are tracked' "
                        + "cannot be confused");
    }

    /**
     * <b>Finishing with a claim nobody looked at is refused, and the refusal NAMES it.</b>
     *
     * <p>"Verify your work" is advice. "You said it would bottle the water and nobody has looked" is
     * an instruction.
     */
    @Test
    void anUncheckedClaimIsNamedNotJustCounted() {
        WishClaims.state(GRINDER);
        WishClaims.answer("it grinds up the trees", WishClaims.Verdict.WORKS, "fed a log, saw it go");
        String nag = WishClaims.nagAboutUnanswered();
        assertNotNull(nag, "two claims are unchecked and finishing was not questioned");
        assertTrue(nag.contains("butt cheek water"),
                "the nag does not name the unchecked claim, so the agent has to guess which: " + nag);
        assertTrue(nag.contains("bottles"), "the third claim is not named either: " + nag);
        assertFalse(nag.contains("grinds up the trees"),
                "a claim that WAS checked is being nagged about, which teaches the agent the gate "
                        + "cries wolf");
    }

    /** Answer them all and the gate opens — or it is just "always refuse". */
    @Test
    void answeringEveryClaimClearsTheGate() {
        WishClaims.state(GRINDER);
        for (String c : GRINDER) {
            WishClaims.answer(c, WishClaims.Verdict.WORKS, "saw it");
        }
        assertNull(WishClaims.nagAboutUnanswered(),
                "every claim was answered and finishing is still blocked, which would make a wish "
                        + "unfinishable");
        assertTrue(WishClaims.allWork());
    }

    /**
     * <b>BROKEN is an answer.</b>
     *
     * <p>The gate is about having LOOKED, not about succeeding. If it demanded success the only
     * winning move would be to claim less, and a wish that cannot end is worse than one that ends
     * imperfectly.
     */
    @Test
    void sayingSomethingIsBrokenCountsAsHavingChecked() {
        WishClaims.state(GRINDER);
        for (String c : GRINDER) {
            WishClaims.answer(c, WishClaims.Verdict.BROKEN, "dropped a log, nothing happened");
        }
        assertNull(WishClaims.nagAboutUnanswered(),
                "admitting everything is broken did not satisfy the gate, so honesty is punished "
                        + "and the agent's cheapest route is to claim nothing");
        assertFalse(WishClaims.allWork());
    }

    /** He does not have to quote his own claim back character-perfect to answer it. */
    @Test
    void aClaimCanBeAnsweredWithoutQuotingItExactly() {
        WishClaims.state(GRINDER);
        assertTrue(WishClaims.answer("It GRINDS UP the trees!", WishClaims.Verdict.WORKS, "saw it"),
                "matching is so strict that punctuation and case defeat it — the agent would be "
                        + "unable to answer its own claims");
        assertFalse(WishClaims.answer("it makes toast", WishClaims.Verdict.WORKS, ""),
                "answering a claim nobody made was accepted, so a verdict can be recorded against "
                        + "nothing and the real claim stays open");
    }

    /**
     * <b>What the child hears: nothing, unless something is broken.</b>
     *
     * <p>He finds a broken bit anyway, in about ten seconds — so that one is told, in one sentence
     * in Moddy's voice, never a list and never a tick. A working or unchecked part says nothing.
     */
    @Test
    void theChildHearsOnlyWhatIsBrokenInOneSentence() {
        WishClaims.state(GRINDER);
        WishClaims.answer("it grinds up the trees", WishClaims.Verdict.WORKS, "saw it");
        WishClaims.answer("it turns them into butt cheek water", WishClaims.Verdict.BROKEN, "no");
        String said = WishClaims.brokenSentence();
        assertNotNull(said, "a broken part must still be told — the honest half");
        assertTrue(said.contains("butt cheek water"), said);
        assertFalse(said.contains("grinds up the trees"), "a working part is not news: " + said);
        assertFalse(said.contains("bottles"), "an unchecked part claims a verdict it does not have: " + said);
        assertFalse(said.contains("✔") || said.contains("✘") || said.contains("I tried")
                || said.contains("\n"), "one sentence, no checklist: " + said);

        WishClaims.answer("it turns them into butt cheek water", WishClaims.Verdict.WORKS, "yes");
        assertNull(WishClaims.brokenSentence(), "everything passing says nothing extra");
    }

    @Test
    void severalBrokenBitsAreStillOneSentence() {
        String said = WishClaims.brokenSentence(List.of("the door opens", "the bell rings."));
        assertEquals("🔧 Hmm — \"the door opens\" and \"the bell rings\" aren't quite working yet. "
                + "Wish for them again and I'll sort them!", said);
        assertNull(WishClaims.brokenSentence(List.of()));
    }

    /** A claim about yesterday's wish must never hold up today's. */
    @Test
    void aNewWishStartsWithNothingOutstanding() {
        WishClaims.state(GRINDER);
        assertNotNull(WishClaims.nagAboutUnanswered());
        WishClaims.clear();
        assertNull(WishClaims.nagAboutUnanswered(),
                "last wish's unchecked claims are still blocking, so the guard would refuse a wish "
                        + "about something the child is no longer asking for");
        assertTrue(WishClaims.all().isEmpty());
    }

    /** A wish that claimed nothing is not blocked — the gate must not punish small wishes. */
    @Test
    void aWishWithNoClaimsIsNotHeldUp() {
        assertNull(WishClaims.nagAboutUnanswered());
        assertTrue(WishClaims.allWork(), "a wish with no claims reads as failing");
    }
}
