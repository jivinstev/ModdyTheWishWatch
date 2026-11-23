package com.moddingfromamod.apprentice;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.moddingfromamod.wishbuilder.Apprentice;
import com.moddingfromamod.wishbuilder.ApprenticeLayer;
import com.moddingfromamod.wishbuilder.ClauseReader;
import com.moddingfromamod.wishbuilder.WordPiece;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>The trained layer, running</b>: a clause → the frozen sentence model (ONNX Runtime, on the CPU, one
 * thread) → {@link ApprenticeLayer} → the picks it most likely means. Server side only: nothing on a client loads
 * this class, and {@link LayerHost} is the only caller.
 *
 * <p><b>Not thread-safe by itself for speed's sake: {@link LayerHost} calls it from its one worker thread.</b> A
 * read is a few milliseconds; the answers to the last {@link #CACHE} clauses are kept.
 *
 * <p><b>What it keeps of what was typed: that cache, in memory, and nothing else.</b> A clause is the key of at most
 * {@value #CACHE} entries, never written to disk, never logged, never sent anywhere, and gone when the server stops
 * (docs/CHILD_SAFETY.md, "The Apprentice's trained layer").
 */
public final class OnnxClauseReader implements ClauseReader, AutoCloseable {

    /** How many clauses' answers are remembered. A clause is a few words; an answer is six short rows. */
    public static final int CACHE = 512;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final WordPiece pieces;
    private final ApprenticeLayer layer;
    private final Map<String, List<Guess>> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Guess>> eldest) {
            return size() > CACHE;
        }
    };

    private OnnxClauseReader(OrtEnvironment env, OrtSession session, WordPiece pieces, ApprenticeLayer layer) {
        this.env = env;
        this.session = session;
        this.pieces = pieces;
        this.layer = layer;
    }

    /**
     * Open the model in {@code dir} for {@code layer}. The caller has already asked {@link ApprenticeLayer#refusal}.
     *
     * @param natives where ONNX Runtime's own library is kept between starts, or null to let it unpack to a new
     *                temporary folder each start
     */
    public static OnnxClauseReader open(Path dir, ApprenticeLayer layer, Path natives) throws IOException, OrtException {
        pinNatives(natives);
        WordPiece pieces = new WordPiece(Files.readAllLines(dir.resolve(ApprenticeLayer.VOCAB_FILE), StandardCharsets.UTF_8));
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            opts.setIntraOpNumThreads(1);   // one clause at a time, off the server thread: never a pool of its own
            opts.setInterOpNumThreads(1);
            OrtSession session = env.createSession(dir.resolve(ApprenticeLayer.MODEL_FILE).toString(), opts);
            return new OnnxClauseReader(env, session, pieces, layer);
        }
    }

    @Override
    public List<Guess> read(String clause) {
        List<Guess> hit = cache.get(clause);
        if (hit != null) {
            return hit;
        }
        try {
            List<Guess> out = List.copyOf(layer.read(embed(clause), Apprentice.LAYER_READS));
            cache.put(clause, out);
            return out;
        } catch (OrtException e) {
            throw new IllegalStateException("the sentence model failed on a clause", e);   // never the clause itself
        }
    }

    /** The clause's sentence vector: the model's token vectors averaged, then scaled to length one. */
    public float[] embed(String clause) throws OrtException {
        long[] ids = pieces.ids(clause);
        long[] ones = new long[ids.length];
        Arrays.fill(ones, 1L);
        try (OnnxTensor a = OnnxTensor.createTensor(env, new long[][] {ids});
             OnnxTensor b = OnnxTensor.createTensor(env, new long[][] {ones});
             OnnxTensor c = OnnxTensor.createTensor(env, new long[][] {new long[ids.length]});
             OrtSession.Result r = session.run(Map.of("input_ids", a, "attention_mask", b, "token_type_ids", c))) {
            float[][][] h = (float[][][]) r.get(0).getValue();
            float[] v = new float[h[0][0].length];
            for (float[] tok : h[0]) {
                for (int i = 0; i < v.length; i++) {
                    v[i] += tok[i];
                }
            }
            double n = 0;
            for (int i = 0; i < v.length; i++) {
                v[i] /= h[0].length;
                n += (double) v[i] * v[i];
            }
            n = Math.max(Math.sqrt(n), 1e-9);
            for (int i = 0; i < v.length; i++) {
                v[i] /= (float) n;
            }
            return v;
        }
    }

    /** The pieces a clause is cut into, for the gate that holds this to the Python reference. */
    public long[] ids(String clause) {
        return pieces.ids(clause);
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            // closing: nothing to do about it
        }
        cache.clear();
    }

    /**
     * ONNX Runtime unpacks its own library from its jar to a NEW temporary folder at every start (about 30 MB each
     * time, left behind after a crash). Keep one copy under the game folder and point it there instead. Any trouble
     * and it is left to do what it does by itself.
     */
    static void pinNatives(Path dir) {
        if (dir == null || System.getProperty("onnxruntime.native.path") != null) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osPart = os.contains("mac") || os.contains("darwin") ? "osx" : os.contains("win") ? "win" : os.contains("linux") ? "linux" : null;
        String archPart = arch.equals("amd64") || arch.equals("x86_64") ? "x64" : arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : null;
        if (osPart == null || archPart == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            for (String lib : List.of("onnxruntime", "onnxruntime4j_jni")) {
                String file = System.mapLibraryName(lib);
                try (InputStream in = OrtEnvironment.class.getResourceAsStream("/ai/onnxruntime/native/" + osPart + "-" + archPart + "/" + file)) {
                    if (in == null) {
                        return;   // this build of the library has no copy for this machine: let it say so itself
                    }
                    Path to = dir.resolve(file);
                    if (!Files.isRegularFile(to)) {
                        Path tmp = Files.createTempFile(dir, file, ".part");
                        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                        Files.move(tmp, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            System.setProperty("onnxruntime.native.path", dir.toAbsolutePath().toString());
        } catch (IOException | RuntimeException e) {
            // fall back to the library's own unpacking
        }
    }
}
