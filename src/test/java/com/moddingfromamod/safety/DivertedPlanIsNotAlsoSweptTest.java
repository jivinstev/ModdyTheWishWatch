package com.moddingfromamod.safety;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ActionLog;
import com.moddingfromamod.agent.HttpReply;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolRegistry;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.runtime.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A plan the L2 gate refused is not ALSO swept by L0 — because the tool body never ran.</b>
 *
 * <p>{@code McpProtocol}'s own comment states the rule ("a DIVERTED plan never reaches the sweep,
 * and that is correct: the tool body did not run either, so no word of it reached the child and a
 * 'text' record would be describing something that never happened") and the code it sat above did
 * the opposite: {@code SafetyTextSweep.sweep} was called unconditionally, one line before the
 * branch that decides whether the call proceeds.
 *
 * <p>The consequence is a log-correctness one rather than a refusal one, which is why it needs a
 * test rather than an eye: the sweep LOGS its hits and writes a grown-up note reading <i>"Moddy was
 * about to say …"</i>. So a diverted {@code plan_wish} whose own {@code status} also tripped the
 * lexicon left a {@code layer:"text"} record and a parent-facing note about a sentence no child
 * ever read. §6's whole premise is that a decision can be explained afterwards from the log, and a
 * log that describes things which did not happen cannot be counted.
 *
 * <p>Driven through the real {@code tools/call} dispatch rather than by calling the two gates in
 * turn, because the defect was entirely in the ORDER of two calls at that one seam — "a rule tested
 * only where it is DEFINED is not tested where it RUNS".
 */
class DivertedPlanIsNotAlsoSweptTest {

    /** A lexicon term that is unambiguous enough to be in the shipped floor. */
    private static final String FLOOR_TERM = "porn";

    private String homeBefore;

    @BeforeEach
    void ownHome(@TempDir Path dir) {
        homeBefore = System.getProperty(ModdingFromAModMod.MODID + ".home");
        System.setProperty(ModdingFromAModMod.MODID + ".home", dir.toString());
        System.setProperty(ModdingFromAModMod.MODID + ".childsafety", "on");
        RuntimeConfig.invalidate();
        ActionLog.resetForTest();
        SafetyRetryBudget.clearAll();
    }

    @AfterEach
    void restore() {
        SafetyGrader.forTest(null);
        SafetyRetryBudget.clearAll();
        if (homeBefore == null) {
            System.clearProperty(ModdingFromAModMod.MODID + ".home");
        } else {
            System.setProperty(ModdingFromAModMod.MODID + ".home", homeBefore);
        }
        System.clearProperty(ModdingFromAModMod.MODID + ".childsafety");
        RuntimeConfig.invalidate();
        ActionLog.resetForTest();
    }

    // ---- the harness ----

    private static McpTool planWish() {
        return new McpTool() {
            @Override public String name() {
                return SafetyPlanGate.GRADED_TOOL;
            }

            @Override public String description() {
                return "a stand-in plan_wish, so the dispatch seam can be driven without a game";
            }

            @Override public JsonObject inputSchema() {
                return Schemas.prop(Schemas.object(), "status", "string", "the child-visible line");
            }

            @Override public ToolResult call(JsonObject args) {
                return ToolResult.ok("planned");
            }
        };
    }

    private static McpProtocol.Headers headers() {
        Map<String, String> m = new HashMap<>();
        m.put("Content-Type", "application/json");
        m.put("Accept", "application/json, text/event-stream");
        return McpProtocol.Headers.of(m);
    }

    /** Drive one real {@code tools/call} with this {@code status}, and say whether it was refused. */
    private static boolean callPlanWish(String status) {
        McpProtocol protocol = new McpProtocol(new ToolRegistry().add(planWish()), new McpSessions());
        HttpReply init = protocol.handlePost(headers(),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", 0L);
        String session = init.headers().get("Mcp-Session-Id");
        Map<String, String> m = new HashMap<>();
        m.put("Content-Type", "application/json");
        m.put("Accept", "application/json, text/event-stream");
        m.put("Mcp-Session-Id", session);
        JsonObject args = new JsonObject();
        args.addProperty("status", status);
        JsonObject params = new JsonObject();
        params.addProperty("name", SafetyPlanGate.GRADED_TOOL);
        params.add("arguments", args);
        JsonObject call = new JsonObject();
        call.addProperty("jsonrpc", "2.0");
        call.addProperty("id", 2);
        call.addProperty("method", "tools/call");
        call.add("params", params);
        HttpReply r = protocol.handlePost(McpProtocol.Headers.of(m), call.toString(), 0L);
        return r.body().contains("\"isError\":true");
    }

    private static List<JsonObject> safetyRecords(Path home) throws Exception {
        Path f = home.resolve("agent-actions.jsonl");
        if (!Files.exists(f)) {
            return List.of();
        }
        return Files.readAllLines(f, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank())
                .map(l -> JsonParser.parseString(l).getAsJsonObject())
                .filter(o -> "safety".equals(o.has("kind") ? o.get("kind").getAsString() : ""))
                .toList();
    }

    // ---- the rule ----

    @Test
    void aDivertedPlanWritesNoTextRecordAndNoGrownUpNote(@TempDir Path dir) throws Exception {
        System.setProperty(ModdingFromAModMod.MODID + ".home", dir.toString());
        RuntimeConfig.invalidate();
        ActionLog.resetForTest();
        // A LIKELY plan with a usable redirect is the DIVERT row — the tool body does not run.
        SafetyGrader.forTest(text -> new SafetyVerdict(
                SafetyVerdict.Band.LIKELY, SafetyVerdict.Band.FINE, SafetyVerdict.Band.FINE,
                SafetyVerdict.Band.FINE, false, List.of(SafetyVerdict.Reason.GORE),
                "a zombie that bonks villagers with a rubber chicken",
                SafetyVerdict.Provenance.HAIKU, 0));

        assertTrue(callPlanWish("building the " + FLOOR_TERM + " machine"),
                "the plan gate should have refused this call");

        List<JsonObject> records = safetyRecords(dir);
        assertFalse(records.isEmpty(), "the PLAN grade itself must still be logged (§6)");
        assertTrue(records.stream().anyMatch(o -> "plan".equals(o.get("layer").getAsString())),
                "the plan layer is what actually decided, and its record is the denominator");
        assertEquals(0, records.stream()
                        .filter(o -> "text".equals(o.get("layer").getAsString())).count(),
                "a diverted plan reached no child, so there is nothing for a `text` record to "
                        + "describe — the sweep must not have run at all");
        assertFalse(Files.exists(dir.resolve("grown-up-notes.jsonl")),
                "and no parent-facing note about a sentence that was never said");
    }

    @Test
    void aPassedPlanIsStillSwept(@TempDir Path dir) throws Exception {
        // The half the fix must not break: when L2 lets the call through, L0 still reads the
        // child-visible argument and still refuses it.
        System.setProperty(ModdingFromAModMod.MODID + ".home", dir.toString());
        RuntimeConfig.invalidate();
        ActionLog.resetForTest();
        SafetyGrader.forTest(text -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));

        assertTrue(callPlanWish("building the " + FLOOR_TERM + " machine"),
                "the text sweep should have refused a child-visible line on the floor");
        assertTrue(safetyRecords(dir).stream()
                        .anyMatch(o -> "text".equals(o.get("layer").getAsString())
                                && "BLOCK".equals(o.get("action").getAsString())),
                "the sweep really ran and really logged, so the fix is an ORDERING change and "
                        + "not a removal");
    }
}
