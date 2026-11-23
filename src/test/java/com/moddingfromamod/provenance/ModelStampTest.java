package com.moddingfromamod.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model-stamp half: which model made an artifact, recorded on every server (the ledger)
 * and in a Mod Repo manifest, carried by a promotion — and never guessed for anything older.
 */
class ModelStampTest {

    private static final ModelStamp OPUS = new ModelStamp("opus", "low", "1.4.0", "1.21.4", "world",
            1_000L);
    private static final ModelStamp MUSE = new ModelStamp("muse:x", "", "1.4.1", "1.21.1", "w2",
            2_000L);

    @AfterEach
    void reset() {
        System.clearProperty("moddingfromamod.persistDir");
        System.clearProperty("moddingfromamod.home");
        com.moddingfromamod.runtime.RuntimeConfig.invalidate();
    }

    @Test
    void aStampRoundTripsAndCarriesNoPersonAndNoWords() {
        assertEquals(OPUS, ModelStamp.fromJson(OPUS.toJson()));
        assertEquals(java.util.Set.of("model", "effort", "mod", "mc", "world", "at"),
                OPUS.toJson().keySet());
    }

    @Test
    void currentNeverThrowsWithoutAGame() {
        ModelStamp s = ModelStamp.current();
        assertTrue(s.at() > 0);
        assertEquals("opus", s.model(), "the shipped default when nothing overrides it");
    }

    @Test
    void theLedgerKeepsEveryGrantInOrderAndTheFirstForever() {
        JsonObject root = new JsonObject();
        StampLedger.add(root, "species", "spikor~1f2e3d4c", OPUS);
        StampLedger.add(root, "species", "spikor~1f2e3d4c", MUSE);
        assertEquals(List.of(OPUS, MUSE), StampLedger.grants(root, "species", "spikor~1f2e3d4c"));
        assertEquals(List.of(), StampLedger.grants(root, "species", "older"),
                "no stamp = unattributable, never a guess");
        for (int i = 0; i < StampLedger.MAX_GRANTS + 10; i++) {
            StampLedger.add(root, "build", "tower", i == 0 ? OPUS : MUSE);
        }
        List<ModelStamp> g = StampLedger.grants(root, "build", "tower");
        assertEquals(StampLedger.MAX_GRANTS, g.size(), "bounded");
        assertEquals(OPUS, g.get(0), "the grant that MADE it is never dropped");
    }

    @Test
    void theFileIsAVersionedStoreAndANewerOneIsLeftAlone(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("provenance").resolve("stamps.json");
        assertTrue(StampLedger.record(f, "species", "x", OPUS));
        assertTrue(StampLedger.record(f, "species", "x", MUSE));
        JsonObject onDisk = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        assertEquals("stamps", onDisk.get("store").getAsString());
        assertEquals(1, onDisk.get("v").getAsInt());
        assertEquals(List.of(OPUS, MUSE), StampLedger.read(f, "species", "x"));
        String newer = "{\"store\":\"stamps\",\"v\":99,\"stamps\":{}}";
        Files.writeString(f, newer);
        assertFalse(StampLedger.record(f, "species", "x", OPUS), "a newer Moddy's file is refused");
        assertEquals(newer, Files.readString(f), "…and left byte for byte");
    }

    @Test
    void recordStampsEveryServerEvenWithNoModRepo(@TempDir Path home) {
        System.clearProperty("moddingfromamod.persistDir");
        System.setProperty("moddingfromamod.home", home.toString());
        com.moddingfromamod.runtime.RuntimeConfig.invalidate();
        assertFalse(ProvenancePublisher.record("species", "dragon", "ref", "a dragon"),
                "no Mod Repo: the manifest is still a no-op");
        List<ModelStamp> g = StampLedger.read(StampLedger.file(), "species", "dragon");
        assertEquals(1, g.size(), "…but the model stamp is recorded on this server");
        assertEquals("opus", g.get(0).model());
        assertTrue(StampLedger.file().startsWith(home), "in the mod home, which the backup carries");
    }

    @Test
    void aModRepoManifestCarriesTheGrantsToo(@TempDir Path repo) throws Exception {
        System.setProperty("moddingfromamod.home", repo.resolve("home").toString());
        System.setProperty("moddingfromamod.persistDir", repo.toString());
        com.moddingfromamod.runtime.RuntimeConfig.invalidate();
        assertTrue(ProvenancePublisher.record("build", "tower", "ref", "a tower"));
        assertTrue(ProvenancePublisher.record("build", "tower", "ref", "make it taller"));
        Path m = repo.resolve("wishes/build/tower/manifest.json");
        WishProvenance p = WishProvenance.fromJson(
                JsonParser.parseString(Files.readString(m)).getAsJsonObject());
        assertEquals(2, p.grants().size(), "the first grant and the refinement each stamped");
        assertEquals(List.of("make it taller"), p.refinements());
    }

    @Test
    void aManifestFromBeforeTheStampReserialisesByteForByte() {
        WishProvenance old = WishProvenance.first("ice_bomb", "datapack", "make an ice bomb", "",
                123L, "wish-datapacks/ice_bomb");
        assertFalse(old.toJson().has("grants"), "no grants key at all, so an old manifest is unchanged");
        WishProvenance stamped = old.withGrant(OPUS).withRefinement("bluer");
        assertEquals(List.of(OPUS), stamped.grants(), "a refinement keeps the grants");
        assertEquals(stamped, WishProvenance.fromJson(stamped.toJson()));
    }
}
