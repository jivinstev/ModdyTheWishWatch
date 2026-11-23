package com.moddingfromamod.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * an event is framed on where its action GOES over the clip, not on where it started. The three
 * that the first cut missed are the cases: a shower dropped from above the frame, a meteor came from
 * outside it, and a wave travelled out of it.
 */
class EventReachTest {

    private static boolean inFrame(EventFraming.Shot s, double px, double py, double pz) {
        return EventFraming.inFrame(s, px, py, pz);
    }

    private static void wholeBoxInFrame(String what, EventReach reach, EventFraming.Shot s) {
        for (double x : new double[] {reach.x0(), reach.x1()}) {
            for (double y : new double[] {reach.y0(), reach.y1()}) {
                for (double z : new double[] {reach.z0(), reach.z1()}) {
                    assertTrue(inFrame(s, x, y, z), what + ": corner (" + x + ", " + y + ", " + z + ") is out of the frame "
                            + s);
                }
            }
        }
    }

    @Test
    void aShowerIsFramedUpToTheHeightItFallsFrom() {
        // chicken rain, medium: a disc of 8 with drops from 18 above the ground
        var reach = EventReach.sky(100.5, 65, 100.5, 8, 18);
        var s = EventFraming.frame(reach, 100.5, 130);
        wholeBoxInFrame("chicken rain", reach, s);
        // the first cut aimed at a quarter of the radius above the ground: the drop top was out of shot
        var old = EventFraming.frame(100.5, 65, 100.5, 8 * 0.5, 100.5, 130);
        assertTrue(!inFrame(old, 100.5, 65 + 18, 100.5), "the old framing really did lose the drop height");
        assertTrue(s.pitch() < EventFraming.ELEVATION_DEG, "a tall event is seen from beside, not from above");
    }

    @Test
    void anEventAimedAtABuildingKeepsTheBuildingInTheShot() {
        // a meteor on a hall's roof at 104,69,204 — the hall 100..109 x 64..70 x 200..209.
        var reach = EventReach.fall(90, 90, 230, 104.5, 70, 204.5, 4).including(100, 64, 200, 109, 70, 209);
        var shot = EventFraming.frame(reach, 104.5, 240);
        wholeBoxInFrame("meteor on the hall", reach, shot);
        assertTrue(inFrame(shot, 100, 64, 200) && inFrame(shot, 109, 64, 209), "the hall's foot is in the shot");
        var tracked = EventReach.front(0.5, 64, -40, 0, 1, 1.0, 98, 9, 8).including(-5, 64, -5, 5, 70, 5);
        assertTrue(tracked.tracked() && tracked.travels(), "a wave is still tracked once it holds a building");
    }

    @Test
    void aMeteorsWholeApproachIsInTheFrame() {
        // a viewer at (0, 65, 0) aiming 26 out; the body starts up and behind the target
        var target = new double[] {0.5, 65, 26.5};
        var s0 = com.moddingfromamod.event.shape.FallShape.start(
                new com.moddingfromamod.event.shape.FallShape.Point(0, 66.6, 0),
                new com.moddingfromamod.event.shape.FallShape.Point(target[0], target[1], target[2]));
        var reach = EventReach.fall(s0.x(), s0.y(), s0.z(), target[0], target[1], target[2], 7);
        var shot = EventFraming.frame(reach, 0, 0);
        wholeBoxInFrame("meteor", reach, shot);
        assertTrue(inFrame(shot, s0.x(), s0.y(), s0.z()), "where it comes from is in the shot");
    }

    @Test
    void aStillAreaIsFramedLikeBeforeFromHisSide() {
        var reach = EventReach.around(0, 64, 0, 10, 4);
        var s = EventFraming.frame(reach, 0, 100);
        wholeBoxInFrame("flood", reach, s);
        assertTrue(s.z() > 0, "from his (south) side");
        assertEquals(180, s.yaw(), 1e-6);
        assertNull(reach.travel());
    }

    @Test
    void aFrontIsSideOnAndOnTheViewersSide() {
        // a wave born 40 blocks north of him, rolling south toward him (heading +z)
        var reach = EventReach.front(0.5, 64, -40, 0, 1, 1.0, 98, 9, 8);
        assertTrue(reach.tracked() && reach.travels());
        var s = EventFraming.frame(reach.frontAt(10), 6, 0);   // he stands a little to the east
        double ox = s.x() - reach.frontAt(10).centreX(), oz = s.z() - reach.frontAt(10).centreZ();
        // side-on turned FRONT_QUARTER_DEG toward its heading (+z): its face shows, not its profile
        assertEquals(Math.sin(Math.toRadians(EventFraming.FRONT_QUARTER_DEG)), oz / Math.hypot(ox, oz), 1e-6,
                "a quarter turn from side-on, toward where it heads");
        assertTrue(ox > 0, "on the side he is on (east)");
    }

    @Test
    void aTrackedFrontKeepsTheFrontInEveryFrameAndSlidesWithIt() {
        var reach = EventReach.front(0.5, 64, -40, 0, 1, 1.0, 98, 9, 8);
        double lastZ = Double.NEGATIVE_INFINITY;
        double yaw = Double.NaN;
        for (int i = 0; i < 48; i++) {
            double tick = i * 2.5;
            var at = reach.frontAt(tick);
            var s = EventFraming.frame(at, 6, 0);
            wholeBoxInFrame("front at tick " + tick, at, s);
            assertTrue(s.z() >= lastZ - 1e-9, "the camera moves the way the front does");
            lastZ = s.z();
            if (!Double.isNaN(yaw)) {
                assertEquals(yaw, s.yaw(), 1e-9, "a parallel slide: the bearing never swings");
            }
            yaw = s.yaw();
        }
        // and the front really is where the motion puts it: SPEED blocks a tick along the heading
        assertEquals(-40 + 60, reach.frontAt(60).centreZ(), 1e-9);
        assertEquals(-40 + 98, reach.frontAt(500).centreZ(), 1e-9, "it stops where its life ends");
    }

    @Test
    void theWholeTravelIsTheReachOfTheClip() {
        var reach = EventReach.front(0, 64, 0, 1, 0, 1.0, 300, 5, 6);
        assertEquals(0, reach.x0(), 1e-9);
        assertEquals(EventReach.CLIP_TICKS, reach.x1(), 1e-9, "the box ends where the front is at the clip's end");
        assertEquals(-5, reach.z0(), 1e-9);
        assertEquals(5, reach.z1(), 1e-9);
    }

    /** a transform is framed whole-middle from his side at every size, trees and all. */
    @Test
    void aTransformIsFramedFromHisSideAtEverySize() {
        for (var scale : com.moddingfromamod.event.EventScale.values()) {
            for (boolean upward : new boolean[] {false, true}) {
                var reach = EventReach.transform(0, 64, 0, scale.radius, upward);
                var s = EventFraming.frame(reach, 0, 100);
                wholeBoxInFrame("transform " + scale + (upward ? " (trees)" : " (ground)"), reach, s);
                assertTrue(s.z() > 0, "from his (south) side");
                assertTrue(reach.y0() < 64, "the ground it changes is in the box");
                assertNull(reach.travel(), "nothing travels");
            }
        }
    }

    /** a crowd is framed whole, from his side, at every count and in both shapes. */
    @Test
    void aCrowdIsFramedWholeFromHisSide() {
        for (int n : new int[] {1, 8, 64, 100}) {
            for (boolean ring : new boolean[] {false, true}) {
                var reach = EventReach.crowd(0, 64, 0, com.moddingfromamod.event.shape.CrowdShape.radius(n, ring));
                var s = EventFraming.frame(reach, 0, 100);
                wholeBoxInFrame("crowd of " + n + (ring ? " (ring)" : ""), reach, s);
                assertTrue(s.z() > 0, "from his (south) side");
            }
        }
    }
}
