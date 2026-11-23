package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The camera's size, speed and cap (HO-14, decision Q9). */
class CameraProfileTest {

    @Test
    @DisplayName("the shipped default is 720p at 30fps, two at once")
    void theDefaultIsWhatQ9Decided() {
        assertEquals(1280, CameraProfile.DEFAULT.width());
        assertEquals(720, CameraProfile.DEFAULT.height());
        assertEquals(30, CameraProfile.DEFAULT.maxFps());
        assertEquals(2, CameraProfile.DEFAULT.concurrent(),
                "a typical server box has no serious GPU — this number is a decision, not a default "
                        + "somebody forgot to set");
    }

    @Test
    @DisplayName("a nonsense concurrency is ONE, never zero")
    void zeroConcurrencyIsNotNoCameras() {
        // ⚠ The floors matter more than the ceilings here. Reading 0 as "no cameras" would stall
        // every wish that wants a photograph behind a queue nothing can drain — a typo becoming a
        // deadlock.
        assertEquals(1, new CameraProfile(1280, 720, 30, 0).concurrent());
        assertEquals(1, new CameraProfile(1280, 720, 30, -5).concurrent());
        assertEquals(4, new CameraProfile(1280, 720, 30, 99).concurrent(),
                "and the ceiling is four for WishSlots' reason: each camera is a whole Minecraft");
    }

    @Test
    @DisplayName("merging keeps every key the profile does not own")
    void mergeDoesNotRewriteTheWholeFile() {
        // ⚠ The keys that must survive are the ones that stop a fresh profile sitting on
        // AccessibilityOnboardingScreen waiting for a human to click — CameraClientTest's own
        // comment records that hang at 190% CPU for twenty-one minutes.
        String existing = "onboardAccessibility:false\ntutorialStep:none\nmaxFps:120\n";
        String merged = CameraProfile.DEFAULT.mergeInto(existing);
        assertTrue(merged.contains("onboardAccessibility:false"), "kept: " + merged);
        assertTrue(merged.contains("tutorialStep:none"), "kept: " + merged);
        assertTrue(merged.contains("maxFps:30"), "overwritten in place: " + merged);
        assertTrue(!merged.contains("maxFps:120"), "the old value is gone: " + merged);
        assertTrue(merged.contains("overrideWidth:1280") && merged.contains("overrideHeight:720"),
                "and the keys it owns are appended when absent: " + merged);
    }

    @Test
    @DisplayName("merging an empty or null file still produces the profile's own keys")
    void mergeHandlesNothing() {
        for (String start : new String[] {null, "", "\n"}) {
            String merged = CameraProfile.DEFAULT.mergeInto(start);
            assertTrue(merged.contains("overrideWidth:1280"), "from " + start + ": " + merged);
            assertTrue(merged.contains("maxFps:30"), "from " + start + ": " + merged);
        }
    }

    @Test
    @DisplayName("the launch environment carries every field the client needs")
    void launchEnvIsComplete() {
        var env = CameraProfile.DEFAULT.launchEnv();
        assertEquals("1280", env.get("CAMERA_WIDTH"));
        assertEquals("720", env.get("CAMERA_HEIGHT"));
        assertEquals("30", env.get("CAMERA_MAX_FPS"));
    }

    @Test
    @DisplayName("describe() says whether it is the shipped value or an override")
    void describeSaysWhichItIs() {
        assertTrue(CameraProfile.DEFAULT.describe().contains("shipped default"));
        assertTrue(new CameraProfile(640, 480, 20, 1).describe().contains("overridden"),
                "an operator reading a boot line needs to know whether somebody changed this");
    }

    @Test
    @DisplayName("a garbled property falls back to the shipped value rather than to a clamp")
    void aTypoIsNotAQuietZero() {
        String key = CameraProfile.PREFIX + "concurrent";
        String prior = System.getProperty(key);
        try {
            System.setProperty(key, "two");
            assertEquals(CameraProfile.DEFAULT.concurrent(),
                    CameraProfile.fromProperties().concurrent(),
                    "WishSlots' rule: a typo'd flag must not silently become something else");
        } finally {
            if (prior == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, prior);
            }
        }
    }
}
