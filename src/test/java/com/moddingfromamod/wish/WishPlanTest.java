package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Gate A for the child's progress bar.
 *
 * <p>Everything here is arithmetic a 7-year-old will read off his screen, which is exactly why it is
 * tested rather than eyeballed: "0 of 3", "4 of 3" or a bar that sits at 100% while Moddy is still
 * working are all small wrongnesses that read to a child as broken.
 */
class WishPlanTest {

    private static WishPlan spikor() {
        return WishPlan.of(List.of("Building Spikor", "Giving him fire breath", "Making it huge"));
    }

    @Test
    void aPlanReadsAsStepXofY() {
        WishPlan p = spikor();
        p.startStep(1);
        assertEquals("Building Spikor (1 of 3)", p.progressLabel("..."));
        p.startStep(2);
        assertEquals("Giving him fire breath (2 of 3)", p.progressLabel("..."));
    }

    @Test
    void theBarFillsAsStepsCompleteAndOnlyReachesFullAtTheEnd() {
        WishPlan p = spikor();
        p.startStep(1);
        assertEquals(0f, p.fraction(), 0.001f, "starting step 1 means nothing is done yet");
        p.startStep(2);
        assertEquals(1 / 3f, p.fraction(), 0.001f, "one of three finished");
        p.startStep(3);
        assertEquals(2 / 3f, p.fraction(), 0.001f);
        // A bar that says 100% while Moddy is still working is a lie the child can see. Full is
        // reached only by markComplete, which only finish_building calls — so the bar filling and the child
        // being told "it's ready" are the same event.
        p.markComplete();
        assertEquals(1f, p.fraction(), 0.001f);
        assertTrue(p.isComplete());
        assertTrue(p.progressLabel("...").endsWith("(3 of 3)"), p.progressLabel("..."));
    }

    @Test
    void anAgentThatMiscountsCannotPutNonsenseOnTheScreen() {
        WishPlan p = spikor();
        p.startStep(99);
        assertEquals(3, p.current(), "clamped to the last real step");
        assertTrue(p.progressLabel("...").endsWith("(3 of 3)"), p.progressLabel("..."));
        // Crucially it is NOT treated as finished: only finish_building gets to say that.
        assertFalse(p.isComplete(), "a miscount must not declare the wish finished");

        p.startStep(-5);
        assertEquals(0, p.current(), "and never negative");
        assertEquals("fallback", p.progressLabel("fallback"),
                "before any step starts, the bar shows the caller's own words rather than blank");
    }

    @Test
    void advanceMovesOneStepForAnAgentThatDoesntCount() {
        WishPlan p = spikor();
        p.startStep(1);
        p.advance();
        assertEquals(2, p.current());
        p.advance();
        p.advance();
        p.advance();
        assertEquals(3, p.current(), "advancing past the end stops at the end");
    }

    @Test
    void junkStepsAreDroppedRatherThanRejected() {
        // A slightly shortened plan is far better for the child than no bar at all.
        WishPlan p = WishPlan.of(Arrays.asList("Real step", "", "   ", null, "Another"));
        assertEquals(2, p.size());
        assertEquals(List.of("Real step", "Another"), p.steps());
    }

    @Test
    void anEmptyOrMissingPlanIsNoPlan() {
        assertNull(WishPlan.of(null));
        assertNull(WishPlan.of(List.of()));
        assertNull(WishPlan.of(Arrays.asList("", "  ")));
    }

    @Test
    void aRamblingPlanIsCappedSoTheBarStaysReadable() {
        WishPlan p = WishPlan.of(java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> "step " + i).toList());
        assertEquals(WishPlan.MAX_STEPS, p.size(), "more steps than this means nothing to a child");
    }

    @Test
    void aLongLabelIsTrimmedToFitTheBar() {
        String rambling = "Building an extremely elaborate and very long-winded description of a thing";
        WishPlan p = WishPlan.of(List.of(rambling));
        p.startStep(1);
        assertTrue(p.currentLabel().length() <= WishPlan.MAX_LABEL,
                "was " + p.currentLabel().length() + ": " + p.currentLabel());
    }

    /** the planning placeholder said "Dreaming up your wish (1 of 1)" — a last step. */
    @Test
    void aThinkingPlanShowsNoCount() {
        WishPlan p = WishPlan.thinking();
        assertTrue(p.isThinking());
        assertEquals(WishPlan.THINKING_LABEL, p.progressLabel("fallback"));
        assertFalse(p.progressLabel("fallback").contains(" of "), p.progressLabel("fallback"));
        assertFalse(p.isComplete(), "planning is not the wish's last step");
    }

    /** "(1 of 1)" says nothing on a real one-step plan either. */
    @Test
    void aOneStepPlanShowsNoCount() {
        WishPlan p = WishPlan.of(List.of("Building your castle"));
        p.startStep(1);
        assertFalse(p.isThinking());
        assertEquals("Building your castle", p.progressLabel("..."));
    }
}
