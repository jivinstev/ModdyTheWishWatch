package com.moddingfromamod.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** the two times on a shelf row, and the backfill that finds them for old rows. */
class ArtifactTimesTest {

    @Test
    void anOldRowStaysByteIdenticalUntilATimeIsKnown() {
        JsonObject row = JsonParser.parseString("{\"id\":\"spikor\",\"ownerId\":\"a1\"}")
                .getAsJsonObject();
        String before = row.toString();
        var book = new ArtifactTimes.Book();
        book.read("spikor", row);
        assertFalse(book.get("spikor").known());
        assertEquals(before, book.stamp("spikor", row.deepCopy()).toString(),
                "an unknown time is written as nothing, not as zero");
    }

    @Test
    void touchMakesThenUpdatesAndARoundTripKeepsBoth() {
        var book = new ArtifactTimes.Book();
        book.touch("Dragon", 100);
        book.touch("dragon", 250);
        JsonObject row = book.stamp("dragon", new JsonObject());
        assertEquals(100, row.get(ArtifactTimes.CREATED).getAsLong());
        assertEquals(250, row.get(ArtifactTimes.UPDATED).getAsLong());

        var reloaded = new ArtifactTimes.Book();
        reloaded.read("dragon", row);
        assertEquals(new ArtifactTimes.Times(100, 250), reloaded.get("dragon"));
    }

    @Test
    void theBackfillNeverOverwritesARecordedTime() {
        var book = new ArtifactTimes.Book();
        book.touch("castle", 900);
        assertFalse(book.fill("castle", new ArtifactTimes.Times(10, 10)));
        assertEquals(900, book.get("castle").createdAt());
        assertTrue(book.fill("tower", new ArtifactTimes.Times(10, 20)));
        assertEquals(20, book.get("tower").recency());
    }

    @Test
    void aDamagedFieldIsUnknownNotAnError() {
        JsonObject row = JsonParser.parseString("{\"createdAt\":\"soon\",\"updatedAt\":[1]}")
                .getAsJsonObject();
        assertFalse(ArtifactTimes.of(row).known());
    }

    @Test
    void theBackfillReadsMadeRecordsAndPrefersTheJournalsSettleTime() {
        String wish = "1f2e3d4c-0000-0000-0000-000000000000";
        List<String> log = List.of(
                "{\"at\":\"2026-09-01T10:00:00Z\",\"kind\":\"made\",\"wishId\":\"1f2e3d4c\","
                        + "\"made\":[{\"kind\":\"creature\",\"id\":\"dragon~1f2e3d4c\"},"
                        + "{\"kind\":\"build\",\"id\":\"castle\"}]}",
                "{\"at\":\"2026-09-03T10:00:00Z\",\"kind\":\"made\",\"wishId\":\"99999999\","
                        + "\"made\":[{\"kind\":\"build\",\"id\":\"castle\"}]}",
                "{\"at\":\"2026-09-02T10:00:00Z\",\"kind\":\"made\",\"wishId\":\"88888888\","
                        + "\"made\":[{\"kind\":\"item\",\"id\":\"zap_wand\"}]}",
                "{\"at\":\"2026-09-04T10:00:00Z\",\"kind\":\"tool_call\","
                        + "\"tool\":\"mcp__moddy__add_template\","
                        + "\"args\":\"{\\\"id\\\":\\\"chicken_rain\\\",\\\"all\\\":[\\\"chicken\\\"]}\"}",
                "not json at all {",
                "{\"at\":\"2026-09-05T10:00:00Z\",\"kind\":\"tool_call\",\"tool\":\"run_command\"}");
        List<String> journal = List.of(
                "{\"id\":\"" + wish + "\",\"createdMs\":1000,\"settledMs\":5000}");
        TimesBackfill b = TimesBackfill.fromLines(log, journal);

        assertEquals(new ArtifactTimes.Times(5000, 5000),
                b.timesOf(TimesBackfill.CREATURE, "dragon~1f2e3d4c"),
                "joined to the journal: the moment he saw it");
        var castle = b.timesOf(TimesBackfill.BUILD, "castle");
        assertEquals(5000, castle.createdAt(), "the first sighting is the making");
        assertEquals(java.time.Instant.parse("2026-09-03T10:00:00Z").toEpochMilli(),
                castle.updatedAt(), "the last sighting is the update");
        assertTrue(b.timesOf(TimesBackfill.ITEM, "id:zap_wand").known(),
                "the item catalogue's id: key finds the design's record");
        assertTrue(b.timesOf(TimesBackfill.TEMPLATE, "chicken_rain").known(),
                "a taught button's time is the add_template call");
        assertFalse(b.timesOf(TimesBackfill.CREATURE, "never_made").known(), "never invented");
    }
}
