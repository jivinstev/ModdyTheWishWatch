package com.moddingfromamod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/** Who may report whom, how often, and what the record carries. */
class PlayerReportTest {

    private static final UUID ANN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final long MIN = 60_000L;

    @Test
    void namesSelfAndRateLimits() {
        PlayerReport r = new PlayerReport();
        assertEquals(PlayerReport.Verdict.BAD_NAME, r.check(ANN, "Ann", "no spaces allowed", 0));
        assertEquals(PlayerReport.Verdict.BAD_NAME, r.check(ANN, "Ann", "ab", 0));
        assertEquals(PlayerReport.Verdict.SELF, r.check(ANN, "Ann", "ann", 0));
        assertEquals(PlayerReport.Verdict.SAVE, r.check(ANN, "Ann", "Bob", 0));
        assertEquals(PlayerReport.Verdict.TOO_MANY, r.check(ANN, "Ann", "bob", MIN), "the same player within 10 min");
        assertEquals(PlayerReport.Verdict.SAVE, r.check(ANN, "Ann", "Cat", 2 * MIN));
        assertEquals(PlayerReport.Verdict.SAVE, r.check(ANN, "Ann", "Bob", 11 * MIN));
        assertEquals(PlayerReport.Verdict.TOO_MANY, r.check(ANN, "Ann", "Dan", 12 * MIN), "three an hour");
        assertEquals(PlayerReport.Verdict.SAVE, r.check(UUID.randomUUID(), "Eve", "Dan", 12 * MIN),
                "another reporter has their own allowance");
        assertEquals(PlayerReport.Verdict.SAVE, r.check(ANN, "Ann", "Dan", 61 * MIN), "an hour later");
    }

    @Test
    void theRecordCarriesWhoWhatAndTheLines() {
        List<ChatHistory.Line> lines = List.of(new ChatHistory.Line(0, "chat.chat", "hello", ""),
                new ChatHistory.Line(1, "chat.msg", "psst", "Ann"));
        JsonObject o = PlayerReport.record("id-1", 0, "closed", ANN, "Ann", "Bob", null, false,
                "  he kept\n saying   mean things " + "x".repeat(400), lines, 1, false);
        assertEquals("player-report", o.get("kind").getAsString());
        assertEquals("closed", o.get("world").getAsString());
        assertEquals("Ann", o.getAsJsonObject("reporter").get("name").getAsString());
        assertEquals("Bob", o.getAsJsonObject("reported").get("name").getAsString());
        assertFalse(o.getAsJsonObject("reported").has("id"), "no id when he is not known");
        assertFalse(o.getAsJsonObject("reported").get("online").getAsBoolean());
        String note = o.get("note").getAsString();
        assertTrue(note.startsWith("he kept saying mean things"), note);
        assertEquals(PlayerReport.NOTE_MAX, note.length());
        assertEquals(2, o.getAsJsonArray("lines").size());
        assertEquals("Ann", o.getAsJsonArray("lines").get(1).getAsJsonObject().get("to").getAsString());
        assertEquals(1, o.get("flagged").getAsInt());
    }
}
