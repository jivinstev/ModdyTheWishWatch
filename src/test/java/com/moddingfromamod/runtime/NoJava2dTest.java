package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.testing.SourceRoots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No Java2D drawing anywhere in the core. On macOS the first {@code createGraphics()} in the JVM starts the Mac
 * desktop toolkit from the drawing thread, headless or not, and the game's window froze for good (#2822). Draw with
 * {@link Pixels} instead.
 */
class NoJava2dTest {

    /** What builds a Graphics, or the graphics environment behind it. */
    static final Pattern JAVA2D = Pattern.compile(
            "\\.createGraphics\\s*\\(|\\.getGraphics\\s*\\(|\\bGraphics2D\\b|\\bjava\\.awt\\.Graphics\\b"
                    + "|\\bGraphicsEnvironment\\b|\\.drawString\\s*\\(");

    /** The code alone: comments and string literals blanked, so an explanation is never a finding. */
    static String code(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"")
                .replaceAll("//[^\\n]*", " ");
    }

    @Test
    @DisplayName("no createGraphics, getGraphics, Graphics2D or GraphicsEnvironment under src/main: draw with Pixels")
    void noJava2d() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> w = SourceRoots.walk(Path.of("src/main/java"))) {
            for (Path p : w.filter(f -> f.toString().endsWith(".java")).toList()) {
                String[] lines = code(Files.readString(p)).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    var m = JAVA2D.matcher(lines[i]);
                    if (m.find()) {
                        found.add(SourceRoots.relativize(Path.of("src/main/java"), p) + ":" + (i + 1) + ": " + m.group());
                    }
                }
            }
        }
        assertEquals(List.of(), found, "Java2D froze the game window on macOS (#2822); use runtime/Pixels");
    }

    @Test
    @DisplayName("the guard sees code, and not comments or strings")
    void theGuardSeesCodeOnly() {
        assertTrue(JAVA2D.matcher(code("var g = img.createGraphics();")).find());
        assertTrue(JAVA2D.matcher(code("java.awt.Graphics2D g = null;")).find());
        assertTrue(JAVA2D.matcher(code("f.getGraphics().drawImage(a, 0, 0, null);")).find());
        assertTrue(!JAVA2D.matcher(code("// no createGraphics() here")).find());
        assertTrue(!JAVA2D.matcher(code("/** never a Graphics2D */ int x;")).find());
        assertTrue(!JAVA2D.matcher(code("String s = \"createGraphics()\";")).find());
    }
}
