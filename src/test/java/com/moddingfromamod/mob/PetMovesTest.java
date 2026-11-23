package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** When a pet walks or hops, and which way a ridden mob goes for the keys he holds. */
class PetMovesTest {

    @Test
    @DisplayName("a follower stays close, walks when a little way off, hops when far")
    void followBands() {
        assertEquals(PetMoves.Follow.STAY, PetMoves.follow(1.0));
        assertEquals(PetMoves.Follow.WALK, PetMoves.follow(PetMoves.FOLLOW_CLOSE));
        assertEquals(PetMoves.Follow.WALK, PetMoves.follow(PetMoves.FOLLOW_TELEPORT));
        assertEquals(PetMoves.Follow.HOP, PetMoves.follow(PetMoves.FOLLOW_TELEPORT + 0.1));
    }

    @Test
    @DisplayName("W goes where he looks: south at yaw 0, west at yaw 90")
    void forwardIsWhereHeLooks() {
        double[] south = PetMoves.ride(0f, 0f, 1f, 0f, 0.3, false);
        assertTrue(south[2] > 0.29 && Math.abs(south[0]) < 1e-9, java.util.Arrays.toString(south));
        assertTrue(Double.isNaN(south[1]), "a walker keeps its own gravity");
        double[] west = PetMoves.ride(90f, 0f, 1f, 0f, 0.3, false);
        assertTrue(west[0] < -0.29 && Math.abs(west[2]) < 1e-9, java.util.Arrays.toString(west));
        double[] back = PetMoves.ride(0f, 0f, -1f, 0f, 0.3, false);
        assertTrue(back[2] < 0);
        double[] still = PetMoves.ride(0f, 0f, 0f, 0f, 0.3, false);
        assertEquals(0.0, still[0], 1e-9);
        assertEquals(0.0, still[2], 1e-9);
    }

    @Test
    @DisplayName("A strafes left, the way a player walks")
    void strafeIsLeft() {
        double[] left = PetMoves.ride(0f, 0f, 0f, 1f, 0.3, false);
        assertTrue(left[0] > 0.29, "facing south, left is +x (east): " + java.util.Arrays.toString(left));
    }

    @Test
    @DisplayName("a flier or a swimmer climbs where he looks, and its speed is clamped")
    void fliersClimbAndSpeedIsClamped() {
        double[] up = PetMoves.ride(0f, -90f, 1f, 0f, 0.3, true);
        assertTrue(up[1] > 0.29, "looking straight up flies straight up: " + java.util.Arrays.toString(up));
        double[] fast = PetMoves.ride(0f, 0f, 1f, 0f, 5.0, false);
        assertEquals(PetMoves.RIDE_MAX, fast[2], 1e-9);
        double[] slow = PetMoves.ride(0f, 0f, 1f, 0f, 0.0, false);
        assertEquals(PetMoves.RIDE_MIN, slow[2], 1e-9);
    }

    // ──: the leash, and a mount that waits ──────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void insideItsLeashItIsFree() {
        org.junit.jupiter.api.Assertions.assertEquals(PetMoves.Leash.FREE, PetMoves.leash(5, false));
        org.junit.jupiter.api.Assertions.assertEquals(PetMoves.Leash.FREE, PetMoves.leash(PetMoves.LEASH, false));
    }

    @org.junit.jupiter.api.Test
    void pastItsLeashItComesBackAndKeepsComingUntilWellInside() {
        org.junit.jupiter.api.Assertions.assertEquals(PetMoves.Leash.RETURN, PetMoves.leash(PetMoves.LEASH + 1, false));
        // on its way back, still 12 blocks out: no dithering on the edge
        org.junit.jupiter.api.Assertions.assertEquals(PetMoves.Leash.RETURN, PetMoves.leash(12, true));
        org.junit.jupiter.api.Assertions.assertEquals(PetMoves.Leash.FREE, PetMoves.leash(PetMoves.LEASH_BACK - 1, true));
        org.junit.jupiter.api.Assertions.assertEquals(PetMoves.Leash.HOP, PetMoves.leash(PetMoves.LEASH_TELEPORT + 1, false));
    }

    @org.junit.jupiter.api.Test
    void aMountWaitsOnlyForACloseRiderWithAFreeHand() {
        org.junit.jupiter.api.Assertions.assertTrue(PetMoves.waitsForRider(true, false, 4, true));
        org.junit.jupiter.api.Assertions.assertFalse(PetMoves.waitsForRider(true, false, 4, false), "holding something");
        org.junit.jupiter.api.Assertions.assertFalse(PetMoves.waitsForRider(true, false, PetMoves.WAIT_RADIUS + 1, true), "too far");
        org.junit.jupiter.api.Assertions.assertFalse(PetMoves.waitsForRider(true, true, 1, true), "already ridden");
        org.junit.jupiter.api.Assertions.assertFalse(PetMoves.waitsForRider(false, false, 1, true), "not a mount");
    }

    @org.junit.jupiter.api.Test
    void aCallNamesOnlyKindsItCanFind() {
        org.junit.jupiter.api.Assertions.assertTrue(com.moddingfromamod.item.CallPets.isKindWord("wolf"));
        org.junit.jupiter.api.Assertions.assertTrue(com.moddingfromamod.item.CallPets.isKindWord("creature_fierce_red_dragon"));
        org.junit.jupiter.api.Assertions.assertTrue(com.moddingfromamod.item.CallPets.isKindWord("event_moon_pack_1_200"));
        org.junit.jupiter.api.Assertions.assertFalse(com.moddingfromamod.item.CallPets.isKindWord("creature_"), "no id");
        org.junit.jupiter.api.Assertions.assertFalse(com.moddingfromamod.item.CallPets.isKindWord("creature_a b"), "a tag cannot hold a space");
        org.junit.jupiter.api.Assertions.assertFalse(com.moddingfromamod.item.CallPets.isKindWord("dragons"));
    }

    @Test
    void aCreatureComingHomeThatGetsNoCloserIsPutBack() {
        assertFalse(PetMoves.stuck(17.8, 17.8, 30), "not yet: it has only been stuck a second and a half");
        assertTrue(PetMoves.stuck(17.8, 17.8, 60), "three seconds no nearer: a wall is in the way");
        assertFalse(PetMoves.stuck(17.8, 17.0, 60), "it is getting closer");
    }

    @Test
    @DisplayName("#1931: a follower whose walk ends short of him hops once it gets no nearer, and only then")
    void aFollowerStuckShortOfHimHops() {
        // The red run: 5.2 blocks off, standing there for the rest of the test.
        assertTrue(PetMoves.followStuck(5.2, 5.2, PetMoves.STUCK_TICKS), "stuck five blocks off: hop");
        assertFalse(PetMoves.followStuck(5.2, 5.2, PetMoves.STUCK_TICKS - 1), "not yet");
        assertFalse(PetMoves.followStuck(5.2, 4.6, PetMoves.STUCK_TICKS), "still closing in: keep walking");
        // Near enough is with him: it never hops for standing a step further off than STAY.
        assertFalse(PetMoves.followStuck(4.0, 4.0, 10_000), "four blocks off is with him");
        assertFalse(PetMoves.followStuck(2.0, 2.0, 10_000), "staying put beside him is not stuck");
        // Past the teleport distance the ordinary hop already applies.
        assertFalse(PetMoves.followStuck(20, 20, 10_000), "far away is the ordinary hop's, not this");
    }
}
