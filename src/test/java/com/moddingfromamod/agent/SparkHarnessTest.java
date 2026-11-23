package com.moddingfromamod.agent;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The agent.sparkHarness knob's own rule: knob, then boot property, then the default. */
class SparkHarnessTest {

    @Test
    @DisplayName("the default is claude-cli: production Spark does not move to Muse Code")
    void defaultIsTheClaudeCli() {
        assertEquals("claude-cli", SparkHarness.resolve(k -> null, p -> null));
        assertEquals(SparkHarness.CLAUDE_CLI, SparkHarness.DEFAULT);
    }

    @Test
    @DisplayName("the knob wins over the boot property")
    void knobWins() {
        assertEquals("muse-code", SparkHarness.resolve(k -> "muse-code", p -> "claude-cli"));
        assertEquals("claude-cli", SparkHarness.resolve(k -> "claude-cli", p -> "muse-code"));
    }

    @Test
    @DisplayName("the boot property answers when the knob is unset; nonsense is the default")
    void propertyThenDefault() {
        assertEquals("muse-code", SparkHarness.resolve(k -> null, p -> " Muse-Code "));
        assertEquals("claude-cli", SparkHarness.resolve(k -> "muse", p -> "codex"));
    }

    @Test
    @DisplayName("it reaches the worker as MODDY_SPARK_HARNESS, the name the shim reads")
    void reachesTheWorker() {
        Map<String, String> env = new HashMap<>();
        SparkHarness.applyTo(env, "muse-code");
        assertEquals(Map.of("MODDY_SPARK_HARNESS", "muse-code"), env);
    }
}
