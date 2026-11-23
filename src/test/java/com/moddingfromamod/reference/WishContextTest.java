package com.moddingfromamod.reference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carried context is only safe if the child can escape it. These are mostly about the escape.
 */
class WishContextTest {

    @BeforeEach
    void reset() {
        CharacterResolver.clear();
        CharacterLineage.clear();
    }

    @Test
    @DisplayName("THE CONTRARY CASE: a new subject overrides what came before")
    void newSubjectWins() {
        // Two wishes in a row are frequently unrelated. Carried context that cannot be overridden
        // turns a helpful hint into a wrong answer the agent has been told to trust.
        CharacterLineage.remember("zippy", "Zippy the Hedgehog");
        String ctx = WishContext.forWish("Make SPIKOR king of the Kroaks!", "zippy");
        assertTrue(ctx.contains("NOT the creature you were last working on"), ctx);
        assertTrue(ctx.contains("Build the new one"), ctx);
        assertFalse(ctx.contains("If this wish is a change to it"),
                "must not invite editing Zippy when the child asked for Spikor: " + ctx);
    }

    @Test
    @DisplayName("a follow-up naming nobody is offered the previous creature, droppably")
    void followUpKeepsContext() {
        CharacterLineage.remember("omega_lad", "Omega Lad");
        String ctx = WishContext.forWish("make it look more like the real one please", "omega_lad");
        assertTrue(ctx.contains("omega_lad"), ctx);
        assertTrue(ctx.contains("ignore this line completely"),
                "carried context must always be escapable: " + ctx);
    }

    @Test
    @DisplayName("a first wish with no history says nothing about history")
    void firstWishIsClean() {
        String ctx = WishContext.forWish("make a OMEGA LAD", null);
        assertFalse(ctx.contains("last working on"), ctx);
    }

    @Test
    @DisplayName("no cached reference means no reference block — never an invented one")
    void noReferenceNoBlock() {
        String ctx = WishContext.forWish("make a creature called Zizzle", null);
        assertFalse(ctx.contains("ACTUALLY looks like"), ctx);
    }
}
