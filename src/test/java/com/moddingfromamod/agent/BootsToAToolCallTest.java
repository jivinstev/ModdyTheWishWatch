package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * a worker must reach {@code await_wish}, and the prompt is the only thing that gets it there.
 *
 * <h2>The measurement</h2>
 * Three real pool boots, nine workers, read from their own transcripts: <b>5 of 9 never called
 * {@code await_wish} at all</b>, and one entire pool of three was deaf. Nothing in the log said so —
 * the mod reported all three "is live", which was true and useless. A whole boot of one deaf worker:
 *
 * <pre>
 * turn 1   read 24,366   write 19,653   "I'll start by loading the wish tools."   &lt;- no tool call
 * turn 2   read 44,019   write 29,998   ToolSearch
 * turn 3   read 74,017   write  5,976   "I'm ready. Starting the wish loop."      &lt;- no tool call
 *                                        (nothing, ever again)
 * </pre>
 *
 * <h2>Why it happens, and why the fix is a prompt</h2>
 * The prompt said <i>"STEP 1: call await_wish"</i> — and on turn 1 the agent <b>cannot</b>. The MCP
 * tools are DEFERRED, so {@code mcp__wishwatch__await_wish} is not in the tool list until after the
 * first assistant turn. The instruction was impossible to obey as written, so the model did the
 * reasonable thing and narrated instead, which ENDS THE TURN.
 *
 * <p>{@code --tools} governs only the built-in set, so the deferral itself is not ours to switch
 * off. What is ours is telling the worker what its first action must be. an earlier issue's own suggestion:
 * <i>"make the last boot turn a tool call, and check it happened."</i> The mod already checks
 * ({@code WishQueue.parked} + {@code PoolPlan.deafCount}); this is the other half.
 *
 * <p>A prompt test rather than a live one on purpose: the deafness reproduces only in the real
 * {@code --bg} path (the stub harness parks 8/8), so a gate here cannot prove a worker parks. What
 * it CAN prove is that the instruction which makes parking possible is still present — and a
 * prompt trim silently removing it is exactly how this regresses, with every other test green.
 */
class BootsToAToolCallTest {

    @Test
    @DisplayName("the first action is a tool call, and the prompt says which")
    void theBootBeginsWithToolSearchRatherThanProse() {
        String p = AgentSession.prompt();

        assertTrue(p.contains("ToolSearch"),
                "#352: the prompt tells the worker to call await_wish first, but the MCP tools are "
                + "DEFERRED and it is not in the tool list yet — so the instruction cannot be "
                + "obeyed on turn 1 and the worker narrates instead, which ends its turn");
        assertTrue(p.contains("select:mcp__wishwatch__await_wish"),
                "…and it must give the exact query. 'Search for your tools' costs a turn to get "
                + "wrong, and the fully-qualified name is what the deferred list is keyed on");
        assertTrue(p.contains("DEFERRED") || p.contains("deferred"),
                "…and say WHY, or the instruction reads as ceremony and gets skipped when the "
                + "model judges it unnecessary — which is precisely what it did");
    }

    @Test
    @DisplayName("STEP 0 is true on BOTH setups: deferred behind ToolSearch, and no ToolSearch at all")
    void stepZeroIsTrueWithAndWithoutToolSearch() {
        //. The worker is offered ToolSearch and nothing else built in (WorkerArgv
        // BUILT_INS) — and through a redirect (Muse) Claude Code turns tool search off, so there
        // it has NO ToolSearch and every wish tool is already listed. A STEP 0 that asserts the
        // tools are deferred is false there; Muse, told only that, echoed "ToolSearch" through
        // Bash in session after session. The prompt must carry the other branch, and must not
        // send a model hunting outside its tools.
        String p = AgentSession.prompt();
        assertTrue(p.contains("If you have NO ToolSearch, await_wish is ALREADY in your tool list"),
                "the no-ToolSearch branch is gone: on a redirected host STEP 0 is impossible again");
        assertTrue(p.contains("no shell, no file reading and no sub-agent"),
                "the prompt must say the worker HAS no shell/files/sub-agent — it does not, and a "
                + "model that believes otherwise spends its boot looking for them");
    }

    @Test
    @DisplayName("narrating instead of calling is named as the failure, with what it costs")
    void theProseTurnIsForbiddenExplicitly() {
        String p = AgentSession.prompt();
        assertTrue(p.contains("DO NOT ANSWER WITH WORDS"),
                "the deaf boot is a POLITE one — it says it is ready and stops. Without naming "
                + "that shape, 'call the tool' does not exclude 'say you are about to'");
        assertTrue(p.contains("deaf"),
                "the consequence has to be in the prompt: a worker that stops after a sentence is "
                + "not idle, it is unreachable, and a child wishing then is heard by nobody");
        assertTrue(p.contains("5 of 9"),
                "the measurement belongs beside the rule. A bare prohibition is advice; a "
                + "prohibition with a number behind it is a finding, and this repo has measured "
                + "that the difference matters to whether a rule is followed");
    }

    @Test
    @DisplayName("the loop itself is untouched — this adds a step 0, it does not replace step 1")
    void theWishLoopStillSaysWhatItAlwaysDid() {
        // The regression to fear from a prompt edit is collateral: the loop is what makes the
        // worker keep granting, and it predates this by a long way.
        String p = AgentSession.prompt();
        assertTrue(p.contains("STEP 1: call await_wish"), "the loop's first step went missing");
        assertTrue(p.contains("never poll, never sleep"),
                "await_wish BLOCKS, and a worker told to poll costs a turn every time round");
        assertTrue(p.contains("Repeat forever"), "the loop no longer says it is a loop");
    }
}
