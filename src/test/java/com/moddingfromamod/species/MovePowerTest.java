package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.moddingfromamod.runtime.Abilities.Kind;

/** {@link MovePower}: what a creature's moves deal and heal a second, from the rig's own numbers (#102). */
class MovePowerTest {

    @Test
    void eachMoveAtItsOwnPace() {
        // ranged: half its damage every 2 s; slam: half every 4 s; charge: all of it every 5 s
        assertEquals(6 * 0.5 / 2.0, MovePower.moveDamagePerSecond(6, Set.of(Kind.RANGED)), 1e-9);
        assertEquals(6 * 0.5 / 4.0, MovePower.moveDamagePerSecond(6, Set.of(Kind.SLAM)), 1e-9);
        assertEquals(6 / 5.0, MovePower.moveDamagePerSecond(6, Set.of(Kind.CHARGE)), 1e-9);
        assertEquals(1.5 + 0.75 + 1.2, MovePower.moveDamagePerSecond(6, EnumSet.of(Kind.RANGED, Kind.SLAM, Kind.CHARGE)), 1e-9);
    }

    @Test
    void aMoveLandsItsFloorEvenOnAZeroHit() {
        // a friendly creature with damage 0 still throws a ball for half a point, and charges for 2
        assertEquals(0.5 / 2.0, MovePower.moveDamagePerSecond(0, Set.of(Kind.RANGED)), 1e-9);
        assertEquals(2 / 5.0, MovePower.moveDamagePerSecond(0, Set.of(Kind.CHARGE)), 1e-9);
    }

    @Test
    void movesThatDoNotHurtCountNothing() {
        assertEquals(0, MovePower.moveDamagePerSecond(10, EnumSet.of(Kind.SUMMON, Kind.TELEPORT, Kind.HEAL)), 1e-9);
        assertFalse(MovePower.hasDamagingMove(EnumSet.of(Kind.SUMMON, Kind.HEAL)));
        assertTrue(MovePower.hasDamagingMove(EnumSet.of(Kind.SLAM)));
        assertEquals(0, MovePower.moveDamagePerSecond(10, null), 1e-9);
    }

    @Test
    void itBitesOnlyWhenItFights() {
        assertEquals(6, MovePower.meleeDamagePerSecond(6, true), 1e-9);
        assertEquals(0, MovePower.meleeDamagePerSecond(6, false), 1e-9);
    }

    @Test
    void healIsATenthOfItsHealthAtLeastTwoEveryFiveSeconds() {
        assertEquals(2 / 5.0, MovePower.healPerSecond(10, Set.of(Kind.HEAL)), 1e-9);
        assertEquals(12 / 5.0, MovePower.healPerSecond(120, Set.of(Kind.HEAL)), 1e-9);
        assertEquals(0, MovePower.healPerSecond(120, Set.of(Kind.RANGED)), 1e-9);
    }
}
