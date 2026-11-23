package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The {@code said} record — one line Moddy sent a child — has the shape
 * the wish trace reads ({@code kind}, {@code wishId}, {@code site}, {@code text}). A renamed field
 * here must move in that reader too.
 */
class SaidRecordTest {

    @Test
    void theFieldsTheTraceReads() {
        var o = ActionLog.saidRecord("21d6042e", "07e1e78c-0000-0000-0000-000000000000",
                "WishFeedback.planned ← PlanWishTool.call", "✨ Here's my plan: sun", false);
        assertEquals("said", o.get("kind").getAsString());
        assertEquals("21d6042e", o.get("wishId").getAsString());
        assertEquals("WishFeedback.planned ← PlanWishTool.call", o.get("site").getAsString());
        assertEquals("✨ Here's my plan: sun", o.get("text").getAsString());
        assertFalse(o.get("overlay").getAsBoolean());
        assertTrue(o.has("at") && o.has("test") && o.has("playerId"));
    }

    @Test
    void aLineNoWishCanBePinnedToCarriesNoWishId() {
        var o = ActionLog.saidRecord("", "p", "WishFeedback.tooFast", "rest", true);
        assertFalse(o.has("wishId"), "never guessed: an empty id is left out");
        assertTrue(o.get("overlay").getAsBoolean());
    }

    @Test
    void aLongLineIsClipped() {
        var o = ActionLog.saidRecord("x", "p", "s", "a".repeat(5000), false);
        assertTrue(o.get("text").getAsString().length() <= 1100);
    }
}
