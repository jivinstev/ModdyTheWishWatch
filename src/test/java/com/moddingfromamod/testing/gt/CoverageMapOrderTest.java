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
}
