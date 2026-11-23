package com.moddingfromamod.agent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * <b>Which program runs Spark</b> — the per-world {@code agent.sparkHarness} knob (owner
 * 2026-11-16).
 *
 * <ul>
 *   <li>{@code claude-cli} (the DEFAULT, production): whenever the shim's arm is the
 *       API-key fallback (a {@code spark-only} world, or Wonder unavailable), it execs the real Claude
 *       CLI redirected to api.meta.ai.</li>
 *   <li>{@code muse-code}: the same worker on {@code muse exec --api-key-stdin} with the lockdown Wonder
 *       runs under. Even then, a Muse Code that will not start falls back to the Claude CLI.</li>
 * </ul>
 *
 * <p>Off by default on purpose: Wonder already runs on Muse Code, so keeping Spark on the Claude CLI
 * means a broken Muse Code update cannot take both arms down. A knob, not a release, turns it on, and
 * its unit tests keep the off path from going stale (docs/COMPAT_SHIMS.md on switches nobody runs).
 *
 * <p>The value reaches {@code tools/muse-cli-shim.py} as one environment variable on each worker's
 * launch, so a change takes effect at the NEXT worker launch and never mid-wish. Any other CLI ignores
 * it. Pure: the knob and the boot property arrive as lookups ({@code SparkHarnessTest}).
 */
public final class SparkHarness {

    /** The runtime knob. */
    public static final String KNOB = "agent.sparkHarness";
    /** The boot-time fallback when the knob is unset: {@code -Dmoddingfromamod.sparkHarness}. */
    public static final String PROPERTY = "moddingfromamod.sparkHarness";
    /** What a worker's environment carries; {@code tools/muse-cli-shim.py} reads the same name. */
    public static final String ENV = "MODDY_SPARK_HARNESS";

    public static final String CLAUDE_CLI = "claude-cli";
    public static final String MUSE_CODE = "muse-code";
    public static final List<String> CHOICES = List.of(CLAUDE_CLI, MUSE_CODE);
    /** The default (2026-11-16): Spark stays on the Claude CLI. */
    public static final String DEFAULT = CLAUDE_CLI;

    private SparkHarness() {
    }

    /** The knob, else the boot property, else {@link #DEFAULT}; an unrecognised value is the default. */
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
    public static void applyTo(Map<String, String> env, String harness) {
        env.put(ENV, harness);
    }
}
