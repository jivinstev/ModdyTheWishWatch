package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** a spin clip's path is threaded THROUGH the framer's own poses. */
class OrbitClipPathTest {

    private static final double EPS = 1e-6;

    /** Four keys as BuildFraming makes them: bearing stop*90, one radius, one eye height, pitch 18. */
    private static List<OrbitClipPath.Pose> buildingKeys(double cx, double cz, double r, double eye) {
        List<OrbitClipPath.Pose> k = new ArrayList<>();
        for (int stop = 0; stop < 4; stop++) {
            double b = stop * 90;
            double rad = Math.toRadians(b);
            k.add(new OrbitClipPath.Pose(cx + r * Math.sin(rad), eye, cz - r * Math.cos(rad), b, 18));
        }
        return k;
    }

    @Test
    void bearingsAreTheFramersConvention() {
        assertEquals(0, OrbitClipPath.bearingOf(0, 0, 0, -10), EPS, "north of centre is bearing 0");
        assertEquals(90, OrbitClipPath.bearingOf(0, 0, 10, 0), EPS);
        assertEquals(180, OrbitClipPath.bearingOf(0, 0, 0, 10), EPS);
        assertEquals(270, OrbitClipPath.bearingOf(0, 0, -10, 0), EPS);
    }

    @Test
    void aRingPassesThroughEveryKeyAndFacesTheCentre() {
        var keys = buildingKeys(100, -40, 30, 75);
        var path = OrbitClipPath.ring(keys, Double.NaN, Double.NaN, 20);
        assertEquals(20, path.size());
        for (int i = 0; i < 4; i++) {
            var at = path.get(i * 5);                     // 20 steps, 4 keys: every 5th step is a key
            var k = keys.get(i);
            assertEquals(k.x(), at.x(), 1e-6, "key " + i + " x");
            assertEquals(k.z(), at.z(), 1e-6, "key " + i + " z");
            assertEquals(k.y(), at.y(), 1e-6, "the framer's eye height, unchanged");
            assertEquals(k.pitch(), at.pitch(), 1e-6);
        }
        for (var p : path) {
            assertEquals(30, Math.hypot(p.x() - 100, p.z() + 40), 1e-6, "the framer's distance, all the way round");
            assertEquals(OrbitClipPath.bearingOf(100, -40, p.x(), p.z()), p.yaw(), 1e-6, "looking at the centre");
        }
    }

    @Test
    void aRingIsOneFullTurnStartingAtTheHeroBearingAndNotRepeatingIt() {
        var keys = buildingKeys(0, 0, 10, 5);
        var path = OrbitClipPath.ring(keys, 0, 0, 24);
        double turned = 0;
        for (int i = 1; i < path.size(); i++) {
            double d = ((path.get(i).yaw() - path.get(i - 1).yaw()) % 360 + 540) % 360 - 180;
            assertEquals(15, d, 1e-6, "an even 15° a step: no jump, no stall");
            turned += d;
        }
        assertEquals(345, turned, 1e-6, "one step short of a full turn, so a loop does not hold a frame");
        assertEquals(0, path.get(0).yaw(), 1e-6, "starts at the first key, the hero shot's bearing");
    }

    @Test
    void aRingFollowsTheFramersDirectionAndStartWhateverTheyAre() {
        // A creature's keys start at its FACE (any bearing) and go round the other way
        var keys = new ArrayList<OrbitClipPath.Pose>();
        for (int stop = 0; stop < 4; stop++) {
            double b = ((200 - stop * 90) % 360 + 360) % 360;
            double rad = Math.toRadians(b);
            keys.add(new OrbitClipPath.Pose(4 * Math.sin(rad), 2, -4 * Math.cos(rad), b, 19));
        }
        var path = OrbitClipPath.ring(keys, 0, 0, 8);
        assertEquals(200, path.get(0).yaw(), 1e-6);
        assertEquals(155, path.get(1).yaw(), 1e-6, "turning the same way the keys do");
        assertEquals(110, path.get(2).yaw(), 1e-6, "and through the second key");
    }

    @Test
    void unequalKeysEaseBetweenRadiiAndHeights() {
        var keys = List.of(new OrbitClipPath.Pose(0, 10, -10, 0, 18), new OrbitClipPath.Pose(20, 14, 0, 90, 18),
                new OrbitClipPath.Pose(0, 10, 10, 180, 18), new OrbitClipPath.Pose(-20, 14, 0, 270, 18));
        var path = OrbitClipPath.ring(keys, 0, 0, 40);
        for (var p : path) {
            double r = Math.hypot(p.x(), p.z());
            assertTrue(r >= 10 - EPS && r <= 20 + EPS, "between the keys' distances: " + r);
            assertTrue(p.y() >= 10 - EPS && p.y() <= 14 + EPS, "between the keys' heights: " + p.y());
        }
    }

    /** A train's keys sweep ~110° across its side; the clip must never wander to the end-on angle. */
    @Test
    void anArcStaysInsideTheFramersSweep() {
        double axis = 90;                                          // running east
        List<OrbitClipPath.Pose> keys = new ArrayList<>();
        for (double off : new double[] {55, 0, -55}) {             // three-quarter front, side, rear
            double b = axis + 90 + off;
            double rad = Math.toRadians(b);
            keys.add(new OrbitClipPath.Pose(30 * Math.sin(rad), 8, -30 * Math.cos(rad), b, 18));
        }
        var path = OrbitClipPath.arc(keys, 0, 0, 12);
        assertEquals(12, path.size());
        assertEquals(keys.get(0).yaw(), path.get(0).yaw(), 1e-6, "starts on the hero three-quarter");
        assertEquals(keys.get(2).yaw(), path.get(11).yaw(), 1e-6, "ends on the rear three-quarter");
        for (var p : path) {
            double off = ((p.yaw() - (axis + 90)) % 360 + 540) % 360 - 180;
            assertTrue(Math.abs(off) <= 55 + 1e-6, "inside the sweep, never end-on: " + off);
        }
    }

    @Test
    void anArcAcrossNorthTakesTheShortWay() {
        var keys = List.of(new OrbitClipPath.Pose(-5, 0, -9, 330, 10), new OrbitClipPath.Pose(5, 0, -9, 30, 10));
        var path = OrbitClipPath.arc(keys, 0, 0, 5);
        for (var p : path) {
            double y = p.yaw();
            assertTrue(y >= 329 || y <= 31, "60° through north, not 300° the long way: " + y);
        }
    }

    @Test
    void tooFewKeysOrStepsIsTheKeysThemselves() {
        var one = List.of(new OrbitClipPath.Pose(1, 2, 3, 4, 5));
        assertEquals(one, OrbitClipPath.ring(one, 0, 0, 10));
        assertEquals(List.of(), OrbitClipPath.ring(null, 0, 0, 10));
        var keys = buildingKeys(0, 0, 10, 5);
        assertEquals(keys, OrbitClipPath.ring(keys, 0, 0, 1));
    }

    /** ClearView raises one seat for a tree: the clip rises once and comes down once, never below the need. */
    @Test
    void aSingleRaiseIsHeldAndEasedNotBobbed() {
        int n = 20;
        double[] asked = new double[n];
        double[] seated = new double[n];
        for (int i = 0; i < n; i++) {
            asked[i] = 70;
            seated[i] = 70;
        }
        seated[10] = 78;                                           // a tree in the way at one step
        double[] out = OrbitClipPath.smoothFeet(asked, seated, 2, true);
        assertEquals(78, out[10], EPS, "never lower than line of sight needs");
        for (int i = 8; i <= 12; i++) {
            assertTrue(out[i] >= 78 - EPS, "held across the neighbours: " + i + " = " + out[i]);
        }
        assertTrue(out[7] > 70 && out[7] < 78, "eased in: " + out[7]);
        assertTrue(out[13] > 70 && out[13] < 78, "eased out: " + out[13]);
        assertEquals(70, out[3], EPS, "and the rest of the turn is where the framer put it");
        for (int i = 0; i < n; i++) {
            assertTrue(out[i] >= seated[i] - EPS, "no seat below its clear view");
            assertTrue(out[i] >= asked[i] - EPS);
        }
    }

    @Test
    void aClosedRingWrapsItsHoldAndAnOpenArcDoesNot() {
        double[] asked = {0, 0, 0, 0, 0, 0, 0, 0};
        double[] seated = {4, 0, 0, 0, 0, 0, 0, 0};
        double[] ring = OrbitClipPath.smoothFeet(asked, seated, 1, true);
        assertTrue(ring[7] >= 4 - EPS, "the step before the start is its neighbour on a ring");
        double[] arc = OrbitClipPath.smoothFeet(asked, seated, 1, false);
        assertTrue(arc[7] < EPS, "but not on an open sweep: " + arc[7]);
        assertTrue(arc[1] >= 4 - EPS);
    }
}
