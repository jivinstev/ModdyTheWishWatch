package com.moddingfromamod.worker;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The argv is a security boundary, so it is tested like one (HO-6).
 *
 * <p>{@code --permission-mode dontAsk} plus an {@code --allowed-tools} list of MCP tools only is
 * what stops the agent reaching for Bash, Write or WebFetch. These are the properties that must
 * hold however the list arrives.
 */
class WorkerArgvTest {

    @Test
    void aShellCannotArriveThroughTheToolList() {
        // THE one thing this list must never carry. The worker does not trust the file it reads:
        // whatever wrote it, anything without our prefix is dropped before it reaches the CLI.
        String hostile = "mcp__wishwatch__await_wish,Bash,Write,WebFetch,mcp__other__do_thing";
        assertEquals("mcp__wishwatch__await_wish", WorkerArgv.safe(hostile),
                "only our own MCP tools may be pre-approved");
    }

    @Test
    void buildItselfFiltersAndNotJustTheHelper() {
        // ⚠ FOUND BY DRIVING THE ARM RED. The test above calls safe() directly, so build() could
        // stop calling it and nothing would notice — a filter that exists and is not wired to the
        // path that matters. "There is no check" and "the check is not wired here" look identical
        // from outside; this asserts the wiring.
        List<String> cmd = WorkerArgv.build("claude", "w1", "p", "/mcp.json",
                "mcp__wishwatch__await_wish,Bash", "", "", List.of());
        int i = cmd.indexOf("--allowed-tools");
        assertTrue(i >= 0, "--allowed-tools is missing: " + cmd);
        assertEquals("mcp__wishwatch__await_wish", cmd.get(i + 1),
                "build() handed Bash to the CLI — the filter is not wired to this path");
    }

    @Test
    void aWorkerNeverStartsRemoteControl() {
        // a wish worker must not set up Remote Control, whatever the
        // player's own Claude Code settings say. The override rides on the argv, which both
        // launchers (AgentSession.startOne, WorkerMain) build here, so this is the wiring check.
        List<String> cmd = WorkerArgv.build("claude", "w1", "p", "/mcp.json",
                "mcp__wishwatch__await_wish", "opus", "low", List.of("--x-cache"));
        int i = cmd.indexOf("--settings");
        assertTrue(i >= 0, "--settings is missing: " + cmd);
        com.google.gson.JsonObject s = com.google.gson.JsonParser.parseString(cmd.get(i + 1)).getAsJsonObject();
        assertTrue(s.has("remoteControlAtStartup") && !s.get("remoteControlAtStartup").getAsBoolean(),
                "the worker's settings must turn remoteControlAtStartup OFF: " + cmd.get(i + 1));
        assertFalse(cmd.contains("--remote-control"), "a worker must never ask for Remote Control: " + cmd);
        // And it must not land between --bg and its positional prompt (see build()'s javadoc).
        assertEquals("p", cmd.get(cmd.indexOf("-n") + 2), "the prompt must stay right after the session name");
    }

    @Test
    void theDenyAllModeIsNotOptional() {
        // Without it, a tool call needing approval parks a detached session with stdin on
        // /dev/null FOREVER — the agent never returns to await_wish and every later wish sits
        // PENDING while the child watches a ticker run on. Measured on CLI 2.1.220.
        List<String> cmd = WorkerArgv.build("claude", "w1", "p", "/mcp.json",
                "mcp__wishwatch__await_wish", "", "", List.of());
        int i = cmd.indexOf("--permission-mode");
        assertTrue(i >= 0, "--permission-mode is missing: " + cmd);
        assertEquals("dontAsk", cmd.get(i + 1));
    }

    @Test
    void noBuiltInToolIsOfferedButToolSearch() {
        //. dontAsk + an MCP-only --allowed-tools did NOT bound the worker: the CLI
        // ran what it counts as read-only, and production executed 1,169 Bash calls, 36 Reads and
        // 34 sub-agents in 58 transcripts — including `cat` of mcp.json and curl with its bearer.
        // --tools is the list the model is OFFERED; nothing absent from it can be tried.
        List<String> cmd = WorkerArgv.build("claude", "w1", "p", "/mcp.json",
                "mcp__wishwatch__await_wish", "opus", "low", List.of("--cache"));
        int i = cmd.indexOf("--tools");
        assertTrue(i >= 0, "--tools is missing, so Bash, Read, Write, WebFetch and Task are all "
                + "OFFERED again and --allowed-tools is the only thing between them and the model: " + cmd);
        assertEquals(1, cmd.stream().filter("--tools"::equals).count(), "one --tools, or the last wins: " + cmd);
        assertEquals("ToolSearch", cmd.get(i + 1),
                "the worker's ONLY built-in is ToolSearch: it is how Opus loads its DEFERRED wish "
                + "tools (\"\" would change that — another question, not this one), and on a "
                + "redirected host, where tool search is off, it offers exactly what \"\" does");
        assertEquals(WorkerArgv.BUILT_INS, cmd.get(i + 1));
    }

    @Test
    void theToolsValueIsOneTokenAndCannotSwallowTheNextFlag() {
        // --tools is VARIADIC on this CLI (`--tools <tools...>`): every bare word after it is read
        // as another tool until the next flag. So the value must be a single token with no space
        // or comma in it, and what follows it must be a flag — otherwise a later positional (or a
        // reordering of this builder) silently becomes a tool name, or the reverse.
        List<String> cmd = WorkerArgv.build("claude", "w1", "p", "/mcp.json",
                "mcp__wishwatch__await_wish", "opus", "low", List.of());
        int i = cmd.indexOf("--tools");
        String v = cmd.get(i + 1);
        assertFalse(v.contains(" ") || v.contains(","), "one tool, one token: '" + v + "'");
        assertTrue(i + 2 >= cmd.size() || cmd.get(i + 2).startsWith("--"),
                "the argument after --tools' value must be a flag, not a bare word: " + cmd);
        assertTrue(i > 4, "--tools must never sit between -n <name> and the positional prompt: " + cmd);
    }

    @Test
    void thePromptStaysPositionalRightAfterTheSessionName() {
        // --bg takes its prompt POSITIONALLY. Anything inserted between -n <name> and the prompt
        // silently changes what the agent is asked to do.
        List<String> cmd = WorkerArgv.build("claude", "w1", "THE-PROMPT", "/mcp.json",
                "mcp__wishwatch__await_wish", "", "", List.of("--cache"));
        assertEquals("--bg", cmd.get(1));
        assertEquals("-n", cmd.get(2));
        assertEquals("w1", cmd.get(3));
        assertEquals("THE-PROMPT", cmd.get(4));
        assertEquals("--cache", cmd.get(cmd.size() - 1), "cache flags go LAST");
    }

    @Test
    void anEmptyModelOrEffortMeansTheCliDecidesRatherThanPassingBlank() {
        // Passing `--model ""` is not the same as omitting it: the CLI would take the empty
        // string as the model name and fail, which reads as a broken agent.
        List<String> cmd = WorkerArgv.build("claude", "w1", "p", "/mcp.json",
                "mcp__wishwatch__await_wish", "", "", List.of());
        assertFalse(cmd.contains("--model"));
        assertFalse(cmd.contains("--effort"));
    }

    @Test
    void theListIsAcceptedInEitherShapeAndDeduplicated() {
        // The resource is generated; a caller holding the live comma-separated string should not
        // have to reformat it, and a duplicate must not be passed on twice.
        assertEquals("mcp__wishwatch__a,mcp__wishwatch__b",
                WorkerArgv.safe("mcp__wishwatch__a\nmcp__wishwatch__b\nmcp__wishwatch__a"));
    }
}
