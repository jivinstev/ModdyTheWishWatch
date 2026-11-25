package com.moddingfromamod.safety;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/** The people-rules lines obey SafetyWords' rules — no blame, no category, always a way on. */
class ChatWordsTest {

    private static final List<String> FORBIDDEN = List.of("you shouldn't", "not allowed", "inappropriate",
            "unsafe", "error", "bad word", "naughty", "rude", "offensive", "banned", "violat", "punish");
    private static final List<String> FORWARD = List.of("try", "again", "keep playing", "still", "wish",
            "/moddy report", "helps");
    private static final List<String> CATEGORIES = List.of("sex", "drug", "hate", "slur", "violen", "swear");

    @Test
    void everyLineBlamesNobodyNamesNothingAndPointsOn() {
        for (String s : ChatWords.all()) {
            String lower = s.toLowerCase(Locale.ROOT);
            for (String bad : FORBIDDEN) {
                assertFalse(lower.contains(bad), "'" + bad + "' in: " + s);
            }
            for (String c : CATEGORIES) {
                assertFalse(lower.contains(c), "names a category ('" + c + "'): " + s);
            }
            assertTrue(FORWARD.stream().anyMatch(lower::contains), "points nowhere: " + s);
            assertTrue(s.length() <= 160, "too long for one chat line: " + s);
            assertFalse(s.contains("?"), "asks him something: " + s);
        }
    }

    @Test
    void theBreakLinesSayHowLongAndThatWishesStillWork() {
        assertTrue(ChatWords.sayBreakStarted(10).contains("10 minutes"));
        assertTrue(ChatWords.sayBreakStarted(1).contains("1 minute."));
        assertTrue(ChatWords.sayBreakContinues(3).contains("3 minutes"));
        assertTrue(ChatWords.sayBreakStarted(10).contains("wishes"));
        assertTrue(ChatWords.sayBreakContinues(3).contains("wishes"));
    }

    @Test
    void theThanksNamesWhoWasReportedAndPromisesNoAction() {
        String s = ChatWords.sayReportThanks("Some_Player");
        assertTrue(s.contains("Some_Player"));
        for (String act : List.of("kick", "ban", "remove", "muted")) {
            assertFalse(s.toLowerCase(Locale.ROOT).contains(act), "promises an action: " + s);
        }
    }
}
