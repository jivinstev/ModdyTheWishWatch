package com.moddingfromamod.testing.gt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * A coverage-mapping run's batches, sorted by their environment paths (testing/mixin/GameTestBatchOrderMixin), must
 * come out as: the idle window, then each class's own batches followed by its settle window, class after class. If a
 * class's batches were split by another's, what one left running would be counted as the other's, and two maps of one
 * commit would disagree (docs/GATE_B.md, "The coverage map").
 */
class CoverageMapOrderTest {

    private static String path(String batch) {
        return ("batch/" + batch).toLowerCase(Locale.ROOT);   // GameTestAdapter registers each environment this way
    }

    @Test
    void eachClassesBatchesAndItsSettleWindowRunTogetherAfterTheIdleWindow() {
        List<String[]> named = new ArrayList<>();   // {path, owner}
        for (String cls : List.of("Foo", "FooBar", "Foo_Z", "Foo_C", "Bar", "Zed", "Foo2")) {
            for (String b : List.of("zzz", "defaultBatch", "a")) {
                named.add(new String[] {path(CoverageMap.batchName(cls, b)), cls});
            }
            named.add(new String[] {path(CoverageMap.settleBatch(cls)), cls + "/settle"});
        }
        named.add(new String[] {path(CoverageMap.IDLE_BATCH), "idle"});
        named.sort((x, y) -> x[0].compareTo(y[0]));

        List<String> owners = new ArrayList<>();
        for (String[] n : named) {
            String o = n[1];
            if (owners.isEmpty() || !owners.get(owners.size() - 1).equals(o)) {
                owners.add(o);
            }
        }
        assertEquals(List.of("idle", "Bar", "Bar/settle", "Foo", "Foo/settle", "Foo2", "Foo2/settle", "Foo_C",
                "Foo_C/settle", "Foo_Z", "Foo_Z/settle", "FooBar", "FooBar/settle", "Zed", "Zed/settle"), owners);
    }

    @Test
    void aSettleWindowWaitsItsMinimumThenForQuietButNeverPastItsMost() {
        long t0 = 1_000_000;
        org.junit.jupiter.api.Assertions.assertFalse(CoverageMap.settled(t0, t0 + 100, true), "never before its minimum");
        org.junit.jupiter.api.Assertions.assertTrue(CoverageMap.settled(t0, t0 + CoverageMap.SETTLE_MS, true));
        org.junit.jupiter.api.Assertions.assertFalse(CoverageMap.settled(t0, t0 + CoverageMap.SETTLE_MS, false),
                "the mod still working: wait");
        org.junit.jupiter.api.Assertions.assertTrue(CoverageMap.settled(t0, t0 + CoverageMap.SETTLE_MAX_MS, false),
                "a thread that never stops cannot hold the run");
    }

    @Test
    void aClassOwnsTheBuilderPageWalkItStartedEvenUnderCoverage() {
        // the page-facts walk a class starts when its world first answers makes: 99 s under coverage (27 Nov 2026).
        // At 15 s it ran on through the next five or six classes, a different number each run.
        long t0 = 1_000_000;
        long walk = 99_000;
        org.junit.jupiter.api.Assertions.assertFalse(CoverageMap.settled(t0, t0 + walk, false),
                "still walking at 99 s: the class that started it is still settling");
        org.junit.jupiter.api.Assertions.assertTrue(CoverageMap.settled(t0, t0 + walk, true), "and it ends when it does");
        org.junit.jupiter.api.Assertions.assertTrue(CoverageMap.SETTLE_MAX_MS >= 2 * walk,
                "room for a slower runner than the one measured");
        // the wall clock ends a settle window, never its ticks: hundreds a second for the longest window
        org.junit.jupiter.api.Assertions.assertTrue(
                CoverageMap.SETTLE_TIMEOUT_TICKS >= 1000 * (CoverageMap.SETTLE_MAX_MS / 1000),
                "a tick budget that outlasts the most at a thousand ticks a second");
    }

    @Test
    void theSweepBetweenTwoClassesIsTheHarnessesNotTheNextClass() {
        // dumped as __sweep__ (ambient in tools/gate/covmap.py): the leave handlers it fires (PetBehaviours#onLeave,
        // LiveFitWatch#onLeave) were counted in whichever classes followed one that left a mob
        org.junit.jupiter.api.Assertions.assertEquals("__sweep__", CoverageMap.SWEEP);
        org.junit.jupiter.api.Assertions.assertTrue(CoverageMap.sweeps("FooGameTest", "BarGameTest"));
        org.junit.jupiter.api.Assertions.assertTrue(CoverageMap.sweeps(CoverageMap.BOOT, CoverageMap.IDLE));
        org.junit.jupiter.api.Assertions.assertTrue(CoverageMap.sweeps("BarGameTest", CoverageMap.SHUTDOWN));
        org.junit.jupiter.api.Assertions.assertFalse(CoverageMap.sweeps("BedrockBatchGameTest#__setup__",
                "BedrockBatchGameTest#watch_panel_grid"), "the Bedrock batch's cases share one staged board");
    }

    @Test
    void theSweepIsAmbientInTheMapTools() throws java.io.IOException {
        // the pure rule's other half lives in tools/gate/covmap.py: a dump nobody reads as ambient would be a class
        String py = java.nio.file.Files.readString(java.nio.file.Path.of("tools/gate/covmap.py"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("PSEUDO = \\(([^)]*)\\)").matcher(py);
        org.junit.jupiter.api.Assertions.assertTrue(m.find(), "covmap.py names its pseudo-units");
        for (String unit : List.of(CoverageMap.BOOT, CoverageMap.IDLE, CoverageMap.SHUTDOWN, CoverageMap.SWEEP)) {
            org.junit.jupiter.api.Assertions.assertTrue(m.group(1).contains("\"" + unit + "\""), unit + " is ambient");
        }
    }
}
