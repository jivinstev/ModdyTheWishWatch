package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.moddingfromamod.testing.SourceRoots;
import org.junit.jupiter.api.Test;

/**
 * <b>A player's game never starts Python.</b> The Magician's powers used to be compiled by the Python CBScript
 * compiler, run as a {@code python3} process, which is why the store pages asked players to install Python 3. The
 * compiler is now moddy-cbscript's Java port, compiled into the jar (third_party/moddy-cbscript), and runs in-process.
 *
 * <p>This holds every place the jar can start a process to that: a class that builds a {@code ProcessBuilder} or calls
 * {@code Runtime.exec} may not name Python, unless it is listed below with why a player's game can never reach it. The
 * vendored compiler may start no process at all. {@code NoPythonGameTest} watches the running game's child processes
 * while a real datapack wish compiles.
 */
class NoPythonTest {

    private static final Pattern STARTS_A_PROCESS = Pattern.compile("new ProcessBuilder|Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder\\(");
    /** A string literal (or a char sequence) naming a Python interpreter or a .py program. */
    private static final Pattern NAMES_PYTHON = Pattern.compile("\"[^\"\\n]*(?:python|\\.py\\b)[^\"\\n]*\"",
            Pattern.CASE_INSENSITIVE);

    /** The classes that may start Python, and why no player's game ever does. Keep the reason true. */
    private static final Map<String, String> SERVER_ONLY = Map.of(
            "agent/MuseExec.java", "runs tools/muse-cli-shim.py only when a DEDICATED server's launcher points "
                    + "-Dmoddingfromamod.cli (or .muse.shim) at it; a player's game has no shim (MuseExec.shim())");

    private static final Path VENDORED = Path.of("third_party/moddy-cbscript/src/main/java");

    @Test
    void noProcessTheJarStartsIsPython() throws IOException {
        List<String> bad = new ArrayList<>();
        List<String> seenServerOnly = new ArrayList<>();
        for (Path root : SourceRoots.MAIN_JAVA) {
            Path base = root.resolve("com/moddingfromamod");
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(base)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String rel = base.relativize(p).toString().replace('\\', '/');
                    if (rel.endsWith("GameTest.java") || rel.startsWith("testing/")) {
                        continue;   // never in the jar
                    }
                    String src = Files.readString(p, StandardCharsets.UTF_8);
                    if (!STARTS_A_PROCESS.matcher(src).find() || !NAMES_PYTHON.matcher(src).find()) {
                        continue;
                    }
                    if (SERVER_ONLY.containsKey(rel)) {
                        seenServerOnly.add(rel);
                    } else {
                        var m = NAMES_PYTHON.matcher(src);
                        m.find();
                        bad.add(rel + " starts a process and names " + m.group());
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), "a player's game must never start Python (the Magician's compiler is in the jar): "
                + bad + ". Do the work in Java, or, if only a dedicated server can reach it, list it in SERVER_ONLY with why.");
        for (String rel : SERVER_ONLY.keySet()) {
            assertTrue(seenServerOnly.contains(rel), rel + " is listed as starting Python and no longer does: delete the row");
        }
    }

    @Test
    void theVendoredCompilerStartsNoProcess() throws IOException {
        assertTrue(Files.isDirectory(VENDORED), "the CBScript compiler is vendored at " + VENDORED);
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(VENDORED)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (STARTS_A_PROCESS.matcher(Files.readString(p, StandardCharsets.UTF_8)).find()) {
                    bad.add(VENDORED.relativize(p).toString());
                }
            }
        }
        assertTrue(bad.isEmpty(), "the CBScript compiler compiles in-process; these start a process: " + bad);
    }

    @Test
    void theCompilerDoesNotDependOnPath() {
        // available() used to mean "a working Python 3 is on PATH". It is now only the test switch.
        String before = System.getProperty(CbScriptCompiler.OFF_PROPERTY);
        try {
            System.clearProperty(CbScriptCompiler.OFF_PROPERTY);
            assertTrue(CbScriptCompiler.available(), "the compiler is always there");
        } finally {
            if (before != null) {
                System.setProperty(CbScriptCompiler.OFF_PROPERTY, before);
            }
        }
    }
}
