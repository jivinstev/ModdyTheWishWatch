package com.moddingfromamod.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** phase 2: the all-triples sampler covers every triple, stays far below the product, and is deterministic. */
class AllTriplesTest {

    @Test
    void everyTripleIsCovered() {
        for (int[] sizes : new int[][] {{2, 2, 2}, {3, 4, 2, 5}, {6, 3, 4, 4, 3, 32}, {2, 2, 2, 2, 2, 2, 2, 2}}) {
            List<int[]> rows = AllTriples.rows(sizes, 7);
            assertEquals(List.of(), AllTriples.missing(sizes, rows), java.util.Arrays.toString(sizes));
            assertEquals(List.of(), AllPairs.missing(sizes, rows), "every pair too");
        }
    }

    @Test
    void itIsSmallerThanTheProductAndThreeFactorsAreTheProduct() {
        int[] houses = {6, 3, 4, 4, 3, 32};
        long product = 6L * 3 * 4 * 4 * 3 * 32;
        int n = AllTriples.rows(houses, 1).size();
        assertTrue(n < product / 10, n + " rows of a " + product + " product");
        assertEquals(24, AllTriples.rows(new int[] {2, 3, 4}, 1).size(), "three factors: all-triples IS the product");
    }

    @Test
    void theSameSeedGivesTheSameRowsAndAnotherSeedOthers() {
        int[] sizes = {4, 3, 5, 2, 3};
        List<String> a = flat(AllTriples.rows(sizes, 2026));
        assertEquals(a, flat(AllTriples.rows(sizes, 2026)));
        assertFalse(a.equals(flat(AllTriples.rows(sizes, 2027))), "a new night's seed is a different set");
    }

    @Test
    void theCheckFindsAMissingTriple() {
        int[] sizes = {2, 2, 2};
        List<int[]> rows = new ArrayList<>(AllTriples.rows(sizes, 1));
        rows.remove(0);
        assertEquals(1, AllTriples.missing(sizes, rows).size());
    }

    static List<String> flat(List<int[]> rows) {
        return rows.stream().map(java.util.Arrays::toString).toList();
    }
}
