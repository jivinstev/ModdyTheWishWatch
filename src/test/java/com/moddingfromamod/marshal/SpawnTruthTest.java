package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Gate A: a wish whose spawned creature is standing nowhere is not done. */
class SpawnTruthTest {

    private static WishRun.CreatureSpawned at(String species) {
        return new WishRun.CreatureSpawned(species, UUID.randomUUID());
    }

    @Test
    void aCreatureStandingThereIsNotMissing() {
        assertNull(SpawnTruth.missing(List.of(at("giant_sea_leaper")),
                Set.of("giant_sea_leaper")::contains));
    }

    @Test
    void theSeaCreatureOf855IsMissing() {
        assertEquals("giant_sea_leaper", SpawnTruth.missing(
                List.of(at("giant_sea_leaper"), new WishRun.CreatureSpawned("giant_sea_leaper",
                        null)), s -> false));
    }

    @Test
    void aWishThatSpawnedNothingIsNeverAskedAbout() {
        // A sculpt for a spawn egg, or a design for later: no attempt, nothing to be missing.
        assertNull(SpawnTruth.missing(List.of(), s -> false));
        assertNull(SpawnTruth.missing(null, s -> false));
    }

    @Test
    void theFirstMissingSpeciesIsNamedAndTheNoteSaysSo() {
        String gone = SpawnTruth.missing(List.of(at("dragon"), at("pickle")),
                Set.of("dragon")::contains);
        assertEquals("pickle", gone);
        assertTrue(SpawnTruth.note(gone).contains("'pickle'"));
        assertTrue(SpawnTruth.note(gone).contains("'failed'"));
    }

    @Test
    void theRunRecordsAttemptsInOrderAndIgnoresABlankSpecies() {
        WishRun run = new WishRun(UUID.randomUUID(), UUID.randomUUID(), "make a dragon", 0L);
        run.creatureSpawned("dragon", null);
        run.creatureSpawned(" ", UUID.randomUUID());
        run.creatureSpawned("dragon", UUID.randomUUID());
        assertEquals(2, run.creaturesSpawned().size());
        assertNull(run.creaturesSpawned().get(0).carrier());
    }
}
