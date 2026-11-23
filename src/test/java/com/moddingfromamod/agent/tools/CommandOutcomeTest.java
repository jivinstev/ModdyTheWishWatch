package com.moddingfromamod.agent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What gets forgiven, and — the half that matters more — what does not.</b>
 *
 * <p>A rule that forgives too much is worse than the bug it fixes: the whole point of stopping a
 * batch at the first failure is that later commands would otherwise build on a world that is not
 * there. So most of these tests are the negative cases.
 */
class CommandOutcomeTest {

    // ── THE BUG, VERBATIM ────────────────────────────────────────────────────────────────────
    // Both of these are copied from the live session that lost the grinder's engine.

    @Test
    void fillingAirOverAirIsNotAFailure() {
        assertTrue(CommandOutcome.isAlreadyDone(
                "fill -1773 1 997 -1768 1 997 minecraft:air", "No blocks were filled"),
                "this exact line discarded the 4 commands that included the repeating command "
                        + "block that WAS the child's grinder");
    }

    @Test
    void settingAirWhereThereIsAlreadyAirIsNotAFailure() {
        assertTrue(CommandOutcome.isAlreadyDone(
                "setblock -1768 5 1006 minecraft:air", "Could not set the block"));
    }

    @Test
    void aClonedRegionThatAlreadyMatchesIsNotAFailure() {
        assertTrue(CommandOutcome.isAlreadyDone(
                "clone 1 2 3 4 5 6 7 8 9", "No blocks were cloned"));
    }

    // ── WHAT MUST STILL STOP THE BATCH ───────────────────────────────────────────────────────

    @Test
    void aBadBlockIdStillStopsTheBatch() {
        assertFalse(CommandOutcome.isAlreadyDone(
                "setblock 1 2 3 minecraft:not_a_block", "Unknown block type 'minecraft:not_a_block'"),
                "a typo'd block means the wall the agent thinks it built is not there; every later "
                        + "command in the batch is standing on nothing");
    }

    @Test
    void anOutOfWorldPositionStillStopsTheBatch() {
        assertFalse(CommandOutcome.isAlreadyDone(
                "setblock 1 -900 3 minecraft:stone", "That position is out of this world!"));
    }

    @Test
    void aBadSelectorStillStopsTheBatch() {
        assertFalse(CommandOutcome.isAlreadyDone(
                "fill 1 2 3 4 5 6 minecraft:air", "No entity was found"));
    }

    @Test
    void theSameMessageUnderADifferentCommandIsNotForgiven() {
        // Belt and braces: forgiveness is keyed on the COMMAND as well as the message, so a
        // message that happens to contain the phrase cannot smuggle an unrelated failure through.
        assertFalse(CommandOutcome.isAlreadyDone(
                "summon minecraft:pig 1 2 3", "No blocks were filled"));
        assertFalse(CommandOutcome.isAlreadyDone(
                "function metal_grinder:tick", "Could not set the block"));
    }

    @Test
    void arealFunctionCrashStillStopsTheBatch() {
        // Also from the live session: the agent could not run a datapack function it had just
        // authored. That is a genuine seam failure and must never be swallowed.
        assertFalse(CommandOutcome.isAlreadyDone("function metal_grinder:tick",
                "java.lang.UnsupportedOperationException: This function should not run"));
    }

    @Test
    void nullsAreNotForgiven() {
        assertFalse(CommandOutcome.isAlreadyDone(null, "No blocks were filled"));
        assertFalse(CommandOutcome.isAlreadyDone("fill 1 2 3 4 5 6 air", null));
    }

    // ── THE PREFIX THE AGENT ACTUALLY WRITES ─────────────────────────────────────────────────

    @Test
    void anExecuteRunPrefixIsSeenThrough() {
        // The agent writes this constantly -- the very first command of the live session was
        // "execute positioned -1768 4 1003 run fill ...". Reading only the first word classifies
        // every one of those as "execute" and forgives nothing, so the fix would not have fixed it.
        assertEquals("fill",
                CommandOutcome.verbOf("execute positioned -1768 4 1003 run fill 1 2 3 4 5 6 air"));
        assertTrue(CommandOutcome.isAlreadyDone(
                "execute positioned -1768 4 1003 run fill 1 2 3 4 5 6 minecraft:air",
                "No blocks were filled"));
    }

    @Test
    void nestedExecutesPeelToTheInnermostCommand() {
        assertEquals("setblock", CommandOutcome.verbOf(
                "execute as @p at @s run execute positioned ~ ~ ~ run setblock ~ ~ ~ air"));
    }

    @Test
    void aLeadingSlashDoesNotHideTheVerb() {
        assertEquals("fill", CommandOutcome.verbOf("/fill 1 2 3 4 5 6 air"));
    }

    @Test
    void executeWithoutARunIsStillExecute() {
        // Malformed, and it must not be mistaken for whatever word happens to be last.
        assertEquals("execute", CommandOutcome.verbOf("execute positioned 1 2 3"));
    }

    // ── the datapack-function advice (in the syntax its fix did not cover) ────────────────────────────────────────────────────────
    //
    // MEASURED in corpus/real-tool-calls.jsonl: 6 of the agent's 15 real run_command failures are a
    // /function into one of our own packs throwing "This function should not run", and every one of
    // them was NESTED inside an `execute` -- the form AddTemplateTool's rewrite cannot see, because
    // it only matches a command that STARTS with `function `.

    private static final String THROWN =
            "java.lang.UnsupportedOperationException: This function should not run";

    @Test
    void aNestedFunctionCallIsExplained() {
        String advice = CommandOutcome.datapackFunctionAdvice(
                "execute as @e[tag=fbtest] at @s run function fireball_sword:shoot", THROWN);
        assertTrue(advice.contains("wishdatapack fireball_sword shoot"),
                "it must name the exact working command, not the shape of one: " + advice);
        assertTrue(advice.contains("AS THE CHILD"),
                "and warn that the entity context is lost, or the agent will swap it in blindly "
                        + "and be confused when the selector no longer applies");
        assertTrue(advice.contains("#142"),
                "and say what NOT to do -- last time this failed unexplained the agent reached for "
                        + "a repeating command block that spammed a child's screen");
    }

    @Test
    void aBareFunctionCallIsExplainedToo() {
        // The rewrite normally catches this one first, but it is reachable when the pack id is not
        // (yet) in the store, and the advice must still be right rather than empty.
        assertTrue(CommandOutcome.datapackFunctionAdvice("function metal_grinder:tick", THROWN)
                .contains("wishdatapack metal_grinder tick"));
    }

    @Test
    void aDifferentFailureIsNeverGivenThisAdvice() {
        // The one that matters. A message that appears on the wrong failure is worse than none: it
        // sends the agent to rewrite a command that was never the problem, and P6 (do not cry wolf)
        // is the linter principle this would break.
        assertEquals("", CommandOutcome.datapackFunctionAdvice(
                "execute as @e[tag=x] at @s run function foo:bar", "No entity was found"));
        assertEquals("", CommandOutcome.datapackFunctionAdvice(
                "give @p minecraft:diamond", "Unknown item 'minecraft:diamond'"));
        assertEquals("", CommandOutcome.datapackFunctionAdvice("summon minecraft:pig", THROWN),
                "a command with no function call in it must get NO advice, however the server "
                        + "happened to word its failure -- gating on the message alone gave a "
                        + "`summon` the whole datapack lecture, which is P6 exactly");
    }

    @Test
    void nullsAreNotAnExcuseToThrow() {
        assertEquals("", CommandOutcome.datapackFunctionAdvice(null, THROWN));
        assertEquals("", CommandOutcome.datapackFunctionAdvice("function a:b", null));
    }

    @Test
    void theFunctionReferenceIsFoundWhereverItSits() {
        assertEquals("a:b", CommandOutcome.functionRefIn("function a:b"));
        assertEquals("a:b", CommandOutcome.functionRefIn("execute at @s run function a:b"));
        assertEquals("a:b", CommandOutcome.functionRefIn("execute as @e run function a:b with x"));
        assertEquals("", CommandOutcome.functionRefIn("execute at @s run say hello"));
        assertEquals("", CommandOutcome.functionRefIn("function nocolon"),
                "an id with no namespace cannot be turned into a wishdatapack call");
    }
}
