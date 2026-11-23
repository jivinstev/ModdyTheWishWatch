package com.moddingfromamod.world;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** the pickup hold is counted in world-rules.jsonl, in a closed vocabulary and with no words. */
class WorldRulesLogTest {

    @Test
    void aHoldIsOneRecordWithItsDelayAndAClosedOutcome(@TempDir Path d) throws Exception {
        Path f = d.resolve("world-rules.jsonl");
        WorldRulesLog.fileForTest(f);
        try {
            WorldRulesLog.hold("abcd1234", "00000000-0000-0000-0000-000000000001", 5123, "REWORDED");
            WorldRulesLog.hold("abcd1235", "", -4, "make me a dragon");   // never free text
        } finally {
            WorldRulesLog.fileForTest(null);
        }
        var lines = Files.readAllLines(f);
        assertEquals(2, lines.size());
        JsonObject a = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("hold", a.get("kind").getAsString());
        assertEquals(5123, a.get("landedMs").getAsLong());
        assertEquals("REWORDED", a.get("outcome").getAsString());
        JsonObject b = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertEquals("FAILED", b.get("outcome").getAsString(), "an outcome outside the vocabulary is FAILED");
        assertEquals(0, b.get("landedMs").getAsLong());
        assertFalse(lines.get(1).contains("dragon"), "the record never carries the words it was handed");
    }

    /**
     * The rule where it RUNS: every phrase {@code AwaitWishTool.resolve} returns maps to a real outcome,
     * never FAILED — so a reworded resolve() cannot silently turn every hold into a failure.
     */
    @Test
    void everyOutcomeResolveReturnsHasItsOwnWord() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/moddingfromamod/agent/tools/AwaitWishTool.java"));
        int at = src.indexOf("static String resolve(");
        assertTrue(at > 0, "AwaitWishTool.resolve is where the hold's outcome comes from");
        String body = src.substring(at, src.indexOf("\n    }\n", at));
        Matcher m = Pattern.compile("\"(cleared[^\"]*|refused[^\"]*|reworded[^\"]*)\"").matcher(body);
        int n = 0;
        while (m.find()) {
            n++;
            assertNotEquals("FAILED", WorldRulesLog.holdOutcome(m.group(1)), m.group(1));
        }
        assertTrue(n >= 4, "resolve() returns cleared, cleared with a note, refused, reworded — found " + n);
        assertEquals("CLEARED", WorldRulesLog.holdOutcome("cleared with a note"));
        assertEquals("FAILED", WorldRulesLog.holdOutcome(null));
    }
}
