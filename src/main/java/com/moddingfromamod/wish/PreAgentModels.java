package com.moddingfromamod.wish;

/**
 * Whether a submitted wish is worth the model calls {@code WishService.submit} starts before any
 * agent sees it: the L1 safety grade, the world-rules grade and the reference prefetch (each a
 * Haiku process, ~$0.01 and ~10 s, and each sends the wish's words to Anthropic).
 *
 * <p><b>The rule (step 1, docs/OPEN_DOOR_PLAN.md §8.1):</b> a wish that NAMES a recipe that
 * exists — a pressed button, {@code /moddy again}, a replay — starts none of them, because it is
 * always served from that recipe and never reaches the agent, where the two grades are enforced
 * and the prefetch is read. A wish matched to a recipe by its WORDS keeps all three: those words
 * are a child's own. The local L0 lexicon is not a model call and is not governed here.
 *
 * <p>Pure, so the rule is pinned by a unit test ({@code PreAgentModelsTest}); the wiring — that the
 * real {@code submit} obeys it — is {@code PreAgentModelsGameTest}.
 */
public final class PreAgentModels {

    private PreAgentModels() {
    }

    /**
     * @param namesARecipe the wish named a recipe the live template set holds (a button, an
     *                     "again", a replay) — NOT a recipe found by matching its words
     * @return true when the pre-agent model calls should start
     */
    public static boolean wanted(boolean namesARecipe) {
        return !namesARecipe;
    }
}
