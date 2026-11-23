package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A creature that follows the child is never offered a carrier that cannot. */
class CarrierFollowersTest {

    @Test
    void aFollowingWalkerIsNeverOfferedTheTurtleOrTheFrog() {
        var all = CarrierPool.allowed(CarrierPool.Move.WALK, false);
        assertTrue(all.contains("turtle") && all.contains("frog"), "the premise: walkers may get both");
        var pet = CarrierPool.allowed(CarrierPool.Move.WALK, false, true);
        assertFalse(pet.contains("turtle") || pet.contains("frog"));
        assertEquals(all.size() - 2, pet.size());
    }

    @Test
    void aCreatureThatDoesNotFollowKeepsTheWholeList() {
        assertEquals(CarrierPool.allowed(CarrierPool.Move.WALK, false),
                CarrierPool.allowed(CarrierPool.Move.WALK, false, false));
    }

    @Test
    void aFollowingSwimmerOnLandGetsAWalkerThatFollowsRatherThanNothing() {
        var onLand = CarrierPool.allowed(CarrierPool.Move.SWIM, false);
        assertTrue(onLand.stream().allMatch(CarrierPool.CANNOT_FOLLOW::contains),
                "the premise: a swimmer on land is offered only the amphibians");
        var pet = CarrierPool.allowed(CarrierPool.Move.SWIM, false, true);
        assertFalse(pet.isEmpty());
        assertTrue(pet.stream().noneMatch(CarrierPool.CANNOT_FOLLOW::contains));
    }
}
