package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * <b>The precompiled ready-mades are compiled from THESE templates and THIS compiler</b>. The store
 * ({@code precompiled/ready-mades.json.gz}) is what lets a player with no Python make every Builder and Apprentice
 * power; it was compiled from the effect and item templates, both catalogues and the bundled CBScript compiler, and
 * its manifest records their fingerprint. Change any of them and the store describes packs nobody would compile any
 * more, so this fails, naming the command that rebuilds it.
 */
class PrecompiledFingerprintTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final String FIX = "run ./tools/precompile-ready-mades.sh (or, for a compiler-only change, "
            + "./tools/precompile-ready-mades.sh --store) and commit src/main/resources/precompiled/";

    @Test
    void theStoreWasCompiledFromTheTemplatesAndCompilerInTheTree() throws Exception {
        Path manifest = RESOURCES.resolve("precompiled/manifest.json");
        assertTrue(Files.isRegularFile(manifest), "there is no precompiled store: " + FIX);
        JsonObject m = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        assertEquals(m.get("fingerprint").getAsString(), PrecompiledPacks.fingerprint(RESOURCES),
                "a ready-made template, a catalogue or the bundled CBScript compiler changed since the precompiled "
                        + "packs were built, so players with no Python would get stale packs: " + FIX);
    }

    @Test
    void theStoreOnTheClasspathIsTheOneTheManifestDescribes() throws Exception {
        JsonObject m = JsonParser.parseString(
                Files.readString(RESOURCES.resolve("precompiled/manifest.json"))).getAsJsonObject();
        PrecompiledPacks.reset();
        assertEquals(m.get("entries").getAsInt(), PrecompiledPacks.size(),
                "the store and its manifest disagree on how many packs it holds: " + FIX);
        try (var in = new java.util.zip.GZIPInputStream(
                Files.newInputStream(RESOURCES.resolve("precompiled/ready-mades.json.gz")))) {
            JsonObject root = JsonParser.parseString(new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(m.get("fingerprint").getAsString(), root.get("fingerprint").getAsString(),
                    "the store was not written with this manifest: " + FIX);
        }
    }
}
