package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.store.WishStoreFile;

/**
 * <b>The census of #104's stat-line repair</b>: a species store as players' shelves hold it
 * ({@code corpus/store-shapes/species-v1-stat-lines.json}: records saved under the old tool wording, with no record
 * version, and one made at v3), repaired as {@code SpeciesRegistry.load} repairs it. It says how many records changed and
 * which, that each changed one changed only its damage, and that every other record came back byte for byte (and, read
 * and written again, differs only by the record version it is now written at).
 */
class StatLineRepairCensusTest {

    static final Path CORPUS = Path.of("corpus", "store-shapes");

    private static JsonArray records() throws Exception {
        WishStoreFile.Read read = WishStoreFile.read(Files.readString(CORPUS.resolve("species-v1-stat-lines.json")),
                SpeciesRegistry.STORE, SpeciesRegistry.SCHEMA);
        assertTrue(read instanceof WishStoreFile.Read.Ok, "the store reads: " + read);
        return ((WishStoreFile.Read.Ok) read).root().getAsJsonArray("species");
    }

    private static JsonObject find(JsonArray a, String id) {
        for (JsonElement el : a) {
            if (el.getAsJsonObject().get("id").getAsString().equals(id)) {
                return el.getAsJsonObject();
            }
        }
        throw new AssertionError("no " + id);
    }

    @Test
    @DisplayName("the census: which records the repair changed, how, and that every other came back byte for byte")
    void theCensus() throws Exception {
        JsonArray before = records();
        assertEquals(12, before.size());
        assertEquals(11, StatLineRepair.below(before, Species.RECORD_VERSION), "11 saved under the old wording, 1 at v3");
        StatLineRepair.Result r = StatLineRepair.repairAll(before, rec -> Double.NaN);
        List<String> want = List.of("guard_bear~1f2e3d4c", "hunter_wolf~1f2e3d4c", "friendly_bunny~1f2e3d4c",
                "coward_mouse~1f2e3d4c");
        assertEquals(want, r.repaired(), "4 of 12 changed: the guardian and the hunter at 0, the two friendly ones at 0");
        int same = 0;
        for (int i = 0; i < before.size(); i++) {
            JsonObject was = before.get(i).getAsJsonObject();
            JsonObject now = r.records().get(i).getAsJsonObject();
            String id = was.get("id").getAsString();
            if (!want.contains(id)) {
                assertEquals(was.toString(), now.toString(), id + " came back byte for byte");
                JsonObject saved = Species.fromJson(now).toJson();
                assertEquals(Species.RECORD_VERSION, saved.remove("v").getAsInt(), id + " is written at the current version");
                was.remove("v");
                assertEquals(was.toString(), saved.toString(), id + ": a load and save adds only its version");
                same++;
                continue;
            }
            assertEquals(was.keySet(), now.keySet(), id + " gained or lost no field");
            for (var e : was.entrySet()) {
                if (!e.getKey().equals("damage")) {
                    assertEquals(e.getValue(), now.get(e.getKey()), id + "." + e.getKey() + " is unchanged");
                }
            }
            assertEquals(0.0, was.get("damage").getAsDouble());
        }
        assertEquals(8, same, "8 of 12 kept exactly as they were");
        assertEquals(3.0, find(r.records(), "guard_bear~1f2e3d4c").get("damage").getAsDouble());     // small humanoid
        assertEquals(1.0, find(r.records(), "friendly_bunny~1f2e3d4c").get("damage").getAsDouble()); // scale 0.5: tiny
    }

    @Test
    @DisplayName("what it never touches: a v3 friendly at 0, a hit already chosen, a vehicle, a wild one, a runner, a pet")
    void whatItLeavesAlone() throws Exception {
        JsonArray before = records();
        for (String id : List.of("made_now~1f2e3d4c", "strong_knight~1f2e3d4c", "giant_boss~1f2e3d4c", "tiny_sprite~1f2e3d4c",
                "toy_car~1f2e3d4c", "angry_blob~1f2e3d4c", "shy_cat~1f2e3d4c", "lazy_pet~1f2e3d4c")) {
            assertNull(StatLineRepair.repair(find(before, id), Double.NaN), id + " needs nothing");
        }
    }

    @Test
    @DisplayName("a v<3 fighter at 0 is repaired once; written back at v3, it is never repaired again")
    void itRunsOncePerRecord() throws Exception {
        JsonObject guard = find(records(), "guard_bear~1f2e3d4c");
        JsonObject fixed = StatLineRepair.repair(guard, Double.NaN);
        assertTrue(fixed != null && fixed.get("damage").getAsDouble() > 0);
        JsonObject written = Species.fromJson(fixed).toJson();
        assertEquals(Species.RECORD_VERSION, Species.recordVersion(written));
        written.addProperty("damage", 0.0);   // even if it were 0 again, a v3 record is made under #101's wording
        assertNull(StatLineRepair.repair(written, Double.NaN));
    }

    @Test
    @DisplayName("a sculpted body is measured: a big sculpt gets a big creature's damage")
    void itMeasuresTheBody() throws Exception {
        JsonObject fixed = StatLineRepair.repair(find(records(), "guard_bear~1f2e3d4c"), 20.0);
        assertEquals(PowerBand.forVolume(20.0).damage(), fixed.get("damage").getAsDouble());
    }

    @Test
    @DisplayName("the store envelope stays v1, so an older build still reads it; the record carries the version")
    void aDowngradeStillReads() throws Exception {
        assertEquals(1, SpeciesRegistry.SCHEMA, "the store's own schema is unchanged: the version is in the record");
        String text = Files.readString(CORPUS.resolve("species-v1-stat-lines.json"));
        assertTrue(WishStoreFile.read(text, SpeciesRegistry.STORE, 1) instanceof WishStoreFile.Read.Ok);
        JsonObject v3 = find(records(), "made_now~1f2e3d4c");
        assertEquals("Made Now", Species.fromJson(v3).name(), "a record with a version reads as before (the field is ignored)");
        assertTrue(JsonParser.parseString(v3.toString()).getAsJsonObject().has("v"), "inline, not nested");
    }
}
