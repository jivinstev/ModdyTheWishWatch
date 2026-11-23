package com.moddingfromamod.agent.tools;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>run_command must apply the same {@code /function} rewrite the other tools already do.</b>
 *
 * <p>Calling a datapack function directly can throw {@code "This function should not run"}, and
 * retrying does not help. That was known, and fixed — in the two places that write such a command
 * <i>for</i> the agent: {@code AddTemplateTool} rewrites it at teach time, {@code MakeItemTool}
 * spells out the working form in its result text.
 *
 * <p><b>Nothing covered the agent typing one itself</b>, which is precisely what it does when it
 * tests its own work. Live 2026-08-14, seconds after {@code verify_datapack} came back clean:
 *
 * <pre>
 * FAILED: function metal_grinder:tick    java.lang.UnsupportedOperationException
 * FAILED: function metal_grinder:reset   java.lang.UnsupportedOperationException
 * </pre>
 *
 * <p>It abandoned the datapack and hand-placed a repeating command block instead — the one that then
 * spammed the child's screen.
 *
 * <p>This test asserts the source-level fact that {@code run_command}'s parser reaches for the
 * rewrite at all. The rewrite's own behaviour is covered where it lives; what was missing was
 * anybody <i>calling</i> it on this path, and that is what regresses silently.
 */
class HandTypedFunctionIsRewrittenTest {

    private static String parserSource() throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/RunCommandTool.java"));
    }

    @Test
    void runCommandRoutesEveryCommandThroughTheDatapackFunctionRewrite() throws Exception {
        String src = parserSource();
        int start = src.indexOf("private static List<String> commandsIn(");
        assertTrue(start > 0, "commandsIn has been renamed — check the rewrite still happens");
        int end = src.indexOf("\n    }", start);
        String body = src.substring(start, end);

        // BOTH shapes the tool accepts. A rewrite applied to the list form but not the bare-string
        // form would fix the batch case and leave the single-command case -- which is exactly how
        // the agent typed it live -- still broken.
        List<String> mustRewrite = List.of("for (JsonElement e :", "raw.isJsonPrimitive()");
        for (String shape : mustRewrite) {
            int at = body.indexOf(shape);
            assertTrue(at > 0, "commandsIn no longer handles: " + shape);
        }
        assertEquals(2, countOf(body, "rewriteDatapackFunction"),
                "every command entering run_command must go through rewriteDatapackFunction — "
                        + "both the list form and the bare-string form. A bare `function "
                        + "<pack>:<entry>` throws \"This function should not run\" and retrying "
                        + "does not help; the agent then abandons its own datapack. Found "
                        + countOf(body, "rewriteDatapackFunction") + " call(s) in: " + body);
    }

    /** The rewrite must be reachable from here at all — it lives in a sibling tool. */
    @Test
    void theRewriteIsVisibleToRunCommand() {
        assertEquals("summon minecraft:pig ~ ~ ~",
                AddTemplateTool.rewriteDatapackFunction("summon minecraft:pig ~ ~ ~"),
                "an unrelated command must pass through untouched");
        // A function into a namespace that is NOT one of our datapacks is left alone: rewriting a
        // vanilla or another mod's function to `wishdatapack` would turn a working command into a
        // broken one.
        assertEquals("function minecraft:some_vanilla_thing",
                AddTemplateTool.rewriteDatapackFunction("function minecraft:some_vanilla_thing"));
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }
}
