package com.moddingfromamod.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where to stand to photograph a creature from four sides. */
class OrbitCameraTest {

    /** How far the camera is from the creature, on the flat. */
    private static double reach(OrbitCamera.Shot s, double cx, double cz) {
        return Math.hypot(s.x() - cx, s.z() - cz);
    }

    /** Where a player at this yaw is looking, as a unit vector on the flat. */
    private static double[] gaze(float yaw) {
        double r = Math.toRadians(yaw);
        return new double[]{-Math.sin(r), Math.cos(r)};
    }

    @Test
    @DisplayName("THE SIGN: the camera looks AT the creature, not away from it")
    void theCameraFacesInward() {
        // Backwards puts the camera on the far side pointing away, which photographs an empty
        // stage very convincingly -- a failure that looks exactly like a successful check.
        for (int stop = 0; stop < OrbitCamera.STOPS; stop++) {
            var s = OrbitCamera.at(100, 64, 200, 3.0, OrbitCamera.bearing(stop));
            double[] look = gaze(s.yaw());
            double toSubjectX = 100 - s.x();
            double toSubjectZ = 200 - s.z();
            double len = Math.hypot(toSubjectX, toSubjectZ);
            double dot = (look[0] * toSubjectX + look[1] * toSubjectZ) / len;
            assertTrue(dot > 0.999,
                    "stop " + stop + " points away from the creature (dot " + dot + ")");
        }
    }

    @Test
    @DisplayName("four distinct stops, evenly spaced around the creature")
    void theFourStopsSurroundIt() {
        double cx = 10;
        double cz = -20;
        var shots = new OrbitCamera.Shot[OrbitCamera.STOPS];
        for (int i = 0; i < OrbitCamera.STOPS; i++) {
            shots[i] = OrbitCamera.at(cx, 0, cz, 2.0, OrbitCamera.bearing(i));
        }
        double r = reach(shots[0], cx, cz);
        for (var s : shots) {
            assertEquals(r, reach(s, cx, cz), 1e-6, "every stop must be the same distance out");
        }
        // Opposite stops are on opposite sides — otherwise "four angles" is four of the same one.
        assertEquals(-(shots[0].x() - cx), shots[2].x() - cx, 1e-6);
        assertEquals(-(shots[0].z() - cz), shots[2].z() - cz, 1e-6);
        assertTrue(Math.abs(shots[1].x() - shots[3].x()) > r, "stops 1 and 3 must be opposite too");
    }

    @Test
    @DisplayName("distance holds a constant share of the frame instead of drifting with size")
    void distanceScalesWithHeight() {
        // The tuned h*2.4+2 it replaces gave 11% of frame for a small creature and 27% for a huge
        // one, so photographs of different creatures were not comparable and most of each was sky.
        // Checked across the UNCLAMPED band. Below about 1.7 blocks the minimum-clearance clamp
        // takes over and a very small creature is deliberately allowed to sit smaller in frame --
        // the alternative is a camera inside its head.
        for (double h : new double[]{2.0, 3.5, 6.0, 12.0}) {
            double share = h / (1.4 * OrbitCamera.distanceFor(h));
            assertEquals(0.55, share, 0.02,
                    h + "-block creature covers " + share + " of the frame, not the derived 55%");
        }
        assertTrue(OrbitCamera.distanceFor(6.0) > OrbitCamera.distanceFor(2.0),
                "a bigger creature needs more room");
    }

    @Test
    @DisplayName("the camera is clamped: never inside the creature, never a dot on the horizon")
    void distanceIsBounded() {
        assertTrue(OrbitCamera.distanceFor(0.1) >= 2.2, "a tiny creature still needs clearance");
        assertTrue(OrbitCamera.distanceFor(1000) <= 26.0, "a huge one must not go over the horizon");
        assertTrue(OrbitCamera.distanceFor(0) > 0, "no geometry at all is not a crash");
    }

    @Test
    @DisplayName("it looks gently DOWN at the creature, not up its chin")
    void theShotLooksSlightlyDown() {
        var s = OrbitCamera.at(0, 64, 0, 4.0, 0);
        assertTrue(s.pitch() > 0, "positive pitch is downward in Minecraft, was " + s.pitch());
        assertTrue(s.pitch() < 35, "a steep overhead shot photographs a hat: " + s.pitch());
        assertTrue(s.eyeY() > 64, "the camera must be above the creature's feet");
        // At most level with the top of its head. The downward angle was deliberately increased
        // (a level shot photographs a wall of torso and the creature merges into the ground behind
        // it), and head height is where that stops being "slightly down" and starts being aerial.
        assertTrue(s.eyeY() <= 64 + 4.0 + 1e-6, "...but not above its head, was " + s.eyeY());
    }

    @Test
    @DisplayName("a creature at the world origin does not divide by zero")
    void degenerateInput() {
        var s = OrbitCamera.at(0, 0, 0, 0, 0);
        assertTrue(Double.isFinite(s.x()) && Double.isFinite(s.z()) && Double.isFinite(s.pitch()));
        assertEquals(0.0, OrbitCamera.bearing(0));
        assertEquals(OrbitCamera.bearing(0), OrbitCamera.bearing(OrbitCamera.STOPS),
                "the orbit wraps");
    }

    @Test
    @DisplayName("STOP 0 LOOKS IT IN THE FACE, whichever way it has turned")
    void theFirstStopIsTheFace() {
        // Fixed world bearings photograph whichever side the creature happens to present, and four
        // stops 90 degrees apart can all miss a face by 45. A reviewer of nine creatures found
        // several with no usable front view. The mob's yaw is readable at runtime, so this is not
        // a guess.
        for (double mobYaw : new double[]{0, 37, 90, 180, -120, 359}) {
            var s = OrbitCamera.at(0, 64, 0, 3.0, OrbitCamera.faceBearing(mobYaw, 0));
            // The creature faces (-sin Y, cos Y); the camera must stand on that side of it.
            double fx = -Math.sin(Math.toRadians(mobYaw));
            double fz = Math.cos(Math.toRadians(mobYaw));
            double len = Math.hypot(s.x(), s.z());
            double dot = (s.x() / len) * fx + (s.z() / len) * fz;
            assertTrue(dot > 0.999,
                    "at mob yaw " + mobYaw + " the camera is not in front of it (dot " + dot + ")");
        }
    }

    @Test
    @DisplayName("...and the other three are its profile, back and other profile")
    void theRemainingStopsGoRoundIt() {
        double mobYaw = 25;
        var front = OrbitCamera.at(0, 0, 0, 2.0, OrbitCamera.faceBearing(mobYaw, 0));
        var back = OrbitCamera.at(0, 0, 0, 2.0, OrbitCamera.faceBearing(mobYaw, 2));
        assertEquals(-front.x(), back.x(), 1e-6, "stop 2 must be directly behind");
        assertEquals(-front.z(), back.z(), 1e-6);
        for (int i = 0; i < OrbitCamera.STOPS; i++) {
            double b = OrbitCamera.faceBearing(mobYaw, i);
            assertTrue(b >= 0 && b < 360, "bearing " + b + " is not a compass bearing");
        }
    }

    @Test
    @DisplayName("the downward angle is real but gentle — not a wall of torso, not a hat")
    void theShotLooksDownEnoughToSeparateItFromTheGround() {
        // Level with the middle photographs a wall of torso and the creature merges with whatever
        // is behind it; too steep and it is a picture of a head from above.
        for (double h : new double[]{1.0, 3.0, 8.0}) {
            var s = OrbitCamera.at(0, 64, 0, h, 0);
            assertTrue(s.pitch() > 4, h + "-block creature: barely tilted (" + s.pitch() + ")");
            assertTrue(s.pitch() < 40, h + "-block creature: too steep (" + s.pitch() + ")");
        }
    }

    @Test
    @DisplayName("filming stands outside what the creature can throw at the lens")
    void aClipStandsBackFurtherThanAPortrait() {
        // A Spikor: about six and a half blocks, whose fire breath reaches about nine.
        double h = 6.5;
        double breathReach = 9.0;

        OrbitCamera.Shot portrait = OrbitCamera.at(0, 64, 0, h, 0.0);
        OrbitCamera.Shot filming = OrbitCamera.at(0, 64, 0, h, 0.0, 2.2);

        double dPortrait = Math.hypot(portrait.x(), portrait.z());
        double dFilming = Math.hypot(filming.x(), filming.z());

        // The portrait distance is deliberately INSIDE the cone, and that is correct for a still:
        // a photograph is over before the breath is. It is recorded here so the two are contrasted
        // rather than one being quietly "fixed".
        assertTrue(dPortrait < breathReach,
                "a portrait frames the creature, not what it emits — if this ever changes, the "
                + "stills have silently been re-framed too");
        assertTrue(dFilming > breathReach,
                "a clip must be shot from outside what the creature throws forwards, or the film "
                + "is a wall of it. Measured on a real Spikor at the portrait distance: the "
                + "creature was visible in ONE frame of twenty. Filming from " + dFilming
                + " against a reach of " + breathReach);
    }

    @Test
    @DisplayName("standing back does not stop the camera looking at the creature")
    void pullingBackStillAims() {
        OrbitCamera.Shot near = OrbitCamera.at(0, 64, 0, 6.5, 90.0);
        OrbitCamera.Shot far = OrbitCamera.at(0, 64, 0, 6.5, 90.0, 2.2);
        assertEquals(near.yaw(), far.yaw(), 0.001,
                "the bearing is the framing decision; the distance is not allowed to change it");
        // Positive pitch is DOWN, which is the sign this class's own javadoc warns is easy to get
        // backwards — and I got it backwards writing this test. Further away means a shallower
        // look down, so the number gets SMALLER.
        assertTrue(far.pitch() < near.pitch() && far.pitch() > 0,
                "from further away the same creature needs a shallower downward tilt, so the pitch "
                + "shrinks toward level without crossing it; near=" + near.pitch()
                + " far=" + far.pitch());
    }

    @Test
    @DisplayName("a creature that turns mid-orbit cannot drag the later stops round with it")
    void lockedBearingsAreFourSidesOfOneFacing() {
        double[] b = OrbitCamera.lockedBearings(37.0);
        assertEquals(OrbitCamera.STOPS, b.length);
        assertEquals(OrbitCamera.faceBearing(37.0, 0), b[0], 1e-9, "stop 0 is still the face");
        for (int i = 1; i < b.length; i++) {
            double step = ((b[i] - b[i - 1]) % 360 + 360) % 360;
            assertEquals(90.0, step, 1e-9, "stop " + i + " is a quarter turn from the last");
        }
    }
}
