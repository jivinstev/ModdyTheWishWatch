package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.agent.tools.NotifyPlayerTool;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.moddingfromamod.agent.tools.SetStatusTool;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>Replays the argument sets that were actually refused.</b>
 *
 * <p>Every case in the first class below is a real {@code argKeys} record from the Spikor runs, not
 * an invented one — because the invented version of this bug is "the model omitted a field", and
 * that reading is wrong. It sent {@code commands} for {@code command} seven times while
 * {@code command} sat in {@code required}, which is why no notation could have helped.
 *
 * <p>The second class is the one that decides whether this is safe to leave switched on. A
 * forgiving boundary that guesses wrong is worse than one that refuses honestly: a refusal costs a
 * turn, an invented argument costs a wrong build nobody can explain.
 */
class ArgAliasesTest {

    private static JsonObject args(String... keys) {
        JsonObject o = new JsonObject();
        for (String k : keys) {
            o.addProperty(k, "x");
        }
        return o;
    }

    static class RealRefusals {

        @Test
        void runCommandSentThePluralSevenTimes() {
            JsonObject a = new JsonObject();
            JsonArray cmds = new JsonArray();
            cmds.add("say hi");
            a.add("commands", cmds);

            List<String> fixed = ArgAliases.normalise(new RunCommandTool().inputSchema(), a);

            assertEquals(List.of("commands->command"), fixed);
            assertTrue(a.has("command"), "the call was refused as 'No command given.' -- it wasn't");
            assertFalse(a.has("commands"), "the stray key must go, or the tool sees both");
            assertEquals(1, RunCommandTool.commandCount(a));
        }

        @Test
        void setStatusSaidMessageSixTimes() {
            JsonObject a = args("message", "step");

            List<String> fixed = ArgAliases.normalise(new SetStatusTool().inputSchema(), a);

            assertEquals(List.of("message->doing"), fixed);
            assertTrue(a.has("doing"));
            assertTrue(a.has("step"), "'step' was already right and must be left alone");
        }

        @Test
        void aRepairedCallIsNoLongerRefused() {
            // The end-to-end claim, through the tool's own body rather than the schema.
            JsonObject a = args("message");
            ArgAliases.normalise(new SetStatusTool().inputSchema(), a);
            String err = new SetStatusTool().call(a).text();
            assertFalse(err != null && err.contains("Say what you're doing"),
                    "the refusal this exists to remove");
        }
    }

    static class CannotMisfire {

        @Test
        void aKeyTheSchemaDeclaresIsNeverStolen() {
            // `message` means the kid-facing line in notify_player, which DECLARES it -- so the
            // doing/message synonym must not fire. Note nothing per-tool arranges this: the
            // schema scopes the vocabulary by itself, which is what makes the rule safe to extend.
            JsonObject a = args("message");
            assertEquals(List.of(), ArgAliases.normalise(new NotifyPlayerTool().inputSchema(), a));
            assertTrue(a.has("message"));
        }

        @Test
        void anAlreadyCorrectCallIsUntouched() {
            JsonObject a = new JsonObject();
            JsonArray cmds = new JsonArray();
            cmds.add("say hi");
            a.add("command", cmds);
            a.addProperty("commands", "a stray key that must NOT overwrite the real one");

            assertEquals(List.of(), ArgAliases.normalise(new RunCommandTool().inputSchema(), a));
            assertTrue(a.get("command").isJsonArray(),
                    "the only reachable transition is refusal -> acceptance, never a changed meaning");
        }

        @Test
        void anUnrecognisedKeyIsLeftWhereItIs() {
            JsonObject a = args("wibble");
            assertEquals(List.of(), ArgAliases.normalise(new SetStatusTool().inputSchema(), a));
            assertTrue(a.has("wibble"), "guessing beyond the vocabulary is how a wrong build happens");
        }

        @Test
        void nullsAndJunkDoNotThrow() {
            // This runs on the path every single tool call takes. It must never be the thing that
            // breaks one.
            ArgAliases.normalise(null, new JsonObject());
            ArgAliases.normalise(new JsonObject(), null);
            ArgAliases.normalise(new JsonObject(), new JsonObject());
            ArgAliases.normalise(new SetStatusTool().inputSchema(), null);
        }
    }

    @Test
    void theRepairIsRecordedRatherThanAbsorbed() {
        // The fix rewrites args IN PLACE, so `argKeys` logs the corrected names -- the evidence
        // that identified this bug would be erased by the fix for it. `normalise` returning the
        // rewrites is what ActionLog's `aliased` field is built from.
        JsonObject a = args("message");
        assertFalse(ArgAliases.normalise(new SetStatusTool().inputSchema(), a).isEmpty(),
                "a forgiving boundary that hides its own use makes the rate unmeasurable");
    }
}
