package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** what a Muse Code worker spent, from its session log's model_completed steps (shape measured, 1.4.23). */
class MuseUsageTest {

    private static String step(long atMs, long in, long cached, long out) {
        // A real worker's record (Muse Code 1.4.23, a smoke's session.jsonl), the numbers changed.
        return "{\"schema_version\":1,\"recorded_at\":" + atMs * 1000 + ",\"record_type\":\"event\","
                + "\"payload_type\":\"runtime.session\",\"payload\":{\"event\":{\"duration_ms\":2612,"
                + "\"finish_reason\":\"tool_calls\",\"kind\":\"model_completed\",\"model\":\"muse-spark-1.2-contributor\","
                + "\"usage\":{\"cache_read_tokens\":0,\"cache_write_tokens\":0,\"cached_tokens\":" + cached
                + ",\"input_tokens\":" + in + ",\"output_tokens\":" + out + ",\"reasoning_tokens\":3}},\"kind\":\"run\"}}";
    }

    private static final List<String> LOG = List.of(
            step(1_000, 20_000, 0, 90),
            "{\"recorded_at\":1500000,\"payload\":{\"kind\":\"tool_started\"}}",
            "not json",
            step(2_000, 20_300, 20_000, 40),
            step(9_000, 1_000_000, 0, 1_000));

    @Test
    void stepsInTheWindowAreSummedAndPricedOnAKey() {
        var t = MuseUsage.sum(LOG, 0, 5_000, "muse-spark-1.2-contributor", true);
        assertEquals(2, t.steps());
        assertEquals(40_300, t.inputTokens());
        assertEquals(20_000, t.cachedTokens(), "1.4.23 records the hit as cached_tokens");
        assertEquals(130, t.outputTokens());
        // fresh 20,300 × $0.10 + cached 20,000 × $0.002 + out 130 × $0.20, per million
        assertEquals((20_300 * 0.10 + 20_000 * 0.002 + 130 * 0.20) / 1e6, t.costUsd(), 1e-12);
    }

    @Test
    void aSubscriptionsTokensAreCountedNeverPriced() {
        var t = MuseUsage.sum(LOG, 0, 10_000, "muse-wonder-1.0", false);
        assertEquals(3, t.steps());
        assertEquals(0.0, t.costUsd());
    }

    @Test
    void theSessionsLogIsFoundByItsPinnedId(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("muse/sessions");
        Path s = Files.createDirectories(root.resolve("2026/11/18/abc-123"));
        Files.write(s.resolve("session.jsonl"), LOG);
        Files.createDirectories(root.resolve("2026/11/18/other"));
        Files.write(root.resolve("2026/11/18/other/session.jsonl"), List.of(step(1_500, 5, 0, 5)));
        assertEquals(2, MuseUsage.of(root, "abc-123", 0, 5_000, "muse-spark-1.2-contributor", true).steps());
        assertEquals(0, MuseUsage.of(root, "missing", 0, 5_000, "x", true).steps());
        assertEquals(dir.resolve("muse/sessions"), MuseUsage.sessionsRoot(k -> k.equals("XDG_DATA_HOME")
                ? dir.toString() : null, "/home/me"));
    }
}
