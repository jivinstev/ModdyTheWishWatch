package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** When "take me back" should learn a new home, and when it must keep the old one. */
class TripHomeTest {

    @Test
    void theFirstHopRemembersWhereHeIsStanding() {
        assertTrue(TripHome.shouldRecord(false, false, -1));
    }

    @Test
    void hoppingStraightFromOneWishToAnotherKeepsTheRealHome() {
        // The reason this is not a plain overwrite. He goes home → castle → dragon; "take me back"
        // must lead home, not to the castle he was standing in a moment ago.
        assertFalse(TripHome.shouldRecord(true, true, 3.0),
                "he is still standing where the last hop dropped him, so the trip is not over");
    }

    @Test
    void wanderingOffAndPlayingEndsTheTrip() {
        // THE BUG. The entry used to be cleared only by pressing "take me back", so a child who
        // teleported out to look at something and then carried on playing left it behind forever —
        // and his next "take me back", hours later, threw him to wherever he stood that time.
        assertTrue(TripHome.shouldRecord(true, true, 900.0),
                "he left and played elsewhere; that trip ended whether or not he used the button");
    }

    @Test
    void walkingAroundTheThingHeCameToSeeIsStillVisitingIt() {
        // Landing next to a castle and walking round, through and over it is all one visit, so the
        // radius has to be comfortably bigger than a big build.
        assertFalse(TripHome.shouldRecord(true, true, TripHome.STILL_THERE - 1));
        assertTrue(TripHome.shouldRecord(true, true, TripHome.STILL_THERE + 1));
    }

    @Test
    void leavingTheWorldEndsTheTrip() {
        // He was taken to the overworld and is now in the nether: whatever he is doing, it is not
        // still looking at that.
        assertTrue(TripHome.shouldRecord(true, false, 1.0));
    }

    @Test
    void anUnknownLandingIsNotTrusted() {
        // No record of where he was put down — from an older save, or a restart. Recording afresh
        // is the safe answer: the worst case is one trip home he could have walked, versus being
        // thrown somewhere he has no memory of ever standing.
        assertTrue(TripHome.shouldRecord(true, true, -1));
    }
}
