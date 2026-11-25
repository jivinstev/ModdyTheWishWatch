package com.moddingfromamod.wish;

/**
 * Which way a creature's live-fitted stats moved when a world's limits changed ({@link LiveFit#refit}). Pure.
 */
final class RefitWords {

    private RefitWords() {
    }

    /**
     * -1 when any stat fell, 1 when none fell and one rose, 0 when none moved. A stat the creature does not hold (NaN on
     * both sides) never moves.
     */
    static int compare(double[] before, double[] after) {
        boolean up = false;
        for (int i = 0; i < Math.min(before.length, after.length); i++) {
            if (Double.isNaN(before[i]) || Double.isNaN(after[i])) {
                continue;
            }
            if (after[i] < before[i] - 1e-6) {
                return -1;
            }
            up |= after[i] > before[i] + 1e-6;
        }
        return up ? 1 : 0;
    }

    /**
     * A creature's health after its most health went from {@code oldMax} to {@code newMax}: raised, it is as hurt as it
     * was (half its hearts before, half after); lowered or the same, it keeps what it had, up to its new most.
     */
    static double healthAfter(double health, double oldMax, double newMax) {
        if (newMax > oldMax + 1e-6 && oldMax > 0) {
            return Math.min(newMax, health / oldMax * newMax);
        }
        return Math.min(health, newMax);
    }
}
