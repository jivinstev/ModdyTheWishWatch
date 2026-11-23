package com.moddingfromamod.provenance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** a creation's model stamp rides on its owner's shelf. */
class ShelfProvenanceTest {

    private static final String KID = "a1b2c3d4-0000-0000-0000-000000000001";

    private static ModelStamp at(String model, long at) {
        return new ModelStamp(model, "low", "1.0", "1.21.4", "world", at);
    }

    @Test
    void aGrantIsOneRowAndTheSameGrantTwiceIsStillOne() {
        JsonObject root = new JsonObject();
        ShelfProvenance.add(root, "species", "spikor", at("muse:spark", 1000));
        ShelfProvenance.add(root, "species", "spikor", at("muse:spark", 1000));
        ShelfProvenance.add(root, "species", "spikor", at("opus", 2000));
        ShelfProvenance.add(root, "building", "castle", at("opus", 1500));
        assertEquals(3, root.getAsJsonArray("grants").size());
        List<ModelStamp> g = ShelfProvenance.grants(root, "species", "spikor");
        assertEquals(List.of("muse:spark", "opus"), g.stream().map(ModelStamp::model).toList());
    }

    @Test
    void pastTheCapTheFirstGrantIsKept() {
        JsonObject root = new JsonObject();
        for (int i = 0; i < StampLedger.MAX_GRANTS + 5; i++) {
            ShelfProvenance.add(root, "species", "spikor", at(i == 0 ? "first" : "m" + i, 1000 + i));
        }
        List<ModelStamp> g = ShelfProvenance.grants(root, "species", "spikor");
        assertEquals(StampLedger.MAX_GRANTS, g.size());
        assertEquals("first", g.get(0).model(), "the first grant says which model MADE it");
        assertEquals("m" + (StampLedger.MAX_GRANTS + 4), g.get(g.size() - 1).model());
    }

    @Test
    void recordWritesTheOwnersShelfAndReadFindsIt(@TempDir Path players) throws Exception {
        assertTrue(ShelfProvenance.record(players, KID, "species", "spikor", at("muse:spark", 1000)));
        assertTrue(ShelfProvenance.record(players, KID, "species", "spikor", at("opus", 2000)));
        Path f = players.resolve(KID).resolve("provenance.json");
        assertTrue(Files.exists(f));
        assertTrue(Files.readString(f).contains("\"store\":\"provenance\""), "the shelf envelope");
        assertEquals(2, ShelfProvenance.read(players, KID, "species", "spikor").size());
        assertEquals(0, ShelfProvenance.read(players, "someone-else", "species", "spikor").size());
    }

    @Test
    void anOwnerThatNamesNoShelfIsNotWritten(@TempDir Path players) throws Exception {
        assertFalse(ShelfProvenance.record(players, "", "species", "spikor", at("x", 1)));
        assertFalse(ShelfProvenance.record(players, "../escape", "species", "spikor", at("x", 1)));
        try (var s = Files.list(players)) {
            assertEquals(0, s.count());
        }
    }

    @Test
    void aNewerFileIsLeftAlone(@TempDir Path players) throws Exception {
        Path f = players.resolve(KID).resolve("provenance.json");
        Files.createDirectories(f.getParent());
        String newer = "{\"store\":\"provenance\",\"v\":99,\"grants\":[]}";
        Files.writeString(f, newer);
        assertFalse(ShelfProvenance.record(players, KID, "species", "spikor", at("x", 1)));
        assertEquals(newer, Files.readString(f));
    }

    @Test
    void aReaderGetsTheHouseLedgerAndTheShelfTogether(@TempDir Path dir) throws Exception {
        Path ledger = dir.resolve("provenance/stamps.json");
        Path players = dir.resolve("players");
        StampLedger.record(ledger, "species", "spikor", at("made-here", 1000));
        ShelfProvenance.record(players, KID, "species", "spikor", at("made-here", 1000));
        ShelfProvenance.record(players, KID, "species", "spikor", at("regranted-on-another-server", 5000));
        List<ModelStamp> all = ShelfProvenance.grantsFor(ledger, players, KID, "species", "spikor");
        assertEquals(List.of("made-here", "regranted-on-another-server"),
                all.stream().map(ModelStamp::model).toList(), "one list, oldest first, each grant once");
    }

    @Test
    void theOwnerComesFromTheArtifactsOwnJson() {
        assertEquals(KID, ShelfProvenance.ownerOf("{\"id\":\"spikor\",\"ownerId\":\"" + KID + "\"}"));
        assertEquals("", ShelfProvenance.ownerOf("{\"id\":\"spikor\"}"), "shared: server ledger only");
        assertEquals("", ShelfProvenance.ownerOf("not json at all"));
        assertEquals("", ShelfProvenance.ownerOf(null));
    }
}
