package com.moddingfromamod.testing;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

/**
 * <b>An all-pairs covering array</b>: a small set of rows over {@code k} factors such that EVERY pair of values
 * of every two factors appears together in at least one row. It replaces a test's full product (which grows as the
 * product of the factors' sizes) with a set that grows roughly as the product of the two LARGEST factors times the log
 * of the number of factors. Every single value appears too, since every value is in some pair.
 *
 * <p>Greedy (AETG-style): each new row starts from the uncovered pair that comes first, then fills the other factors one
 * at a time with the value that covers the most still-uncovered pairs, candidate rows drawn from a fixed-seed
 * {@link Random} and the best kept. Deterministic for a given seed and factor sizes. Pure; {@code AllPairsTest} proves the
 * coverage on known fixtures, so a sampler that silently covers less cannot read green.
 */
public final class AllPairs {

    private AllPairs() {
    }

    /** Rows of value INDICES, one per factor; factor {@code i} takes values {@code 0..sizes[i]-1}. */
    public static List<int[]> rows(int[] sizes, long seed) {
        int k = sizes.length;
        for (int s : sizes) {
            if (s < 1) {
                throw new IllegalArgumentException("a factor with no values: " + java.util.Arrays.toString(sizes));
            }
        }
        List<int[]> out = new ArrayList<>();
        if (k == 0) {
            return out;
        }
        if (k == 1) {
            for (int v = 0; v < sizes[0]; v++) {
                out.add(new int[] {v});
            }
            return out;
        }
        // uncovered[i][j]: a bit per (a, b) pair of factor i's value a and factor j's value b, i < j.
        BitSet[][] uncovered = new BitSet[k][k];
        int left = 0;
        for (int i = 0; i < k; i++) {
            for (int j = i + 1; j < k; j++) {
                uncovered[i][j] = new BitSet(sizes[i] * sizes[j]);
                uncovered[i][j].set(0, sizes[i] * sizes[j]);
                left += sizes[i] * sizes[j];
            }
        }
        Random rnd = new Random(seed);
        while (left > 0) {
            int[] best = null;
            int bestGain = -1;
            for (int attempt = 0; attempt < 30; attempt++) {
                int[] row = candidate(sizes, uncovered, rnd);
                int gain = gain(row, uncovered, sizes);
                if (gain > bestGain) {
                    best = row;
                    bestGain = gain;
                }
            }
            for (int i = 0; i < k; i++) {
                for (int j = i + 1; j < k; j++) {
                    int bit = best[i] * sizes[j] + best[j];
                    if (uncovered[i][j].get(bit)) {
                        uncovered[i][j].clear(bit);
                        left--;
                    }
                }
            }
            out.add(best);
        }
        return out;
    }

    private static int[] candidate(int[] sizes, BitSet[][] uncovered, Random rnd) {
        int k = sizes.length;
        int[] row = new int[k];
        java.util.Arrays.fill(row, -1);
        // Seed the row with an uncovered pair: the first one, so every round makes progress.
        outer:
        for (int i = 0; i < k; i++) {
            for (int j = i + 1; j < k; j++) {
                int bit = uncovered[i][j].nextSetBit(0);
                if (bit >= 0) {
                    row[i] = bit / sizes[j];
                    row[j] = bit % sizes[j];
                    break outer;
                }
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            if (row[i] < 0) {
                order.add(i);
            }
        }
        java.util.Collections.shuffle(order, rnd);
        for (int f : order) {
            int bestV = rnd.nextInt(sizes[f]);
            int bestScore = -1;
            int start = rnd.nextInt(sizes[f]);
            for (int n = 0; n < sizes[f]; n++) {
                int v = (start + n) % sizes[f];
                int score = 0;
                for (int g = 0; g < k; g++) {
                    if (g == f || row[g] < 0) {
                        continue;
                    }
                    int i = Math.min(f, g);
                    int j = Math.max(f, g);
                    int a = i == f ? v : row[g];
                    int b = j == f ? v : row[g];
                    if (uncovered[i][j].get(a * sizes[j] + b)) {
                        score++;
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestV = v;
                }
            }
            row[f] = bestV;
        }
        return row;
    }

    private static int gain(int[] row, BitSet[][] uncovered, int[] sizes) {
        int g = 0;
        for (int i = 0; i < row.length; i++) {
            for (int j = i + 1; j < row.length; j++) {
                if (uncovered[i][j].get(row[i] * sizes[j] + row[j])) {
                    g++;
                }
            }
        }
        return g;
    }

    /** The pairs {@code rows} leave uncovered, as "factor i=value a, factor j=value b" (empty when complete). */
    public static List<String> missing(int[] sizes, List<int[]> rows) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++) {
            for (int j = i + 1; j < sizes.length; j++) {
                for (int a = 0; a < sizes[i]; a++) {
                    for (int b = 0; b < sizes[j]; b++) {
                        boolean seen = false;
                        for (int[] r : rows) {
                            if (r[i] == a && r[j] == b) {
                                seen = true;
                                break;
                            }
                        }
                        if (!seen) {
                            out.add("factor " + i + "=" + a + ", factor " + j + "=" + b);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** {@code rows} over named value lists: each row as the values themselves. */
    public static <T> List<List<T>> of(List<List<T>> factors, long seed) {
        int[] sizes = new int[factors.size()];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = factors.get(i).size();
        }
        List<List<T>> out = new ArrayList<>();
        for (int[] r : rows(sizes, seed)) {
            List<T> row = new ArrayList<>();
            for (int i = 0; i < r.length; i++) {
                row.add(factors.get(i).get(r[i]));
            }
            out.add(row);
        }
        return out;
    }
}
