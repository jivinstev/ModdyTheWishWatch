package com.moddingfromamod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RunContextTest {

    @Test
    @DisplayName("a plain JUnit run counts as a test run")
    void junitIsATestRun() {
        // This test IS the case that was missed. isTestRun() knew about Gate B and Gate C but not
        // Gate A, so unit-test fixtures (always_fails, boom, no_such_tool) were stamped test=false
        // and survived every filter -- 198 calls each in a census of the real log, corrupting any
        // attempt to measure what the agent actually does in play.
        assertTrue(RunContext.isTestRun(),
                "if this fails, unit-test traffic is being recorded as real play");
    }
}
