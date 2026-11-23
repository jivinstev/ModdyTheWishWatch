package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** the film scripts put each pose on cue, so a take shows the thing whatever the AI would have done. */
class RigStateScriptTest {

    @Test
    void napcycleLiesDownSleepsAndWakesOnASchedule() {
        assertEquals(0f, RigStateScript.at("napcycle", 10).sleep());
        assertEquals(0.5f, RigStateScript.at("napcycle", 32).sleep(), 1e-6f, "lying down, half way");
        assertEquals(1f, RigStateScript.at("napcycle", 100).sleep());
        assertEquals(0.5f, RigStateScript.at("napcycle", 156).sleep(), 1e-6f, "waking, half way");
        assertEquals(0f, RigStateScript.at("napcycle", 200).sleep());
        assertEquals(RigStateScript.at("napcycle", 100).sleep(), RigStateScript.at("napcycle", 100 + RigStateScript.NAP_PERIOD).sleep());
    }

    @Test
    void swimcycleSwimsWithAPitchThatKeepsChanging() {
        float lo = 0;
        float hi = 0;
        for (int t = 0; t < RigStateScript.SWIM_PERIOD; t++) {
            RigLocomotion.Weights w = RigStateScript.at("swimcycle", t);
            assertEquals(1f, w.swim());
            lo = Math.min(lo, w.travelPitch());
            hi = Math.max(hi, w.travelPitch());
            float next = RigStateScript.at("swimcycle", t + 2).travelPitch();
            assertTrue(Math.abs(next - w.travelPitch()) < 5f, "a smooth swing, never a jump");
        }
        assertEquals(-RigStateScript.SWIM_PITCH, lo, 0.5f);
        assertEquals(RigStateScript.SWIM_PITCH, hi, 0.5f);
    }

    @Test
    void anythingElseIsNoScript() {
        assertNull(RigStateScript.named(null));
        assertNull(RigStateScript.named("dance"));
        assertEquals("napcycle", RigStateScript.named(" NapCycle "));
    }

    /** The showcase puts every pose and look on its own beat, one at a time, and loops. */
    @Test
    void showcaseRunsEachBeatAloneOnALoop() {
        assertEquals(0f, RigStateScript.at("showcase", 60).crouch(), "the take's own beats first");
        assertEquals(1f, RigStateScript.at("showcase", RigStateScript.SC_CROUCH + 20).crouch());
        assertEquals(1f, RigStateScript.at("showcase", RigStateScript.SC_JUMP + 10).jump());
        assertEquals(1f, RigStateScript.at("showcase", RigStateScript.SC_SIT + 30).sit());
        assertEquals(1f, RigStateScript.at("showcase", RigStateScript.SC_LIE + 30).lie());
        assertEquals(1f, RigStateScript.at("showcase", RigStateScript.SC_SLEEP + 60).sleep());
        assertEquals(0f, RigStateScript.at("showcase", RigStateScript.SC_POISON + 5).sleep(), "awake for the looks");
        assertEquals(RigTint.POISON, RigStateScript.look("showcase", RigStateScript.SC_POISON + 5).tint());
        assertEquals(RigTint.FROST, RigStateScript.look("showcase", RigStateScript.SC_FROST + 5).tint());
        assertEquals(RigTint.WITHER, RigStateScript.look("showcase", RigStateScript.SC_WITHER + 5).tint());
        assertEquals(1f, RigStateScript.look("showcase", RigStateScript.SC_WHITEN + 20).white(), 1e-6f);
        assertTrue(RigStateScript.look("showcase", RigStateScript.SC_BRIGHT + 5).bright());
        assertEquals(1.4f, RigStateScript.look("showcase", RigStateScript.SC_SCALE + 30).scale(), 1e-6f);
        assertEquals(RigStateScript.Look.NONE, RigStateScript.look("showcase", RigStateScript.SHOWCASE_PERIOD + 10));
        assertNull(RigStateScript.look("napcycle", 100));
    }

    @org.junit.jupiter.api.Test
    void liesleepLiesThenSleepsOnePeriod() {
        org.junit.jupiter.api.Assertions.assertEquals("liesleep", RigStateScript.named("LieSleep"));
        var awake = RigStateScript.at("liesleep", 10);
        org.junit.jupiter.api.Assertions.assertEquals(0f, awake.lie() + awake.sleep());
        var lying = RigStateScript.at("liesleep", 100);
        org.junit.jupiter.api.Assertions.assertEquals(1f, lying.lie());
        org.junit.jupiter.api.Assertions.assertEquals(0f, lying.sleep());
        var asleep = RigStateScript.at("liesleep", 250 + RigStateScript.LIESLEEP_PERIOD);
        org.junit.jupiter.api.Assertions.assertEquals(0f, asleep.lie());
        org.junit.jupiter.api.Assertions.assertEquals(1f, asleep.sleep());
        var between = RigStateScript.at("liesleep", 160);
        org.junit.jupiter.api.Assertions.assertEquals(0f, between.lie() + between.sleep());
    }
}
