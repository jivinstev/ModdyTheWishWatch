package com.moddingfromamod.safety;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Does the off-switch switch anything off?</b>
 *
 * <p>The {@link com.moddingfromamod.agent.EffectCatalogueArmTest} pre-flight, for the arm this
 * experiment turns on. If the control arm silently keeps grading, both arms are identical, the run
 * reports "no measurable effect", and the conclusion is about the switch rather than about the
 * layer. That is not hypothetical here either: the first version of a driver script in this repo
 * passed its switch as a variable the launcher does not read, and the whole run would have been
 * control-versus-control.
 *
 * <p>It also pins the OTHER half, which is easier to get wrong: every value is read FRESH. A field
 * initialised once at class load would measure the default in every test that tried to change it,
 * and every GameTest sets its properties after class loading.
 */
class ChildSafetyArmTest {

    private static final String MODE = ModdingFromAModMod.MODID + ".childsafety";
    private static final String STRICTNESS = ModdingFromAModMod.MODID + ".childSafetyStrictness";
    private static final String UNAVAILABLE = ModdingFromAModMod.MODID + ".childSafetyUnavailable";
    private static final String BUDGET = ModdingFromAModMod.MODID + ".childSafetyInputBudgetMs";
    private static final String MODEL = ModdingFromAModMod.MODID + ".childSafetyModel";
    private static final String EFFORT = ModdingFromAModMod.MODID + ".childSafetyEffort";
    private static final String PLAN_MODEL = ModdingFromAModMod.MODID + ".childSafetyPlanModel";
    private static final String PLAN_EFFORT = ModdingFromAModMod.MODID + ".childSafetyPlanEffort";

    private static final String[] ALL = {MODE, STRICTNESS, UNAVAILABLE, BUDGET, MODEL, EFFORT,
            PLAN_MODEL, PLAN_EFFORT};

    @BeforeEach
    @AfterEach
    void clean() {
        for (String k : ALL) {
            System.clearProperty(k);
        }
        RuntimeConfig.invalidate();
    }

    private static void set(String key, String value) {
        System.setProperty(key, value);
        RuntimeConfig.invalidate();
    }

    @Test
    void onIsTheDefault() {
        // docs/CHILD_SAFETY_PLAN.md §8: shadow shipped first, so a base rate existed before
        // anything was blocked; the default was flipped to ON on 2026-09-11 once the shadow
        // arm had measured 0.0% false positives on 84 real typed wishes.
        // This test used to pin SHADOW and was rewritten in the same commit as the flip —
        // deliberately a visible edit, so the default can never move quietly in either direction.
        assertEquals(ChildSafetyConfig.Mode.ON, ChildSafetyConfig.mode());
        assertTrue(ChildSafetyConfig.grading());
        assertTrue(ChildSafetyConfig.enforcing());
    }

    @Test
    void onIsTheEnforcingArm() {
        set(MODE, "on");
        assertEquals(ChildSafetyConfig.Mode.ON, ChildSafetyConfig.mode());
        assertTrue(ChildSafetyConfig.grading());
        assertTrue(ChildSafetyConfig.enforcing());
    }

    @Test
    void shadowGradesEverythingAndActsOnNothing() {
        // §8: shadow is the first thing that ships, so a base rate exists before anything is
        // blocked. Both halves matter — a shadow mode that stopped GRADING would produce no base
        // rate at all, which is the whole reason it exists.
        set(MODE, "shadow");
        assertEquals(ChildSafetyConfig.Mode.SHADOW, ChildSafetyConfig.mode());
        assertTrue(ChildSafetyConfig.grading(), "shadow must still grade");
        assertFalse(ChildSafetyConfig.enforcing(), "shadow must never act");
    }

    @Test
    void theControlArmDoesNotGradeAtAll() {
        set(MODE, "off");
        assertEquals(ChildSafetyConfig.Mode.OFF, ChildSafetyConfig.mode());
        assertFalse(ChildSafetyConfig.grading(),
                "the control arm still grades, so both arms cost the same ~10s and ~$0.006 and the "
                        + "A/B is measuring the enforcement alone while claiming to measure the "
                        + "layer");
        assertFalse(ChildSafetyConfig.enforcing());
    }

    @Test
    void anyOtherValueIsTheDefaultNeverOff() {
        // A typo in a harness must fail toward the arm we can detect from the data (every record
        // carries the mode), never toward silently disabling a child-safety layer.
        for (String typo : new String[]{"", " ", "ON!", "yes", "disabled", "0", "nope", "øff"}) {
            set(MODE, typo);
            assertEquals(ChildSafetyConfig.Mode.ON, ChildSafetyConfig.mode(),
                    "the value \"" + typo + "\" did not fall back to the default");
            assertTrue(ChildSafetyConfig.grading(), "the value \"" + typo + "\" turned grading off");
        }
    }

    @Test
    void theModeIsReadFreshEveryTime() {
        assertEquals(ChildSafetyConfig.Mode.ON, ChildSafetyConfig.mode());
        set(MODE, "off");
        assertEquals(ChildSafetyConfig.Mode.OFF, ChildSafetyConfig.mode(),
                "cached in a static: a GameTest sets its property after class loading, so every "
                        + "test that tried to turn this off would have measured the default");
        System.clearProperty(MODE);
        RuntimeConfig.invalidate();
        assertEquals(ChildSafetyConfig.Mode.ON, ChildSafetyConfig.mode());
    }

    @Test
    void theStrictnessDialDefaultsToStandardAndIgnoresRubbish() {
        assertEquals(SafetyVerdict.Strictness.STANDARD, ChildSafetyConfig.strictness());
        set(STRICTNESS, "strict");
        assertEquals(SafetyVerdict.Strictness.STRICT, ChildSafetyConfig.strictness());
        set(STRICTNESS, "RELAXED");
        assertEquals(SafetyVerdict.Strictness.RELAXED, ChildSafetyConfig.strictness());
        set(STRICTNESS, "very strict indeed");
        assertEquals(SafetyVerdict.Strictness.STANDARD, ChildSafetyConfig.strictness());
    }

    @Test
    void unavailableIsClosedUnlessSomebodyAsksForOpen() {
        // D8 → closed. Pinned so the default cannot move quietly back.
        // As a REAL run: plain JUnit counts as a test run, where unset means open (below).
        String was = System.getProperty(com.moddingfromamod.RunContext.FORCE_REAL_RUN);
        try {
            set(com.moddingfromamod.RunContext.FORCE_REAL_RUN, "true");
            assertTrue(ChildSafetyConfig.unavailableClosed(), "closed is the DEFAULT");
            set(UNAVAILABLE, "open");
            assertFalse(ChildSafetyConfig.unavailableClosed());
            set(UNAVAILABLE, "opne");
            assertTrue(ChildSafetyConfig.unavailableClosed(),
                    "only the documented word opens it: a typo in a safety switch must fail CLOSED");
        } finally {
            if (was == null) {
                System.clearProperty(com.moddingfromamod.RunContext.FORCE_REAL_RUN);
            } else {
                System.setProperty(com.moddingfromamod.RunContext.FORCE_REAL_RUN, was);
            }
            RuntimeConfig.invalidate();
        }
    }

    @Test
    void anUnsetDialIsOpenOnlyInsideAGateAndAnExplicitOneIsObeyedThere() {
        // A gate has no grader, so every grade there is unavailable: unset must not refuse every
        // wish in every GameTest (ModdyTired.armed()'s precedent). Explicit still wins inside one.
        // Plain JUnit is itself a test run (RunContext), so no property is needed to be "in" one.
        assertTrue(com.moddingfromamod.RunContext.isTestRun(), "this fixture assumes a test run");
        assertFalse(ChildSafetyConfig.unavailableClosed(), "unset, in a test run: open");
        set(UNAVAILABLE, "closed");
        assertTrue(ChildSafetyConfig.unavailableClosed(), "explicit closed, in a test run: closed");
    }

    @Test
    void theBudgetIsClampedRatherThanObeyedOrRejected() {
        assertEquals(ChildSafetyConfig.DEFAULT_INPUT_BUDGET_MS, ChildSafetyConfig.inputBudgetMs());
        set(BUDGET, "5000");
        assertEquals(5000L, ChildSafetyConfig.inputBudgetMs());
        set(BUDGET, "0");
        assertEquals(ChildSafetyConfig.MIN_INPUT_BUDGET_MS, ChildSafetyConfig.inputBudgetMs(),
                "a zero budget makes the layer permanently UNAVAILABLE while looking configured");
        set(BUDGET, "999999999");
        assertEquals(ChildSafetyConfig.MAX_INPUT_BUDGET_MS, ChildSafetyConfig.inputBudgetMs(),
                "past two minutes the wish is abandoned by the MCP transport while the grader is "
                        + "still being politely waited on");
        set(BUDGET, "twenty seconds");
        assertEquals(ChildSafetyConfig.DEFAULT_INPUT_BUDGET_MS, ChildSafetyConfig.inputBudgetMs());
    }

    @Test
    void theInputGraderIsEmberWithHaiku55AtHighAsItsFallback() {
        // (docs/EMBER_GRADERS.md): the input grader moved to Muse Ember 1.0
        // (its forked prompt, the text framed, at low), falling back to Haiku 5.5 at --effort high,
        // which it was on since round 3 of its tuning. PINNED ids, never an alias.
        // DEFAULT_EFFORT is now the Haiku fallback's. tools/test-safety-arm.py reads these constants
        // to keep the arm in step.
        assertEquals("muse:muse-ember-1.0", ChildSafetyConfig.DEFAULT_MODEL);
        assertEquals("high", ChildSafetyConfig.DEFAULT_EFFORT);
        assertEquals("muse:muse-ember-1.0", ChildSafetyConfig.model());
        assertEquals("high", ChildSafetyConfig.effort());
        set(MODEL, "sonnet");
        assertEquals("sonnet", ChildSafetyConfig.model());
        set(MODEL, "   ");
        assertEquals("muse:muse-ember-1.0", ChildSafetyConfig.model());
        set(MODEL, "claude-haiku-5-5");
        assertEquals("claude-haiku-5-5", ChildSafetyConfig.model(), "Haiku 5.5 is one setting away");
        set(EFFORT, "medium");
        assertEquals("medium", ChildSafetyConfig.effort());
        set(EFFORT, "none");
        assertNull(ChildSafetyConfig.effort(), "`none` passes no --effort flag at all");
        set(MODEL, "claude-haiku-4-5-20251001");
        assertEquals("claude-haiku-4-5-20251001", ChildSafetyConfig.model(),
                "the 4.5 grader stays one setting away, as the rollback");
    }

    @Test
    void thePlanGraderIsPinnedToEmberAtLowAndHasItsOwnKnobs() {
        // docs/EMBER_GRADERS.md: the plan grader moved to Muse Ember 1.0 through
        // Muse Code at low, falling back to Haiku 5.5 when Muse gives no answer. Before that, its tuning
        // (2026-10-21) had it on Haiku 5.5 at low. It does NOT follow child_safety_model: the input
        // grader is measured and adopted separately. tools/test-safety-plan-arm.py reads these
        // constants to keep the plan arm in step.
        assertEquals("muse:muse-ember-1.0", ChildSafetyConfig.DEFAULT_PLAN_MODEL);
        assertEquals("low", ChildSafetyConfig.DEFAULT_PLAN_EFFORT);
        assertEquals("muse:muse-ember-1.0", ChildSafetyConfig.planModel());
        assertEquals("low", ChildSafetyConfig.planEffort());
        set(MODEL, "sonnet");
        assertEquals("muse:muse-ember-1.0", ChildSafetyConfig.planModel(),
                "child_safety_model moves the input grader only");
        set(EFFORT, "high");
        assertEquals("low", ChildSafetyConfig.planEffort(),
                "child_safety_effort moves the input grader only");
        set(PLAN_MODEL, "claude-haiku-4-5-20251001");
        assertEquals("claude-haiku-4-5-20251001", ChildSafetyConfig.planModel());
        set(PLAN_EFFORT, "none");
        assertNull(ChildSafetyConfig.planEffort(), "`none` passes no --effort flag at all");
    }

    @Test
    void theLogLabelsMatchTheKeysAReportWillGroupBy() {
        assertEquals("on", ChildSafetyConfig.modeLabel(), "the shipped default");
        set(MODE, "off");
        assertEquals("off", ChildSafetyConfig.modeLabel());
        assertEquals("standard", ChildSafetyConfig.strictnessLabel());
        set(MODE, "shadow");
        assertEquals("shadow", ChildSafetyConfig.modeLabel());
    }
}
