package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Gate A 's option C: where a REPLAYED seated part goes. The recipe says {@code at
 * castle:throne}; his aim decides which castle, or none.
 */
class ReplaySeatTest {

    /** The castle the recipe names, at the origin; a newer one 300 blocks east. */
    private static final ReplaySeat.Candidate CASTLE =
            new ReplaySeat.Candidate("castle", new ReplaySeat.Box(0, 64, 0, 10, 74, 10), true);
    private static final ReplaySeat.Candidate NEWER =
            new ReplaySeat.Candidate("castle_2", new ReplaySeat.Box(300, 64, 0, 312, 76, 12), true);
    private static final ReplaySeat.Candidate SHED =
            new ReplaySeat.Candidate("shed", new ReplaySeat.Box(-40, 64, -40, -36, 67, -36), false);

    private static ReplaySeat.Point p(double x, double y, double z) {
        return new ReplaySeat.Point(x, y, z);
    }

    @Test
    void theSameCastleInViewKeepsItsThrone() {
        var d = ReplaySeat.decide("castle", "throne", false, List.of(CASTLE, NEWER),
                List.of(p(5.5, 66.5, 0.5)), p(5, 64, -20));
        assertEquals("castle", d.buildId());
        assertEquals("", d.note(), "the link honoured needs no words");
    }

    @Test
    void aNewerCastleInViewTakesItsThroneInstead() {
        var d = ReplaySeat.decide("castle", "throne", false, List.of(CASTLE, NEWER),
                List.of(p(300.5, 70.5, 6.5)), p(290, 64, 6));
        assertEquals("castle_2", d.buildId(), "the castle he is LOOKING at, not the one the recipe names");
        assertTrue(d.note().contains("castle_2") && d.note().contains("looking at"), d.note());
    }

    @Test
    void aLockedSpotJustInFrontOfTheWallIsStillAimingAtIt() {
        var d = ReplaySeat.decide("castle", "throne", false, List.of(NEWER),
                List.of(p(299.5, 64.5, 6.5)), p(280, 64, 6));
        assertEquals("castle_2", d.buildId());
    }

    @Test
    void standingInsideOneCountsWhenHeAimsAtNothing() {
        var d = ReplaySeat.decide("castle", "throne", false, List.of(CASTLE, NEWER), List.of(),
                p(305, 65, 5));
        assertEquals("castle_2", d.buildId());
    }

    @Test
    void farAwayItComesToHim() {
        var d = ReplaySeat.decide("castle", "throne", false, List.of(CASTLE, NEWER),
                List.of(p(1000.5, 64.5, 1000.5)), p(990, 64, 990));
        assertNull(d.buildId(), "no castle in view: where he aims, like every other wish");
        assertFalse(d.onBuilding());
        assertTrue(d.note().contains("right here with him") && d.note().contains("somewhere else"), d.note());
    }

    @Test
    void aBuildingInViewWithNoSpotOfThatNameIsNotUsed() {
        var d = ReplaySeat.decide("castle", "throne", false, List.of(CASTLE, SHED),
                List.of(p(-38.5, 65.5, -38.5)), p(-50, 64, -50));
        assertNull(d.buildId());
        assertTrue(d.note().contains("'shed'") && d.note().contains("has no throne"), d.note());
    }

    @Test
    void theCastleRemovedAndNoOtherInViewSaysSo() {
        var d = ReplaySeat.decide("castle", "throne", false, List.of(SHED),
                List.of(p(1000, 64, 1000)), p(990, 64, 990));
        assertNull(d.buildId());
        assertTrue(d.note().contains("'castle' is not standing any more"), d.note());
    }

    @Test
    void theAgentInTheLoopOrAJustRebuiltCastleKeepsTheLink() {
        // his words win (the agent read them), and a whole-wish replay that just put the castle up
        // again is seating on the castle he is watching rise
        var d = ReplaySeat.decide("castle", "throne", true, List.of(NEWER),
                List.of(p(300.5, 70.5, 6.5)), p(290, 64, 6));
        assertEquals("castle", d.buildId());
        assertEquals("", d.note());
    }

    @Test
    void twoInViewTheLinkedOneWinsElseTheSmaller() {
        var outer = new ReplaySeat.Candidate("keep", new ReplaySeat.Box(-20, 60, -20, 30, 90, 30), true);
        var aim = List.of(p(5.5, 66.5, 5.5));
        assertEquals("castle", ReplaySeat.decide("castle", "throne", false, List.of(outer, CASTLE),
                aim, null).buildId());
        assertEquals("castle", ReplaySeat.decide("gone", "throne", false, List.of(outer, CASTLE),
                aim, null).buildId(), "the throne room inside the keep is the more specific one");
    }
}
