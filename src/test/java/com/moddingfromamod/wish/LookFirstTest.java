package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** a question is answered after looking. */
class LookFirstTest {

    @Test
    @DisplayName("an answer to a question that has not looked is refused, naming the read tools")
    void refusedBeforeLooking() {
        String r = LookFirst.refusal("notify_player", true, false, 0);
        assertNotNull(r);
        assertTrue(r.contains("list_creatures") && r.contains("inspect_creature"), r);
        assertNotNull(LookFirst.refusal("finish_wish", true, false, 1));
        assertNotNull(LookFirst.refusal("finish_building", true, false, 0));
    }

    @Test
    @DisplayName("let through: once it has looked, for a non-question, for a non-answer tool, and after MAX_REFUSALS")
    void letThrough() {
        assertNull(LookFirst.refusal("notify_player", true, true, 0));
        assertNull(LookFirst.refusal("notify_player", false, false, 0));
        assertNull(LookFirst.refusal("list_creatures", true, false, 0));
        assertNull(LookFirst.refusal("notify_player", true, false, LookFirst.MAX_REFUSALS));
    }

    @Test
    @DisplayName("the live wish is a question, and the read tools include the ones that would have answered it")
    void theLiveWish() {
        assertTrue(WishIntent.isQuestion("spikor stopped following me, why?"));
        assertTrue(LookFirst.isRead("list_creatures") && LookFirst.isRead("inspect_creature")
                && LookFirst.isRead("look_around"));
        assertTrue(!LookFirst.isRead("notify_player") && !LookFirst.isRead("run_command"));
    }
}
