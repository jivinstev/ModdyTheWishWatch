package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import com.moddingfromamod.ModdingFromAModMod;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Gate A — pure JUnit.</b> The header normalisation and id rules are pure and always run. The
 * real end-to-end compile runs only when the CBScript compiler is present on this machine
 * ({@link CbScriptCompiler#available()}) and skip-succeeds otherwise, so a clean CI checkout without
 * the Python fork stays green — but on a dev box it proves the whole Java→Python→datapack path.
 */
class CbScriptCompilerTest {

    @Test
    void headerIsInjectedAndAgentWrittenHeaderIsStripped() {
        String body = "dir \"C:/some/machine/path\"\ndesc \"agent wrote this\"\n\nimport common\n"
                + "reset\nend\n";
        String out = CbScriptCompiler.withHeader("Ice bomb", body);
        // Exactly one dir header, and it's ours (".") — never the agent's machine path.
        assertTrue(out.startsWith("dir \".\"\n"), out);
        assertFalse(out.contains("C:/some/machine/path"), "agent's dir path must be stripped");
        assertFalse(out.contains("agent wrote this"), "agent's desc must be stripped");
        assertTrue(out.contains("import common"), "body must survive");
        assertTrue(out.contains("desc \"Ice bomb\""), "our desc must be present: " + out);
        // A desc must never contain a raw quote/newline that would break the header line.
        assertFalse(CbScriptCompiler.withHeader("he said \"hi\"\nthen left", "").contains("\"hi\""));
    }

    @Test
    void annotateLineQuotesTheOffendingSource() {
        String full = "dir \".\"\ndesc \"d\"\n\nreset\n    /say hi\n";
        // The compiler counts the injected header, so "line 5" is the /say line; the annotation
        // must quote that text so the author isn't misled by the offset.
        String msg = CbScriptCompiler.annotateLine("Syntax error at line 5 column 1.", full);
        assertTrue(msg.contains("/say hi"), "should quote the offending line: " + msg);
        // Out-of-range and no-match are left untouched, never throw.
        assertTrue(CbScriptCompiler.annotateLine("Syntax error at line 999.", full)
                .contains("line 999"));
        assertTrue(CbScriptCompiler.annotateLine("no line ref here", full).equals("no line ref here"));
    }

    @Test
    void idValidation() {
        assertTrue(CbScriptCompiler.VALID_ID.matcher("ice_bomb").matches());
        assertTrue(CbScriptCompiler.VALID_ID.matcher("fireball2").matches());
        assertFalse(CbScriptCompiler.VALID_ID.matcher("Ice").matches(), "no uppercase");
        assertFalse(CbScriptCompiler.VALID_ID.matcher("2cool").matches(), "must start with a letter");
        assertFalse(CbScriptCompiler.VALID_ID.matcher("ice-bomb").matches(), "no hyphens");
        assertFalse(CbScriptCompiler.VALID_ID.matcher("").matches());
    }

    @Test
    void compilesARealDatapackWhenTheCompilerIsAvailable(@TempDir Path work) throws Exception {
        Assumptions.assumeTrue(CbScriptCompiler.available(),
                "CBScript compiler not on this machine — skipping the live compile");
        Path zip = work.resolve("out.zip");
        String source = String.join("\n",
                "function mark()",
                "    /scoreboard objectives add bolmark dummy",
                "    /scoreboard players set #done bolmark 1",
                "end",
                "reset",
                "    /scoreboard objectives add bolmark dummy",
                "end");
        CompileResult r = CbScriptCompiler.compile(work, "boltest_dp", "unit test pack", source, zip);
        assertTrue(r.ok(), "should compile: " + r.failureSummary());
        assertNotNull(r.zip());
        assertTrue(Files.isRegularFile(zip), "zip must exist");
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            // 1.21.1 layout (singular 'function/') + our namespace + the mcmeta.
            assertNotNull(zf.getEntry("data/boltest_dp/function/mark.mcfunction"), "mark fn present");
            assertNotNull(zf.getEntry("data/minecraft/tags/function/load.json"), "load tag present");
            assertNotNull(zf.getEntry("pack.mcmeta"), "mcmeta present");
        }
    }

    @Test
    void aCompiledPackCarriesNoClickableLink(@TempDir Path work) throws Exception {
        // the strip is on the one path every compile takes, so the COMPILED function has no link.
        Assumptions.assumeTrue(CbScriptCompiler.available(), "compiler not present — skipping");
        Path zip = work.resolve("out.zip");
        String source = String.join("\n",
                "function greet()",
                "    /tellraw @s [\"\",{\"text\":\"[Click for Fireballs]\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/function linkdp:greet\"}}]",
                "end",
                "reset",
                "    /scoreboard objectives add linkdp dummy",
                "end");
        CompileResult r = CbScriptCompiler.compile(work, "linkdp", "unit test pack", source, zip);
        assertTrue(r.ok(), "should compile: " + r.failureSummary());
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var fn = zf.getEntry("data/linkdp/function/greet.mcfunction");
            assertNotNull(fn, "greet fn present");
            String body = new String(zf.getInputStream(fn).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.contains("Fireballs") && !body.contains("lick"),
                    "the compiled greeting keeps its words and loses its link: " + body);
        }
    }

    @Test
    void aBrokenSourceReportsAFixableError(@TempDir Path work) {
        Assumptions.assumeTrue(CbScriptCompiler.available(), "compiler not present — skipping");
        // 'reset' with no closing 'end' is a syntax error the agent must be told about.
        CompileResult r = CbScriptCompiler.compile(work, "broken_dp", "bad",
                "reset\n    /say hi\n", work.resolve("b.zip"));
        assertFalse(r.ok(), "a malformed script must fail, not silently pass");
        assertNotNull(r.failureSummary());
        assertFalse(r.failureSummary().isBlank(), "the failure must carry a message to fix");
    }

    // ---- CS-D4: the child-safety sweep is WIRED to the one path every compile takes ----

    /**
     * The sweep refuses a real {@code compile} call, with no compiler and no subprocess.
     *
     * <p>{@code SafetyDatapackSweepTest} proves the sweep DECIDES correctly; this proves it is
     * CALLED — a different claim, and the one that has been false twice in this repo
     * (docs/MACHINE_DEBUG_LOOP.md: "a rule tested only where it is DEFINED is not tested where it
     * RUNS"). It needs no {@code Assumptions} because the sweep deliberately runs above
     * {@link CbScriptCompiler#available()}, which is exactly what makes the wiring provable rather
     * than conditional on the machine.
     */
    @Test
    void theChildSafetySweepRefusesACompileBeforeAnythingIsRun(@TempDir Path work) {
        String mode = ModdingFromAModMod.MODID + ".childsafety";
        String before = System.getProperty(mode);
        System.setProperty(mode, "on");
        com.moddingfromamod.runtime.RuntimeConfig.invalidate();
        try {
            Path zip = work.resolve("nope.zip");
            CompileResult r = CbScriptCompiler.compile(work, "unsafe_dp", "pack",
                    "function greet()\n    /say have a bag of cocaine\nend\n", zip);
            assertFalse(r.ok(), "a datapack that would SAY a lexicon term must not compile");
            assertTrue(r.failureSummary().contains("SAFETY"),
                    "the agent must be told this was a wording refusal rather than a syntax "
                            + "error: " + r.failureSummary());
            assertTrue(r.failureSummary().contains("line 2"),
                    "the refusal must name the line to rewrite: " + r.failureSummary());
            assertFalse(Files.exists(zip), "nothing may be written for a refused compile");
        } finally {
            if (before == null) {
                System.clearProperty(mode);
            } else {
                System.setProperty(mode, before);
            }
            com.moddingfromamod.runtime.RuntimeConfig.invalidate();
        }
    }

    @Test
    void andTheControlArmCompilesTheVerySameSource(@TempDir Path work) {
        // The A/B, so the test above cannot pass because `compile` refuses everything. OFF grades
        // nothing, so this source reaches the compiler and fails (or succeeds) on its own merits --
        // what must NOT happen is a SAFETY refusal.
        Assumptions.assumeTrue(CbScriptCompiler.available(), "compiler not present — skipping");
        String mode = ModdingFromAModMod.MODID + ".childsafety";
        String before = System.getProperty(mode);
        System.setProperty(mode, "off");
        com.moddingfromamod.runtime.RuntimeConfig.invalidate();
        try {
            CompileResult r = CbScriptCompiler.compile(work, "unsafe_dp", "pack",
                    "function greet()\n    /say have a bag of cocaine\nend\n",
                    work.resolve("ok.zip"));
            assertFalse(r.failureSummary().contains("SAFETY"),
                    "the OFF arm must not refuse: " + r.failureSummary());
        } finally {
            if (before == null) {
                System.clearProperty(mode);
            } else {
                System.setProperty(mode, before);
            }
            com.moddingfromamod.runtime.RuntimeConfig.invalidate();
        }
    }

    // ---- Gate D: a denied/unreadable compiler checkout is caught HERE, not mid-compile ----

    @Test
    void aReadableCompilerDirIsUsable(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("compile_once.py"), "print('{}')\n");
        assertTrue(CbScriptCompiler.isReadableCompilerDir(dir),
                "a checkout whose compile_once.py can be read is usable");
    }

    @Test
    void aMissingCompilerFileIsNotUsable(@TempDir Path dir) {
        assertFalse(CbScriptCompiler.isReadableCompilerDir(dir),
                "an empty dir has no compiler and must be rejected");
    }

    @Test
    void anUnreadableCompilerFileIsRejectedNotCrashed(@TempDir Path dir) throws Exception {
        // This is the exact TCC failure that broke Spikor's fireball: the compiler file EXISTS but
        // the process can't READ it. It must be reported as "not usable" up front, so resolution
        // falls through to the bundled tool instead of throwing FileSystemException mid-compile.
        Path entry = dir.resolve("compile_once.py");
        Files.writeString(entry, "print('{}')\n");
        boolean madeUnreadable = entry.toFile().setReadable(false, false);
        // Skip if the OS/user can't actually remove read (e.g. running as root) — the assertion would
        // be meaningless there.
        Assumptions.assumeTrue(madeUnreadable && !Files.isReadable(entry),
                "could not make the file unreadable on this platform/user — skipping");
        try {
            assertFalse(CbScriptCompiler.isReadableCompilerDir(dir),
                    "a present-but-unreadable compiler must be rejected, not selected");
        } finally {
            entry.toFile().setReadable(true, false); // restore so @TempDir cleanup can delete it
        }
    }
}
