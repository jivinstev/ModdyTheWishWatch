package com.moddingfromamod.apprentice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** the store jar's model is copied out once, never over an accepted copy, and never when the jar has none. */
class BundledModelTest {

    @TempDir
    Path tmp;

    private URLClassLoader jarWith(String model, String vocab) throws IOException {
        Path root = tmp.resolve("jar");
        Files.createDirectories(root.resolve(BundledModel.PREFIX));
        if (model != null) {
            Files.writeString(root.resolve(BundledModel.PREFIX + "model.onnx"), model);
        }
        if (vocab != null) {
            Files.writeString(root.resolve(BundledModel.PREFIX + "vocab.txt"), vocab);
        }
        return new URLClassLoader(new URL[] {root.toUri().toURL()}, null);
    }

    /** Accepts the folder when its model reads {@code want}. */
    private static Function<Path, String> accepts(String want) {
        return dir -> {
            try {
                return Files.isRegularFile(dir.resolve("model.onnx"))
                        && Files.readString(dir.resolve("model.onnx")).equals(want)
                        && Files.isRegularFile(dir.resolve("vocab.txt")) ? null : "not the model";
            } catch (IOException e) {
                return e.toString();
            }
        };
    }

    @Test
    void anEmptyFolderGetsTheModelTheJarCarries() throws IOException {
        Path dir = tmp.resolve("game/moddingfromamod/apprentice-model");
        try (URLClassLoader jar = jarWith("M", "V")) {
            assertNotNull(BundledModel.unpack(dir, jar, accepts("M")));
        }
        assertEquals("M", Files.readString(dir.resolve("model.onnx")));
        assertEquals("V", Files.readString(dir.resolve("vocab.txt")));
        try (var left = Files.list(dir)) {
            assertEquals(2, left.count(), "no .part file is left behind");
        }
    }

    @Test
    void anAcceptedCopyIsNeverOverwritten() throws IOException {
        Path dir = tmp.resolve("model");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("model.onnx"), "OURS");
        Files.writeString(dir.resolve("vocab.txt"), "v");
        try (URLClassLoader jar = jarWith("M", "V")) {
            assertNull(BundledModel.unpack(dir, jar, accepts("OURS")));
        }
        assertEquals("OURS", Files.readString(dir.resolve("model.onnx")));
    }

    @Test
    void aJarWithoutTheModelChangesNothing() throws IOException {
        Path dir = tmp.resolve("model");
        try (URLClassLoader jar = jarWith(null, null)) {
            assertNull(BundledModel.unpack(dir, jar, accepts("M")));
        }
        assertEquals(false, Files.exists(dir));
    }

    @Test
    void aBundledModelTheLayerRefusesIsLoud() throws IOException {
        try (URLClassLoader jar = jarWith("WRONG", "V")) {
            assertThrows(IOException.class, () -> BundledModel.unpack(tmp.resolve("m"), jar, accepts("M")));
        }
    }
}
