package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Gate A for wish sanitising — the first thing that touches untrusted, kid-typed text. */
class WishTextTest {

    @Test
    void keepsOrdinaryWishesIntact() {
        assertEquals("make it rain chickens", WishText.sanitize("make it rain chickens"));
    }

    @Test
    void collapsesWhitespaceAndTrims() {
        assertEquals("a big castle", WishText.sanitize("   a    big \t castle  "));
    }

    @Test
    void stripsControlCharactersIncludingNewlines() {
        // A newline is the vector for smuggling a second command downstream.
        String cleaned = WishText.sanitize("give me a sword\nstop");
        assertFalse(cleaned.contains("\n"), "newlines must not survive: " + cleaned);
        assertEquals("give me a sword stop", cleaned);
    }

    @Test
    void clampsRunawayInput() {
        String cleaned = WishText.sanitize("chicken ".repeat(5000));
        assertTrue(cleaned.length() <= WishText.MAX_LENGTH,
                "expected <= " + WishText.MAX_LENGTH + " but was " + cleaned.length());
    }

    @Test
    void treatsMeaninglessInputAsEmpty() {
        assertEquals("", WishText.sanitize(null));
        assertEquals("", WishText.sanitize(""));
        assertEquals("", WishText.sanitize("   "));
        assertEquals("", WishText.sanitize("!!!???..."), "punctuation-only carries no intent");
        assertFalse(WishText.isUsable(WishText.sanitize("***")));
    }

    @Test
    void survivesEmojiAndUnicode() {
        // Kids type emoji constantly; it must not crash or wipe the real words.
        String cleaned = WishText.sanitize("make a 🐉 dragon 🔥");
        assertTrue(cleaned.contains("dragon"), "real words must survive: " + cleaned);
        assertTrue(WishText.isUsable(cleaned));
    }

    @Test
    void dedupeKeyIgnoresCaseAndPunctuation() {
        assertEquals(WishText.dedupeKey(WishText.sanitize("MAKE IT RAIN CHICKENS!!!")),
                WishText.dedupeKey(WishText.sanitize("make it rain chickens")));
    }

    // ---- /moddy redirect: the re-aimed text (docs/CHILD_SAFETY_PLAN.md §12.7, CS-D2) ----

    @Test
    void aReAimedWishKeepsBothTheOriginalAndWhatHeMeant() {
        String out = WishText.reaimed("make it bigger", "the stone tower");
        assertTrue(out.contains("make it bigger"),
                "the original says WHAT to do and must survive: " + out);
        assertTrue(out.contains("the stone tower"),
                "the re-aim says WHICH THING and must survive: " + out);
        assertTrue(out.indexOf("make it bigger") < out.indexOf("the stone tower"),
                "the wish reads as a sentence, not as two fragments: " + out);
    }

    @Test
    void theReAimedTextIsADIFFERENTStringFromTheOriginal() {
        // THE WHOLE POINT of §12.7. SafetyGrader.gradeInput is memoised on the sanitised text, so
        // a new string is a new key and therefore a new grade; a re-aim that left the text alone is
        // the bug this replaced — the free text reached the agent having been seen by L0 only.
        String original = "make it bigger";
        assertNotEquals(original, WishText.reaimed(original, "the stone tower"));
    }

    @Test
    void theReAimedTextIsSanitizeStableSoTheGraderSeesExactlyWhatTheAgentDoes() {
        // The grader sanitises whatever it is handed. Anything this returns that sanitising would
        // change is a string the agent reads and the grade did not — the derived-text leak, one
        // level down. Checked on the long case too, where the clamp is what makes it true.
        for (String[] pair : new String[][]{
                {"make it bigger", "the stone tower"},
                {"make it   bigger\n", "  the stone tower  "},
                {"tower ".repeat(60), "the little house by the lake"},
                {"make it bigger", "house ".repeat(60)}}) {
            String out = WishText.reaimed(pair[0], pair[1]);
            assertEquals(out, WishText.sanitize(out),
                    "sanitising changed it, so the grade and the agent would see different "
                            + "strings: " + out);
            assertTrue(out.length() <= WishText.MAX_LENGTH, "over the cap: " + out.length());
        }
    }

    @Test
    void overTheCapItIsTheORIGINALThatGetsCutAndNeverTheReAim() {
        // A safety layer must never be the thing that got truncated: the re-aim is both the newest
        // words and the half nothing has graded yet, so it is the half that is kept whole.
        String aim = "the little wooden house by the lake";
        String out = WishText.reaimed("make the enormous stone tower ".repeat(20), aim);
        assertTrue(out.endsWith(aim), "the re-aim did not survive intact: " + out);
        assertTrue(out.contains("make the enormous"), "nothing of the original survived: " + out);
        assertTrue(out.length() <= WishText.MAX_LENGTH);
        // Cut on a word boundary, not mid-word: the agent has to act on this sentence.
        assertFalse(out.contains("  "), "a ragged cut left a double space: " + out);
    }

    @Test
    void aReAimSoLongItFillsTheBudgetIsKeptWholeAndTheWishIsDropped() {
        // The degenerate end of the same rule. Nothing is invented and nothing half-graded is
        // left behind: the newest words win outright.
        String aim = WishText.sanitize("house ".repeat(100));
        assertEquals(aim, WishText.reaimed("make it bigger", aim));
    }

    @Test
    void aBlankReAimLeavesTheWishExactlyAsItWas() {
        // `/moddy redirect` with nothing after it must not rewrite his wish into punctuation.
        for (String blank : new String[]{"", "   ", "!!!", null}) {
            assertEquals("make it bigger", WishText.reaimed("make it bigger", blank),
                    "a blank re-aim changed the wish");
        }
    }

    @Test
    void reAimingTwiceKeepsTheNEWESTAimAtTheEnd() {
        String once = WishText.reaimed("make it bigger", "the tower");
        String twice = WishText.reaimed(once, "the house");
        assertTrue(twice.endsWith("the house"),
                "the thing he just said must be the thing that is kept: " + twice);
        assertTrue(twice.contains("make it bigger"), "the wish itself was lost: " + twice);
    }

    @Test
    void findsTheNumberTheKidAskedFor() {
        assertEquals(20, WishText.firstNumber("spawn 20 zombies").getAsInt());
        assertEquals(7, WishText.firstNumber("i want 7 pigs and 3 cows").getAsInt(),
                "the FIRST number wins");
        assertTrue(WishText.firstNumber("spawn some zombies").isEmpty());
        // A number too big to be an int must not throw.
        assertTrue(WishText.firstNumber("spawn 99999999999999999999 zombies").isEmpty());
    }
}
