package com.moddingfromamod.vanilla;

import java.util.List;
import java.util.Set;

import com.google.gson.JsonParser;
import com.moddingfromamod.species.Species;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A creature he RIDES: the power, the behaviour it means, the carriers it may have (2026-10-11). */
class RideableDesignTest {

    private static Species species(String powers) {
        return Species.fromJson(JsonParser.parseString(
                "{\"id\":\"rex\",\"modelKey\":\"rex\",\"powers\":" + powers + "}").getAsJsonObject());
    }

    @Test
    void theRideablePowerParsesAndMeansRideable() {
        Species rex = species("[\"friendly\",\"rideable\"]");
        assertTrue(rex.has(Species.Power.RIDEABLE));
        var pet = SpeciesOnRig.behaviour(rex);
        assertEquals(Boolean.TRUE, pet.rideable());
        assertEquals(Boolean.TRUE, pet.friendly());
        assertNull(pet.follow(), "riding it does not make it follow him");
    }

    @Test
    void ridingAloneIsABehaviour() {
        var pet = SpeciesOnRig.behaviour(species("[\"rideable\"]"));
        assertEquals(Boolean.TRUE, pet.rideable());
    }

    @Test
    void ridingAndHarmlessnessAreTheDesignAndCanBeTakenOffAgain() {
        Species plain = species("[]");
        Species ridden = plain.withDesignedBehaviour(true, true);
        assertTrue(ridden.has(Species.Power.RIDEABLE) && ridden.has(Species.Power.FRIENDLY));
        Species not = ridden.withDesignedBehaviour(false, null);
        assertFalse(not.has(Species.Power.RIDEABLE));
        assertTrue(not.has(Species.Power.FRIENDLY), "null leaves a power as it was");
        assertEquals(plain, plain.withDesignedBehaviour(null, null));
    }

    @Test
    void aRiddenCreatureIsNeverGivenACarrierThatThrowsItsRider() {
        List<String> walk = CarrierPool.allowed(CarrierPool.Move.WALK, false, false, true);
        assertFalse(walk.isEmpty());
        for (String id : CarrierPool.CANNOT_RIDE) {
            assertFalse(walk.contains(id), id + " throws a rider and was offered to a ridden creature");
        }
        assertTrue(walk.contains("cow"), "the cow the live T-Rex rode stays allowed");
        assertTrue(CarrierPool.allowed(CarrierPool.Move.WALK, false, false, false).contains("horse"),
                "a creature nobody rides may still be a horse");
        assertEquals(Set.of("horse", "llama"), CarrierPool.CANNOT_RIDE);
    }
}
