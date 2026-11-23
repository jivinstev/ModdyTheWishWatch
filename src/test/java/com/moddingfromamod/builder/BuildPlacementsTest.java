package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Gate A: a building's position, saved per world on its own record. */
class BuildPlacementsTest {

    private static final long SEED = 123456789L;

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static BuildPlacements.Placement at(int x, int y, int z) {
        return new BuildPlacements.Placement("minecraft:overworld", x, y, z, 4, 9, SEED, false);
    }

    @Test
    void aPlacementRoundTrips() {
        var p = new BuildPlacements.Placement("minecraft:the_nether", 10, 64, -3, 4, -9, SEED, true);
        assertEquals(p, BuildPlacements.Placement.fromJson(p.toJson()));
        var bare = new BuildPlacements.Placement(null, 1, 2, 3, 0, 0, null, false);
        assertEquals("{\"x\":1,\"y\":2,\"z\":3}", bare.toJson().toString(), "unknowns are written as nothing");
        assertEquals(bare, BuildPlacements.Placement.fromJson(bare.toJson()));
    }

    @Test
    void aRowThatIsNotAPositionIsNeverGuessed() {
        assertNull(BuildPlacements.Placement.fromJson(json("{\"x\":1,\"y\":2}")));
        assertNull(BuildPlacements.Placement.fromJson(json("{\"x\":\"far\",\"y\":2,\"z\":3}")));
        assertNull(BuildPlacements.Placement.fromJson(null));
    }

    @Test
    void aBuildingNeverPutUpStaysByteIdentical() {
        JsonObject record = json("{\"id\":\"castle\",\"name\":\"Castle\",\"shapes\":[]}");
        String before = record.toString();
        BuildPlacements.stamp(record, Map.of(), "open", null);
        assertEquals(before, record.toString());
    }

    @Test
    void thisWorldsRowIsReplacedAndEveryOtherWorldsIsKept() {
        JsonObject read = json("{\"id\":\"castle\",\"placed\":{"
                + "\"survival\":{\"x\":500,\"y\":70,\"z\":500,\"seed\":42},"
                + "\"open\":{\"x\":1,\"y\":1,\"z\":1,\"seed\":" + SEED + "}}}");
        var rows = BuildPlacements.rows(read);
        JsonObject out = BuildPlacements.stamp(json("{\"id\":\"castle\"}"), rows, "open", at(10, 64, -3));
        var after = BuildPlacements.rows(out);
        assertEquals(rows.get("survival"), after.get("survival"), "another world's position is not ours to touch");
        assertEquals(at(10, 64, -3), BuildPlacements.Placement.fromJson(after.get("open")));
        // and nothing placed here this run: every row comes back as read
        JsonObject kept = BuildPlacements.stamp(json("{\"id\":\"castle\"}"), rows, "open", null);
        assertEquals(read.get("placed"), kept.get("placed"));
    }

    @Test
    void itStandsHereOnlyInItsOwnWorldWithItsOwnSeed() {
        var rows = BuildPlacements.rows(json("{\"placed\":{\"open\":" + at(1, 2, 3).toJson() + "}}"));
        var here = BuildPlacements.find(rows, "open", SEED);
        assertEquals(BuildPlacements.Status.HERE, here.status());
        assertEquals(at(1, 2, 3), here.here());

        var other = BuildPlacements.find(rows, "survival", SEED);
        assertEquals(BuildPlacements.Status.ELSEWHERE, other.status());
        assertEquals("open", other.otherWorld());
        assertNull(other.here());

        // the same NAME, made again: its seed says the old blocks are gone — never cleared here
        var remade = BuildPlacements.find(rows, "open", 999L);
        assertEquals(BuildPlacements.Status.REMADE, remade.status());
        assertNull(remade.here());
    }

    @Test
    void aBuildingSavedBeforePositionsWereSavedIsUnknownAndSaysSo() {
        var none = BuildPlacements.find(BuildPlacements.rows(json("{\"id\":\"castle\"}")), "open", SEED);
        assertEquals(BuildPlacements.Status.UNKNOWN, none.status());
        String said = BuildPlacements.whyNotHere("castle", none.status(), null);
        assertTrue(said.startsWith("I don't know where 'castle' is any more"), said);
        assertTrue(said.contains("design is still saved"), said);
    }

    @Test
    void aBuildingTakenDownIsRemovedNotStanding() {
        var rows = BuildPlacements.rows(json("{\"placed\":{\"open\":"
                + at(1, 2, 3).withRemoved(true).toJson() + "}}"));
        var f = BuildPlacements.find(rows, "open", SEED);
        assertEquals(BuildPlacements.Status.REMOVED, f.status());
        assertEquals(at(1, 2, 3).withRemoved(true), f.here(), "the position is kept for a rebuild in place");
        assertTrue(BuildPlacements.whyNotHere("castle", f.status(), null).contains("taken down"));
        // and a removed copy in another world is not "standing elsewhere" either
        assertEquals(BuildPlacements.Status.UNKNOWN, BuildPlacements.find(rows, "survival", SEED).status());
    }

    @Test
    void anOldReaderStillFindsItsKeys() {
        // INLINE: a jar from reads the plan's own keys and ignores `placed`.
        BuildPlan plan = BuildSpotsTest.hall(true);
        JsonObject written = BuildPlacements.stamp(plan.toJson(), Map.of(), "open", at(1, 2, 3));
        assertTrue(written.has("placed"));
        assertEquals(plan, BuildPlan.fromJson(written));
        assertFalse(plan.toJson().has("placed"), "the plan itself never writes a position");
    }
}
