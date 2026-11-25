package com.moddingfromamod.world;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>A world's limits, stated up front</b> ({@link WorldMakes#limits}): the most it allows of each measured number
 * (a {@code MakeFacts.NUMBERS} key → its maximum) for every make, not tied to one, and a cheap {@code stamp} that changes
 * whenever they change, so the core can keep what it worked out for one set of limits until the stamp moves. The
 * limits may differ per player. DATA only: numbers, never words.
 */
public record WorldLimits(Map<String, Double> limits, String stamp) {

    /** No limits: the plain game. */
    public static final WorldLimits NONE = new WorldLimits(Map.of(), "");

    public WorldLimits {
        limits = clean(limits);
        stamp = stamp == null ? "" : stamp;
    }

    /** A copy without null keys, nulls, NaN or infinities, sorted by key. */
    static Map<String, Double> clean(Map<String, Double> in) {
        Map<String, Double> out = new TreeMap<>();
        if (in != null) {
            in.forEach((k, v) -> {
                if (k != null && v != null && !v.isNaN() && !v.isInfinite()) {
                    out.put(k, v);
                }
            });
        }
        return Collections.unmodifiableMap(out);
    }

    /** The tighter of two sets of limits, key by key. */
    public static Map<String, Double> tightest(Map<String, Double> a, Map<String, Double> b) {
        Map<String, Double> out = new TreeMap<>(clean(a));
        clean(b).forEach((k, v) -> out.merge(k, v, Math::min));
        return Collections.unmodifiableMap(out);
    }
}
