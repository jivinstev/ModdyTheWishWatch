package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The numbers a rig carrier gets — and the swap that must not heal or kill anything.</b>
 *
 * <p>These are feel rules rather than arithmetic, which is why they are worth pinning: each one is a
 * decision that could reasonably have gone the other way and would then be a defect a child noticed
 * before a test did.
 */
class RigVitalsTest {

    @Test
    @DisplayName("a fresh creature gets the species' own numbers, at full health")
    void aFreshCreatureIsFull() {
        RigVitals v = RigVitals.fresh(200, 18, 6, 0.4, 0.31);
        assertEquals(200, v.maxHealth());
        assertEquals(200, v.health(), "a creature that has just been wished is not injured");
        assertEquals(18, v.damage());
        assertEquals(6, v.armor());
        assertEquals(0.4, v.knockbackResistance());
        assertEquals(0.31, v.speed());
    }

    @Test
    @DisplayName("health carries across the swap as a FRACTION, because the maximum is changing")
    void healthCarriesAsAFraction() {
        // Halfway through a fight on the legacy path: 30 of 60. The species says 200, and the
        // carrier is about to stop being whatever vanilla mob it was.
        RigVitals v = RigVitals.carrying(200, 18, 6, 0.4, 0.31, 30, 60);
        assertEquals(200, v.maxHealth());
        assertEquals(100, v.health(), 1e-9,
                "copying the raw 30 would leave a barely-scratched creature at 30 of 200 -- and "
                        + "copying nothing would full-heal every creature in an existing save the "
                        + "first time it loaded on the new path. The fraction is the only reading "
                        + "that survives the maximum changing underneath it.");
    }

    @Test
    @DisplayName("a creature that was nearly dead is still nearly dead, and is never rounded to zero")
    void aSurvivorSurvives() {
        RigVitals v = RigVitals.carrying(200, 18, 6, 0.4, 0.31, 0.5, 60);
        assertTrue(v.health() > 0,
                "a rig that spawns dead is a creature that VANISHED, and the child's last memory "
                        + "of it is that it was still standing");
        assertTrue(v.health() < 5, "…but it must still be nearly dead: " + v.health());
    }

    @Test
    @DisplayName("an unreadable previous health falls back to full rather than to nothing")
    void anUnreadablePreviousHealthIsNotAnInjury() {
        assertEquals(200, RigVitals.carrying(200, 18, 6, 0.4, 0.31, 0, 0).health(),
                "a creature we cannot measure is not a creature we may injure");
        assertEquals(200, RigVitals.carrying(200, 18, 6, 0.4, 0.31, 12, 0).health(),
                "a zero maximum is a broken reading, not a creature at infinite health");
    }

    @Test
    @DisplayName("an over-full reading is clamped, not amplified")
    void anOverfullReadingIsClamped() {
        // Absorption, or a species edit that lowered the maximum after the entity was hurt.
        assertEquals(200, RigVitals.carrying(200, 18, 6, 0.4, 0.31, 90, 60).health(), 1e-9,
                "150% of the old maximum must not become 300 health on the new one");
    }

    @Test
    @DisplayName("a species with no health at all still yields a creature that can exist")
    void aZeroHealthSpeciesIsNotACorpse() {
        assertTrue(RigVitals.fresh(0, 0, 0, 0, 0).maxHealth() >= 0);
        assertTrue(RigVitals.carrying(0, 0, 0, 0, 0, 5, 10).maxHealth() >= 1,
                "a max of 0 makes an entity that dies on the tick it spawns");
    }
}
