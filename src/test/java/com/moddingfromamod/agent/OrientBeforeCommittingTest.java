package com.moddingfromamod.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The agent must be told to open a catalogue before it commits.</b>
 *
 * <p>Measured on {@code tools/routing-arm.py} at the model and effort that actually grant wishes
 * ({@code opus} / {@code low}), 25 runs per arm:
 *
 * <pre>
 *   machine fixture   18/25 (72%)  ->  25/25 (100%)   Fisher p = 0.0096
 *   creature fixture   8/10 (80%)  ->  10/10 (100%)
 *   whole fixture set  escape column 1 -> 0, no class worse
 * </pre>
 *
 * <p>The 72% is the whole. Steve's metal factory was one of the seven misses — it never
 * called {@code build_contraption} or {@code author_recipe} once, and hand-built a grinder out of 24
 * {@code run_command}s instead. Six separate causes were proposed and refuted before the rate itself
 * turned out to be the answer: a 28% miss rate does not have a cause, and the whole hunt was an
 * attempt to explain one flip of a weighted coin.
 *
 * <p><b>Why a test on the prompt text.</b> The instruction is one line among hundreds and does not
 * change any behaviour that another test can observe — nothing crashes, nothing fails to compile,
 * every gate stays green without it. It would be the easiest thing in the file to lose in a merge,
 * and the only symptom would be Steve's next factory quietly coming back as a pile of
 * {@code fill} commands. This repo has already shipped one whole capability dead that way
 * ({@code author_recipe}, silently unregistered by a merge, in a build that passed both gates).
 *
 * <p><b>Why this and not raising effort.</b> {@code opus} at {@code high} reaches the same 25/25 —
 * and takes 62s against 34s, on every wish, when a wish already costs $2.21–$5.29. The
 * instruction is free and lands at 31s, slightly faster than doing without it: one catalogue call
 * costs less than the flailing it prevents.
 */
class OrientBeforeCommittingTest {

    @Test
    void thePromptTellsTheAgentToOrientBeforeItCommits() {
        String prompt = AgentSession.prompt();
        assertTrue(prompt.contains("OPEN THE CATALOGUE"),
                "the orient-before-committing instruction is gone from the system prompt. Without "
                        + "it, machine wishes reach the machine tools about 7 times in 10 and the "
                        + "rest get hand-built out of raw commands — which is what happened to "
                        + "Steve's metal factory (#144)");
    }

    /**
     * It has to be tied to {@code plan_wish} specifically.
     *
     * <p>{@code plan_wish} is the commitment point: it publishes the steps to the child's progress
     * bar, and every measured failure came from reaching it with nothing opened. An instruction that
     * merely suggested browsing at some point would not name the moment that matters.
     */
    @Test
    void itIsAnchoredToTheMomentOfCommitment() {
        String prompt = AgentSession.prompt();
        int at = prompt.indexOf("OPEN THE CATALOGUE");
        assertTrue(at > 0, "instruction missing");
        String around = prompt.substring(Math.max(0, at - 120),
                Math.min(prompt.length(), at + 240));
        assertTrue(around.contains("plan_wish"),
                "the instruction no longer names plan_wish, the point where the agent commits. "
                        + "Around it: " + around);
    }

    /**
     * <b>It must stay general.</b>
     *
     * <p>It names four catalogues and lets the agent choose; it matches no word from the wish. That
     * is deliberate and is why it lifted the creature fixture as well as the machine one — a
     * keyword rule on "factory" was explicitly ruled out, and would have helped exactly one class
     * while adding a thing to maintain for every new wish class after it.
     */
    @Test
    void itNamesEveryCatalogueRatherThanPickingAPipeline() {
        String prompt = AgentSession.prompt();
        int at = prompt.indexOf("OPEN THE CATALOGUE");
        String around = prompt.substring(at, Math.min(prompt.length(), at + 300));
        for (String catalogue : new String[]{"list_items", "list_creatures", "list_builds",
                                             "list_templates"}) {
            assertTrue(around.contains(catalogue),
                    "the instruction no longer offers " + catalogue + ", so it steers toward the "
                            + "pipelines it still names instead of telling the agent to orient. "
                            + "Measured: being general is what made it help creature wishes too");
        }
    }
}
