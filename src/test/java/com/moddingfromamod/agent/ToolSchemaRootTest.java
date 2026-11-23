package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>No wish tool's input schema may combine at the ROOT</b>.
 *
 * <p>A redirected (Muse) worker runs the Claude CLI from its own config directory, and from
 * there the CLI drops every tool whose input schema has a top-level {@code anyOf}, {@code oneOf} or
 * {@code allOf} before the request is sent. Measured with {@code tools/muse-tee-proxy.py}: 52 tools from
 * the default directory, 49 from the worker's; the three missing were exactly the three built with a
 * root {@code anyOf} ({@code add_template}, {@code inspect_event}, {@code remove_event}). Muse then
 * could not undo an event and looped on {@code run_command}, while Opus, on the default directory,
 * passed. Nothing errors: the tool is simply not there, so this is the check that it cannot recur.
 * "At least one of these" is {@link Schemas#requireOneOf} (a field prefix plus the tool's refusal);
 * a combinator inside a property is delivered and is fine.
 */
class ToolSchemaRootTest {

    private static final List<String> ROOT_COMBINATORS = List.of("anyOf", "oneOf", "allOf", "not");

    @Test
    void noToolSchemaCombinesAtTheRoot() {
        List<String> bad = new ArrayList<>();
        int n = 0;
        for (McpTool tool : WishAgent.buildTools().all()) {
            n++;
            JsonObject s = tool.inputSchema();
            for (String k : ROOT_COMBINATORS) {
                if (s.has(k)) {
                    bad.add(tool.name() + " (" + k + ")");
                }
            }
            assertTrue("object".equals(s.get("type").getAsString()), tool.name() + "'s schema is not an object");
        }
        assertTrue(n > 40, "the registry offered only " + n + " tools; this test would pass over nothing");
        assertTrue(bad.isEmpty(), "a root-level combinator makes the CLI drop the tool for a Muse worker "
                + "(#2227) — use Schemas.requireOneOf, which marks the fields instead: " + bad);
    }

    @Test
    void theThreeToolsThatVanishedStillSayWhichFieldIsRequired() {
        for (McpTool tool : WishAgent.buildTools().all()) {
            String want = switch (tool.name()) {
                case "remove_event" -> "[id|name REQUIRED";
                case "inspect_event" -> "[name|id REQUIRED";
                case "add_template" -> "[all|any REQUIRED";
                default -> null;
            };
            if (want == null) {
                continue;
            }
            JsonObject props = tool.inputSchema().getAsJsonObject("properties");
            long marked = props.entrySet().stream()
                    .filter(e -> e.getValue().getAsJsonObject().has("description")
                            && e.getValue().getAsJsonObject().get("description").getAsString().startsWith(want))
                    .count();
            assertTrue(marked == 2, tool.name() + " should mark both alternative fields with " + want
                    + ", found " + marked);
        }
    }
}
