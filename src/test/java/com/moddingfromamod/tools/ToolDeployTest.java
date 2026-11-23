package com.moddingfromamod.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gate A for the "deploy a bundled tool" step. Proves the CBScript compiler bundled in the jar
 * extracts to a writable location with all its pieces — the whole point being that the game process
 * then runs it from there instead of a TCC-protected dev checkout.
 */
class ToolDeployTest {

    @Test
    void theBundledCbscriptCompilerExtractsWithAllItsPieces(@TempDir Path root) {
        Path dir = ToolDeploy.extractTo("cbscript", root);
        assertNotNull(dir, "the cbscript tool must be bundled in the jar and extract");

        // The entry point, the vendored parser library, and at least one .cblib the compiler copies.
        assertTrue(Files.isRegularFile(dir.resolve("compile_once.py")), "compile_once.py present");
        assertTrue(Files.isRegularFile(dir.resolve("ply").resolve("__init__.py")),
                "the vendored ply parser library is bundled (self-contained, no pip/venv)");
        assertTrue(Files.isDirectory(dir.resolve("Scripts")), "the Scripts/ lib folder is present");
        assertTrue(anyCblib(dir.resolve("Scripts")), "at least one .cblib library is bundled");
        assertTrue(Files.isRegularFile(dir.resolve(".version")), "a version marker is written");
        // The LESSONS, which CbScriptLessons reads from this same directory. The bundle once
        // shipped only code, so cbscript_help served the primer with no lessons on every machine
        // that used it — the commonest compile failures were rules the file already had.
        assertTrue(Files.isRegularFile(dir.resolve("CBSCRIPT_LESSONS.md")),
                "CBSCRIPT_LESSONS.md is bundled, so cbscript_help can append it");
    }

    @Test
    void reExtractingIsIdempotentWhenTheBundleIsUnchanged(@TempDir Path root) throws Exception {
        Path first = ToolDeploy.extractTo("cbscript", root);
        String version = Files.readString(first.resolve(".version")).strip();
        long compileOnceMtime = Files.getLastModifiedTime(first.resolve("compile_once.py")).toMillis();

        Path second = ToolDeploy.extractTo("cbscript", root);
        assertEquals(first, second, "same directory returned");
        assertEquals(version, Files.readString(second.resolve(".version")).strip(),
                "same version marker — no needless churn");
        assertEquals(compileOnceMtime,
                Files.getLastModifiedTime(second.resolve("compile_once.py")).toMillis(),
                "an unchanged bundle is NOT re-extracted (fast path leaves files untouched)");
    }

    @Test
    void theToolIsNAMEDByItsVersion_soTwoCheckoutsCannotFightOverIt(@TempDir Path root)
            throws Exception {
        // The property that fixes it. This machine runs a dozen worktrees against ONE home
        // directory, and three different bundles were live at once when this was found. The old
        // scheme rewrote a single tools/<name>/ in place — delete, then move — so between those two
        // steps the compiler did not exist, and another run got
        // "can't open file '.../cbscript/compile_once.py'". Six unrelated GameTests went red and
        // not one of them named a tool.
        Path dir = ToolDeploy.extractTo("cbscript", root);
        assertNotNull(dir);
        String version = Files.readString(dir.resolve(".version")).strip();
        assertEquals(ToolDeploy.versionedDir(root, "cbscript", version), dir,
                "the directory must be named by the bundle it holds");
        assertTrue(dir.getFileName().toString().startsWith("cbscript-"), dir.toString());

        // A different bundle is a different directory, so unpacking it cannot disturb this one.
        Path other = ToolDeploy.versionedDir(root, "cbscript", "0".repeat(64));
        assertTrue(!other.equals(dir), "a different version must not resolve to the same place");
    }

    @Test
    void anUnfinishedExtractionIsNotMistakenForATool(@TempDir Path root) throws Exception {
        // The marker is written last, which matters much more now that a directory is never
        // replaced: without this check a crashed extraction would be reused for ever.
        Path dir = ToolDeploy.extractTo("cbscript", root);
        Files.delete(dir.resolve(".version"));
        Path again = ToolDeploy.extractTo("cbscript", root);
        assertTrue(Files.isRegularFile(again.resolve(".version")),
                "a directory with no marker must be unpacked again, not trusted");
    }

    @Test
    void anUnbundledToolNameYieldsNothing(@TempDir Path root) {
        assertTrue(ToolDeploy.extractTo("no_such_tool_xyz", root) == null,
                "a tool the jar doesn't ship returns null rather than a bogus empty dir");
    }

    /**
     * Several PROCESSES unpacking one bundle into one cold root — Gradle's parallel test forks on a
     * fresh machine, which is every weekly servicing session. A process that lost the race used to
     * delete the winner's directory as "abandoned wreckage" while the winner was compiling out of
     * it (`NoSuchFileException …/Scripts/click_detector.cblib`, LeaveBehindCompilesTest,
     * 2026-10-15). Threads cannot show it — {@code extractTo} is synchronized — so this starts
     * real JVMs, each of which extracts and then keeps reading the tool for a while.
     */
    @Test
    void processesUnpackingAtOnceNeverDeleteATheOtherIsUsing(@TempDir Path base) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        for (int round = 0; round < 4; round++) {
            Path root = Files.createDirectories(base.resolve("r" + round));
            java.util.List<Process> ps = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                ps.add(new ProcessBuilder(java, "-cp", cp, Unpacker.class.getName(), root.toString())
                        .redirectErrorStream(true).start());
            }
            for (Process p : ps) {
                String out = new String(p.getInputStream().readAllBytes());
                assertEquals(0, p.waitFor(), "an unpacking process lost its tool mid-use:\n" + out);
            }
        }
    }

    /** One process of the race above: extract, then read the tool repeatedly for ~1.5 s. */
    public static final class Unpacker {
        public static void main(String[] args) throws Exception {
            Path dir = ToolDeploy.extractTo("cbscript", Path.of(args[0]));
            if (dir == null) {
                System.exit(2);
            }
            long until = System.nanoTime() + 1_500_000_000L;
            while (System.nanoTime() < until) {
                if (!Files.isRegularFile(dir.resolve("compile_once.py"))
                        || !Files.isDirectory(dir.resolve("Scripts"))) {
                    System.out.println("vanished: " + dir);
                    System.exit(1);
                }
                Thread.sleep(5);
            }
            System.exit(0);
        }
    }

    private static boolean anyCblib(Path scripts) {
        try (var s = Files.list(scripts)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(".cblib"));
        } catch (Exception e) {
            return false;
        }
    }
}
