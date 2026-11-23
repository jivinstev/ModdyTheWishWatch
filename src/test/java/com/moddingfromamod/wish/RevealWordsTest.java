package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** the description said after the photo wins; the plan-time say is the labelled fallback. */
class RevealWordsTest {

    @Test
    @DisplayName("finish_wish's say, written after the photo, is the description")
    void afterThePhotoWins() {
        var d = RevealWords.choose("  a blue garage with a red post ", "a spiral track and a T-Rex");
        assertEquals("a blue garage with a red post", d.text());
        assertEquals(RevealWords.From.PHOTO, d.from());
    }

    @Test
    @DisplayName("with no say after the photo, the plan-time say is said — and labelled PLAN")
    void thePlanIsTheFallback() {
        var d = RevealWords.choose("", "a spiral track");
        assertEquals("a spiral track", d.text());
        assertEquals(RevealWords.From.PLAN, d.from());
    }

    @Test
    @DisplayName("neither: nothing is described")
    void nothing() {
        assertEquals(RevealWords.From.NONE, RevealWords.choose(null, " ").from());
    }
}
