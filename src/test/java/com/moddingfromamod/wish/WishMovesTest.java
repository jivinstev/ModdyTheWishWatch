package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** a journey, not a nudge, is "the wish took him somewhere". */
class WishMovesTest {

    @Test
    void aNudgeOutOfABlockIsNotAJourney() {
        assertFalse(WishMoves.isJourney(0, 0, 1, 1));
        assertFalse(WishMoves.isJourney(100, 100, 120, 110), "onto the floor of what it just built");
        assertFalse(WishMoves.isJourney(0, 0, WishMoves.JOURNEY_BLOCKS, 0), "the threshold itself is a nudge");
    }

    @Test
    void theFarAwayPlainsIsAJourney() {
        assertTrue(WishMoves.isJourney(55, 48, 4520, 3520), "the live wish, 2026-10-16");
        assertTrue(WishMoves.isJourney(0, 0, 0, -40));
        assertTrue(WishMoves.isJourney(0, 0, 30, 30), "diagonal: 42 blocks");
    }
}
