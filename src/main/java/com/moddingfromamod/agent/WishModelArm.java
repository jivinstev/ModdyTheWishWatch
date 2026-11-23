package com.moddingfromamod.agent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * <b>Wonder first, or Spark only</b> — the per-world choice behind the {@code agent.wishModel} knob
 *.
 *
 * <ul>
 *   <li>{@code wonder-first} (the DEFAULT): a worker is launched on Muse Wonder through the server's
 *       Muse Code subscription when there is one that works, and on the world's configured model
 *       (Spark 1.2 contributor, through the API key) whenever there is not — no login, no active
 *       subscription, the window spent, the login failing. {@code tools/muse-cli-shim.py} makes that
 *       call at each launch. With no login ever set up it is exactly today's Spark-only behaviour,
 *       which is what makes "on by default" harmless before anyone has logged in.</li>
 *   <li>{@code spark-only}: never the subscription. The shim launches the configured model straight
 *       away, and the server's Muse alerts go quiet (a world that chose Spark has nothing to report).</li>
 * </ul>
 *
 * <p>The value reaches the shim as one environment variable on each worker's launch, so a change
 * takes effect at the NEXT worker launch and never mid-wish. A worker that is not the shim (a Claude
 * model, or a server that launches the real CLI) ignores the variable.
 *
 * <p>Pure: the knob and the boot property arrive as lookups ({@code WishModelArmTest}).
 */
public final class WishModelArm {

    /** The runtime knob. */
    public static final String KNOB = "agent.wishModel";
    /** The boot-time fallback when the knob is unset: {@code -Dmoddingfromamod.wishModel}. */
    public static final String PROPERTY = "moddingfromamod.wishModel";
    /** What a worker's environment carries; {@code tools/muse-cli-shim.py} reads the same name. */
    public static final String ENV = "MODDY_WISH_MODEL_ARM";

    public static final String WONDER_FIRST = "wonder-first";
    public static final String SPARK_ONLY = "spark-only";
    public static final List<String> CHOICES = List.of(WONDER_FIRST, SPARK_ONLY);
    /** The default (2026-10-15). */
    public static final String DEFAULT = WONDER_FIRST;

    private WishModelArm() {
    }

    /**
     * The knob, else the boot property, else {@link #DEFAULT}. An unrecognised value is the default —
     * the knob store already refuses one, so this only guards a hand-typed {@code -D}.
     */
    public static String resolve(UnaryOperator<String> knob, UnaryOperator<String> property) {
        for (String v : new String[]{knob.apply(KNOB), property.apply(PROPERTY)}) {
            String s = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
            if (CHOICES.contains(s)) {
                return s;
            }
        }
        return DEFAULT;
    }

    /** The live answer: the runtime knob store, then the system property. */
    public static String resolved() {
        return resolve(k -> com.moddingfromamod.config.ModdySettings.get(k).orElse(null), System::getProperty);
    }

    /** Hand it to one worker's launch. */
    public static void applyTo(Map<String, String> env, String arm) {
        env.put(ENV, arm);
    }
}
