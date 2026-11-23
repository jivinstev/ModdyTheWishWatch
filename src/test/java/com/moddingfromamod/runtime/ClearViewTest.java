package com.moddingfromamod.runtime;

import com.moddingfromamod.runtime.ClearView.Seat;
import com.moddingfromamod.runtime.ClearView.Sight;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The camera has to be able to SEE it</b>.
 *
 * <p>Every case here is a photograph that came back wrong somewhere in this project's history, so
 * the tests are named after the picture rather than after the branch of code.
 */
class ClearViewTest {

    private static final double EYE = 1.62;

    /** Nothing in the way, nothing solid — the world a framing test imagines. */
    private static final Sight OPEN = new Sight() {
        public boolean solid(double x, double y, double z) {
            return false;
        }

        public boolean clear(double ex, double ey, double ez, double ax, double ay, double az) {
            return true;
        }
    };

    /**
     * <b>A real occluder: a wall standing at {@code wallX}, {@code wallTop} blocks high.</b>
     *
     * <p>⚠ THE FIRST VERSION OF THIS FIXTURE WAS NOT A WORLD, and it made correct code look
     * broken. It modelled "clear" as a property of the CAMERA'S HEIGHT alone — {@code ey >= 90} —
     * which lets a segment be clear while a sub-segment of it is not. That is geometrically
     * impossible, and it is the exact invariant the slide-back-down-the-ray step relies on
     * ("sliding toward the aim keeps the camera on a sub-segment of it, so the subject cannot
     * become occluded again"). The test duly failed, on a seat that was correct.
     *
     * <p>So this asks the real question: where does the segment cross the wall's column, and is it
     * over the top there? Raising the camera helps, sliding along the ray cannot hurt, and both
     * are true for the same reason they are true in a world.
     */
    private static Sight wall(double wallX, double wallTop) {
        return new Sight() {
            public boolean solid(double x, double y, double z) {
                return Math.abs(x - wallX) < 0.5 && y < wallTop;
            }

            public boolean clear(double ex, double ey, double ez,
                                 double ax, double ay, double az) {
                if ((ex - wallX) * (ax - wallX) > 0) {
                    return true;               // the wall is not between them at all
                }
                double t = (ex - wallX) / (ex - ax);          // 0 at the eye, 1 at the aim
                return ey + t * (ay - ey) > wallTop;          // the segment clears the top
            }
        };
    }

    @Test
    @DisplayName("a clear seat is left exactly where the framing put it")
    void aClearSeatIsNotMoved() {
        Seat s = ClearView.find(OPEN, 40, 64, 0, 90f, 0, 66, 0, EYE);
        assertEquals(40, s.x(), 1e-9);
        assertEquals(64, s.feetY(), 1e-9);
        assertEquals(0, s.z(), 1e-9);
        assertEquals(0.0, s.rose(), 1e-9, "an unobstructed camera must not climb at all");
        assertFalse(s.slid(), "nothing to slide back from");
        assertFalse(s.blind(), "it could see");
    }

    @Test
    @DisplayName("the yaw the framing chose is never overridden")
    void theBearingIsTheFramingsToChoose() {
        // The whole of TrainFraming is an argument about WHICH BEARING to shoot a train from. A
        // camera helper that quietly turned the camera would be overruling that, and on a
        // 100-block subject the bearing IS the photograph.
        for (float yaw : new float[] {0f, 90f, 145f, -35f}) {
            assertEquals(yaw, ClearView.find(wall(20, 90), 40, 64, 0, yaw, 0, 66, 0, EYE).yaw(),
                    "the seat turned the camera away from the bearing it was framed on");
        }
    }

    @Test
    @DisplayName("a camera behind a canopy climbs until it can see over it")
    void itRisesUntilItCanSee() {
        Seat s = ClearView.find(wall(20, 90), 40, 64, 0, 90f, 0, 66, 0, EYE);
        assertFalse(s.blind(), "it should have found a clear line by rising");
        assertTrue(s.rose() > 0, "it claims not to have risen");
        // ⚠ ASSERT THE VIEW, NOT THE HEIGHT. The first cut demanded the eye finish above the
        // obstacle, which is wrong twice over: the slide deliberately brings it back down, and
        // "high enough" was never the requirement — SEEING IT is. Ask the same question the code
        // asked.
        assertTrue(wall(20, 90).clear(s.x(), s.feetY() + EYE, s.z(), 0, 66, 0),
                "the seat it settled on cannot see the subject");
    }

    @Test
    @DisplayName("having risen, it comes back DOWN THE RAY rather than shooting from orbit")
    void itSlidesBackInAlongTheClearRay() {
        // ⚠ THE BUG THIS EXISTS FOR. A rise that is not undone turns a portrait into a map view:
        // measured once at 96 blocks up, pitch 89, the subject a speck, and the run's grader
        // scored it the best of its batch because no grader opens a photograph.
        double aimX = 0;
        double aimY = 66;
        double aimZ = 0;
        Seat s = ClearView.find(wall(20, 90), 40, 64, 0, 90f, aimX, aimY, aimZ, EYE);
        assertTrue(s.slid(), "it rose and never came back — the framing distance is gone");

        double asked = Math.hypot(40 - aimX, 0 - aimZ);
        asked = Math.hypot(asked, aimY - (64 + EYE));
        double got = Math.hypot(Math.hypot(s.x() - aimX, s.z() - aimZ),
                (s.feetY() + EYE) - aimY);
        assertEquals(asked, got, 0.5,
                "the SLANT range is what decides how big the subject is in frame, and it is "
                        + asked + " but came back " + got);
    }

    @Test
    @DisplayName("a horizontal pull-in would NOT have been enough — the proof, kept as a test")
    void slidingIsNotAHorizontalPullIn() {
        // This shipped once, believing it worked. Shrinking `flat` while leaving the camera at the
        // risen height holds the horizontal projection of a distance whose vertical leg has
        // already blown past it. So: the slide must bring the camera DOWN as well as IN.
        Seat s = ClearView.find(wall(20, 90), 40, 64, 0, 90f, 0, 66, 0, EYE);
        assertTrue(s.slid(), "precondition: it should have slid");
        double risenFeet = 64 + s.rose();
        assertTrue(s.feetY() < risenFeet - 1.0,
                "the camera stayed at the height it climbed to (" + s.feetY() + " vs " + risenFeet
                        + "), so only the horizontal leg was corrected");
        assertTrue(Math.hypot(s.x(), s.z()) < 40.0,
                "it came down without coming IN, so it is not on the ray it proved clear");
    }

    @Test
    @DisplayName("it looks DOWN at a subject below it, by an angle that matches the geometry")
    void theRecomputedPitchPointsAtTheSubject() {
        // Re-aiming is step 3 for a reason: two of four frames once came back as empty sky
        // because the camera was lifted and kept the pitch computed for its old height.
        Seat s = ClearView.find(wall(20, 100), 40, 64, 0, 90f, 0, 66, 0, EYE);
        double flat = Math.hypot(s.x(), s.z());
        double want = -Math.toDegrees(Math.atan2(66 - (s.feetY() + EYE), Math.max(0.001, flat)));
        assertEquals(want, s.pitch(), 0.01, "the camera is not looking at the subject");
        assertTrue(s.pitch() > 0, "a camera above its subject must be tilted DOWN");
    }

    @Test
    @DisplayName("a camera inside a block is lifted out of it, and only out of it")
    void itLiftsOutOfSolidButNoFurther() {
        // The rule this replaced raised the camera to its own column's terrain height, which on a
        // cliff edge or under a tree put it on the ridge or in the canopy — metres above the shot
        // that was asked for, photographing empty sky.
        Sight buried = new Sight() {
            public boolean solid(double x, double y, double z) {
                return y < 66.0;                     // two blocks of rock over the camera
            }

            public boolean clear(double ex, double ey, double ez,
                                 double ax, double ay, double az) {
                return true;                          // once out, the view is fine
            }
        };
        Seat s = ClearView.find(buried, 40, 64, 0, 90f, 0, 66, 0, EYE);
        assertTrue(s.feetY() >= 65, "still buried at " + s.feetY());
        assertTrue(s.feetY() <= 66, "climbed to " + s.feetY() + ", far past merely clearing rock");
    }

    @Test
    @DisplayName("when nothing works it says so, instead of reporting a good photograph")
    void aBlindSeatIsReportedRatherThanHidden() {
        // docs/CAMERA.md: "Say why you did nothing." A seat that never found a line still gets
        // photographed -- a bad picture beats no picture -- but the caller has to be able to tell
        // the difference, or a run of unusable frames reads exactly like a clean one.
        Sight sealed = new Sight() {
            public boolean solid(double x, double y, double z) {
                return false;
            }

            public boolean clear(double ex, double ey, double ez,
                                 double ax, double ay, double az) {
                return false;                          // underground: no height helps
            }
        };
        Seat s = ClearView.find(sealed, 40, 64, 0, 90f, 0, 66, 0, EYE);
        assertTrue(s.blind(), "a camera that never saw the subject reported success");
        // It DOES still slide back to the framing distance — see the note in find(). A blind seat
        // that is framed beats a blind seat 96 blocks up, and this is what the creature orbit has
        // always done, so sharing the code must not quietly change it.
        assertTrue(s.slid(), "a blind seat should still be brought back to the asked range");
    }

    @Test
    @DisplayName("the climb is bounded, so a sealed room cannot loop or fly to the build limit")
    void theClimbIsBounded() {
        Sight sealed = new Sight() {
            public boolean solid(double x, double y, double z) {
                return false;
            }

            public boolean clear(double ex, double ey, double ez,
                                 double ax, double ay, double az) {
                return false;
            }
        };
        Seat s = ClearView.find(sealed, 40, 64, 0, 90f, 0, 66, 0, EYE);
        assertEquals(ClearView.MAX_RISE * ClearView.RISE_STEP, s.rose(), 1e-9,
                "the rise is not bounded by MAX_RISE * RISE_STEP");
    }
}
