package com.moddingfromamod.agent;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The agent.wishModel knob's own rule: knob, then boot property, then the default. */
class WishModelArmTest {

    @Test
    @DisplayName("the default is wonder-first: both worlds are 'Wonder on' once a release carries it")
    void defaultIsWonderFirst() {
        assertEquals("wonder-first", WishModelArm.resolve(k -> null, p -> null));
        assertEquals(WishModelArm.WONDER_FIRST, WishModelArm.DEFAULT);
    }

    @Test
    @DisplayName("the knob wins over the boot property")
    void knobWins() {
        assertEquals("spark-only", WishModelArm.resolve(k -> "spark-only", p -> "wonder-first"));
        assertEquals("wonder-first", WishModelArm.resolve(k -> "wonder-first", p -> "spark-only"));
    }

    @Test
    @DisplayName("the boot property answers when the knob is unset; nonsense is the default")
    void propertyThenDefault() {
        assertEquals("spark-only", WishModelArm.resolve(k -> null, p -> " Spark-Only "));
        assertEquals("wonder-first", WishModelArm.resolve(k -> "wonderish", p -> "nope"));
    }

    @Test
    @DisplayName("it reaches the worker as MODDY_WISH_MODEL_ARM, the name the shim reads")
    void reachesTheWorker() {
        Map<String, String> env = new HashMap<>();
        WishModelArm.applyTo(env, "spark-only");
        assertEquals(Map.of("MODDY_WISH_MODEL_ARM", "spark-only"), env);
    }
}
