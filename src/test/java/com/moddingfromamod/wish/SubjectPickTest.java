package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** follow-up: the creature framed is this wish's, not the one nearest wherever the camera parked. */
class SubjectPickTest {

    private static final UUID MINE = UUID.randomUUID();
    private static final UUID OLD = UUID.randomUUID();

    private static List<SubjectPick.Candidate> two() {
        return List.of(
                new SubjectPick.Candidate(OLD, 3, 64, 3, 9),          // beside the camera
                new SubjectPick.Candidate(MINE, 300, 64, 300, 180000)); // at the wish, far away
    }

    @Test
    void theOneThisWishSpawnedWins() {
        assertEquals(1, SubjectPick.pick(two(), Set.of(MINE), List.of()));
    }

    @Test
    void elseTheOneAtTheWishsSpot() {
        assertEquals(1, SubjectPick.pick(two(), Set.of(), List.of(new double[] { 302, 64, 298 })));
    }

    @Test
    void elseTheNearestAsBefore() {
        assertEquals(0, SubjectPick.pick(two(), Set.of(), List.of()));
        assertEquals(0, SubjectPick.pick(two(), Set.of(UUID.randomUUID()),
                List.of(new double[] { 5000, 64, 5000 })), "a spot nowhere near either");
        assertEquals(-1, SubjectPick.pick(List.of(), Set.of(MINE), List.of()));
    }
}
