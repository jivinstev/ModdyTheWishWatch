package com.moddingfromamod.apprentice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.wishbuilder.Apprentice;
import com.moddingfromamod.wishbuilder.ApprenticeLayer;
import com.moddingfromamod.wishbuilder.ClauseReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The Java path a server runs (tokenizer → ONNX Runtime → layer) against the Python that trained the layer</b>
 *, clause by clause: the same pieces, the same sentence vector (cosine ≥ 0.999) and the same best pick.
 *
 * <p>Needs the sentence model, which is not in the repo: {@code ./tools/fetch-apprentice-model.sh}, then
 * {@code python3 tools/apprentice-layer.py numerics} writes what Python computed. Without either it is SKIPPED,
 * and {@code ./tools/apprentice-gate.sh} says so loudly; it is that script's job to run both halves.
 */
class OnnxClauseReaderTest {

    static final Path NUMERICS = Path.of("build", "apprentice-layer-numerics.json");

    static Path modelDir() {
        String env = System.getenv("MODDY_APPRENTICE_MODEL");
        return Path.of(env == null || env.isBlank() ? ".apprentice-model" : env);
    }

    @Test
    @DisplayName("with the model: Java's pieces, sentence vectors and best picks equal Python's on every clause")
    void javaReadsAClauseAsPythonDoes() throws Exception {
        ApprenticeLayer layer = ApprenticeLayer.bundled();
        assumeTrue(layer != null && Files.isRegularFile(modelDir().resolve(ApprenticeLayer.MODEL_FILE)) && Files.isRegularFile(NUMERICS),
                "no sentence model or no build/apprentice-layer-numerics.json: ./tools/apprentice-gate.sh runs this");
        assertNull(layer.refusal(modelDir()), "the model in " + modelDir() + " is the one the layer was trained on");
        JsonObject numerics = JsonParser.parseString(Files.readString(NUMERICS, StandardCharsets.UTF_8)).getAsJsonObject();
        // build/ outlives a retrain: a numerics file written for the OLD layer fails on a probability, which reads as a
        // Java/Python difference. Name the real cause instead (2026-11-15).
        String layerSha;
        try (InputStream bin = ApprenticeLayer.class.getResourceAsStream("/apprentice/layer.bin")) {
            layerSha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bin.readAllBytes()));
        }
        assertTrue(numerics.has("layerSha256") && numerics.get("layerSha256").getAsString().equals(layerSha),
                NUMERICS + " was written for another trained layer (stale build state, not a defect): re-run "
                        + "python3 tools/apprentice-layer.py numerics --model " + modelDir());
        JsonArray rows = numerics.getAsJsonArray("rows");
        assertTrue(rows.size() >= 200, "only " + rows.size() + " clauses");
        double worst = 1;
        // The library is unpacked ONCE to a folder we name, not to a new temporary one at every start.
        Path natives = Path.of("build", "onnxruntime-native").toAbsolutePath();
        try (OnnxClauseReader reader = OnnxClauseReader.open(modelDir(), layer, natives)) {
            assertEquals(natives.toString(), System.getProperty("onnxruntime.native.path"), "ONNX Runtime was pointed at the kept copy");
            assertTrue(Files.isRegularFile(natives.resolve(System.mapLibraryName("onnxruntime"))), "and the copy is there");
            for (int n = 0; n < rows.size(); n++) {
                JsonObject row = rows.get(n).getAsJsonObject();
                String clause = row.get("clause").getAsString();
                JsonArray ids = row.getAsJsonArray("ids");
                long[] want = new long[ids.size()];
                for (int i = 0; i < want.length; i++) {
                    want[i] = ids.get(i).getAsLong();
                }
                assertArrayEquals(want, reader.ids(clause), "the pieces of: " + clause);
                float[] v = reader.embed(clause);
                JsonArray vec = row.getAsJsonArray("vec");
                assertEquals(vec.size(), v.length);
                double dot = 0, a = 0, b = 0;
                for (int i = 0; i < v.length; i++) {
                    double w = vec.get(i).getAsDouble();
                    dot += v[i] * w;
                    a += (double) v[i] * v[i];
                    b += w * w;
                }
                double cos = dot / Math.sqrt(a * b);
                worst = Math.min(worst, cos);
                assertTrue(cos >= 0.999, "cosine " + cos + " for: " + clause);
                List<ClauseReader.Guess> got = reader.read(clause);
                JsonArray top = row.getAsJsonArray("top");
                assertEquals(top.get(0).getAsJsonArray().get(0).getAsString(), got.get(0).key(), "the best pick for: " + clause);
                assertEquals(top.get(0).getAsJsonArray().get(1).getAsDouble(), got.get(0).prob(), 0.01, "its probability for: " + clause);
                assertEquals(Apprentice.LAYER_READS, got.size());
                assertSame(got, reader.read(clause), "a clause read twice is answered from memory");
            }
        }
        System.out.printf("apprentice layer numerics: %d clauses, Java equals Python (worst cosine %.6f)%n", rows.size(), worst);
        Files.writeString(Path.of("build", "apprentice-layer-numerics.txt"), String.format("clauses %d%nworst_cosine %.6f%n", rows.size(), worst));
    }
}
