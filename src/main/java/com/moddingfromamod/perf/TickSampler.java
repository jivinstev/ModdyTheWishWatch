package com.moddingfromamod.perf;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Where a slow tick's time went</b>, by sampling the server thread while the tick is still
 * running — the pure half of {@link TickTimer}'s sampler. The one technique this repo has found that
 * names a blocker (docs/GATE_PERFORMANCE.md: "60 jstack samples of the Server thread name the
 * culprit in about a minute"), built in, because nobody may attach a profiler to a live server.
 *
 * <p>Each sample is reduced to ONE place: the datapack whose function is running if there is one
 * ({@code datapack:<namespace>}), else the first frame of our own code, else the first Minecraft or
 * NeoForge frame. Counted, and the top few printed beside the {@code [tick]} line.
 */
public final class TickSampler {

    private final Map<String, Integer> counts = new ConcurrentHashMap<>();

    /** The one place a sample stands for. Pure. */
    public static String where(StackTraceElement[] stack, String runningPack) {
        if (runningPack != null && !runningPack.isEmpty()) {
            return "datapack:" + runningPack;
        }
        if (stack == null || stack.length == 0) {
            return "?";
        }
        for (StackTraceElement f : stack) {
            if (f.getClassName().startsWith("com.moddingfromamod.")) {
                return frame(f);
            }
        }
        for (StackTraceElement f : stack) {
            String c = f.getClassName();
            if (c.startsWith("net.minecraft.") || c.startsWith("net.neoforged.")) {
                return frame(f);
            }
        }
        return frame(stack[0]);
    }

    private static String frame(StackTraceElement f) {
        String c = f.getClassName();
        return c.substring(c.lastIndexOf('.') + 1) + "." + f.getMethodName();
    }

    public void add(String where) {
        counts.merge(where, 1, Integer::sum);
    }

    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** {@code datapack:x ×41, Foo.bar ×9} — the top {@code n}, most first; "" when empty. */
    public String top(int n) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(n)
                .map(e -> e.getKey() + " ×" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    public void reset() {
        counts.clear();
    }
}
