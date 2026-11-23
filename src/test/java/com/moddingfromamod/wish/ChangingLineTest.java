package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * <b>An edit of his creature is "changing", never "appearing"</b>: the early
 * reveal said "Look, it's starting to appear!" of a dragon he had owned for days.
 */
class ChangingLineTest {

    @Test
    void itNamesHisCreature() {
        assertEquals("Changing your Pet Dragon! The rainbow shimmer means I'm still working on it!",
                WishFeedback.changingLine("Pet Dragon"));
    }

    @Test
    void aNameWithColourCodesIsSaidPlainly() {
        assertEquals("Changing your SPIKOR! The rainbow shimmer means I'm still working on it!",
                WishFeedback.changingLine("§6SPIKOR§r"));
    }

    @Test
    void noNameIsStillASentence() {
        assertEquals("Changing it! The rainbow shimmer means I'm still working on it!",
                WishFeedback.changingLine(null));
        assertEquals(WishFeedback.changingLine(""), WishFeedback.changingLine("  "));
    }

    @Test
    void itNeverSaysItIsAppearing() {
        assertFalse(WishFeedback.changingLine("Dragon").toLowerCase().contains("appear"));
    }

    /** The quiet line after a polish in place, after the wish ended. */
    @Test
    void aPolishIsSaidQuietlyAndNamesHisThing() {
        assertEquals("✨ I gave your Slime Bow a little polish.", WishFeedback.polishedLine("Slime Bow"));
        assertEquals("✨ I gave it a little polish.", WishFeedback.polishedLine(""));
    }
}
