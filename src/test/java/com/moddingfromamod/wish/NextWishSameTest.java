package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** step 5: an idea sent "as it is" survives case, spacing and end punctuation; any real edit is his own wish. */
class NextWishSameTest {

    @Test
    void sentAsItIsIgnoresCaseSpacingAndPunctuation() {
        assertTrue(NextWish.same("Make Sparky fly", "make sparky  fly!"));
        assertTrue(NextWish.same(" Make Sparky fly. ", "Make Sparky fly"));
    }

    @Test
    void anyRealEditIsHisOwnWish() {
        assertFalse(NextWish.same("Make Sparky fly", "Make Sparky fly and glow"));
        assertFalse(NextWish.same("Make Sparky fly", "Make Sparky swim"));
        assertFalse(NextWish.same("Make Sparky fly", ""));
    }
}
