package com.moddingfromamod.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.gson.JsonParser;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.vanilla.RigInstinct;
import com.moddingfromamod.vanilla.SpeciesOnRig;

import org.junit.jupiter.api.Test;

/**
 * <b>A shipped body behaves like the animal it is</b> (an earlier issue's follow-up).
 *
 * <p>The seven base bodies were harvested from real grants made, when {@code companion}
 * read as "harmless", so every one FOLLOWED whoever was nearest when spawned as it is
 * ({@code wishmob spawn wolf}). A body is not a pet: it gets the instinct its real animal has.
 * Only the starter pet dragon follows, because its starter says "a pet dragon that follows me".
 * (A creature built FROM a body does not inherit any of this; {@code start_from} copies parts.)
 */
class LibraryInstinctsTest {

    static final List<String> PASSIVE = List.of("chicken", "sheep", "turtle", "frog", "snail");

    private static Species shipped(String id) throws Exception {
        try (InputStream in = LibraryInstinctsTest.class.getResourceAsStream(
                "/starter-library/species/" + id + "/species.json")) {
            assertNotNull(in, id + " does not ship");
            return Species.fromJson(JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
        }
    }

    private static boolean follows(Species sp) {
        return Boolean.TRUE.equals(SpeciesOnRig.behaviour(sp).follow());
    }

    @Test
    void onlyThePetDragonFollows() throws Exception {
        assertTrue(follows(shipped("pet_dragon")), "the starter promises a pet dragon that follows");
        for (String id : List.of("wolf", "cat", "chicken", "sheep", "turtle", "frog", "snail")) {
            assertFalse(follows(shipped(id)), id + " is a base body, not a pet: it must not follow");
        }
    }

    @Test
    void theWolfIsNeutralLikeAVanillaWolf() throws Exception {
        Species wolf = shipped("wolf");
        RigInstinct i = RigInstinct.of(wolf);
        assertEquals(Species.Base.GUARDIAN, wolf.base());
        assertFalse(i.huntsPlayers(), "a wolf never goes for a player on sight");
        assertTrue(i.fights(), "but it bites back whoever hurts it");
        assertEquals(4.0, RigInstinct.hit(wolf.damage()), 1e-9,
                "a vanilla wolf bites for 4; a guardian that hits for 0 bites back for nothing");
        assertFalse(wolf.has(Species.Power.FRIENDLY),
                "'friendly' never targets a player, so it would not bite back either");
    }

    @Test
    void theCatKeepsItsDistanceLikeAVanillaCat() throws Exception {
        RigInstinct i = RigInstinct.of(shipped("cat"));
        assertTrue(i.flees(), "a wild cat runs from a player who comes close");
        assertFalse(i.fights());
    }

    @Test
    void theFarmAndPondAnimalsArePassive() throws Exception {
        for (String id : PASSIVE) {
            Species sp = shipped(id);
            RigInstinct i = RigInstinct.of(sp);
            assertEquals(Species.Base.WILD, sp.base(), id);
            assertTrue(sp.has(Species.Power.FRIENDLY), id + " must be harmless");
            assertFalse(i.fights() || i.flees(), id + " is passive: it neither fights nor runs");
        }
    }
}
