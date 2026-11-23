package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An authored ability is a property OF the creature, not a world rule that mentions it —.
 *
 * <p>The built-in {@code abilities} enum is six fixed kinds with hardcoded cooldowns, so "breathe
 * fire every 15 seconds" is not expressible there and never will be without a mod rebuild. An
 * authored CBScript ability can say it — but it did not persist with the species, was not listed
 * with it, and was not accounted for when the creature was removed.
 */
class SpeciesAbilityPackTest {

    private static Species bare() {
        return Species.fromJson(com.google.gson.JsonParser.parseString(
                "{\"id\":\"spikor\",\"name\":\"Spikor\",\"body\":\"humanoid\"}").getAsJsonObject());
    }

    @Test
    @DisplayName("a species starts with none, and binding one is not destructive")
    void bindingAddsWithoutLosingAnything() {
        Species s = bare();
        assertTrue(s.abilityPacks().isEmpty());

        Species withFire = s.withAbilityPack("breath_cone_spikor");
        assertEquals(Set.of("breath_cone_spikor"), withFire.abilityPacks());
        assertEquals(s.name(), withFire.name(), "binding an ability must not disturb the design");
        assertEquals(s.scale(), withFire.scale());

        Species withTwo = withFire.withAbilityPack("impulse_spikor");
        assertEquals(2, withTwo.abilityPacks().size(), "a creature can have more than one ability");
    }

    @Test
    @DisplayName("binding the same ability twice does not double it")
    void bindingIsIdempotent() {
        // Re-authoring under the same feature id is how an ability is EDITED, so it must not
        // accumulate — that is the items 'one wand, two rows' failure in another costume.
        Species once = bare().withAbilityPack("breath_cone_spikor");
        assertEquals(once, once.withAbilityPack("breath_cone_spikor"));
    }

    @Test
    @DisplayName("it survives being written out and read back")
    void itPersists() {
        Species s = bare().withAbilityPack("breath_cone_spikor").withAbilityPack("impulse_spikor");
        Species back = Species.fromJson(s.toJson());
        assertEquals(s.abilityPacks(), back.abilityPacks(),
                "an ability that does not survive a reload is not a property of the creature");
    }

    @Test
    @DisplayName("a species file written before this existed still loads")
    void oldFilesLoad() {
        Species old = bare();
        assertFalse(old.toJson().has("abilityPacks"), "nothing is written when there is nothing");
        assertTrue(Species.fromJson(old.toJson()).abilityPacks().isEmpty());
    }
}
