package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Framing a BUILDING, which is not a big creature.
 *
 * <p>The creature camera frames on height and clamps at 26 blocks. Both assumptions are safe for
 * something sculpted out of boxes and wrong for architecture, and each failure produces a
 * photograph that looks like a broken build rather than a bad camera — which is the expensive kind
 * of wrong, because it gets attributed to the model under test.
 */
class OrbitCameraBuildTest {

    @Test
    void awideLowHallIsFramedOnItsWidthNotItsHeight() {
        // 40 long, 8 tall. On height alone the camera stands ~10 blocks away and photographs the
        // middle third of one wall.
        double onHeightOnly = OrbitCamera.distanceFor(8);
        double forBuild = OrbitCamera.distanceForBuild(40, 8);
        assertTrue(forBuild > onHeightOnly * 2,
                "a hall five times wider than it is tall needs far more standoff than its height "
                        + "implies: height-only " + onHeightOnly + " vs build " + forBuild);
    }

    @Test
    void atallKeepIsNotClippedByTheCreatureCeiling() {
        // The failure this exists for: 26 blocks is plenty for anything sculpted and silently too
        // close for a keep, which then overflows the frame with no error anywhere.
        double keep = OrbitCamera.distanceForBuild(20, 30);
        assertTrue(keep > 26.0,
                "a 30-block keep must be shot from beyond the creature cap, not at it: " + keep);
    }

    @Test
    void agiantBuildStopsAtTheFogRatherThanRunningToTheHorizon() {
        double huge = OrbitCamera.distanceForBuild(400, 300);
        assertEquals(OrbitCamera.MAX_BUILD_DISTANCE, huge, 0.001,
                "past this the render distance decides the picture, and a subject lost in fog "
                        + "photographs as an empty landscape — which reads as a failed build");
    }

    @Test
    void atinyBuildIsNotPhotographedFromInsideItself() {
        double shed = OrbitCamera.distanceForBuild(3, 3);
        assertTrue(shed >= 6.0, "a 3x3 shed still needs the camera outside it: " + shed);
    }

    @Test
    void distanceGrowsWithTheSubject() {
        double small = OrbitCamera.distanceForBuild(10, 6);
        double medium = OrbitCamera.distanceForBuild(30, 12);
        double large = OrbitCamera.distanceForBuild(60, 25);
        assertTrue(small < medium && medium < large,
                "monotonic, or two buildings photographed at different sizes are not comparable: "
                        + small + " / " + medium + " / " + large);
    }

    @Test
    void thecreatureCameraIsUntouched() {
        // A build-specific change must not move the creature framing: every creature photograph in
        // the eval ledger was taken with this exact formula, and silently altering it would make
        // tomorrow's arm incomparable with every arm already recorded.
        assertEquals(1.3 * 4, OrbitCamera.distanceFor(4), 0.001);
        assertEquals(26.0, OrbitCamera.distanceFor(1000), 0.001);
        assertEquals(2.2, OrbitCamera.distanceFor(0.1), 0.001);
    }
}
