package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>The trained layer</b>: one small matrix that turns a clause's sentence vector into "which Builder pick
 * does this mean, or none". It ships in the jar ({@code apprentice/layer.bin} + {@code layer.json}, written by
 * {@code tools/apprentice-layer.py train}); the sentence model it was trained on does not, and
 * {@link #modelSha256()} names the one file it may be used with. Pure: no Minecraft, no model.
 *
 * <p>{@code layer.bin} is float32, little-endian: the weights, one row of {@link #dim()} per class, then one bias
 * per class. {@code layer.json} names the classes in row order ({@code "<slot>=<entry id>"}, and
 * {@link ClauseReader#NONE}), the threshold a first guess must reach, and the model.
 */
public final class ApprenticeLayer {

    private final List<String> classes;
    private final float[][] weights;
    private final float[] bias;
    private final int dim;
    private final double threshold;
    private final String model;
    private final String modelSha256;
    private final String vocabSha256;

    private ApprenticeLayer(JsonObject meta, byte[] bin) {
        JsonArray names = meta.getAsJsonArray("classes");
        classes = new ArrayList<>();
        names.forEach(n -> classes.add(n.getAsString()));
        dim = meta.get("dim").getAsInt();
        threshold = meta.get("threshold").getAsDouble();
        model = meta.get("model").getAsString();
        modelSha256 = meta.get("modelSha256").getAsString();
        vocabSha256 = meta.get("vocabSha256").getAsString();
        int n = classes.size();
        if (bin.length != 4 * (n * dim + n)) {
            throw new IllegalArgumentException("layer.bin is " + bin.length + " bytes; layer.json describes " + n
                    + " classes of " + dim + " (" + 4 * (n * dim + n) + " bytes)");
        }
        ByteBuffer buf = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        weights = new float[n][dim];
        for (float[] row : weights) {
            for (int j = 0; j < dim; j++) {
                row[j] = buf.getFloat();
            }
        }
        bias = new float[n];
        for (int i = 0; i < n; i++) {
            bias[i] = buf.getFloat();
        }
    }

    public static ApprenticeLayer of(String metaJson, byte[] bin) {
        return new ApprenticeLayer(JsonParser.parseString(metaJson).getAsJsonObject(), bin);
    }

    /** The layer in the jar, or null when this build carries none. */
    public static ApprenticeLayer bundled() {
        try (InputStream meta = ApprenticeLayer.class.getResourceAsStream("/apprentice/layer.json");
             InputStream bin = ApprenticeLayer.class.getResourceAsStream("/apprentice/layer.bin")) {
            if (meta == null || bin == null) {
                return null;
            }
            return of(new String(meta.readAllBytes(), StandardCharsets.UTF_8), bin.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("the bundled Apprentice layer cannot be read", e);
        }
    }

    /** The picks this vector most likely means, best first, at most {@code most}. Ties keep class order. */
    public List<ClauseReader.Guess> read(float[] vec, int most) {
        if (vec.length != dim) {
            throw new IllegalArgumentException("a sentence vector of " + vec.length + ", the layer reads " + dim);
        }
        int n = classes.size();
        double[] z = new double[n];
        double top = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double s = bias[i];
            float[] row = weights[i];
            for (int j = 0; j < dim; j++) {
                s += (double) row[j] * vec[j];
            }
            z[i] = s;
            top = Math.max(top, s);
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            z[i] = Math.exp(z[i] - top);
            sum += z[i];
        }
        List<ClauseReader.Guess> out = new ArrayList<>();
        boolean[] taken = new boolean[n];
        for (int k = 0; k < Math.min(most, n); k++) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (!taken[i] && (best < 0 || z[i] > z[best])) {
                    best = i;
                }
            }
            taken[best] = true;
            out.add(new ClauseReader.Guess(classes.get(best), z[best] / sum));
        }
        return out;
    }

    /** The two files a model folder holds. */
    public static final String MODEL_FILE = "model.onnx";
    public static final String VOCAB_FILE = "vocab.txt";

    /**
     * Why this folder's model may not be used with this layer, or null when it may. A layer is only numbers that
     * mean something for the ONE model it was trained on, so any other file is refused by its sha256.
     */
    public String refusal(java.nio.file.Path dir) {
        try {
            if (dir == null || !java.nio.file.Files.isRegularFile(dir.resolve(MODEL_FILE))
                    || !java.nio.file.Files.isRegularFile(dir.resolve(VOCAB_FILE))) {
                return "no " + MODEL_FILE + " and " + VOCAB_FILE + " in " + dir;
            }
            String vocab = sha256(dir.resolve(VOCAB_FILE));
            if (!vocab.equals(vocabSha256)) {
                return VOCAB_FILE + " in " + dir + " is not the one the layer was trained with (sha256 " + vocab + ", wanted " + vocabSha256 + ")";
            }
            String got = sha256(dir.resolve(MODEL_FILE));
            if (!got.equals(modelSha256)) {
                return MODEL_FILE + " in " + dir + " is not the model the layer was trained on (sha256 " + got + ", wanted " + modelSha256 + ")";
            }
            return null;
        } catch (IOException e) {
            return "the model in " + dir + " cannot be read: " + e;
        }
    }

    static String sha256(java.nio.file.Path file) throws IOException {
        try (InputStream in = java.nio.file.Files.newInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> classes() {
        return classes;
    }

    public int dim() {
        return dim;
    }

    /** What a first guess must reach to be shown; later ones a third of it. */
    public double threshold() {
        return threshold;
    }

    public String model() {
        return model;
    }

    /** The sha256 of the one {@code model.onnx} this layer was trained on. Any other file is refused. */
    public String modelSha256() {
        return modelSha256;
    }

    public String vocabSha256() {
        return vocabSha256;
    }
}
