package com.moddingfromamod.datapack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays every CBScript program the agent has really written, through the real compiler.
 *
 * <p><b>The corpus is mined, not authored</b> ({@code tools/mine-datapack-corpus.py}). Until now
 * the datapacks class had two cases, both {@code expectAbsent} negative controls — so nothing had
 * ever verified a datapack that <i>exists</i>. Hand-writing replacements would have encoded one
 * person's idea of a CBScript program; a gate tuned on hand-made fixtures once caught <b>0 of 14</b>
 * real creatures on its first live run. {@code ~/.moddingfromamod/cbscript-compiles.jsonl} already
 * held 276 real attempts with their exact source and the compiler's own error.
 *
 * <p><b>The asymmetry is the design.</b> {@code observed} records what the compiler DID, not what
 * it ought to do:
 *
 * <ul>
 *   <li>a program that compiled and now fails is a <b>regression</b>, and fails this test;
 *   <li>a program that failed and now compiles is an <b>improvement to the fork</b>, and is
 *       reported rather than failed — otherwise this test would enforce the compiler's current
 *       bugs as required behaviour;
 *   <li>a program that still fails but in a <b>different way</b> is reported too, because "it
 *       failed" is a weak assertion that a compiler rejecting everything would satisfy.
 * </ul>
 *
 * <p>Rows where the compiler <b>never ran</b> (macOS refusing the checkout) are excluded by the
 * miner. They say nothing about the source, and banking them would demand that three good programs
 * keep failing.
 */
class CbScriptCorpusTest {

    private static final String RESOURCE = "/corpus/datapacks.jsonl";

    private record Case(String id, String featureId, boolean expectCompiles,
                        String errorClass, String needle, String source) {
    }

    private static List<Case> corpus() {
        List<Case> out = new ArrayList<>();
        try (InputStream in = CbScriptCorpusTest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return out;
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("//")) {
                    continue;
                }
                JsonObject o = JsonParser.parseString(t).getAsJsonObject();
                out.add(new Case(o.get("id").getAsString(),
                        o.get("featureId").getAsString(),
                        "compiles".equals(o.get("observed").getAsString()),
                        o.get("errorClass").isJsonNull() ? null
                                : o.get("errorClass").getAsString(),
                        o.get("errorNeedle").isJsonNull() ? null
                                : o.get("errorNeedle").getAsString(),
                        o.get("source").getAsString()));
            }
        } catch (Exception e) {
            throw new AssertionError("could not read " + RESOURCE, e);
        }
        return out;
    }

    @Test
    @DisplayName("the mined corpus is present, substantial, and has both directions in it")
    void theCorpusIsRealAndCoversBothOutcomes() {
        List<Case> all = corpus();
        assertTrue(all.size() > 100,
                "expected the mined corpus, got " + all.size() + " case(s) — re-run "
                        + "tools/mine-datapack-corpus.py");
        long fails = all.stream().filter(c -> !c.expectCompiles()).count();
        assertTrue(fails >= 5,
                "a corpus with no failing programs cannot detect a compiler that accepts anything; "
                        + "got " + fails);
        assertTrue(all.stream().filter(c -> !c.expectCompiles())
                        .allMatch(c -> c.needle() != null && !c.needle().isBlank()),
                "every failing case needs a distinctive error slice, or 'it failed' is all we "
                        + "assert and a compiler rejecting everything would stay green");
        assertFalse(all.stream().anyMatch(c -> "harness-error".equals(c.errorClass())),
                "a row where the compiler never ran says nothing about the source and must not be "
                        + "in the corpus");
    }

    @Test
    @DisplayName("REPLAY: every program the agent wrote, through the real compiler")
    void everyMinedProgramStillBehavesTheSameWay(@TempDir Path work) {
        // LOUD skip. On a machine with the fork this must actually run; ratchet.sh refuses to
        // call a run green when the compiler is missing, for the same reason it refuses to call
        // the train gates green without the Create jar.
        Assumptions.assumeTrue(CbScriptCompiler.available(),
                "SKIPPED — no CBScript compiler/python here. This proves NOTHING about the "
                        + "compiler; ./tools/ratchet.sh will fail rather than report it green.");

        List<Case> all = corpus();
        List<String> regressions = Collections.synchronizedList(new ArrayList<>());
        List<String> improvements = Collections.synchronizedList(new ArrayList<>());
        Map<String, Integer> changedMode = new ConcurrentHashMap<>();
        long started = System.currentTimeMillis();

        // COMPILE THE CORPUS IN PARALLEL. Each case is an independent `python compile_once.py` in
        // its OWN work directory, so there is nothing to share and nothing to order — and this one
        // test was 36s of Gate A's 59s of test-body time, run one subprocess at a time on a 4-core
        // box.
        //
        // Deliberately NOT a @ParameterizedTest, which is the obvious move and does not work:
        // Gradle distributes tests across forks BY CLASS, so 276 invocations of one class all land
        // in one fork and the wall clock is unchanged. Threads inside the class are what actually
        // overlap the subprocesses.
        //
        // The compiler checkout is extracted lazily by ToolDeploy on first use; `available()` in
        // the assumption above has already forced that, so no thread races to extract it here.
        int threads = Math.max(1, Integer.getInteger(
                ModdingFromAModMod.MODID + ".corpusthreads", Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                Case c = all.get(i);
                // Index-prefixed so two cases sharing an id can never share a work directory. The
                // serial version silently overwrote; under threads that would be a race.
                Path dir = work.resolve(i + "_" + c.id());
                futures.add(pool.submit(() -> {
                    CompileResult r = CbScriptCompiler.compile(dir, c.featureId(), "corpus replay",
                            c.source(), dir.resolve(c.id() + ".zip"));

                    if (c.expectCompiles() && !r.ok()) {
                        regressions.add(c.id() + " (" + c.featureId() + ") no longer compiles: "
                                + firstLine(r.failureSummary()));
                    } else if (!c.expectCompiles() && r.ok()) {
                        improvements.add(c.id() + " (" + c.errorClass() + ") now compiles");
                    } else if (!c.expectCompiles()) {
                        String summary = r.failureSummary() == null ? "" : r.failureSummary();
                        if (c.needle() != null && !summary.contains(c.needle())) {
                            changedMode.merge(c.errorClass() + " -> " + firstLine(summary), 1,
                                    Integer::sum);
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("corpus replay interrupted", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("a corpus case threw: " + e.getCause(), e.getCause());
                }
            }
        } finally {
            pool.shutdown();
        }
        // Threads finish in whatever order they finish; sort so the report is byte-identical run to
        // run and a diff of two runs shows a real change rather than a reshuffle.
        Collections.sort(regressions);
        Collections.sort(improvements);

        long ms = System.currentTimeMillis() - started;
        System.out.printf("%n=== CBSCRIPT CORPUS REPLAY ===%n%d program(s) in %.1fs%n",
                all.size(), ms / 1000.0);
        System.out.printf("  %d expected to compile, %d expected to fail%n",
                all.stream().filter(Case::expectCompiles).count(),
                all.stream().filter(c -> !c.expectCompiles()).count());
        if (!improvements.isEmpty()) {
            System.out.println("  IMPROVED (the fork now accepts these — re-mine the corpus):");
            improvements.forEach(i -> System.out.println("    + " + i));
        }
        if (!changedMode.isEmpty()) {
            System.out.println("  STILL FAILS, DIFFERENTLY:");
            changedMode.forEach((k, n) -> System.out.println("    ~ " + k + " x" + n));
        }
        System.out.println("==============================\n");

        assertTrue(regressions.isEmpty(),
                regressions.size() + " program(s) the agent wrote no longer compile. These are "
                        + "real regressions in the compiler or its libs:\n  " + String.join("\n  ",
                        regressions));
    }

    @Test
    @DisplayName("the compiler still rejects SOMETHING — a yes-machine cannot pass this file")
    void theCompilerStillRejectsSomething(@TempDir Path work) {
        Assumptions.assumeTrue(CbScriptCompiler.available(), "SKIPPED — no compiler here");
        // Deliberately "at least one", not a named case. The first cut asserted on
        // corpus().findFirst(), which happened to be the compiler-crash case -- and the fork has
        // since been FIXED so that one compiles. A correct improvement upstream broke the test,
        // which is the wrong way round: this check exists to catch a compiler that says yes to
        // everything, not to freeze any particular bug in place.
        List<Case> failing = corpus().stream().filter(c -> !c.expectCompiles()).toList();
        Assumptions.assumeFalse(failing.isEmpty(), "no failing cases in the corpus to check");

        boolean anyStillRejected = false;
        for (Case c : failing) {
            Path dir = work.resolve(c.id());
            if (!CbScriptCompiler.compile(dir, c.featureId(), "negative control", c.source(),
                    dir.resolve("x.zip")).ok()) {
                anyStillRejected = true;
                break;
            }
        }
        assertTrue(anyStillRejected,
                "every program the compiler used to reject now compiles. That is either a very "
                        + "good day for the fork or a compiler that accepts anything — verify "
                        + "which, then re-mine the corpus so this file has teeth again.");
    }

    @Test
    @DisplayName("a compiled pack declares THIS build's pack_format ")
    void aCompiledPackDeclaresThisBuildsPackFormat(@TempDir Path work) throws Exception {
        Assumptions.assumeTrue(CbScriptCompiler.available(), "SKIPPED — no compiler here");
        // The fork wrote 48 (1.21.1) for every target until it learned CBSCRIPT_MC; on 1.21.4 such
        // a pack loads as "made for an older version" and the install path had to rewrite it.
        // Expected numbers are Minecraft's own (DatapackDialect.packFormat() at runtime).
        int expected = switch (com.moddingfromamod.compat.Target.MINECRAFT) {
            case "26.3" -> 121;    //: SharedConstants.DATA_PACK_FORMAT_MAJOR = 121 (minor 0) in 26.3 (the client jar's version.json)
            case "26.1.2" -> 101;  //: SharedConstants.DATA_PACK_FORMAT_MAJOR = 101 (minor 1) in 26.1.2
            case "1.21.11" -> 94;  //: SharedConstants.DATA_PACK_FORMAT_MAJOR = 94 (minor 1) in 1.21.11
            case "1.21.10" -> 88;  //: SharedConstants.DATA_PACK_FORMAT_MAJOR = 88 (minor 0) in 1.21.10
            case "1.21.8" -> 81;   //: the bundled compiler's mc_target.py needs this row (VERSION_BUMP_1218 §8)
            case "1.21.4" -> 61;
            case "1.21.1" -> 48;
            default -> throw new AssertionError("no pack_format known for "
                    + com.moddingfromamod.compat.Target.MINECRAFT);
        };
        Case c = corpus().stream().filter(Case::expectCompiles).findFirst().orElse(null);
        Assumptions.assumeTrue(c != null, "no compiling case in the corpus");
        Path dir = work.resolve(c.id());
        Path zip = dir.resolve("x.zip");
        assertTrue(CbScriptCompiler.compile(dir, c.featureId(), "pack format", c.source(), zip).ok(),
                "the first compiling corpus case must compile");
        try (var zf = new java.util.zip.ZipFile(zip.toFile())) {
            var e = zf.getEntry("pack.mcmeta");
            assertTrue(e != null, "the compiled zip has no pack.mcmeta");
            int got = com.google.gson.JsonParser.parseString(
                    new String(zf.getInputStream(e).readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("pack").get("pack_format").getAsInt();
            org.junit.jupiter.api.Assertions.assertEquals(expected, got,
                    "the bundled compiler wrote the wrong pack_format for Minecraft "
                            + com.moddingfromamod.compat.Target.MINECRAFT
                            + " — is the bundle stale? ./tools/refresh-cbscript-bundle.sh");
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "(no detail)";
        }
        String t = s.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl);
    }
}
