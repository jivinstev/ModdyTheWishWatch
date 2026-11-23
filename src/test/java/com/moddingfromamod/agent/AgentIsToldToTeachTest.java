package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Every wish class that can be REBUILT must tell the agent to teach it.
 *
 * <p>Gate D asserts the whole wish cycle — built AND remembered AND reproducible — and creatures
 * were the one built thing the prompt never asked to do the remembering. Machines were told
 * ({@code wishmachine}), trains were told ({@code wishtrain}), datapacks are explicitly told NOT to
 * (author_datapack teaches for them). Creatures got nothing, so the agent taught the watch only
 * when it happened to think of it.
 *
 * <p><b>Measured, five gated runs of one prompt on 27 Aug 2026:</b> {@code add_template} was called
 * in two of them, and those were exactly the two that passed Gate D. Two for two, three for three.
 * The gate was not flaky — it was faithfully reporting a step nobody had ever asked for.
 *
 * <p>Buildings were found by grepping the siblings rather than by measurement: {@code /wishbuild}
 * replays a saved plan exactly as the others do, and the word appeared nowhere in the prompt.
 *
 * <p>This is a PIN, not a style check. The rule is cheap to write and invisible to lose: nothing
 * fails when it is trimmed, the agent simply goes back to remembering 40% of the time, and the
 * only symptom is a gate verdict that reads like flakiness.
 */
class AgentIsToldToTeachTest {

    /** wish class -> the replay command the child's watch button has to run. */
    private static final Map<String, String> REPLAY = new LinkedHashMap<>();

    static {
        REPLAY.put("creature", "wishmob spawn <its id>");
        // "machine" (wishmachine) and "train" (wishtrain) went with Create.
        REPLAY.put("building", "wishbuild <its id>");
    }

    /**
     * The prompt is assembled from wrapped Java string literals, so a rule can be split across
     * lines. Joining them is what lets this assert on the text the MODEL sees rather than on the
     * source layout — the same trimming mistake would otherwise be invisible to a naive contains().
     */
    private static String flattened() {
        return AgentSession.prompt().replace("\n", " ").replaceAll(" +", " ");
    }

    @Test
    void everyRebuildableWishClassIsToldToTeachTheWatch() {
        String p = flattened();
        for (Map.Entry<String, String> e : REPLAY.entrySet()) {
            assertTrue(p.contains(e.getValue()),
                    "the agent is never told to teach a " + e.getKey() + " — expected the prompt to "
                    + "name add_template with commands [\"" + e.getValue() + "\"]. Gate D asserts "
                    + "the cycle is complete, so dropping this makes the gate fail for a step the "
                    + "agent was never asked to take (#265).");
        }
    }

    @Test
    void theTeachInstructionSaysAddTemplateForEachOfThem() {
        String p = flattened();
        assertTrue(p.contains("add_template"), "add_template is the tool that teaches the watch");
        // One teach instruction per rebuildable class, and no more: a second copy for the same
        // class is how a prompt drifts into contradicting itself.
        for (Map.Entry<String, String> e : REPLAY.entrySet()) {
            int n = p.split(java.util.regex.Pattern.quote(e.getValue()), -1).length - 1;
            assertEquals(1, n,
                    "expected exactly one teach instruction for " + e.getKey() + ", found " + n);
        }
    }

    @Test
    void theTeachStepIsOrderedAFTERThePhotographNotMerelyBeforeFinishWish() {
        // MEASURED REGRESSION. An earlier cut said only "before finish_wish", so the agent taught
        // FIRST -- Gate D saw the cycle complete, passed, and tore the world down while
        // finish_building was still coming. It failed with "No world is open yet" and the run
        // produced ZERO photographs, on a run that otherwise passed. Both runs that photographed
        // correctly did finish_building THEN add_template. A deadline is not an order.
        String p = flattened();
        assertTrue(p.contains("Do it AFTER finish_building"),
                "the teach step must be ordered after the photo, not merely before finish_wish");
    }

    @Test
    void datapacksAreStillToldNOTToTeachSeparately() {
        // The one class where teaching is WRONG: author_datapack registers the button itself when
        // it is passed reuse_action, so a second add_template would double it up. Pinned next to
        // the others precisely because a well-meaning "make it consistent" edit would break it.
        assertTrue(flattened().contains("Do NOT also call add_template"),
                "author_datapack teaches its own button; the prompt must keep saying so");
    }
}
