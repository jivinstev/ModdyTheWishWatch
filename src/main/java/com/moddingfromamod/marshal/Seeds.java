package com.moddingfromamod.marshal;

import java.util.Random;

import com.moddingfromamod.ModdingFromAModMod;

/**
 * One place anything sampled gets its randomness, so a failure reproduces.
 *
 * <p>The rule the rest of the marshal follows: <b>never call {@code new Random()} or
 * {@code Math.random()}</b>. A scheduling test that shuffles arrival order, a fake worker whose
 * latency jitters, a tie broken between two equally-near candidates — each is a place where "it
 * failed once and I can't get it back" is the worst possible outcome, because a concurrency test
 * that passes once proves nothing and flakiness is the finding rather than a reason to retry.
 *
 * <p>Seeded from {@code -Dmoddingfromamod.wishseed=N} when given, otherwise from a fixed constant —
 * <b>not</b> from the wall clock. A run that is reproducible only when someone remembered to pass a
 * flag is not reproducible.
 */
public final class Seeds {

    /** {@code -Dmoddingfromamod.wishseed=N} (or {@code -Ptestprops="wishseed=N"}). */
    public static final String PROPERTY = ModdingFromAModMod.MODID + ".wishseed";

    /** Arbitrary, fixed, and written down so a repeat run is byte-identical by default. */
    public static final long DEFAULT_SEED = 0x5EEDL;

    private Seeds() {
    }

    /** The base seed for this JVM. */
    public static long base() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SEED;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_SEED;
        }
    }

    /**
     * A stream named for what it is sampling, so two independent samplers never march in lockstep
     * (which is how a "random" tie-break ends up always picking the same side).
     */
    public static Random stream(String name) {
        return new Random(seedFor(name));
    }

    /** The seed a named stream would use — exposed so a failure can be replayed exactly. */
    public static long seedFor(String name) {
        long h = base();
        if (name != null) {
            for (int i = 0; i < name.length(); i++) {
                h = h * 31 + name.charAt(i);
            }
        }
        return h;
    }
}
