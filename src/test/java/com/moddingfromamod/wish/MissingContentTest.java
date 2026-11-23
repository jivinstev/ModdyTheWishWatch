package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>What a wish asked for and could not have.</b>
 *
 * <p>The measured case: with no companion mod jar, a wish for a machine making the child's own
 * fluid was answered with a flour mill and scored {@code delivered: 4, working: true}. This is the
 * record that lets a grader call that unjudgeable rather than passing.
 */
class MissingContentTest {

    @BeforeEach
    void reset() {
        MissingContent.clear();
    }

    @Test
    @DisplayName("a wish that found everything it asked for is not flagged")
    void nothingMissingByDefault() {
        assertFalse(MissingContent.anythingMissing(),
                "a fresh wish must start clean, or every rung inherits the last one's verdict");
        assertTrue(MissingContent.asked().isEmpty());
    }

    @Test
    @DisplayName("a search that found nothing is remembered, in the child's own words")
    void aMissedSearchIsRemembered() {
        MissingContent.record("butt cheek water");
        assertTrue(MissingContent.anythingMissing());
        assertEquals(java.util.List.of("butt cheek water"), MissingContent.asked(),
                "the QUERY is what matters — it is the child's word for the thing, and it is what "
                        + "makes a substituted grant recognisable afterwards");
    }

    @Test
    @DisplayName("clear() really clears — a static that leaks poisons the next wish's verdict")
    void clearingIsNotOptional() {
        MissingContent.record("butt cheek water");
        MissingContent.clear();
        assertFalse(MissingContent.anythingMissing(),
                "one wish's misses must not decide the next wish's score; that would be a worse "
                        + "bug than the one this class fixes");
    }

    @Test
    @DisplayName("the same miss twice is one miss, and a runaway is capped")
    void duplicatesCollapseAndTheListIsBounded() {
        MissingContent.record("cheek");
        MissingContent.record("CHEEK");
        MissingContent.record(" cheek ");
        assertEquals(1, MissingContent.asked().size(),
                "the agent searches the same thing several ways; three spellings of one miss is "
                        + "one miss, not evidence of three");
        for (int i = 0; i < 200; i++) {
            MissingContent.record("thing" + i);
        }
        assertTrue(MissingContent.asked().size() <= 32,
                "a wish that searched for two hundred absent things is a runaway, and an unbounded "
                        + "list would carry it into every snapshot");
    }

    @Test
    @DisplayName("blank and null are not misses")
    void nothingIsNotAMiss() {
        MissingContent.record(null);
        MissingContent.record("   ");
        assertFalse(MissingContent.anythingMissing());
    }
}
