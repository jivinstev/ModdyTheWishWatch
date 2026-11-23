package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.Species.Base;
import com.moddingfromamod.species.Species.Power;

import org.junit.jupiter.api.Test;

/** The pure half: {@code WishMobEntity.rebuildGoals}, as a table. */
class RigInstinctTest {

    @Test
    void aCowardRunsAndNeverFights() {
        RigInstinct in = RigInstinct.of(Base.COWARD, Set.of(Power.LEAPS));
        assertTrue(in.flees());
        assertFalse(in.fights());
        assertFalse(in.leaps(), "leaps is a fighter's power; a coward has nothing to pounce on");
    }

    @Test
    void aSkittishWildCreatureRunsAndIsNotHostile() {
        RigInstinct in = RigInstinct.of(Base.WILD, Set.of(Power.SKITTISH));
        assertTrue(in.flees());
        assertFalse(in.fights(), "skittish makes Species.isHostile false");
    }

    @Test
    void aGuardianHuntsMonstersAndNeverAPlayer() {
        RigInstinct in = RigInstinct.of(Base.GUARDIAN, Set.of());
        assertTrue(in.fights());
        assertTrue(in.huntsMonsters());
        assertFalse(in.huntsPlayers());
        assertFalse(in.huntsAnimals());
    }

    @Test
    void aHunterHuntsPlayersAndAnimals() {
        RigInstinct in = RigInstinct.of(Base.HUNTER, Set.of(Power.LEAPS));
        assertTrue(in.huntsPlayers());
        assertTrue(in.huntsAnimals());
        assertFalse(in.huntsMonsters());
        assertTrue(in.leaps());
    }

    @Test
    void aWildCreatureWithNoPowersIsHostile() {
        RigInstinct in = RigInstinct.of(Base.WILD, Set.of());
        assertTrue(in.fights());
        assertTrue(in.huntsPlayers());
        assertFalse(in.huntsAnimals());
    }

    @Test
    void aFriendlyWildCreatureAndACompanionDoNothingHere() {
        assertTrue(RigInstinct.of(Base.WILD, Set.of(Power.FRIENDLY)).isEmpty());
        assertTrue(RigInstinct.of(Base.COMPANION, Set.of()).isEmpty(),
                "a companion is PetBehaviours' (4a), not this table's");
    }

    @Test
    void aMissingSpeciesDoesNothing() {
        assertTrue(RigInstinct.of(null).isEmpty());
        assertTrue(RigInstinct.of(null, null).fights(), "a null base is WILD, and no powers is hostile");
    }

    @Test
    void itRunsStraightAwayAndNeverToNaN() {
        double[] p = RigInstinct.awayFrom(0, 0, 3, 0);
        assertEquals(-RigInstinct.FLEE_DISTANCE, p[0], 1e-9);
        assertEquals(0, p[1], 1e-9);
        double[] same = RigInstinct.awayFrom(5, 5, 5, 5);
        assertFalse(Double.isNaN(same[0]) || Double.isNaN(same[1]));
        assertEquals(RigInstinct.FLEE_DISTANCE,
                Math.hypot(same[0] - 5, same[1] - 5), 1e-9);
    }

    @Test
    void itPouncesOnlyFromTwoToFourBlocks() {
        assertFalse(RigInstinct.canLeap(1.9));
        assertTrue(RigInstinct.canLeap(2.0));
        assertTrue(RigInstinct.canLeap(4.0));
        assertFalse(RigInstinct.canLeap(4.1));
    }

    @Test
    void thePounceIsLeapAtTargetGoals() {
        double[] v = RigInstinct.leap(3, 4, 0.5, 0);
        assertEquals(0.6 * 0.4 + 0.1, v[0], 1e-9);
        assertEquals(RigInstinct.LEAP_Y, v[1], 1e-9);
        assertEquals(0.8 * 0.4, v[2], 1e-9);
    }

    @Test
    void theHitIsTheSpeciesOwnDamage() {
        assertEquals(6f, RigInstinct.hit(6));
        assertEquals(0f, RigInstinct.hit(0), "a harmless species stays harmless");
        assertEquals(0f, RigInstinct.hit(-2));
    }

    @org.junit.jupiter.api.Test
    void aTargetItCannotGetToIsGivenUp() {
        org.junit.jupiter.api.Assertions.assertFalse(RigInstinct.givesUp(2.0, 2.0, 40), "a second of no progress is a pause, not a wall");
        org.junit.jupiter.api.Assertions.assertTrue(RigInstinct.givesUp(2.0, 2.0, 60), "three seconds no nearer: it cannot get there");
        org.junit.jupiter.api.Assertions.assertFalse(RigInstinct.givesUp(6.0, 4.0, 80), "it is closing in");
    }
}
