package com.moddingfromamod.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The publisher writes a manifest into the configured Mod Repo, appends refinements on repeat, uses
 * the {@link ProvenanceContext} intent when none is passed, and is a no-op with no repo configured.
 */
class ProvenancePublisherTest {

    private static final String PDIR = "moddingfromamod.persistDir";
    private static final String HOME = "moddingfromamod.home";

    @AfterEach
    void reset() {
        System.clearProperty(PDIR);
        System.clearProperty(HOME);
        RuntimeConfig.invalidate();
        ProvenanceContext.clear();
    }

    private void pointAt(Path repo) {
        System.setProperty(HOME, repo.resolve("home").toString());
        System.setProperty(PDIR, repo.toString());
        RuntimeConfig.invalidate();
    }

    private WishProvenance readManifest(Path repo, String kind, String id) throws Exception {
        Path m = repo.resolve("wishes").resolve(kind).resolve(id).resolve("manifest.json");
        assertTrue(Files.isRegularFile(m), "manifest should exist at " + m);
        return WishProvenance.fromJson(
                JsonParser.parseString(Files.readString(m)).getAsJsonObject());
    }

    /**
     *, through the path the tools really call: an OWNED artifact's model stamp lands on its
     * owner's shelf, read from the artifact's own {@code ownerId}; a shared one does not.
     */
    @Test
    void anOwnedArtifactsStampGoesOnItsOwnersShelf(@TempDir Path repo) throws Exception {
        pointAt(repo);
        Path players = repo.resolve("players");
        System.setProperty("moddingfromamod.playersDir", players.toString());
        RuntimeConfig.invalidate();
        try {
            String kid = "a1b2c3d4-0000-0000-0000-000000000001";
            ProvenancePublisher.publish("species", "spikor", "sculpt.json",
                    "{\"id\":\"spikor\",\"ownerId\":\"" + kid + "\"}", "make me a spikor", "note");
            assertEquals(1, ShelfProvenance.read(players, kid, "species", "spikor").size(),
                    "the grant is on the child's shelf, so it follows him");
            ProvenancePublisher.publish("species", "kroak", "sculpt.json", "{\"id\":\"kroak\"}",
                    "a shared kroak", "note");
            assertEquals(List.of(kid), listDir(players), "a shared thing writes no shelf");
        } finally {
            System.clearProperty("moddingfromamod.playersDir");
        }
    }

    private static List<String> listDir(Path p) throws Exception {
        try (var s = Files.list(p)) {
            return s.map(x -> x.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void firstGrantWritesIntent(@TempDir Path repo) throws Exception {
        pointAt(repo);
        assertTrue(ProvenancePublisher.record("datapack", "ice_bomb",
                "wish-datapacks/ice_bomb", "make an ice bomb"));

        WishProvenance p = readManifest(repo, "datapack", "ice_bomb");
        assertEquals("make an ice bomb", p.intent());
        assertEquals("datapack", p.kind());
        assertEquals(List.of(), p.refinements());
    }

    @Test
    void secondGrantAppendsRefinement(@TempDir Path repo) throws Exception {
        pointAt(repo);
        ProvenancePublisher.record("datapack", "ice_bomb", "ref", "make an ice bomb");
        ProvenancePublisher.record("datapack", "ice_bomb", "ref", "make it bluer");

        WishProvenance p = readManifest(repo, "datapack", "ice_bomb");
        assertEquals("make an ice bomb", p.intent(), "intent stays the FIRST wish");
        assertEquals(List.of("make it bluer"), p.refinements());
    }

    @Test
    void usesContextIntentWhenNonePassed(@TempDir Path repo) throws Exception {
        pointAt(repo);
        ProvenanceContext.begin("wished via context");
        assertTrue(ProvenancePublisher.record("template", "big_bun", "wishes/template/big_bun"));

        assertEquals("wished via context", readManifest(repo, "template", "big_bun").intent());
    }

    @Test
    void publishingWritesSelfDescribingReadme(@TempDir Path repo) throws Exception {
        pointAt(repo);
        ProvenancePublisher.record("datapack", "ice_bomb", "ref", "make an ice bomb");

        Path readme = repo.resolve("wishes").resolve("README.md");
        assertTrue(Files.isRegularFile(readme), "publishing must drop a self-describing README");
        String txt = Files.readString(readme);
        assertTrue(txt.contains(ProvenanceReadme.PARENT_NAME), "README must name its producer");
        assertTrue(txt.contains(ProvenanceReadme.PARENT_URL), "README must point back to the parent");
        assertTrue(txt.contains("intent") && txt.contains("manifest.json"),
                "README must explain what the published files mean");
    }

    @Test
    void publishWritesEditableSourceAndManifestTogether(@TempDir Path repo) throws Exception {
        pointAt(repo);
        ProvenanceContext.begin("build me a floating castle");
        ProvenanceContext.note("quartz keep on four towers; hollow for rooms");

        assertTrue(ProvenancePublisher.publish("build", "floating_castle", "build.json",
                "{\"id\":\"floating_castle\",\"shapes\":[]}"));

        // The editable source landed beside the manifest...
        Path src = repo.resolve("wishes/build/floating_castle/build.json");
        assertTrue(Files.isRegularFile(src), "publish must write the editable source");
        assertTrue(Files.readString(src).contains("floating_castle"));
        // ...and the manifest carries BOTH the wish and the design note.
        WishProvenance p = readManifest(repo, "build", "floating_castle");
        assertEquals("build me a floating castle", p.intent());
        assertEquals("quartz keep on four towers; hollow for rooms", p.designNote());
        assertEquals("wishes/build/floating_castle/build.json", p.artifactRef());
    }

    @Test
    void designNoteFlowsFromContext(@TempDir Path repo) throws Exception {
        pointAt(repo);
        ProvenanceContext.begin("a fast red train");
        ProvenanceContext.note("two cars, controls in front, red wool body");
        assertTrue(ProvenancePublisher.record("train", "red_train", "wishes/train/red_train/train.json"));

        assertEquals("two cars, controls in front, red wool body",
                readManifest(repo, "train", "red_train").designNote());
    }

    @Test
    void noRepoConfiguredIsNoOp(@TempDir Path emptyHome) {
        System.clearProperty(PDIR);
        System.setProperty(HOME, emptyHome.toString()); // empty home -> no config.properties
        RuntimeConfig.invalidate();

        assertFalse(ProvenancePublisher.record("datapack", "x", "ref", "intent"),
                "with no Mod Repo configured, publishing is a quiet no-op");
        assertNull(ProvenancePublisher.manifestPath("datapack", "x"));
    }
}
