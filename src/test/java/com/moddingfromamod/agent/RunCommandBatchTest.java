package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.agent.tools.RunCommandTool;
import org.junit.jupiter.api.Test;

/**
 * {@code run_command} takes a LIST, because a model turn costs ~4s and it used to run one line per
 * turn.
 *
 * <p>Measured over 354 wishes: 1436 of 2731 calls immediately followed another with no other tool
 * between them — 121 minutes of model time — with observed runs of 19, 21 and 27 commands dictated
 * one at a time.
 *
 * <p>These tests cover what can be checked without a running server: the argument shapes, the batch
 * cap, and — the one that actually matters for safety — that a batch containing a refused command
 * runs <b>nothing</b>. Execution ordering and stop-on-failure need a world and live in the GameTest.
 */
class RunCommandBatchTest {

    private static JsonObject one(String command) {
        JsonObject args = new JsonObject();
        args.addProperty("command", command);
        return args;
    }

    private static JsonObject many(String... commands) {
        JsonArray list = new JsonArray();
        for (String c : commands) {
            list.add(c);
        }
        JsonObject args = new JsonObject();
        args.add("command", list);
        return args;
    }

    private static String call(JsonObject args) {
        return new RunCommandTool().call(args).text();
    }

    @Test
    void aSingleStringStillWorks() {
        // Backwards compatibility is the point: the agent has already learned this shape, and a
        // change that invalidated it would trade one latency win for a season of retries.
        String msg = call(one("stop"));
        assertTrue(msg.contains("isn't allowed"),
                "a bare string must still be read as one command: " + msg);
    }

    @Test
    void anEmptyCallIsRefusedCleanly() {
        assertTrue(call(new JsonObject()).contains("No command given"));
        assertTrue(call(one("   ")).contains("No command given"));
        assertTrue(call(many()).contains("No command given"), "an empty list is not a command");
    }

    @Test
    void oneRefusedCommandStopsTheWholeBatchBeforeAnythingRuns() {
        // THE SAFETY PROPERTY. Whether a command is allowed needs no execution to decide, so a
        // batch that was always going to be rejected must change nothing at all. Half-applying the
        // allowed prefix and then refusing leaves the world in a state nobody asked for, and the
        // agent cannot see what stuck.
        String msg = call(many("summon minecraft:chicken ~ ~ ~", "stop", "give @p diamond"));
        assertTrue(msg.contains("isn't allowed"), "the refusal should survive batching: " + msg);
        assertTrue(msg.contains("nothing was run"),
                "it must say the batch did not partially apply: " + msg);
        assertTrue(msg.contains("command 2 of 3"),
                "it must say WHICH command was the problem, or the agent has to bisect: " + msg);
    }

    @Test
    void aSingleRefusalDoesNotGainBatchWording() {
        // Same refusal as before for the one-command case: the extra clause is only meaningful
        // when there were siblings that did not run.
        String msg = call(one("stop"));
        assertFalse(msg.contains("nothing was run"),
                "a lone refused command has no siblings to report on: " + msg);
    }

    @Test
    void theRefusalRedirectSurvivesBatching() {
        // A refusal that names no alternative is an invitation to improvise (RefusalRedirectTest).
        // That reasoning does not stop applying because the command arrived in a list.
        String msg = call(many("wishmob list", "give @p diamond"));
        assertTrue(msg.contains("list_creatures"),
                "the redirect must still name the tool that does the job: " + msg);
    }

    @Test
    void aBatchLongerThanTheCapIsRefusedWithTheReason() {
        String[] tooMany = new String[RunCommandTool.MAX_BATCH + 1];
        java.util.Arrays.fill(tooMany, "give @p diamond");
        String msg = call(many(tooMany));
        assertTrue(msg.contains("the most I can run"), "the cap should explain itself: " + msg);
        assertTrue(msg.contains("Split it"), "and say what to do instead: " + msg);
    }

    @Test
    void theCapLeavesRoomForTheLongestSequenceRealAgentsProduce() {
        // The longest observed streak from a live agent was 27 commands. A cap under that would
        // reintroduce the very one-at-a-time typing this change exists to remove.
        assertTrue(RunCommandTool.MAX_BATCH >= 27,
                "27 in a row has actually happened; a lower cap forces the agent back to "
                        + "single calls for exactly the sequences that cost the most");
    }

    @Test
    void theDescriptionTeachesWhenNotToBatch() {
        // The read-then-decide loop is why this tool returns real output. If the description sells
        // batching without naming the exception, the agent will batch a sequence whose later
        // commands depend on an output it has not seen yet.
        String d = new RunCommandTool().description();
        assertTrue(d.contains("LIST") || d.contains("list"), "it must offer batching: " + d);
        assertTrue(d.toLowerCase().contains("read"),
                "it must say to keep calls separate when it needs to READ an output first: " + d);
        assertTrue(d.contains("not run"),
                "it must say what happens to the rest when one fails: " + d);
    }

    @Test
    void theSchemaOffersOneParameterAndOnlyTheListShape() {
        // One concept, one parameter — a `command` and a `commands` side by side would make the
        // model choose between two spellings of the same idea on every call.
        // added `keep`, which is a different concept (keep what this call summons), not a
        // second spelling of the commands — so the set is pinned exactly, and a `commands` alias
        // still fails here.
        JsonObject props = new RunCommandTool().inputSchema().getAsJsonObject("properties");
        // added `at` — WHERE they run, a different question from what they are — so the rule
        // is now: the commands themselves have exactly one parameter, and `at` and `keep` are the others.
        assertEquals(java.util.Set.of("command", "at", "keep"), props.keySet(),
                "exactly one way to say the commands: " + props);

        // AND ONLY ONE SHAPE. This used to assert oneOf[string, array], which is what shipped —
        // and over the real action log the array was chosen ZERO times in 752 calls while 480 of
        // them sat in unbroken streaks of three or more. The description explained batching
        // perfectly and changed nothing; the schema offered something simpler and won.
        JsonObject command = props.getAsJsonObject("command");
        assertEquals("array", command.get("type").getAsString(),
                "an optional field is not an input to generation — require it, or remove it");
        assertEquals(RunCommandTool.MAX_BATCH, command.get("maxItems").getAsInt(),
                "the cap belongs in the schema too, so the model sees it before it overruns");
    }
}
