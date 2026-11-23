package com.moddingfromamod.testing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * <b>An all-triples covering array</b> (phase 2): rows over {@code k} factors such that EVERY triple of values of
 * every three factors appears together in at least one row — {@link AllPairs} one strength up, for the NIGHTLY, where an
 * interaction of three parts (a roof on three floors with the door at the back) is worth the larger set. With three or
 * fewer factors it IS the full product.
 *
 * <p>Greedy, the same way as {@link AllPairs}: each row starts from the first uncovered triple, then fills the other
 * factors one at a time with the value that completes the most still-uncovered triples, the best of several candidates
 * drawn from a fixed-seed {@link Random}. Deterministic for a seed and factor sizes; {@code AllTriplesTest} proves the
 * coverage.
 */
public final class AllTriples {

    private AllTriples() {
    }

    /** Rows of value INDICES, one per factor; factor {@code i} takes values {@code 0..sizes[i]-1}. */
    public static List<int[]> rows(int[] sizes, long seed) {
        int k = sizes.length;
        for (int s : sizes) {
            if (s < 1) {
                throw new IllegalArgumentException("a factor with no values: " + Arrays.toString(sizes));
            }
        }
        if (k < 3) {
            return AllPairs.rows(sizes, seed);
        }
        List<int[]> triples = new ArrayList<>();
        for (int a = 0; a < k; a++) {
            for (int b = a + 1; b < k; b++) {
                for (int c = b + 1; c < k; c++) {
                    triples.add(new int[] {a, b, c});
                }
            }
        }
        BitSet[] uncovered = new BitSet[triples.size()];
        long left = 0;
        for (int t = 0; t < triples.size(); t++) {
            int[] f = triples.get(t);
            int n = sizes[f[0]] * sizes[f[1]] * sizes[f[2]];
            uncovered[t] = new BitSet(n);
            uncovered[t].set(0, n);
            left += n;
        }
        Random rnd = new Random(seed);
        List<int[]> out = new ArrayList<>();
        while (left > 0) {
            int[] best = null;
            int bestGain = -1;
            for (int attempt = 0; attempt < 12; attempt++) {
                int[] row = candidate(sizes, triples, uncovered, rnd);
                int gain = gain(row, sizes, triples, uncovered);
                if (gain > bestGain) {
                    best = row;
                    bestGain = gain;
                }
            }
            for (int t = 0; t < triples.size(); t++) {
                int bit = bit(best, sizes, triples.get(t));
                if (uncovered[t].get(bit)) {
                    uncovered[t].clear(bit);
                    left--;
                }
            }
            out.add(best);
        }
        return out;
    }

    private static int bit(int[] row, int[] sizes, int[] f) {
        return (row[f[0]] * sizes[f[1]] + row[f[1]]) * sizes[f[2]] + row[f[2]];
    }

    private static int[] candidate(int[] sizes, List<int[]> triples, BitSet[] uncovered, Random rnd) {
        int k = sizes.length;
        int[] row = new int[k];
        Arrays.fill(row, -1);
        for (int t = 0; t < triples.size(); t++) {   // seed it with the first uncovered triple: every round makes progress
            int b = uncovered[t].nextSetBit(0);
            if (b >= 0) {
                int[] f = triples.get(t);
                row[f[2]] = b % sizes[f[2]];
                b /= sizes[f[2]];
                row[f[1]] = b % sizes[f[1]];
                row[f[0]] = b / sizes[f[1]];
                break;
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            if (row[i] < 0) {
                order.add(i);
            }
        }
        Collections.shuffle(order, rnd);
        for (int f : order) {
            int bestV = rnd.nextInt(sizes[f]);
            int bestScore = -1;
            int start = rnd.nextInt(sizes[f]);
            for (int n = 0; n < sizes[f]; n++) {
                int v = (start + n) % sizes[f];
                row[f] = v;
                int score = 0;
                for (int t = 0; t < triples.size(); t++) {
                    int[] tr = triples.get(t);
                    if ((tr[0] == f || tr[1] == f || tr[2] == f) && row[tr[0]] >= 0 && row[tr[1]] >= 0 && row[tr[2]] >= 0
                            && uncovered[t].get(bit(row, sizes, tr))) {
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

    private static int gain(int[] row, int[] sizes, List<int[]> triples, BitSet[] uncovered) {
        int g = 0;
        for (int t = 0; t < triples.size(); t++) {
            if (uncovered[t].get(bit(row, sizes, triples.get(t)))) {
                g++;
            }
        }
        return g;
    }

    /** The triples {@code rows} leave uncovered, as "f0=a f1=b f2=c" (empty when complete). */
    public static List<String> missing(int[] sizes, List<int[]> rows) {
        List<String> out = new ArrayList<>();
        int k = sizes.length;
        for (int a = 0; a < k; a++) {
            for (int b = a + 1; b < k; b++) {
                for (int c = b + 1; c < k; c++) {
                    BitSet seen = new BitSet();
                    for (int[] r : rows) {
                        seen.set((r[a] * sizes[b] + r[b]) * sizes[c] + r[c]);
                    }
                    int n = sizes[a] * sizes[b] * sizes[c];
                    for (int x = seen.nextClearBit(0); x < n; x = seen.nextClearBit(x + 1)) {
                        out.add("f" + a + "=" + x / (sizes[b] * sizes[c]) + " f" + b + "=" + x / sizes[c] % sizes[b] + " f" + c + "=" + x % sizes[c]);
                    }
                }
            }
        }
        return out;
    }
}
