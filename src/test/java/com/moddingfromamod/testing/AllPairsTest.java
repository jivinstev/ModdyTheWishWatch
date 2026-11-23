package com.moddingfromamod.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The all-pairs sampler really covers every pair, deterministically, and stays small. */
class AllPairsTest {

    @Test
    void everyPairAppearsOnKnownFixtures() {
        int[][] fixtures = {{2, 2}, {3, 3, 3, 3}, {5, 3, 4, 4, 3}, {2, 2, 2, 2, 2, 2, 2, 2, 2, 2}, {7, 1, 6, 2}, {1, 1, 1}, {9}};
        for (int[] sizes : fixtures) {
            List<int[]> rows = AllPairs.rows(sizes, 2222);
            assertEquals(List.of(), AllPairs.missing(sizes, rows), java.util.Arrays.toString(sizes));
            for (int[] r : rows) {
                for (int i = 0; i < sizes.length; i++) {
                    assertTrue(r[i] >= 0 && r[i] < sizes[i], "a value out of range");
                }
            }
        }
    }

    @Test
    void itIsSmallerThanTheProduct() {
        int[] sizes = {5, 3, 4, 4, 3};   // five factors: 720 in the product
        int n = AllPairs.rows(sizes, 2222).size();
        assertTrue(n >= 20, "at least the two largest factors' product: " + n);
        assertTrue(n <= 30, "all-pairs of 5x3x4x4x3 is about 20-25 rows: " + n);
        int[] binary = new int[20];
        java.util.Arrays.fill(binary, 2);   // 2^20 = a million in the product
        assertTrue(AllPairs.rows(binary, 1).size() <= 16, "twenty on/off options pair up in a handful of rows");
    }

    @Test
    void theSameSeedGivesTheSameRows() {
        int[] sizes = {5, 3, 4, 4, 3};
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        AllPairs.rows(sizes, 7).forEach(r -> a.add(java.util.Arrays.toString(r)));
        AllPairs.rows(sizes, 7).forEach(r -> b.add(java.util.Arrays.toString(r)));
        assertEquals(a, b);
    }

    /** The checker fires: a set short of one row is reported missing a pair, so a sampler that covers less is red. */
    @Test
    void theCoverageCheckFires() {
        int[] sizes = {3, 3, 3};
        List<int[]> rows = new ArrayList<>(AllPairs.rows(sizes, 1));
        rows.remove(rows.size() - 1);
        assertFalse(AllPairs.missing(sizes, rows).isEmpty());
    }

    @Test
    void namedValues() {
        List<List<String>> rows = AllPairs.of(List.of(List.of("a", "b"), List.of("x", "y", "z")), 3);
        assertEquals(6, rows.size(), "two factors: all-pairs IS the product");
    }
}
