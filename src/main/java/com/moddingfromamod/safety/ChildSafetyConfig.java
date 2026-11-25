package com.moddingfromamod.safety;

import java.util.Locale;

import com.moddingfromamod.runtime.RuntimeConfig;

/**
 * <b>The five dials, and their defaults.</b>
 *
 * <p>{@code docs/CHILD_SAFETY_PLAN.md} §8. Three-tier like every machine-local setting — system
 * property, then {@code MODDINGFROMAMOD_*} environment, then
 * {@code ~/.moddingfromamod/config.properties} — and <b>read fresh every time</b>, never cached in
 * a static.
 *
 * <p>That last part is not a style choice: a GameTest sets a property after class loading, so a
 * field initialised once would silently measure the default in every test that tried to turn the
 * feature off. {@code ChildSafetyArmTest} is the pre-flight that proves the switch switches
 * something.
 *
 * <p><b>The default is {@code on}</b> — see {@link #mode()} for the history. (It shipped as
 * {@code shadow} for the first build day so a base rate existed before anything was blocked; it
 * was flipped on 2026-09-11 after the shadow numbers came in — 0.0% false positives on
 * 84 real typed wishes, graded three times each. This paragraph was stale once
 * already, saying {@code on} while the code said {@code shadow}; it is the one kind of stale
 * comment worth fixing on sight, because a reader who trusts it reaches the opposite conclusion
 * from the code.) Every unrecognised value falls back to the default rather than to the safest or
 * the cheapest value, so a typo in a harness produces the arm we can detect from the data instead
 * of a mislabelled run.
 */
public final class ChildSafetyConfig {

    private ChildSafetyConfig() {
    }

    /** What the layer is allowed to do. */
    public enum Mode {
        /** Grade and act. <b>The default</b> since the flip — see {@link #mode()}. */
        ON,
        /**
         * Grade, log, and act on <b>nothing</b>.
         *
         * <p>§8: what shipped FIRST, so a base rate existed before anything was blocked — a layer
         * switched on without one cannot tell over-blocking from a quiet evening. That base rate
         * now exists (252 grades over the 84 real wishes, 0.0% false positives), {@link #ON} was
         * switched on 2026-09-11, and this is the MEASURING arm from here on.
         */
        SHADOW,
        /** The A/B control arm: no grade, no log, no cost. */
        OFF
    }

    /**
     * Milliseconds the dequeue side waits for the input grade before giving up on it.
     *
     * <p>40 s, not 20: CS-S1b measured p95 at 16.0 s across the corpus and <b>33.6 s on the
     * objectionable rows</b> — a 20 s budget dropped the slow half of exactly the grades worth
     * acting on, and at {@code closed} that was a refusal caused by the budget rather than by the
     * wish. The subprocess timeout ({@link SafetyGrader.CliTransport#TIMEOUT_MS}) is a separate
     * number on purpose.
     */
    public static final long DEFAULT_INPUT_BUDGET_MS = 40_000L;

    /**
     * Bounds on that budget.
     *
     * <p>A zero would make the layer permanently UNAVAILABLE while looking configured, and anything
     * past two minutes outlives the MCP handler's own 110 s park — so a wish would be abandoned by
     * the transport while the grader was still politely waited on. Clamped rather than rejected: a
     * silly number is an operator slip, and refusing to start over one is worse than ignoring it.
     */
    public static final long MIN_INPUT_BUDGET_MS = 1_000L;

    /** @see #MIN_INPUT_BUDGET_MS */
    public static final long MAX_INPUT_BUDGET_MS = 120_000L;

    /** Haiku 4.5 by its exact id: the model every safety floor on record was measured on. */
    public static final String HAIKU_45 = "claude-haiku-4-5-20251001";

    /** Haiku 5.5 by its exact id: both graders' model. */
    public static final String HAIKU_55 = "claude-haiku-5-5";

    /**
     * The INPUT grader's model. Small on purpose: the job is classification, not judgement
     * (docs/PATTERNS.md).
     *
     * <p><b>A PINNED id, never the {@code haiku} alias</b>: the alias is resolved by
     * whichever CLI is installed, so it would let a CLI update move the grader with the floors
     * standing still. <b>Haiku 5.5</b>, adopted on its own
     * evidence (docs/HAIKU55_GRADERS.md, Results, round 3): {@code --check} green against the
     * 4.5 floors on the full corpus, 0 false positives on the 84 typed wishes, sexual block
     * recall 100% of grades on the full corpus and on a fresh held-out, cruelty interception on
     * that held-out above 4.5's, and faster than 4.5 through the CLI.
     *
     * <p><b>Muse Ember 1.0 through Muse Code since 2026-11-16</b> (
     * docs/EMBER_GRADERS.md, "The wish-text grader on Ember"), asked with its own forked prompt
     * ({@code input-grader-prompt.ember.md}), the wish text framed, at {@code low}
     * ({@code SafetyGrader.museEffort}). Measured corpus-wide ×3 against Haiku 5.5's run: 0 of 2,973
     * harmless grades stopped (Haiku 2), block recall 117/117 (108/117), redirect interception
     * 100/105 (92/105), 0 unavailable (6), p50 1.3 s; the floors in corpus/safety-floor.json now
     * describe it (Haiku's are kept in corpus/safety-floor-haiku55.json). When Muse Code gives no
     * answer the grade falls back to {@link #HAIKU_55} at {@link #DEFAULT_EFFORT} with Haiku's own
     * prompt and the text as typed. {@code child_safety_model=claude-haiku-5-5} puts Haiku back.
     */
    public static final String DEFAULT_MODEL = com.moddingfromamod.agent.MuseGrade.EMBER;

    /**
     * The input grader's HAIKU {@code --effort}, which since Ember became the default is the
     * effort of the Haiku FALLBACK (an Ember grade always runs at low, {@code SafetyGrader.museEffort}):
     * {@code high}, the effort input-grader-prompt.md was
     * tuned and measured at (round 3). Part of the measured invocation: the same model at
     * another effort is a different grader — at {@code low} 5.5 named the right concern and banded
     * it MAYBE, and {@code high} was the most stable on the DEV probe. {@code none} passes no flag.
     */
    public static final String DEFAULT_EFFORT = "high";

    /**
     * The PLAN grader's model: <b>Muse Ember 1.0 through Muse Code since 2026-11-15</b> (docs/EMBER_GRADERS.md: held-out interception 114/114 against Haiku 5.5's 106/114, 0 false
     * positives on 300 real-plan grades, the right step 100%, p50 1.5 s). When Muse Code gives no
     * answer the grade falls back to {@link #HAIKU_55} ({@code SafetyGrader.CliTransport}).
     * {@code child_safety_plan_model=claude-haiku-5-5} puts it back. Before: <b>Haiku 5.5, pinned</b>.
     * Adopted on its
     * own evidence (docs/HAIKU55_GRADERS.md, Results): 0 false positives on 600 grades of real
     * archived plans, every CLEARLY step blocked in every grade on both held-out halves (4.5: 7 of
     * 13 and 0 of 5 rows), sexual block recall 100%, cruelty interception on the fair held-out
     * equal to 4.5's, and ~2.6× faster through the CLI (p50 3.06 s vs 7.9 s). It has its own knob
     * because it has its own prompt: the input grader stays on {@link #HAIKU_45} until it clears
     * its own bar, and must not be moved by this one.
     */
    public static final String DEFAULT_PLAN_MODEL = com.moddingfromamod.agent.MuseGrade.EMBER;

    /**
     * The plan grader's {@code --effort}: {@code low}, the effort plan-grader-prompt.md was tuned
     * and measured at. The same model at another effort is a different grader.
     */
    public static final String DEFAULT_PLAN_EFFORT = "low";

    /**
     * {@code child_safety} — {@code on} (default) / {@code shadow} / {@code off}.
     *
     * <p><b>The default is ON</b> — a maintainer's decision on 2026-09-11 (docs/CHILD_SAFETY_PLAN.md §8,
     * §12). It shipped as SHADOW for the first build day so a base rate existed before anything
     * was blocked, which is the plan's rule; the shadow arm then measured 0.0% false positives on
     * 84 real typed wishes (252 grades, Wilson upper 1.5%), and the choice was to see where it
     * visibly blocks rather than read logs. {@code shadow} grades every wish
     * and every plan, logs every verdict with a {@code SHADOW_} action, costs exactly what
     * {@code on} costs, and changes nothing a child sees — it survives as the measuring arm;
     * {@code off} is the no-grade control. Any unrecognised value is the default, which is
     * detectable from the data because every record carries the mode.
     */
    public static Mode mode() {
        // The safety.mode knob can choose ON or SHADOW and nothing else: the catalogue does
        // not offer OFF, so the no-grade control arm stays a boot-time decision.
        var knob = com.moddingfromamod.config.ModdySettings.get("safety.mode");
        if (knob.isPresent()) {
            return knob.get().equals("shadow") ? Mode.SHADOW : Mode.ON;
        }
        String v = RuntimeConfig.childSafety();
        if (v == null) {
            return Mode.ON;
        }
        return switch (v.strip().toLowerCase(Locale.ROOT)) {
            case "off", "false" -> Mode.OFF;
            case "shadow" -> Mode.SHADOW;
            default -> Mode.ON;
        };
    }

    /** Is anything graded at all? False only in the control arm. */
    public static boolean grading() {
        return mode() != Mode.OFF;
    }

    /** May a grade change what happens? False in shadow mode, which is the point of it. */
    public static boolean enforcing() {
        return mode() == Mode.ON;
    }

    /** {@code child_safety_strictness} — the parent's dial. Default {@code standard}. */
    public static SafetyVerdict.Strictness strictness() {
        String v = RuntimeConfig.childSafetyStrictness();
        if (v == null) {
            return SafetyVerdict.Strictness.STANDARD;
        }
        return switch (v.strip().toLowerCase(Locale.ROOT)) {
            case "relaxed" -> SafetyVerdict.Strictness.RELAXED;
            case "strict" -> SafetyVerdict.Strictness.STRICT;
            default -> SafetyVerdict.Strictness.STANDARD;
        };
    }

    /**
     * {@code child_safety_unavailable} — what an ungradeable INPUT does. Default {@code closed}.
     *
     * <p>Closed turns a grade that never came back into "the genie is resting"; open lets the wish
     * through on the lexicon alone. §12.2 was a maintainer's decision, taken 2026-09-23 (D8 →
     * closed): nothing a child types reaches the agent unless the grader read it. Only the
     * documented word {@code open} opens it — a typo, or no value at all, stays closed, because the
     * safe failure of a safety switch is the closed one. The lexicon runs either way.
     *
     * <p>⚠ <b>Unset in a GATE means {@code open}</b>, for the reason {@code ModdyTired.armed()} gives
     * and measured the same way: a gate has no grader — and on a runner no {@code claude} CLI — so
     * EVERY grade there is unavailable, and a closed default refused every wish in eight unrelated
     * GameTests the first time this ran. A test about the closed path sets it explicitly
     * ({@code ChildSafetyEnforceGameTest.closedRefusesAnUngradeableWish}). Anything EXPLICIT is
     * obeyed everywhere, and a real run with nothing configured is closed.
     */
    public static boolean unavailableClosed() {
        String v = RuntimeConfig.childSafetyUnavailable();
        if (v == null || v.isBlank()) {
            return !com.moddingfromamod.RunContext.isTestRun();
        }
        return !"open".equalsIgnoreCase(v.strip());
    }

    /** {@code child_safety_input_budget_ms}. Clamped; garbage falls back to the default. */
    public static long inputBudgetMs() {
        String v = RuntimeConfig.childSafetyInputBudgetMs();
        if (v == null || v.isBlank()) {
            return DEFAULT_INPUT_BUDGET_MS;
        }
        try {
            long ms = Long.parseLong(v.strip());
            return Math.max(MIN_INPUT_BUDGET_MS, Math.min(MAX_INPUT_BUDGET_MS, ms));
        } catch (NumberFormatException e) {
            return DEFAULT_INPUT_BUDGET_MS;
        }
    }

    /** {@code child_safety_model} — the INPUT grader. Default {@link #DEFAULT_MODEL}. */
    public static String model() {
        String v = RuntimeConfig.childSafetyModel();
        // the host's helper-model pick, where nobody overrode it; the shipped default otherwise.
        return v == null || v.isBlank() ? com.moddingfromamod.config.MagicianWiring.cheapSpec(DEFAULT_MODEL) : v.strip();
    }

    /**
     * {@code child_safety_effort} — the input grader's {@code --effort}, or null for no flag.
     * Default {@link #DEFAULT_EFFORT}; {@code none} means pass nothing.
     */
    public static String effort() {
        String v = RuntimeConfig.childSafetyEffort();
        String e = v == null || v.isBlank() ? DEFAULT_EFFORT : v.strip();
        return e.equalsIgnoreCase("none") ? null : e;
    }

    /** {@code child_safety_plan_model} — the PLAN grader. Default {@link #DEFAULT_PLAN_MODEL}. */
    public static String planModel() {
        String v = RuntimeConfig.childSafetyPlanModel();
        return v == null || v.isBlank() ? com.moddingfromamod.config.MagicianWiring.cheapSpec(DEFAULT_PLAN_MODEL)
                : v.strip();
    }

    /**
     * {@code child_safety_plan_effort} — the plan grader's {@code --effort}, or null for no flag.
     * Default {@link #DEFAULT_PLAN_EFFORT}; {@code none} means pass nothing (the plan
     * grader is adopted on its own evidence, so its effort is its own knob).
     */
    public static String planEffort() {
        String v = RuntimeConfig.childSafetyPlanEffort();
        String e = v == null || v.isBlank() ? DEFAULT_PLAN_EFFORT : v.strip();
        return e.equalsIgnoreCase("none") ? null : e;
    }

    /** The mode as a log field — {@code on} / {@code shadow} / {@code off}. */
    public static String modeLabel() {
        return mode().name().toLowerCase(Locale.ROOT);
    }

    /** The strictness as a log field. */
    public static String strictnessLabel() {
        return strictness().name().toLowerCase(Locale.ROOT);
    }
}
