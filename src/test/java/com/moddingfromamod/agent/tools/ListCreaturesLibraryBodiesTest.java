package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@code list_creatures} names the library's ready-made bodies, so the agent can start a
 * variant from one instead of sculpting from nothing.
 */
class ListCreaturesLibraryBodiesTest {

    @Test
    void namesEveryLibraryBodyNotAlreadyDesignedHereAndHowToUseIt() {
        String out = ListCreaturesTool.libraryBodies(List.of("wolf", "cat", "pet_dragon"), Set.of("pet_dragon"));
        assertTrue(out.contains("Ready-made BODIES in the library (not yet in this world): cat, wolf."), out);
        assertTrue(out.contains("sculpt_creature start_from '<key>'"), out);
        assertTrue(out.contains("wishmob spawn <key>"), out);
    }

    @Test
    void saysNothingWhenTheLibraryHasNoNewBody() {
        assertEquals("", ListCreaturesTool.libraryBodies(List.of("pet_dragon"), Set.of("pet_dragon")));
        assertEquals("", ListCreaturesTool.libraryBodies(List.of(), Set.of()));
    }

    @Test
    void aDesignedCreatureShadowsALibraryBodyWhateverItsCase() {
        assertEquals("", ListCreaturesTool.libraryBodies(List.of("Wolf"), Set.of("wolf")));
    }
}
