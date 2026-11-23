package com.moddingfromamod.perf;

import java.util.Arrays;

/**
 * <b>A minute of server ticks, summarised</b> — the pure half of {@link TickTimer}. Holds each
 * tick's duration and answers the mean, the 95th percentile, the worst, and how many went over
 * the 50 ms a tick is allowed (a tick over that is one a player can feel).
 */
public final class TickStats {

    /** One tick's budget: 20 ticks a second. */
    public static final double BUDGET_MS = 50.0;

    private final double[] ms;
    private int n;

    public TickStats(int capacity) {
        this.ms = new double[capacity];
    }

    /** Record one tick; false once full (the caller reports and resets). */
    public boolean add(double tickMs) {
        if (n < ms.length) {
            ms[n++] = tickMs;
        }
        return n < ms.length;
    }

    public int count() {
        return n;
    }

    public void reset() {
        n = 0;
    }

    /** One line: {@code mean 4.1 ms, p95 9.8, max 61.0, 2 over 50 ms}. */
    public String summary() {
        if (n == 0) {
            return "no ticks";
        }
        double[] s = Arrays.copyOf(ms, n);
        Arrays.sort(s);
        double sum = 0;
        int over = 0;
        for (double v : s) {
            sum += v;
            if (v > BUDGET_MS) {
                over++;
            }
        }
        double p95 = s[Math.min(n - 1, (int) Math.ceil(n * 0.95) - 1)];
        return String.format(java.util.Locale.ROOT, "mean %.1f ms, p95 %.1f, max %.1f, %d over %.0f ms",
                sum / n, p95, s[n - 1], over, BUDGET_MS);
    }

    /** Ticks over budget. */
    public int over() {
        int over = 0;
        for (int i = 0; i < n; i++) {
            if (ms[i] > BUDGET_MS) {
                over++;
            }
        }
        return over;
    }
}
