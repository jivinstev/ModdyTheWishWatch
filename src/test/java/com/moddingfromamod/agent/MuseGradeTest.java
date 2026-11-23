package com.moddingfromamod.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Muse Code grader path (docs/EMBER_GRADERS.md): the pure half, and the process half against a
 * fake {@code muse} that prints the JSONL events the real one prints (measured 2026-11-15).
 */
@org.junit.jupiter.api.extension.ExtendWith(com.moddingfromamod.config.MagicianOn.class)   // fake CLIs run only with Magician on
class MuseGradeTest {

    @TempDir
    Path tmp;

    /** Gate A pins this to `none` (gate-a.gradle); put the pin back, never clear it. */
    private final String pinned = System.getProperty("moddingfromamod.muse.bin");

    @AfterEach
    void restore() {
        if (pinned == null) {
            System.clearProperty("moddingfromamod.muse.bin");
        } else {
            System.setProperty("moddingfromamod.muse.bin", pinned);
        }
    }

    @Test
    void aMuseSpecNamesItsModel() {
        assertTrue(MuseGrade.isMuse("muse:muse-ember-1.0"));
        assertTrue(MuseGrade.isMuse(MuseGrade.EMBER));
        assertFalse(MuseGrade.isMuse("haiku"));
        assertFalse(MuseGrade.isMuse("claude-haiku-5-5"));
        assertFalse(MuseGrade.isMuse(null));
        assertEquals("muse-ember-1.0", MuseGrade.modelId("muse:muse-ember-1.0"));
    }

    @Test
    void anOptionalFieldIsSentRequiredAndNullableAndDroppedWhenNull() {
        // Meta's API refuses a schema whose properties are not all `required` (400, measured).
        JsonObject schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{"
                + "\"violence\":{\"type\":\"string\"},\"redirect\":{\"type\":\"string\",\"maxLength\":200}},"
                + "\"required\":[\"violence\"],\"additionalProperties\":false}").getAsJsonObject();
        JsonObject strict = MuseGrade.strictSchema(schema);
        assertEquals(2, strict.getAsJsonArray("required").size());
        assertEquals("[\"string\",\"null\"]",
                strict.getAsJsonObject("properties").getAsJsonObject("redirect").get("type").toString());
        assertEquals(200, strict.getAsJsonObject("properties").getAsJsonObject("redirect").get("maxLength").getAsInt());
        assertEquals("\"string\"", schema.getAsJsonObject("properties").getAsJsonObject("redirect")
                .get("type").toString(), "the shipped schema is not touched");
        JsonObject verdict = JsonParser.parseString("{\"violence\":\"FINE\",\"redirect\":null}").getAsJsonObject();
        assertEquals("{\"violence\":\"FINE\"}", MuseGrade.dropNulls(verdict, schema).toString());
    }

    @Test
    void theArgvCarriesTheGradersPromptAndNoToolsAndContributorOff() {
        List<String> a = MuseGrade.argv("/bin/muse", "muse-ember-1.0", "PROMPT \"x\"", "low", "/t/s.json", true, "wish");
        assertEquals(List.of("/bin/muse", "exec", "--api-key-stdin", "--json", "--model", "muse-ember-1.0"),
                a.subList(0, 6));
        assertTrue(a.contains("run.system_prompt=\"PROMPT \\\"x\\\"\""));
        assertTrue(a.containsAll(List.of("--disable-shell", "--disable-write", "--no-session-log")));
        assertEquals("off", a.get(a.indexOf("--contributor-mode") + 1));
        assertEquals("/t/s.json", a.get(a.indexOf("--output-schema") + 1));
        assertEquals("low", a.get(a.indexOf("--reasoning-effort") + 1));
        assertEquals("wish", a.get(a.size() - 1));
        List<String> plain = MuseGrade.argv("/bin/muse", "m", null, null, null, false, "w");
        assertFalse(plain.contains("--output-schema"));
        assertFalse(plain.contains("--api-key-stdin"));
        assertFalse(plain.contains("--reasoning-effort"));
        assertTrue(plain.contains("run.system_prompt=\"" + MuseGrade.PLAIN_SYSTEM_PROMPT + "\""));
    }

    static final String RUN = String.join("\n",
            "{\"payload_type\":\"run.usage\",\"payload\":{\"models\":[{\"model\":\"muse-ember-1.0\","
                    + "\"input_tokens\":9856,\"cached_tokens\":0,\"output_tokens\":200}]}}",
            "not json",
            "{\"payload_type\":\"run.terminal.completed\",\"payload\":{\"terminal\":\"completed\","
                    + "\"text\":\"{\\\"violence\\\":\\\"FINE\\\",\\\"redirect\\\":null}\"}}");

    @Test
    void theRunIsReadFromItsEventsAndPricedFromItsTokens() {
        MuseGrade.Run r = MuseGrade.parseRun(RUN, "muse-ember-1.0");
        assertEquals("{\"violence\":\"FINE\",\"redirect\":null}", r.finalText());
        assertEquals(9856, r.inputTokens());
        assertNull(r.failure());
        assertEquals((9856 * 0.10 + 200 * 0.50) / 1e6, MuseGrade.price("muse-ember-1.0", r), 1e-12);
        assertEquals(0.0, MuseGrade.price("unknown-model", r));
        MuseGrade.Run failed = MuseGrade.parseRun("{\"payload_type\":\"run.terminal.failed\",\"payload\":"
                + "{\"terminal\":\"failed\",\"reason\":\"API error 400\"}}", "muse-ember-1.0");
        assertNull(failed.finalText());
        assertTrue(failed.failure().startsWith("failed"));
    }

    private void fakeMuse(String stdout) throws Exception {
        Path out = tmp.resolve("out.jsonl");
        Files.writeString(out, stdout);
        Path bin = tmp.resolve("muse");
        Files.writeString(bin, "#!/bin/sh\ncat > /dev/null\ncat '" + out + "'\n");
        assertTrue(bin.toFile().setExecutable(true));
        System.setProperty("moddingfromamod.muse.bin", bin.toString());
    }

    @Test
    void aGradeThroughAFakeMuseComesBackWithItsOptionalNullsDropped() throws Exception {
        fakeMuse(RUN);
        MuseExec.Answer a = MuseExec.grade("P", "wish", "{\"type\":\"object\",\"properties\":{"
                        + "\"violence\":{\"type\":\"string\"},\"redirect\":{\"type\":\"string\"}},\"required\":[\"violence\"]}",
                "muse-ember-1.0", "low", 10_000);
        assertNotNull(a);
        assertEquals("{\"violence\":\"FINE\"}", a.text());
        assertEquals("muse-ember-1.0", a.modelId());
        assertTrue(a.costUsd() > 0);
    }

    @Test
    void noAnswerFromMuseIsNullSoTheCallerFallsBack() throws Exception {
        fakeMuse("{\"payload_type\":\"run.terminal.failed\",\"payload\":{\"terminal\":\"failed\"}}");
        assertNull(MuseExec.grade("P", "w", "{\"type\":\"object\",\"properties\":{}}", "muse-ember-1.0", "low", 10_000));
        fakeMuse("{\"payload_type\":\"run.terminal.completed\",\"payload\":{\"terminal\":\"completed\",\"text\":\"prose\"}}");
        assertNull(MuseExec.grade("P", "w", "{\"type\":\"object\",\"properties\":{}}", "muse-ember-1.0", "low", 10_000),
                "an answer that is not the JSON asked for is no answer");
        System.setProperty("moddingfromamod.muse.bin", tmp.resolve("absent").toString());
        assertNull(MuseExec.binary());
        assertNull(MuseExec.text(null, "w", "muse-ember-1.0", "low", 10_000), "no binary is no answer");
    }
}
