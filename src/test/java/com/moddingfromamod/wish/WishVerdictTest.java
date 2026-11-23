package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Gate A for how a wish ends.
 *
 * <p>The four verdicts differ in what they DO, not merely in what they say, and each of those
 * behaviours is a promise to a child: an improvement he can press, a way to say no, a mess cleaned
 * up after itself. Wiring one to the wrong verdict would be invisible in review and obvious to Steve.
 */
class WishVerdictTest {

    @Test
    void onlyOutrightFailureCountsAsNotGranted() {
        assertTrue(WishVerdict.DONE.granted());
        assertTrue(WishVerdict.COULD_BE_BETTER.granted(), "imperfect is still granted");
        assertTrue(WishVerdict.ROUGH.granted(), "rough-but-working is still granted");
        assertFalse(WishVerdict.FAILED.granted());
    }

    @Test
    void onlyCouldBeBetterOffersAFollowUp() {
        // Seeding the child's wish box is a strong move — it puts words in his mouth. It belongs
        // only where Moddy genuinely has an improvement in mind.
        assertTrue(WishVerdict.COULD_BE_BETTER.seedsFollowUp());
        assertFalse(WishVerdict.DONE.seedsFollowUp(), "don't nag about something that's good");
        assertFalse(WishVerdict.ROUGH.seedsFollowUp(), "rough offers a reject, not a retry");
        assertFalse(WishVerdict.FAILED.seedsFollowUp());
    }

    @Test
    void onlyRoughTellsTheChildHowToRejectIt() {
        assertTrue(WishVerdict.ROUGH.offersReject());
        assertFalse(WishVerdict.DONE.offersReject(),
                "telling a child how to delete something he likes is discouraging");
        assertFalse(WishVerdict.COULD_BE_BETTER.offersReject());
        assertFalse(WishVerdict.FAILED.offersReject(), "failed already cleaned itself up");
    }

    @Test
    void onlyFailureUndoesItself() {
        // Undoing on merely-imperfect would delete work the child was happy with.
        assertTrue(WishVerdict.FAILED.undoesItself());
        assertFalse(WishVerdict.DONE.undoesItself());
        assertFalse(WishVerdict.COULD_BE_BETTER.undoesItself());
        assertFalse(WishVerdict.ROUGH.undoesItself());
    }

    @Test
    void anUnknownOrMissingVerdictStillEndsTheWish() {
        // The failure being fixed is a wish that NEVER ENDS. A mislabelled ending is a small
        // problem; refusing to end is the eleven-minute ticker all over again.
        assertEquals(WishVerdict.DONE, WishVerdict.parse(null));
        assertEquals(WishVerdict.DONE, WishVerdict.parse(""));
        assertEquals(WishVerdict.DONE, WishVerdict.parse("banana"));
    }

    @Test
    void theWordsAnAgentIsLikelyToReachForAllLand() {
        assertEquals(WishVerdict.DONE, WishVerdict.parse("done"));
        assertEquals(WishVerdict.DONE, WishVerdict.parse("  GOOD  "));
        assertEquals(WishVerdict.COULD_BE_BETTER, WishVerdict.parse("could_be_better"));
        assertEquals(WishVerdict.COULD_BE_BETTER, WishVerdict.parse("better"));
        assertEquals(WishVerdict.ROUGH, WishVerdict.parse("rough"));
        assertEquals(WishVerdict.ROUGH, WishVerdict.parse("BAD"));
        assertEquals(WishVerdict.FAILED, WishVerdict.parse("failed"));
        assertEquals(WishVerdict.FAILED, WishVerdict.parse("fail"));
    }

    @Test
    void everyVerdictIsAdvertisedToTheAgent() {
        // A verdict the tool schema never mentions is one the agent will never pick.
        String choices = WishVerdict.choices();
        for (WishVerdict v : WishVerdict.values()) {
            assertTrue(choices.contains(v.name().toLowerCase(java.util.Locale.ROOT)),
                    v + " missing from the advertised choices: " + choices);
        }
    }
}
