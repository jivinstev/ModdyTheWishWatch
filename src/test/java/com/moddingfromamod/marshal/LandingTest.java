package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Where a child lands when he asks to be taken to his wish.
 *
 * <p>Pure, because "does he end up outside the build volume, looking at it" is a question about
 * numbers — and answering it in a real world means building a real castle first, every time.
 *
 * <p>The failure this family exists to prevent is not a wrong coordinate, it is <b>suffocating in a
 * wall that did not exist a tick ago</b>: the volume is written at 8,000 blocks a tick, so landing
 * on the anchor is landing in the foundations.
 */
class LandingTest {

    @Test
    void heLandsOutsideTheBuildNotInIt() {
        var spot = Landing.standOff(100, 64, 100, 200, 100);

        assertTrue(spot.x() > 100, "he should be on the side he came from");
        double dx = spot.x() - 100.5;
        double dz = spot.z() - 100.5;
        assertEquals(Landing.STAND_OFF, Math.sqrt(dx * dx + dz * dz), 0.01,
                "exactly the stand-off distance — the anchor is the foundations, not the doorstep");
    }

    @Test
    void heIsLookingAtIt() {
        // Minecraft's yaw: 0 = south (+Z), 90 = WEST (−X), 180 = north, −90 = east. Measured from
        // south and growing clockwise, which is the detail that makes this easy to get exactly
        // backwards — and facing away from the thing he asked to see reads as the teleport going to
        // the wrong place, not as a rotation bug. This assertion was itself written backwards first.
        var fromEast = Landing.standOff(0, 64, 0, 500, 0);
        assertEquals(90f, fromEast.yaw(), 0.01f,
                "he came from +X, so he is looking towards −X, which is west, which is yaw 90");

        var fromNorth = Landing.standOff(0, 64, 0, 0, -500);
        assertEquals(0f, fromNorth.yaw(), 0.01f, "coming from −Z, he looks towards +Z (south, yaw 0)");

        var fromWest = Landing.standOff(0, 64, 0, -500, 0);
        assertEquals(-90f, fromWest.yaw(), 0.01f, "and from −X he looks east");
    }

    @Test
    void heArrivesAtEyeLevelNotAtItsFeet() {
        var spot = Landing.standOff(10, 70, 10, 40, 10);
        assertEquals(72, spot.y(), 0.01, "standing on the ground at the anchor's height, eyes up");
    }

    @Test
    void standingExactlyOnTheAnchorStillMovesHimOff() {
        // The one spot that must never be the answer is the anchor itself, and a naive direction
        // vector is exactly zero there.
        var spot = Landing.standOff(50, 64, 50, 50.5, 50.5);
        double dx = spot.x() - 50.5;
        double dz = spot.z() - 50.5;
        assertEquals(Landing.STAND_OFF, Math.sqrt(dx * dx + dz * dz), 0.01,
                "a zero direction must not leave him standing in the foundations");
    }

    @Test
    void theApproachDirectionIsUsedSoTwoTripsDoNotStackHim() {
        var fromEast = Landing.standOff(0, 64, 0, 100, 0);
        var fromWest = Landing.standOff(0, 64, 0, -100, 0);
        assertTrue(Math.abs(fromEast.x() - fromWest.x()) > 1,
                "a fixed offset puts him in the same spot every time, and two wishes in one place "
                        + "then land him inside each other");
    }

    @Test
    void theStandOffIsOutsideAnythingSmall() {
        // A 4-block hut around its anchor must not contain the landing spot.
        var spot = Landing.standOff(0, 64, 0, 100, 0);
        assertFalse(Landing.insideBuild(spot.x(), spot.y(), spot.z(), 0, 64, 0, 4),
                "he lands inside a small building");
    }

    @Test
    void aHugeBuildIsHonestlyReportedAsSwallowingTheLandingSpot() {
        // Six blocks is not enough for a 200,000-block castle, and the maths says so rather than
        // pretending. What to do about it is the caller's problem; silently claiming he is outside
        // is what would hurt.
        var spot = Landing.standOff(0, 64, 0, 100, 0);
        assertTrue(Landing.insideBuild(spot.x(), spot.y(), spot.z(), 0, 64, 0, 40));
    }

    @Test
    void theWayHomeStaysOpenForAMinuteAndThenCloses() {
        assertTrue(Landing.wayHomeOpen(1_000, 1_000), "immediately after landing");
        assertTrue(Landing.wayHomeOpen(1_000, 1_000 + Landing.WAY_HOME_MS),
                "right on the boundary he can still get home");
        assertFalse(Landing.wayHomeOpen(1_000, 1_000 + Landing.WAY_HOME_MS + 1));
        assertFalse(Landing.wayHomeOpen(0, 5_000), "never departed, nothing to go back to");
    }

    @Test
    void aMinuteIsLongEnoughToBeAPromise() {
        assertTrue(Landing.WAY_HOME_MS >= 30_000,
                "'you can always get home' cannot mean 'you have a few seconds to decide'");
    }
}
