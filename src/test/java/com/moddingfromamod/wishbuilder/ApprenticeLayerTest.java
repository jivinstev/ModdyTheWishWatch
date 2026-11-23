package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The trained layer's file and arithmetic, and the tokenizer in front of the sentence model. */
class ApprenticeLayerTest {

    private static ApprenticeLayer tiny(String modelSha, String vocabSha) {
        // three classes over two dimensions: a is "x", b is "y", none sits between them
        float[] w = {4, 0, 0, 4, 1, 1};
        float[] b = {0, 0, 0.5f};
        ByteBuffer buf = ByteBuffer.allocate(4 * 9).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : w) {
            buf.putFloat(f);
        }
        for (float f : b) {
            buf.putFloat(f);
        }
        return ApprenticeLayer.of("{\"classes\":[\"powers=power:a\",\"powers=power:b\",\"none\"],\"dim\":2,\"threshold\":0.5,"
                + "\"model\":\"tiny\",\"modelSha256\":\"" + modelSha + "\",\"vocabSha256\":\"" + vocabSha + "\"}", buf.array());
    }

    @Test
    @DisplayName("a read is a softmax over the classes, best first, cut to the number asked for")
    void aReadIsASoftmaxBestFirst() {
        ApprenticeLayer layer = tiny("m", "v");
        List<ClauseReader.Guess> got = layer.read(new float[] {1, 0}, 2);
        assertEquals(2, got.size());
        assertEquals("powers=power:a", got.get(0).key());
        assertEquals(ClauseReader.NONE, got.get(1).key());
        double a = Math.exp(4), n = Math.exp(1.5), b = Math.exp(0);
        assertEquals(a / (a + n + b), got.get(0).prob(), 1e-9);
        assertEquals(n / (a + n + b), got.get(1).prob(), 1e-9);
        List<ClauseReader.Guess> all = layer.read(new float[] {0.1f, 0.1f}, 6);
        assertEquals(3, all.size(), "never more than there are classes");
        assertEquals(ClauseReader.NONE, all.get(0).key(), "a vector near nothing reads as none");
        assertEquals(1.0, all.stream().mapToDouble(ClauseReader.Guess::prob).sum(), 1e-9);
        assertEquals("powers=power:a", all.get(1).key(), "a tie keeps class order");
    }

    @Test
    @DisplayName("a layer.bin that is not the size layer.json describes is refused, and so is a wrong-sized vector")
    void aFileThatDoesNotFitIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ApprenticeLayer.of(
                "{\"classes\":[\"a=b\",\"none\"],\"dim\":2,\"threshold\":0.5,\"model\":\"t\",\"modelSha256\":\"m\",\"vocabSha256\":\"v\"}", new byte[20]));
        assertThrows(IllegalArgumentException.class, () -> tiny("m", "v").read(new float[3], 1));
    }

    @Test
    @DisplayName("only the model the layer was trained on may be used with it: any other file is refused by its sha256")
    void aModelThatIsNotTheTrainedOneIsRefused(@TempDir Path dir) throws IOException {
        assertTrue(tiny("m", "v").refusal(dir).startsWith("no model.onnx"), "an empty folder is the normal case");
        Files.writeString(dir.resolve("model.onnx"), "the model");
        Files.writeString(dir.resolve("vocab.txt"), "the vocab");
        String model = ApprenticeLayer.sha256(dir.resolve("model.onnx"));
        String vocab = ApprenticeLayer.sha256(dir.resolve("vocab.txt"));
        assertEquals(64, model.length());
        assertNull(tiny(model, vocab).refusal(dir), "the trained model and its vocabulary are let in");
        assertTrue(tiny(vocab, vocab).refusal(dir).contains("is not the model the layer was trained on"));
        assertTrue(tiny(model, model).refusal(dir).contains("vocab.txt"));
    }

    @Test
    @DisplayName("the layer in the jar has a class for every modifier pick the Builder offers, and none")
    void theBundledLayerCoversTheBuilder() {
        ApprenticeLayer layer = ApprenticeLayer.bundled();
        assertNotNull(layer, "src/main/resources/apprentice/layer.{bin,json}: python3 tools/apprentice-layer.py train");
        assertEquals(384, layer.dim());
        assertEquals(ClauseReader.NONE, layer.classes().get(layer.classes().size() - 1));
        assertTrue(layer.threshold() >= 0.2 && layer.threshold() <= 0.7, "threshold " + layer.threshold());
        BuilderTree tree = BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        Set<String> offered = new HashSet<>();
        for (BuilderTree.Kind k : tree.kinds()) {
            for (BuilderTree.Form f : k.forms()) {
                for (BuilderTree.Slot s : f.slots()) {
                    s.entries().forEach(e -> offered.add(s.id() + "=" + e.id()));
                }
            }
        }
        int live = 0;
        for (String c : layer.classes()) {
            live += offered.contains(c) ? 1 : 0;
        }
        assertTrue(live > 100, "only " + live + " of the layer's classes are picks the Builder offers");
        float[] v = new float[384];
        v[0] = 1;
        assertEquals(Apprentice.LAYER_READS, layer.read(v, Apprentice.LAYER_READS).size());
    }

    @Test
    @DisplayName("WordPiece: lower case, accents dropped, punctuation split, longest piece first, ## for the rest")
    void wordPieceCutsAsBertDoes() {
        WordPiece wp = new WordPiece(List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "spit", "##s", "flame", "##s", "!", "that", "fire", "##proof"));
        // the vocabulary maps a piece to its LAST line, as a dict built from the file does
        assertEquals(List.of(2L, 9L, 4L, 7L, 6L, 7L, 8L, 8L, 3L), box(wp.ids("Thät  SPITS flames!!")));
        assertEquals(List.of(2L, 10L, 11L, 1L, 3L), box(wp.ids("fireproof zzz")), "a word with no pieces is [UNK], whole");
        assertEquals(List.of(2L, 3L), box(wp.ids("   ")));
        long[] many = wp.ids("that ".repeat(200));
        assertEquals(WordPiece.MOST, many.length, "cut to what the model reads");
        assertEquals(3L, many[many.length - 1], "and still closed with [SEP]");
        assertThrows(IllegalArgumentException.class, () -> new WordPiece(List.of("a", "b")));
    }

    private static List<Long> box(long[] ids) {
        return java.util.Arrays.stream(ids).boxed().toList();
    }
}
