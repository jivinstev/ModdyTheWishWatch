package com.moddingfromamod.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * <b>The engine's half of {@code spend.wish}</b>.
 *
 * <p>{@code tools/wish-cost.py} counts a wish's tokens from the CLI's transcripts and nowhere
 * else; these are the two things it cannot know without the engine. {@code workerName} is the
 * join key that charges a model turn to the wish its OWN worker held — without it two children's
 * overlapping wishes are priced from one time window, which recorded 13 true turns as 7
 * ({@code tools/test-wish-cost.py TwoWorkersAtOnce}). And the {@code settled} record is the
 * outcome, the child (a UUID, never a name) and the model that was ASKED FOR.
 */
class SpendRecordTest {

    private String homeBefore;

    @BeforeEach
    void ownHome(@TempDir Path dir) {
        homeBefore = System.getProperty(ModdingFromAModMod.MODID + ".home");
        System.setProperty(ModdingFromAModMod.MODID + ".home", dir.toString());
        RuntimeConfig.invalidate();
        ActionLog.resetForTest();
    }

    @AfterEach
    void restore() {
        AgentContext.unbindWorkerName();
        if (homeBefore == null) {
            System.clearProperty(ModdingFromAModMod.MODID + ".home");
        } else {
            System.setProperty(ModdingFromAModMod.MODID + ".home", homeBefore);
        }
        RuntimeConfig.invalidate();
        ActionLog.resetForTest();
    }

    static JsonObject lastRecord() throws Exception {
        List<String> lines = Files.readAllLines(ActionLog.path(), StandardCharsets.UTF_8);
        assertFalse(lines.isEmpty(), "nothing was written");
        return JsonParser.parseString(lines.get(lines.size() - 1)).getAsJsonObject();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    @Test
    void aSettledRecordCarriesWhatOnlyTheEngineKnows() throws Exception {
        ActionLog.settled("abcd1234", "11111111-2222-3333-4444-555555555555", "DONE", "TYPED",
                183_000L, "muse:muse-spark-1.2-contributor", "low");
        JsonObject o = lastRecord();

        assertEquals("settled", str(o, "kind"));
        assertEquals("abcd1234", str(o, "wishId"));
        assertEquals("11111111-2222-3333-4444-555555555555", str(o, "playerId"));
        assertEquals("DONE", str(o, "status"));
        assertEquals("TYPED", str(o, "source"));
        assertEquals(183_000L, o.get("tookMs").getAsLong());
        // the SPEC, provider and all: the contributor tier is priced by what was asked for
        assertEquals("muse:muse-spark-1.2-contributor", str(o, "model"));
        assertEquals("low", str(o, "effort"));
        for (String leak : List.of("playerName", "name", "text")) {
            assertFalse(o.has(leak), "a cost record must never carry " + leak);
        }
    }

    @Test
    void theCallingSlotIsStampedOnTheRecordsItsRequestWrites() throws Exception {
        AgentContext.bindWorkerName("wish-granter-2");
        ActionLog.toolCall("run_command", new JsonObject(), ToolResult.ok("x"), 5, "abcd1234");
        assertEquals("wish-granter-2", str(lastRecord(), "workerName"));
    }

    @Test
    void noSlotMeansNoField() throws Exception {
        ActionLog.toolCall("run_command", new JsonObject(), ToolResult.ok("x"), 5, "abcd1234");
        assertFalse(lastRecord().has("workerName"),
                "a gate or stock call must be told apart from a slot named \"\"");
    }

    @Test
    void aHeaderThatIsNotASlotNameIsDroppedNotLogged() throws Exception {
        // The header is authenticated but its VALUE is whatever the client typed, and it lands
        // in a file that is mined into committed corpora.
        for (String bad : List.of("wish granter", "a\nkind:\"forged\"", "x".repeat(65), "")) {
            AgentContext.bindWorkerName("wish-granter-1");
            AgentContext.bindWorkerName(bad);
            ActionLog.toolCall("run_command", new JsonObject(), ToolResult.ok("x"), 5, "abcd1234");
            assertFalse(lastRecord().has("workerName"), "stamped an unsafe name: " + bad);
        }
    }
}
