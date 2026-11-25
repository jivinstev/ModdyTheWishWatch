package com.moddingfromamod.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** A test's seconds in the run record: the run history's costs and trends are read from them (docs/GATE_B.md). */
class GateBTimingsTest {

    @Test
    void aTestsTimeIsMeasuredFromItsBodysStartWhenTheFrameworkSaysZero() {
        // 1.21.8's GameTestInfo.getRunTime() reads 0 for every test: the record would say every test is free
        assertEquals(1500, GateBTimings.runMillis(0, 1000L, 2500L));
        assertEquals(700, GateBTimings.runMillis(700, 1000L, 2500L), "the framework's own number wins when it has one");
        assertEquals(0, GateBTimings.runMillis(0, null, 2500L), "a test whose body never started ran for nothing");
        assertEquals(0, GateBTimings.runMillis(0, 3000L, 2500L), "never negative");
    }
}
