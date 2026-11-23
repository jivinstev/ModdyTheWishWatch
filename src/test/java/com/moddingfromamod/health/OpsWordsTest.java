package com.moddingfromamod.health;


import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operations layer's child-facing strings, against {@code SafetyWordsTest}'s rules.
 *
 * <p>The rules are copied rather than shared, and that is the point of having two files: they are
 * the same RULES about two different vocabularies, and a single test over a merged list would
 * quietly encourage a merged list — which is the thing {@link OpsWords}' javadoc exists to prevent.
 */
class OpsWordsTest {

    /** Phrasings this project has already removed once, each with the reason it was removed. */
    private static final List<String> FORBIDDEN = List.of(
            "i don't know how",      // read as "never", while it was being built
            "i dont know how",
            "you shouldn't",         // blames him
            "you should not",
            "not allowed",
            "bad wish",
            "naughty",
            "inappropriate",
            "unsafe",
            "error",                 // "timed out" is an operator's word, not a child's
            "timed out",
            "timeout",
            "failed",
            "maintenance",           // vocabulary he does not have
            "server",
            "sorry, i can't");

    /** Something has to be happening NEXT in every one of them. */
    private static final List<String> FORWARD = List.of("wish", "try", "again");

    @Test
    void everyChildFacingStringInvitesTheNextWishAndBlamesNobody() {
        for (String s : OpsWords.all()) {
            String lower = s.toLowerCase(Locale.ROOT);
            for (String bad : FORBIDDEN) {
                assertFalse(lower.contains(bad),
                        "a child-facing ops string says \"" + bad + "\": " + s);
            }
            assertTrue(FORWARD.stream().anyMatch(lower::contains),
                    "this one leaves him with nowhere to go: " + s);
            assertTrue(s.length() <= 140,
                    "too long to read at a glance (" + s.length() + "): " + s);
            assertFalse(s.isBlank());
        }
    }

    /**
     * Both are "the world is busy for a moment", never "your wish was wrong" — so both name Moddy
     * and neither names the child. Asserted rather than trusted, because the register is the whole
     * difference between a deploy and a refusal as a seven-year-old hears them.
     */
    @Test
    void bothLinesAreAboutModdyRatherThanAboutHim() {
        for (String s : OpsWords.all()) {
            assertTrue(s.contains("Moddy"), "should say who is busy: " + s);
            String lower = s.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains(" you ") || lower.startsWith("you "),
                    "a maintenance message must not be about him: " + s);
        }
    }

    /**
     * The spend cap's line says WHEN, rounded UP (a child who comes back when told finds
     * Moddy awake), in relative time (the server does not know his time zone); every other cause
     * keeps the ordinary tired line, which the gates and the dequeue compare by equality.
     */
    @Test
    void theSpendCapSaysWhenNewWishesComeBackAndNothingElseChanges() {
        assertEquals("in 5 hours", OpsWords.inHowLong(4 * 3_600_000L + 60_000L));
        assertEquals("in 2 hours", OpsWords.inHowLong(61 * 60_000L));
        assertEquals("in an hour", OpsWords.inHowLong(50 * 60_000L));
        assertEquals("in 20 minutes", OpsWords.inHowLong(19 * 60_000L + 1));
        assertEquals("in a minute", OpsWords.inHowLong(5_000L));
        assertEquals("in a minute", OpsWords.inHowLong(-1L));
        long now = 1_000_000L;
        var cap = new ModdyTired.Tired(ModdyTired.Cause.SPEND_CAP, "$", now, now + 3 * 3_600_000L);
        assertEquals("😴 Moddy is tired — new wishes in 3 hours. Your buttons still work!",
                OpsWords.sayTired(cap, now));
        var quota = new ModdyTired.Tired(ModdyTired.Cause.OVER_QUOTA, "q", now);
        assertEquals(OpsWords.sayTired(), OpsWords.sayTired(quota, now));
        assertEquals(OpsWords.sayTired(), OpsWords.sayTired(null, now));
        assertEquals(OpsWords.sayTired(), OpsWords.sayTired(cap, now + 3 * 3_600_000L),
                "a cap past its end is no reason to name an end");
        // the action bar does not wrap: no wider than the line Gate C already measured, give or take
        assertTrue(OpsWords.tiredForToday(24 * 3_600_000L).length() <= OpsWords.sayTired().length() + 2);
    }

    @Test
    void theTwoLinesAreDifferentThings() {
        assertFalse(OpsWords.sayDraining().equals(OpsWords.sayTimedOut()),
                "a wish nobody started and a wish nobody finished are different facts");
    }
}
