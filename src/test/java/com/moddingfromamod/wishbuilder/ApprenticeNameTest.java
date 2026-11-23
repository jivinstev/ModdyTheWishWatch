package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** the name he gives in a typed wish, read narrowly, and the words without it. */
class ApprenticeNameTest {

    @Test
    void theNameAfterACueUpToTheEndOrAJoiningWord() {
        assertEquals(new ApprenticeName.Named("Sir Fluffy", "a pet wolf"), ApprenticeName.parse("a pet wolf called sir fluffy"));
        assertEquals(new ApprenticeName.Named("Rex", "a dragon that breathes fire"),
                ApprenticeName.parse("a dragon named Rex that breathes fire"));
        assertEquals(new ApprenticeName.Named("Captain Sparkles", "a unicorn"),
                ApprenticeName.parse("a unicorn whose name is captain sparkles!"));
        assertEquals(new ApprenticeName.Named("Zappy", "a sword and make it blue"),
                ApprenticeName.parse("a sword call it zappy and make it blue"));
    }

    @Test
    void anythingElseIsNoName() {
        assertNull(ApprenticeName.parse("a pet wolf"));
        assertNull(ApprenticeName.parse("a wolf called"), "nothing after the cue");
        assertNull(ApprenticeName.parse("a wolf called the big bad fluffy wolf"), "more than three words");
        assertNull(ApprenticeName.parse("a robot called r2d2"), "letters only");
        assertNull(ApprenticeName.parse("a cat named abcdefghijklmnopqrstuvwxyz"), "longer than 24 characters");
        assertNull(ApprenticeName.parse(null));
    }
}
