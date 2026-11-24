package com.moddingfromamod.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.RunContext;

import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.TestReporter;

/**
 * <b>Which Gate B test is actually slow.</b>
 *
 * <p>Gate B prints one number — {@code All N required tests passed} — and nothing else about cost.
 * That is enough to know the gate is green and nothing at all to know why it takes as long as it
 * does, which is how it grew from <b>4m57s at 431 tests to 16m53s at 522</b> without anyone being
 * able to name a cause. Every attempt to attribute that time so far has gone through log
 * archaeology on {@code debug.log}, which can see BATCH boundaries and cannot see tests.
 *
 * <p>Minecraft already measures each test ({@link GameTestInfo#getRunTime()}); it simply never
 * reports it. {@link GlobalTestReporter} is a replaceable single delegate, so this wraps whatever
 * reporter is installed — it does not replace it — and writes a slowest-first table at the end.
 *
 * <h2>Read the batch column, not just the seconds</h2>
 *
 * <p>Batches run <b>strictly sequentially</b> ({@code GameTestRunner.runBatch(i)} only calls
 * {@code runBatch(i+1)} once every test in batch {@code i} is done) while the tests inside one run
 * <b>concurrently</b>. So a batch costs its SLOWEST test, and a single 83-second straggler holds 49
 * finished tests waiting — which is worth far more than its own 83 seconds suggests. A test that is
 * slow but shares a batch with something slower is free.
 *
 * <p>Deliberately observability only: it asserts nothing and fails nothing. A timing threshold here
 * would fail on machine load rather than on a defect, which is the trap
 * {@code MallClientTest}'s crowd-cost probe already records.
 */
public final class GateBTimings implements TestReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** How many rows the summary prints. The tail is a long flat list of fast tests. */
    private static final int TOP_N = 30;

    private record Row(String name, long ms, boolean passed, boolean required,
                       int timeoutTicks, String error, String batch, long finishedMs,
                       boolean skipped) { }

    private final TestReporter delegate;
    private final List<Row> rows = new CopyOnWriteArrayList<>();
    private final long startedMillis = System.currentTimeMillis();

    private GateBTimings(TestReporter delegate) {
        this.delegate = delegate;
    }

    /**
     * Install the recorder, wrapping whatever is already there. No-op outside a test run.
     *
     * <p>Called from the mod constructor rather than a server event: {@code GlobalTestReporter} is
     * a static the framework reads as soon as the first test finishes, and there is no event that
     * is guaranteed to land before that.
     */
    public static void install() {
        if (!RunContext.isTestRun()) {
            return;
        }
        // Wrap, never replace. LogTestReporter is what prints a failing test's reason, and losing
        // that to gain a timing table would be a straight downgrade.
        GlobalTestReporter.replaceWith(new GateBTimings(new PassThrough()));
    }

    /**
     * The reporter that was in place before us.
     *
     * <p>{@link GlobalTestReporter} exposes no getter for its current delegate, so there is nothing
     * to capture — this reproduces the default {@code LogTestReporter} behaviour that matters (a
     * failed test says why) without reaching into the framework's private state.
     */
    private static final class PassThrough implements TestReporter {
        @Override
        public void onTestFailed(GameTestInfo info) {
            if (info.isRequired()) {
                LOGGER.error("{} failed! {}", info.id().toString(), describe(info));
            } else {
                LOGGER.warn("(optional) {} failed. {}", info.id().toString(), describe(info));
            }
        }

        @Override
        public void onTestSuccess(GameTestInfo info) {
            // The framework's own progress bar already reports passes.
        }

        private static String describe(GameTestInfo info) {
            return info.getError() == null ? "" : info.getError().getMessage();
        }
    }

    @Override
    public void onTestFailed(GameTestInfo info) {
        rows.add(row(info, false));
        delegate.onTestFailed(info);
    }

    @Override
    public void onTestSuccess(GameTestInfo info) {
        rows.add(row(info, true));
        delegate.onTestSuccess(info);
    }

    @Override
    public void finish() {
        delegate.finish();
        if (rows.isEmpty()) {
            return;
        }
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingLong(Row::ms).reversed());
        long total = sorted.stream().mapToLong(Row::ms).sum();

        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT,
                "%n=== GATE B PER-TEST TIME ===%n%d tests, %.1fs of test time%n"
                + "(batches are sequential and tests inside one are concurrent, so a batch costs "
                + "its SLOWEST test — the top of this list is what the run is waiting on)%n",
                sorted.size(), total / 1000.0));
        out.append(String.format(Locale.ROOT, "%9s  %s%n", "seconds", "test"));
        for (Row r : sorted.subList(0, Math.min(TOP_N, sorted.size()))) {
            out.append(String.format(Locale.ROOT, "%9.2f  %s%s%n",
                    r.ms() / 1000.0, r.name(), r.passed() ? "" : "   <-- FAILED"));
        }
        long slow = sorted.stream().filter(r -> r.ms() >= 1000).count();
        long skipped = sorted.stream().filter(Row::skipped).count();
        if (skipped > 0) {
            out.append(String.format(Locale.ROOT,
                    "%d of these tests SKIPPED and proved nothing (Create not loaded) -- "
                    + "they are 0.00s because they did not run%n", skipped));
        }
        out.append(String.format(Locale.ROOT,
                "%d of %d tests took a second or more; the other %d total %.1fs%n"
                + "============================%n",
                slow, sorted.size(), sorted.size() - slow,
                sorted.stream().filter(r -> r.ms() < 1000).mapToLong(Row::ms).sum() / 1000.0));

        System.out.print(out);
        LOGGER.info("Gate B per-test timing: {} tests, {}s", sorted.size(), total / 1000);

        // Also to a file, so a run can be diffed against an earlier one rather than remembered.
        try {
            Path dest = Path.of("gateb-timings.tsv");
            StringBuilder tsv = new StringBuilder(
                    "seconds\tpassed\trequired\tbatch\tfinished_ms\ttest\n");
            for (Row r : sorted) {
                tsv.append(String.format(Locale.ROOT, "%.3f\t%s\t%s\t%s\t%d\t%s%n",
                        r.ms() / 1000.0, r.passed(), r.required(), r.batch(), r.finishedMs(),
                        r.name()));
            }
            Files.writeString(dest, tsv.toString());
            LOGGER.info("Gate B per-test timing written to {}", dest.toAbsolutePath());
        } catch (IOException e) {
            // Never fail a run over the instrument. A missing file is visible; a failed gate here
            // would be a timing tool breaking a green suite.
            LOGGER.warn("could not write gateb-timings.tsv: {}", e.toString());
        }

        writeRunRecord(sorted);
    }

    private static Row row(GameTestInfo info, boolean passed) {
        String err = null;
        if (info.getError() != null) {
            String m = info.getError().getMessage();
            err = m == null ? info.getError().getClass().getSimpleName() : m.split("\n", 2)[0];
        }
        // BATCH + FINISH TIME, so "which batch was this in" stops being a manual join.
        //
        // `batchName()` is the DECLARED batch, not the partition: GameTestBatchFactory groups by
        // name and then chops each group into MAX_TESTS_PER_BATCH(50) chunks, so 447 tests all
        // report `defaultBatch` while the log calls them defaultBatch:0..8. The partition index is
        // never handed to a reporter. `finishedMs` is what closes that gap -- the server log stamps
        // every "Running test batch" line, so a wall-clock finish time places each test in its real
        // chunk without guessing.
        //
        // Worth the two columns because this join has now been hand-rolled three times while
        // chasing, and each time from a different ad-hoc script.
        String batch;
        try {
            batch = info.getTestHolder().value().batch().unwrapKey()      //: the adapter names each batch's
                    .map(k -> k.identifier().getPath().replaceFirst("^batch/", "")).orElse("?");   // environment batch/<name>
        } catch (RuntimeException e) {
            batch = "?";  // never fail a run over the instrument
        }
        // SKIPPED: nothing skips any more. It was the Create gate's record of a test that took
        // the no-Create path (removed Create and its gate); the column stays so a history
        // row keeps its shape, and is always false.
        return new Row(info.id().toString(), info.getRunTime(), passed, info.isRequired(),
                info.getTimeoutTicks(), err, batch, System.currentTimeMillis(), false);
    }

    /**
     * The durable half: one JSON record per run, outside the ephemeral {@code run/} directory.
     *
     * <p>{@code gateb-timings.tsv} answers "what was slow in the run I just did". It cannot answer
     * "is this test getting slower", "how often does it fail", or "was that a flake or a
     * regression" — every one of which needs the PREVIOUS runs, and {@code run/} is wiped before
     * each one. were both diagnosed by a human running the gate several times and
     * hand-counting the results into a table; this is that table, written down.
     *
     * <p>Deliberately writes a per-run file rather than appending to one log: two Gate B runs can
     * be in flight at once ({@code -Prundir}), and concurrent appends to a single file interleave.
     * {@code tools/gate-history.py --fold} is what merges them, and it is a separate, explicit step
     * — see docs/GATE_HISTORY.md for why folding must never be a side effect of running the gate.
     *
     * <p>{@code commit}/{@code dirty} are written as JSON null when the build did not supply them,
     * never guessed. A history row that quietly attributes a result to the wrong commit is worse
     * than one that admits it does not know.
     */
    private void writeRunRecord(List<Row> sorted) {
        try {
            // The CONFIGURED home, so a run given its own -Dmoddingfromamod.home writes its
            // history where the rest of its records went.
            Path dir = com.moddingfromamod.runtime.RuntimeConfig.home().resolve("gate-runs");
            Files.createDirectories(dir);

            String commit = System.getProperty(ModdingFromAModMod.MODID + ".gitcommit", "");
            String dirty = System.getProperty(ModdingFromAModMod.MODID + ".gitdirty", "");
            String started = java.time.Instant.ofEpochMilli(startedMillis).toString();
            String runId = started.replaceAll("[-:]", "").replaceAll("\\.\\d+", "")
                    + "-" + Long.toHexString(startedMillis & 0xffffffL);

            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("schema", 1);
            o.addProperty("gate", "B");
            o.addProperty("runId", runId);
            if (commit.isBlank()) {
                o.add("commit", com.google.gson.JsonNull.INSTANCE);
                o.add("dirty", com.google.gson.JsonNull.INSTANCE);
            } else {
                o.addProperty("commit", commit);
                o.addProperty("dirty", Boolean.parseBoolean(dirty));
            }
            o.addProperty("startedUtc", started);
            o.addProperty("wallSeconds", (System.currentTimeMillis() - startedMillis) / 1000.0);
            // The DISCRIMINATOR, and it has to be here rather than inferred from the absence of
            // a per-test flag: without it a record written before CreateGate existed and a
            // record from a run where nothing skipped are byte-identical, and a reader would
            // have to guess which. `recordsSkips` says "this run could have told you".
            o.addProperty("recordsSkips", true);
            o.addProperty("skipped", 0);   // nothing can skip since Create went
            // WHICH Minecraft and WHICH shards ran. The release check (tools/release-evidence.py)
            // asks for both halves of --all, and a commit alone cannot say which half a row is.
            o.addProperty("mc", net.minecraft.SharedConstants.getCurrentVersion().name());
            o.addProperty("namespaces", System.getProperty("neoforge.enabledGameTestNamespaces", ""));
            // a CLASS-FILTERED run boots the same namespaces as the full tier and is not one.
            // Named here, so release-evidence.py can refuse it as full-tier evidence; absent = no filter.
            String classes = System.getProperty(GameTestClassFilter.PROPERTY, "").trim();
            if (!classes.isEmpty()) {
                o.addProperty("classes", classes);
            }
            String excluded = System.getProperty(GameTestClassFilter.EXCLUDE, "").trim();
            if (!excluded.isEmpty()) {
                o.addProperty("excluded", excluded);
            }
            String patch = System.getProperty(ModdingFromAModMod.MODID + ".gatepatch", "").trim();
            if (!patch.isEmpty()) {
                o.addProperty("patch", patch);
            }

            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (Row r : sorted) {
                com.google.gson.JsonObject t = new com.google.gson.JsonObject();
                t.addProperty("name", r.name());
                t.addProperty("seconds", Math.round(r.ms() / 10.0) / 100.0);
                t.addProperty("passed", r.passed());
                t.addProperty("required", r.required());
                t.addProperty("timeoutTicks", r.timeoutTicks());
                t.addProperty("batch", r.batch());
                t.addProperty("finishedMs", r.finishedMs());
                // Written only when true: a run record stays the shape it was for every test
                // that really ran, so gate-history.py keeps reading 3-tuples for those.
                if (r.skipped()) {
                    t.addProperty("skipped", true);
                }
                if (r.error() == null) {
                    t.add("error", com.google.gson.JsonNull.INSTANCE);
                } else {
                    t.addProperty("error", r.error());
                }
                arr.add(t);
            }
            o.add("tests", arr);

            Path dest = dir.resolve(runId + ".json");
            Files.writeString(dest, o.toString());
            LOGGER.info("Gate B run record written to {} — fold it with "
                    + "`python3 tools/gate-history.py --fold`", dest);
        } catch (IOException | RuntimeException e) {
            // Same rule as the TSV: never fail a run over the instrument.
            LOGGER.warn("could not write the Gate B run record: {}", e.toString());
        }
    }
}
