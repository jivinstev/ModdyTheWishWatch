package com.moddingfromamod.apprentice;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Function;

/**
 * <b>The sentence model a store jar carries</b> (D13): the public release ships {@code model.onnx} and
 * {@code vocab.txt} INSIDE the jar, under {@value #PREFIX}, so the Apprentice works offline and nothing is downloaded.
 * ONNX Runtime opens a model by path, so the two files are copied out of the jar into the model folder the first time
 * a world starts, the way ONNX Runtime copies its own native library out. A folder that already holds an accepted
 * model is left alone, so a server that puts its own copy there (and a jar built without the model) behave as before.
 *
 * <p>Minecraft-free: the caller hands in the class loader and the acceptance check ({@code ApprenticeLayer.refusal}).
 */
public final class BundledModel {

    /** Where the store build puts the two files inside the jar. */
    public static final String PREFIX = "apprentice/model/";
    static final List<String> FILES = List.of("model.onnx", "vocab.txt");

    private BundledModel() {
    }

    /** Does this jar carry the model? */
    public static boolean bundled(ClassLoader loader) {
        return FILES.stream().allMatch(f -> loader.getResource(PREFIX + f) != null);
    }

    /**
     * Make {@code dir} hold the model when the jar carries one and the folder does not already hold an accepted copy.
     * {@code refusal} is the layer's check: null when the folder's files are the right ones, else why not.
     *
     * @return what happened, for the one log line: {@code null} when nothing needed doing
     */
    public static String unpack(Path dir, ClassLoader loader, Function<Path, String> refusal) throws IOException {
        if (dir == null || refusal.apply(dir) == null || !bundled(loader)) {
            return null;
        }
        Files.createDirectories(dir);
        for (String f : FILES) {
            Path tmp = Files.createTempFile(dir, f, ".part");
            try (InputStream in = loader.getResourceAsStream(PREFIX + f)) {
                if (in == null) {
                    throw new IOException("the jar lost " + PREFIX + f);
                }
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, dir.resolve(f), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        String still = refusal.apply(dir);
        if (still != null) {
            throw new IOException("the model this jar carries was refused: " + still);
        }
        return "copied the sentence model this jar carries into " + dir;
    }
}
