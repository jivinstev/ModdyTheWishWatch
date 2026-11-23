package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import com.google.gson.JsonParser;
import com.moddingfromamod.mob.PetBehaviour;
import com.moddingfromamod.species.Species.Base;
import com.moddingfromamod.species.Species.Power;
import com.moddingfromamod.vanilla.RigInstinct;
import com.moddingfromamod.vanilla.SpeciesOnRig;

import org.junit.jupiter.api.Test;

/**
 * the instinct → behaviour table, pinned in both directions so an intentional
 * companion never silently stops following and an unintentional one never starts.
 */
class InstinctTableTest {

    @Test
    void onlyACompanionFollows() {
        for (Base b : Base.values()) {
            PetBehaviour pet = SpeciesOnRig.behaviour(b, Set.of(Power.FRIENDLY));
            assertEquals(b == Base.COMPANION, Boolean.TRUE.equals(pet.follow()),
                    b + (b == Base.COMPANION ? " must follow" : " must NOT follow"));
        }
    }

    @Test
    void friendlyAloneIsHarmlessAndStaysPut() {
        PetBehaviour pet = SpeciesOnRig.behaviour(Base.WILD, Set.of(Power.FRIENDLY));
        assertEquals(Boolean.TRUE, pet.friendly(), "friendly never targets a player");
        assertFalse(Boolean.TRUE.equals(pet.follow()), "friendly without companion must not follow");
        assertFalse(RigInstinct.of(Base.WILD, Set.of(Power.FRIENDLY)).huntsPlayers());
    }

    @Test
    void aCompanionIsAlsoFriendly() {
        assertEquals(Boolean.TRUE, SpeciesOnRig.behaviour(Base.COMPANION, Set.of()).friendly());
    }

    @Test
    void anUnstatedInstinctIsWildNeverCompanion() {
        Species sp = Species.fromJson(JsonParser.parseString("{\"id\":\"x\"}").getAsJsonObject());
        assertEquals(Base.WILD, sp.base(), "the default instinct must not be companion (#944)");
        assertFalse(Boolean.TRUE.equals(SpeciesOnRig.behaviour(sp).follow()));
    }

    // ── what the tools say back ─────────────────────────────────────────────────────────────

    private static Species sp(Base b, Power... powers) {
        return Species.fromJson(JsonParser.parseString("{\"id\":\"t\",\"name\":\"Tester\",\"base\":\""
                + b.name().toLowerCase() + "\",\"powers\":[" + String.join(",",
                java.util.Arrays.stream(powers).map(p -> "\"" + p.name().toLowerCase() + "\"").toList())
                + "]}").getAsJsonObject());
    }

    @Test
    void aCompanionIsToldItWillFollowAndACrowdLouder() {
        List<String> one = InstinctNotes.forSpecies(sp(Base.COMPANION, Power.FRIENDLY), 1);
        assertEquals(1, one.size());
        assertTrue(one.get(0).contains("FOLLOW him everywhere") && one.get(0).contains("'wild'"));
        List<String> crowd = InstinctNotes.forSpecies(sp(Base.COMPANION, Power.FRIENDLY), 4);
        assertTrue(crowd.stream().anyMatch(l -> l.contains("4 creatures")));
    }

    @Test
    void aCreatureThatWillAttackHimIsSaidToAndAHarmlessOneIsNot() {
        assertTrue(InstinctNotes.forSpecies(sp(Base.WILD), 1).stream()
                .anyMatch(l -> l.contains("ATTACK")), "wild with no friendly attacks him");
        assertTrue(InstinctNotes.forSpecies(sp(Base.WILD, Power.FRIENDLY), 1).isEmpty(),
                "wild + friendly is the quiet default: nothing to warn about");
        assertTrue(InstinctNotes.forSpecies(sp(Base.GUARDIAN), 1).isEmpty(),
                "a guardian never goes for a player");
        assertTrue(InstinctNotes.forSpecies(sp(Base.COWARD), 1).isEmpty());
        assertTrue(InstinctNotes.forSpecies(sp(Base.HUNTER), 1).get(0).contains("hunter"));
    }
}
