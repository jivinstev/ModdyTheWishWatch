package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gate A for the on-disk model repository: resolve a folder, parse it, and — crucially — re-parse
 * when the file changes (the live-iteration mechanism), all without booting the game. The repo is
 * pointed at a temp dir via the {@code moddingfromamod.modelDir} system property, the same seam the
 * headless and client tests use.
 */
class ModelRepositoryTest {

    private static final String PROP = "moddingfromamod.modelDir";
    private String previous;

    @BeforeEach
    void setup(@TempDir Path dir) {
        previous = System.getProperty(PROP);
        System.setProperty(PROP, dir.toString());
        RuntimeConfig.invalidate();
        ModelRepository.clearCache();
    }

    @AfterEach
    void teardown() {
        if (previous == null) {
            System.clearProperty(PROP);
        } else {
            System.setProperty(PROP, previous);
        }
        RuntimeConfig.invalidate();
        ModelRepository.clearCache();
    }

    private static final String GEOMETRY = """
        {"minecraft:geometry":[{"description":{"texture_width":16,"texture_height":16},
          "bones":[{"name":"body","pivot":[0,8,0],
            "cubes":[{"origin":[-4,0,-4],"size":[8,8,8],"uv":[0,0]}]}]}]}
        """;

    private void writeModel(String key, String geometry) throws Exception {
        Path d = ModelRepository.dir(key);
        Files.createDirectories(d);
        Files.writeString(d.resolve("geometry.json"), geometry);
    }

    @Test
    void keyValidationRejectsPathTricks() {
        assertTrue(ModelRepository.isValidKey("baby_dragon"));
        assertTrue(ModelRepository.isValidKey("dragon-2"));
        assertFalse(ModelRepository.isValidKey(""));
        assertFalse(ModelRepository.isValidKey("../etc/passwd"));
        assertFalse(ModelRepository.isValidKey("a/b"));
        assertFalse(ModelRepository.isValidKey("Dragon")); // uppercase not allowed
    }

    @Test
    void missingModelResolvesToNullNotCrash() {
        assertFalse(ModelRepository.has("nope"));
        assertNull(ModelRepository.geometry("nope"));
    }

    @Test
    void resolvesAndParsesAModel() throws Exception {
        writeModel("baby_dragon", GEOMETRY);
        assertTrue(ModelRepository.has("baby_dragon"));
        BedrockGeometry g = ModelRepository.geometry("baby_dragon");
        assertNotNull(g);
        assertEquals(1, g.cubeCount());
    }

    @Test
    void rereadsWhenTheFileChanges() throws Exception {
        writeModel("baby_dragon", GEOMETRY);
        BedrockGeometry first = ModelRepository.geometry("baby_dragon");
        assertEquals(1, first.cubeCount());

        // Re-export with two cubes and a newer mtime — the cache must miss and pick up the change.
        String twoCubes = """
            {"minecraft:geometry":[{"description":{"texture_width":16,"texture_height":16},
              "bones":[{"name":"body","cubes":[
                {"origin":[-4,0,-4],"size":[8,8,8],"uv":[0,0]},
                {"origin":[-2,8,-2],"size":[4,4,4],"uv":[0,0]}]}]}]}
            """;
        Path f = ModelRepository.geometryFile("baby_dragon");
        Files.writeString(f, twoCubes);
        Files.setLastModifiedTime(f,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));

        BedrockGeometry second = ModelRepository.geometry("baby_dragon");
        assertEquals(2, second.cubeCount());
    }

    @Test
    void brokenJsonDegradesToNull() throws Exception {
        writeModel("broken", "{ this is not json ");
        assertTrue(ModelRepository.has("broken")); // the file exists...
        assertNull(ModelRepository.geometry("broken")); // ...but yields nothing drawable
    }

    /**
     * <b>A base has to be what the creature actually IS.</b>
     *
     * <p>A sculpt spec stores the agent's input — hand-written parts plus a {@code kits} directive
     * — and kit expansion happens afterwards, on the way to the baked geometry. Reading
     * {@code parts} alone made "another Spikor but blue" come back with no spikes, no claws and no
     * shell, silently: the creature the child pointed at and the creature the agent received were
     * different animals.
     */
    @Test
    @DisplayName("start_from carries the KITS, not only the hand-written parts")
    void aBaseCarriesItsKits(@TempDir Path dir) throws Exception {
        System.setProperty("moddingfromamod.modelDir", dir.toString());
        Path folder = dir.resolve("spiky");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("sculpt.json"), """
                {"id":"spiky",
                 "parts":[{"name":"body","from":[-8,0,-6],"to":[8,20,6],"colour":"green"}],
                 "kits":[{"kit":"spike_row","on":"body","count":5,"colour":"white"}]}""");

        var base = ModelRepository.sculptedParts("spiky");

        assertTrue(base.size() > 1, "the base came back as bare geometry: " + base);
        assertTrue(base.stream().anyMatch(p ->
                        p.name().startsWith(com.moddingfromamod.runtime.SculptKits.MARK)),
                "no kit-built part in the base — the spikes were dropped: "
                        + base.stream().map(com.moddingfromamod.runtime.CreatureSculpt.Part::name)
                                .toList());
    }

    @Test
    @DisplayName("a base with no kits is unchanged, and a damaged kits array is not fatal")
    void aBaseWithoutKitsIsUntouched(@TempDir Path dir) throws Exception {
        System.setProperty("moddingfromamod.modelDir", dir.toString());
        Path folder = dir.resolve("plain");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("sculpt.json"), """
                {"id":"plain","kits":"not an array",
                 "parts":[{"name":"body","from":[-4,0,-4],"to":[4,12,4],"colour":"blue"}]}""");
        var base = ModelRepository.sculptedParts("plain");
        assertEquals(1, base.size(), "a malformed kits field must cost the base nothing");
    }
}
