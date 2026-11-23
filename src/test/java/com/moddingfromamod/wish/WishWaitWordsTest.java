package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.moddingfromamod.safety.SafetyLexicon;
import org.junit.jupiter.api.Test;

/**
 * the words a child reads while his wish is granted, pinned EXACTLY — they were reviewed
 * and approved verbatim, so a change here is a change to what was approved and needs the same
 * review first. Same pattern as {@code SafetyWordsTest} and
 * {@code WishBankWordsTest}: pinned, and swept by the lexicon.
 */
class WishWaitWordsTest {

    @Test
    void theOpenersAreThePinnedFour() {
        assertEquals(List.of(
                "✨ Got your wish! Moddy's on it…",
                "🪄 Ooh, a new wish! Grabbing my wand…",
                "✨ Wish received! Let's make some magic…",
                "🌟 On it! Moddy's getting to work…"), WishWaitWords.OPENERS);
    }

    @Test
    void theOtherLinesAreThePinnedOnes() {
        assertEquals("✨ See the sparkles? That's where it'll appear!", WishWaitWords.SPARKLES);
        assertEquals("✨ This one's one of a kind — I can't save it for next time, so enjoy it now!",
                "✨ " + WishWaitWords.ONE_OF_A_KIND, "said through WishFeedback.tooSpecialToSave");
        assertEquals("Reading your wish…", WishWaitWords.READING);
        assertEquals("Working out how to make it…", WishWaitWords.WORKING_OUT);
        assertEquals("Looking up Scorch…", WishWaitWords.lookingUp("Scorch"), "#2645");
        assertEquals("Still working on it — thanks for waiting!", WishWaitWords.STILL_WORKING);
        assertEquals("Still going — some wishes take a minute or two!", WishWaitWords.STILL_GOING);
        assertEquals(OneOffWish.TELL_THE_CHILD, WishWaitWords.ONE_OF_A_KIND);
    }

    @Test
    void theOpenersRotateRatherThanRepeat() {
        Set<String> firstFour = new HashSet<>();
        for (int n = 0; n < 4; n++) {
            firstFour.add(WishWaitWords.opener(n));
            assertNotEquals(WishWaitWords.opener(n), WishWaitWords.opener(n + 1),
                    "the same line twice in a row at " + n);
        }
        assertEquals(4, firstFour.size(), "four wishes read four different lines");
        assertEquals(WishWaitWords.opener(0), WishWaitWords.opener(4));
        assertEquals(WishWaitWords.opener(3), WishWaitWords.opener(-1), "never throws on wrap");
    }

    /** The point: no line claims difficulty, size or progress the mod cannot see. */
    @Test
    void noLineClaimsDifficultyOrSize() {
        for (String s : WishWaitWords.all()) {
            String l = s.toLowerCase(Locale.ROOT);
            for (String claim : new String[] {"tricky", "really hard", "big", "huge", "nearly",
                    "almost", "%"}) {
                assertFalse(l.contains(claim), "\"" + claim + "\" in: " + s);
            }
            assertTrue(s.length() <= 140, "too long to read at a glance: " + s);
        }
    }

    @Test
    void everyLineAChildReadsPassesTheLexicon() {
        SafetyLexicon lex = SafetyLexicon.shared();
        List<String> lines = new java.util.ArrayList<>(WishWaitWords.all());
        lines.add(WishWaitWords.step("Building the floating island"));
        assertEquals(11, WishWaitWords.all().size(),
                "a line was added without being listed in all(), so nothing here checks it");
        for (String l : lines) {
            assertFalse(l.isBlank());
            assertTrue(lex.check(l).isEmpty(), "the lexicon flags a wait line: " + l);
        }
    }

    @Test
    void aStepReadsAsWorkInProgress() {
        assertEquals("Building the floating island…", WishWaitWords.step("Building the floating island"));
        assertEquals("Giving it wings…", WishWaitWords.step("  Giving it wings... "));
        assertEquals(WishWaitWords.WORKING_OUT, WishWaitWords.step(""));
        assertEquals(WishWaitWords.WORKING_OUT, WishWaitWords.step("…"));
    }

    /** the lookup line repeats the child's name, cut short and cleaned, never blank. */
    @Test
    void theLookupLineIsShortAndNeverBlank() {
        assertEquals(WishWaitWords.READING, WishWaitWords.lookingUp(null));
        assertEquals(WishWaitWords.READING, WishWaitWords.lookingUp("  \u0007 "));
        assertEquals("Looking up Scorch…", WishWaitWords.lookingUp("§cScorch"));
        String longName = WishWaitWords.lookingUp("Captain Wonderful Magnificent Zoomer of the Seven Seas");
        assertTrue(longName.length() <= "Looking up …".length() + 28, longName);
        assertEquals("Looking up Captain Wonderful…", longName);
    }
}
