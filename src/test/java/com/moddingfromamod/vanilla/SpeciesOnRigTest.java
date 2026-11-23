package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.moddingfromamod.mob.PetBehaviour;
import com.moddingfromamod.species.Species;

import org.junit.jupiter.api.Test;

/** The pure half: what a species asks its rig carrier to do. */
class SpeciesOnRigTest {

    @Test
    void aCompanionFollowsAndIsFriendly() {
        PetBehaviour b = SpeciesOnRig.behaviour(Species.Base.COMPANION, Set.of());
        assertEquals(Boolean.TRUE, b.follow());
        assertEquals(Boolean.TRUE, b.friendly());
        assertNull(b.rideable(), "nothing in a species asks to be ridden");
        assertNull(b.finds());
    }

    @Test
    void theFriendlyPowerAloneMakesItFriendlyButNotAFollower() {
        PetBehaviour b = SpeciesOnRig.behaviour(Species.Base.WILD, Set.of(Species.Power.FRIENDLY));
        assertEquals(Boolean.TRUE, b.friendly());
        assertNull(b.follow());
    }

    @Test
    void everythingElseIsLeftToItsCarrierUntil4bAnd4c() {
        for (Species.Base base : Species.Base.values()) {
            if (base == Species.Base.COMPANION) {
                continue;
            }
            assertTrue(SpeciesOnRig.behaviour(base, Set.of(Species.Power.HOSTILE,
                    Species.Power.FIREPROOF, Species.Power.GLOWING)).isEmpty(), base.name());
        }
    }

    @Test
    void theStarterPetDragonIsACompanion() {
        // starter-library/species/pet_dragon: companion + friendly + fireproof, "It follows you".
        PetBehaviour b = SpeciesOnRig.behaviour(Species.Base.COMPANION,
                Set.of(Species.Power.FRIENDLY, Species.Power.FIREPROOF));
        assertEquals(Boolean.TRUE, b.follow());
        assertEquals(Boolean.TRUE, b.friendly());
    }

    @Test
    void theChildsOwnWordIsLaidOverTheSpeciesDefault() {
        PetBehaviour species = SpeciesOnRig.behaviour(Species.Base.COMPANION, Set.of());
        PetBehaviour stopFollowing = new PetBehaviour(false, null, null, null);
        PetBehaviour effective = stopFollowing.over(species);
        assertEquals(Boolean.FALSE, effective.follow());
        assertEquals(Boolean.TRUE, effective.friendly(), "only what he changed changes");
    }
}
