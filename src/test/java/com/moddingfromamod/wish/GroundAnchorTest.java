package com.moddingfromamod.wish;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settling rule, and — the half that keeps it safe — everything it must leave alone. */
class GroundAnchorTest {

    @Test
    void theSkyscraperComesDownToTheGround() {
        // The live numbers: locked at y=26, ground at y=4.
        assertEquals(4, GroundAnchor.settle(26, 4, false),
                "a wish made while flying hung 22 blocks in the air");
        assertTrue(GroundAnchor.wouldSettle(26, 4, false));
    }

    @Test
    void standingOnTheGroundChangesNothing() {
        assertEquals(4, GroundAnchor.settle(4, 4, false));
        assertFalse(GroundAnchor.wouldSettle(4, 4, false));
    }

    @Test
    void anExplicitUpIsNeverOverridden() {
        // "up: 40 for something in the sky" is the agent deliberately asking for altitude. A rule
        // that dragged those down would make every flying-castle wish impossible to express.
        assertEquals(64, GroundAnchor.settle(64, 4, true));
        assertFalse(GroundAnchor.wouldSettle(64, 4, true));
    }

    @Test
    void aWishMadeInACaveIsNotShovedUpThroughTheRoof() {
        // THE REASON SETTLING IS ONE-DIRECTIONAL. He is standing in a mineshaft at y=-20 with the
        // surface 84 blocks over his head. Raising the anchor would put his wish out in the
        // daylight while he stares at a wall.
        assertEquals(-20, GroundAnchor.settle(-20, 64, false));
        assertFalse(GroundAnchor.wouldSettle(-20, 64, false));
    }

    @Test
    void standingInsideAHillIsAlsoLeftAlone() {
        assertEquals(70, GroundAnchor.settle(70, 90, false));
    }

    @Test
    void deepNegativeYIsHandledLikeAnyOther() {
        // Y goes to -64. A rule written as `if (y >= 0)` — the classic — would misbehave here, and
        // a deepslate-level build is exactly where a child ends up mining.
        assertEquals(-59, GroundAnchor.settle(-40, -59, false));
        assertEquals(-59, GroundAnchor.settle(-59, -59, false));
    }

    @Test
    void oneBlockOffTheGroundStillSettles() {
        // No dead band: hovering by a single block is still hovering, and Creative flight parks you
        // there constantly.
        assertEquals(63, GroundAnchor.settle(64, 63, false));
        assertTrue(GroundAnchor.wouldSettle(64, 63, false));
    }
}
