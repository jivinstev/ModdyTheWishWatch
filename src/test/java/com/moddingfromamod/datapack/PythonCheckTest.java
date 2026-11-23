package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>finding a working Python 3</b> for the Magician's powers — found, missing, the Windows Microsoft Store
 * stub, Python 2 — with stand-in programs, so no Python is needed on the machine; plus the words a player and the
 * agent read.
 */
class PythonCheckTest {

    private static final String STUB_SAYS = "Python was not found; run without arguments to install from the Microsoft"
            + " Store, or disable this shortcut from Settings > Manage App Execution Aliases.";

    /** A runner that answers {@code --version} per path from a table, and records what ran. */
    private static final class Table implements PythonCheck.Runner {
        final Map<String, PythonCheck.Run> versions;
        final List<String> ran = new ArrayList<>();

        Table(Map<String, PythonCheck.Run> versions) {
            this.versions = versions;
        }

        @Override
        public PythonCheck.Run run(List<String> argv, Path dir, long timeoutMs) {
            ran.add(String.join(" ", argv));
            return versions.getOrDefault(argv.get(0), new PythonCheck.Run(-1, "No such file"));
        }
    }

    @Test
    void found() {
        Table t = new Table(Map.of("/usr/bin/python3", new PythonCheck.Run(0, "Python 3.12.1\n")));
        PythonCheck.Verdict v = PythonCheck.find(List.of("/usr/bin/python3"), t, py -> "");
        assertEquals(PythonCheck.Kind.FOUND, v.kind());
        assertEquals("3.12.1", v.version());
        assertEquals("/usr/bin/python3", v.path());
        assertTrue(PythonCheck.setupLine(v, false).isEmpty(), "a working Python says nothing in the result");
        assertTrue(PythonCheck.setupLine(v, true).isEmpty());
    }

    @Test
    void missing() {
        PythonCheck.Verdict v = PythonCheck.find(List.of(), new Table(Map.of()), py -> "");
        assertEquals(PythonCheck.Kind.MISSING, v.kind());
        String line = PythonCheck.setupLine(v, false).orElseThrow();
        assertEquals("✖ Python 3 isn't installed, so the Magician can't give things powers yet. Install it from"
                + " python.org, then press the button again.", line);
        assertTrue(PythonCheck.setupLine(v, true).orElseThrow().endsWith(
                "On the installer's first page, tick \"Add python.exe to PATH\"."), "Windows names the PATH box");
        // A program that does not answer like a Python is not one.
        assertEquals(PythonCheck.Kind.MISSING, PythonCheck.classify("x", new PythonCheck.Run(0, "hello")).kind());
    }

    @Test
    void windowsStoreStub() {
        String stub = "C:\\Users\\kid\\AppData\\Local\\Microsoft\\WindowsApps\\python.exe";
        assertEquals(PythonCheck.Kind.STORE_STUB, PythonCheck.classify(stub, new PythonCheck.Run(9009, "")).kind(),
                "exit 9009 alone is the stub");
        Table t = new Table(Map.of(stub, new PythonCheck.Run(1, STUB_SAYS)));
        boolean[] compiled = {false};
        PythonCheck.Verdict v = PythonCheck.find(List.of(stub), t, py -> {
            compiled[0] = true;
            return "";
        });
        assertEquals(PythonCheck.Kind.STORE_STUB, v.kind());
        assertFalse(compiled[0], "the stub is never asked to compile");
        String line = PythonCheck.setupLine(v, true).orElseThrow();
        assertTrue(line.contains("Microsoft Store") && line.contains("python.org") && line.contains("Add python.exe to PATH"),
                line);
    }

    @Test
    void tooOldPython2() {
        // Python 2 prints its version on stderr (merged by the runner).
        Table t = new Table(Map.of("/usr/bin/python", new PythonCheck.Run(0, "Python 2.7.18\n")));
        PythonCheck.Verdict v = PythonCheck.find(List.of("/usr/bin/python"), t, py -> "");
        assertEquals(PythonCheck.Kind.TOO_OLD, v.kind());
        assertEquals("2.7.18", v.version());
        assertEquals("✖ This computer has Python 2.7.18, which is too old, so the Magician can't give things powers"
                + " yet. Install Python 3 from python.org, then press the button again.",
                PythonCheck.setupLine(v, false).orElseThrow());
        assertEquals(PythonCheck.Kind.TOO_OLD,
                PythonCheck.classify("p", new PythonCheck.Run(0, "Python 3.6.9")).kind(), "3.6 is too old too");
    }

    @Test
    void theFirstWorkingPython3WinsAndABadOneEarlierIsSkipped() {
        Table t = new Table(Map.of(
                "stub", new PythonCheck.Run(9009, STUB_SAYS),
                "old", new PythonCheck.Run(0, "Python 2.7.18"),
                "good", new PythonCheck.Run(0, "Python 3.11.4")));
        PythonCheck.Verdict v = PythonCheck.find(List.of("stub", "old", "good"), t, py -> "");
        assertEquals(PythonCheck.Kind.FOUND, v.kind());
        assertEquals("good", v.path());
    }

    @Test
    void python2BeatsTheStubAsTheThingToSay() {
        Table t = new Table(Map.of(
                "stub", new PythonCheck.Run(9009, STUB_SAYS),
                "old", new PythonCheck.Run(0, "Python 2.7.18")));
        assertEquals(PythonCheck.Kind.TOO_OLD, PythonCheck.find(List.of("stub", "old"), t, py -> "").kind());
        assertEquals(PythonCheck.Kind.TOO_OLD, PythonCheck.find(List.of("old", "stub"), t, py -> "").kind());
    }

    @Test
    void aPython3TheCompilerFailsOnIsBrokenWithTheReason() {
        Table t = new Table(Map.of("py", new PythonCheck.Run(0, "Python 3.13.0")));
        PythonCheck.Verdict v = PythonCheck.find(List.of("py"), t, py -> "ModuleNotFoundError: No module named 'x'");
        assertEquals(PythonCheck.Kind.BROKEN, v.kind());
        assertTrue(PythonCheck.setupLine(v, false).orElseThrow().contains("No module named"));
    }

    @Test
    void theAgentIsToldNotToRewriteTheSource() {
        for (PythonCheck.Verdict v : List.of(PythonCheck.Verdict.missing(),
                new PythonCheck.Verdict(PythonCheck.Kind.STORE_STUB, "s", "", ""),
                new PythonCheck.Verdict(PythonCheck.Kind.TOO_OLD, "o", "2.7.18", ""))) {
            String m = PythonCheck.agentMessage(v);
            assertTrue(PythonCheck.isAgentMessage(m), m);
            assertTrue(m.contains("Python 3") && m.contains("Do not retry") && m.contains("python.org")
                    && m.contains("Magician"), m);
            assertFalse(m.toLowerCase(java.util.Locale.ROOT).contains("wizard"), m);
        }
        assertTrue(PythonCheck.isAgentMessage(PythonCheck.agentMessage(null)));
        assertFalse(PythonCheck.isAgentMessage("CBScript did not compile"));
    }

    @Test
    void candidatesOnLinuxComeFromThePath(@TempDir Path dir) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path py = exec(bin.resolve("python3"));
        List<String> c = CbScriptCompiler.pythonCandidates(k -> "PATH".equals(k) ? bin.toString() : null, false);
        assertEquals(py.toAbsolutePath().toString(), c.get(0));
    }

    @Test
    void candidatesOnWindowsPutTheStoreFolderLast(@TempDir Path dir) throws Exception {
        Path apps = Files.createDirectories(dir.resolve("Microsoft").resolve("WindowsApps"));
        Path stub = exec(apps.resolve("python.exe"));
        Path other = Files.createDirectories(dir.resolve("Python312"));
        Path real = exec(other.resolve("python.exe"));
        // The Store folder FIRST on the PATH, as a fresh Windows has it: the real Python must still come first.
        Map<String, String> env = Map.of("PATH", apps + ";" + other);
        List<String> c = CbScriptCompiler.pythonCandidates(env::get, true);
        assertEquals(real.toAbsolutePath().toString(), c.get(0), c.toString());
        assertEquals(stub.toAbsolutePath().toString(), c.get(c.size() - 1), "the Store folder comes last: " + c);
    }

    @Test
    void aRealPythonHereRunsTheBundledCompiler() {
        Path compiler = CbScriptCompiler.cbscriptDir();
        Assumptions.assumeTrue(compiler != null, "no bundled compiler here");
        List<String> c = CbScriptCompiler.pythonCandidates(System::getenv, PythonCheck.windows());
        Assumptions.assumeFalse(c.isEmpty(), "no Python on this machine");
        PythonCheck.Verdict v = PythonCheck.find(c, PythonCheck::runProgram,
                py -> PythonCheck.tinyCompile(py, compiler, PythonCheck::runProgram));
        assertEquals(PythonCheck.Kind.FOUND, v.kind(), v.toString());
    }

    @Test
    void realStandInPrograms(@TempDir Path dir) throws Exception {
        Assumptions.assumeFalse(PythonCheck.windows(), "shell stand-ins");
        Path stub = script(dir.resolve("stub"), "echo '" + STUB_SAYS + "'; exit 9009");
        Path two = script(dir.resolve("two"), "echo 'Python 2.7.18' >&2");
        assertEquals(PythonCheck.Kind.STORE_STUB, PythonCheck.find(List.of(stub.toString()), PythonCheck::runProgram,
                py -> "").kind());
        assertEquals(PythonCheck.Kind.TOO_OLD, PythonCheck.find(List.of(two.toString()), PythonCheck::runProgram,
                py -> "").kind());
    }

    private static Path exec(Path p) throws Exception {
        Files.writeString(p, "");
        p.toFile().setExecutable(true);
        return p;
    }

    private static Path script(Path p, String body) throws Exception {
        Files.writeString(p, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        return p;
    }
}
