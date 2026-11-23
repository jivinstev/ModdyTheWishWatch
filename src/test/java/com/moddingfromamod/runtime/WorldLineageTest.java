package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Can the records tell a revision from a fresh start?</b>
 *
 * <p>Until they could, one analysis said Spikor "got worse over six revisions" and was filed as a
 * guardrail failure. It was four independent creations in four different worlds — quality
 * flat within each world, changing only at the boundaries — and since species are per-world, the
 * agent had never seen the earlier one to iterate on it.
 *
 * <p>That conclusion was not careless. It was the <i>only</i> reading the data supported, because
 * neither the action log nor the provenance commits recorded which world an action happened in. A
 * publisher's linear git history makes independent creations look like successive revisions by
 * construction.
 *
 * <p>So the property under test is not "the field exists" but the two distinctions an analysis has
 * to draw: <b>different worlds are different lineages</b>, and <b>the same world revisited is a
 * different sitting</b>.
 */
class WorldLineageTest {

    @AfterEach
    void tearDown() {
        WorldPaths.close();
    }

    @Test
    void twoWorldsAreNeverOneLineage() {
        WorldPaths.openAt(Path.of("/saves/modwatch-daddus"));
        String worldA = WorldPaths.worldName();
        String sessionA = WorldPaths.sessionId();

        WorldPaths.openAt(Path.of("/saves/daddus moddy3"));

        assertEquals("modwatch-daddus", worldA);
        assertEquals("daddus moddy3", WorldPaths.worldName(),
                "the world must be identifiable by the name a person would recognise, or a report "
                        + "cannot say WHERE two sculpts of the same creature happened");
        assertNotEquals(sessionA, WorldPaths.sessionId(),
                "two different worlds shared a session id, so an analysis would still splice them "
                        + "into one revision series — which is exactly how #19 was misfiled");
    }

    @Test
    void reopeningTheSameWorldIsANewSitting() {
        // The subtle half. Spikor v4-v6 and v7 were 3.4 hours and a world apart; v1 and v2 were 58
        // hours apart. Neither is a revision series, and a per-WORLD id alone would still merge the
        // first three of those into one.
        WorldPaths.openAt(Path.of("/saves/daddus moddy world"));
        String first = WorldPaths.sessionId();
        WorldPaths.close();
        WorldPaths.openAt(Path.of("/saves/daddus moddy world"));

        assertEquals("daddus moddy world", WorldPaths.worldName(), "same world, so same name");
        assertNotEquals(first, WorldPaths.sessionId(),
                "re-opening one world reused its session id, so two separate sittings would read "
                        + "as one continuous piece of work");
    }

    /**
     * <b>The shape the GAME actually hands over — which is not the shape the tests above use.</b>
     *
     * <p>Every test here called {@code openAt} with a tidy path, so every one of them passed while
     * the real thing was broken. {@code open(server)} asks vanilla for
     * {@code getWorldPath(LevelResource.ROOT)}, and ROOT is literally {@code "."} — so the path that
     * arrives ends in a {@code "."} component and {@code getFileName()} returned exactly that.
     *
     * <p>The result: <b>every record ever written said {@code world="."}</b>. Measured on one
     * afternoon of logs — 14 distinct sittings, 14 identical world names. The field that exists so
     * lineage is a fact rather than an inference was a constant, and the analysis it was built
     * to make possible could not have been redone with it.
     *
     * <p>The lesson is about the seam, not the path handling: {@code openAt} was introduced as "a
     * seam so the isolation rules can be unit-tested without booting two servers", and it skipped
     * the one transformation that broke. A seam that bypasses the caller's own argument shape tests
     * the seam.
     */
    @Test
    void theWorldPathTheServerHandsOverEndsInADotAndIsStillNamed() {
        // Exactly what getWorldPath(LevelResource.ROOT) produces: <world>/.
        WorldPaths.openAt(Path.of("/saves/daddus moddy3/."));
        assertEquals("daddus moddy3", WorldPaths.worldName(),
                "the world path the game really hands over ends in a '.' component, and the name "
                        + "came back as \".\" — so every logged action claimed the same world and "
                        + "no analysis could tell two worlds apart");
    }

    /**
     * <b>The dedicated server, where the world path is nothing but a dot.</b>
     *
     * <p>A dedicated server runs INSIDE its world directory, so ROOT resolves to bare {@code "."}.
     * Normalising that relatively yields an <i>empty</i> path with no name at all, which is why the
     * fix has to go absolute first. Every GameTest in this repo runs on a dedicated server, so
     * without this the whole headless corpus keeps writing an unusable world field.
     */
    @Test
    void aDedicatedServerRunningInsideItsWorldStillNamesIt() {
        String name = null;
        try {
            WorldPaths.openAt(Path.of("."));
            name = WorldPaths.worldName();
        } finally {
            WorldPaths.close();
        }
        assertNotNull(name, "a bare '.' world path produced no name at all");
        assertNotEquals(".", name,
                "the dedicated server's world is still called '.', so every headless record is "
                        + "stamped with a name that identifies nothing");
        assertFalse(name.isBlank(), "the name normalised away to nothing");
    }

    @Test
    void theTitleScreenClaimsNothing() {
        // Omitted, not the string "null": a consumer must be able to tell "no world open" from a
        // world genuinely named that, and a stamp that lies is worse than a missing one.
        WorldPaths.close();
        assertNull(WorldPaths.worldName());
        assertNull(WorldPaths.sessionId());
    }
}
