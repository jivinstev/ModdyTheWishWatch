package com.moddingfromamod.runtime;

import java.util.Locale;

/**
 * <b>The A/B lever for the fidelity work</b>.
 *
 * <p>{@code -Ptestprops="fidelity=off"} puts the sculptor back exactly where it was this morning:
 * axis-aligned boxes, one flat colour each, no part-kits. That is the only honest "arm A" — an arm
 * built by hobbling the new code halfway would measure neither the old behaviour nor the new one.
 *
 * <h2>Why it must switch the SCHEMA, not just the parser</h2>
 *
 * <p>Silently discarding rotation while still advertising it in the tool description would tell the
 * agent to tilt things and then throw the tilt away. The agent would spend tokens on a field that
 * does nothing, and arm A would be slower than the real baseline while producing the same geometry —
 * an A/B that is unfair in both directions at once. So when this is off, {@code sculpt_creature}
 * does not mention rotation, patterns or kits at all, and the agent has no way to know they exist.
 *
 * <p>Default is <b>on</b>: this is a test lever, not a feature flag, and a shipped build must never
 * quietly be the old one.
 */
public final class FidelityFlags {

    private FidelityFlags() {
    }

    private static final String KEY =
            com.moddingfromamod.ModdingFromAModMod.MODID + ".fidelity";

    /** Are rotation, texture patterns and part-kits available to the agent? */
    public static boolean enabled() {
        String v = System.getProperty(KEY, "on");
        return !"off".equalsIgnoreCase(v.trim()) && !"false".equalsIgnoreCase(v.trim());
    }

    /** For a report header, so a run's arm is recorded with its results rather than remembered. */
    public static String describe() {
        return enabled() ? "fidelity=on (rotation + patterns + kits)"
                : "fidelity=OFF (axis-aligned flat boxes only — the pre-#70 baseline)";
    }

    /** Test-only: force the flag and return the previous raw value so a test can restore it. */
    public static String forceForTest(String value) {
        String before = System.getProperty(KEY);
        if (value == null) {
            System.clearProperty(KEY);
        } else {
            System.setProperty(KEY, value.toLowerCase(Locale.ROOT));
        }
        return before;
    }
}
