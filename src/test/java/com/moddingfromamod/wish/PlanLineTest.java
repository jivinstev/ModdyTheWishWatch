package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** the plan line names what the AGENT said the wish is about — the first name he sees. */
class PlanLineTest {

    @Test
    void aWishAboutOneOfHisThingsNamesIt() {
        assertEquals("✨ Here's my plan for your Pegasus: Giving her wings → Teaching her to fly",
                WishPlan.line(List.of("Giving her wings", "Teaching her to fly"), "your Pegasus"));
    }

    @Test
    void aNewThingNamesNothingOfHis() {
        assertEquals("✨ Here's my plan: Making a cake fork",
                WishPlan.line(List.of("Making a cake fork"), ""));
        assertEquals("✨ Here's my plan: Making a cake fork",
                WishPlan.line(List.of("Making a cake fork"), null));
    }
}
