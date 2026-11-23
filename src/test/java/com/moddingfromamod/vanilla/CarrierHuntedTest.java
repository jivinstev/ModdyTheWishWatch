package com.moddingfromamod.vanilla;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** a friendly creature never rides a carrier that monsters hunt. */
class CarrierHuntedTest {

    @Test
    void aFriendlyWalkerIsNeverOfferedTheIronGolem() {
        List<String> pool = CarrierPool.allowed(CarrierPool.Move.WALK, false);
        assertTrue(pool.contains("iron_golem"), "the walking pool still lists it: " + pool);
        List<String> friendly = CarrierPool.forInstinct(pool, false);
        assertFalse(friendly.contains("iron_golem"), friendly.toString());
        assertTrue(friendly.containsAll(List.of("polar_bear", "cow", "llama")), friendly.toString());
    }

    @Test
    void aGuardianStillMayRideIt() {
        assertTrue(CarrierPool.forInstinct(CarrierPool.allowed(CarrierPool.Move.WALK, false), true)
                .contains("iron_golem"));
    }
}
