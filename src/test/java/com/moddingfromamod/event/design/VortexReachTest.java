package com.moddingfromamod.event.design;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.event.EventScale;
import com.moddingfromamod.event.shape.Vortex;
import com.moddingfromamod.event.shape.VortexPath;
import com.moddingfromamod.runtime.EventFraming;
import com.moddingfromamod.runtime.EventReach;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * A vortex's camera is planned from the SAME path the motion walks (EVENTS_PLAN, "Changing an event? Change its
 * camera too"). For every shape, path and size: where its foot is at every moment of the clip is inside the reach, and
 * the frame fitted to that reach from his side holds it.
 */
class VortexReachTest {

    /** He stands at (0.5, 65, 0.5) looking north (yaw 180); the vortex is aimed ahead of him. */
    private static final float YAW = 180f;

    private static EventDesign design(String json) {
        EventDesign.Parsed p = EventDesign.parse(json);
        assertTrue(p.ok(), p.errors().toString());
        return p.design();
    }

    private static void whole(String what, EventReach reach, EventFraming.Shot s) {
        for (double x : new double[] {reach.x0(), reach.x1()}) {
            for (double y : new double[] {reach.y0(), reach.y1()}) {
                for (double z : new double[] {reach.z0(), reach.z1()}) {
                    assertTrue(EventFraming.inFrame(s, x, y, z), what + ": corner (" + x + ", " + y + ", " + z
                            + ") is out of the frame " + s);
                }
            }
        }
    }

    @Test
    void everyPathOfEveryShapeHasItsFootInsideItsReachForTheWholeClip() {
        for (Vortex.Shape shape : Vortex.Shape.values()) {
            for (Vortex.Path path : new Vortex.Path[] {Vortex.Path.STAY, Vortex.Path.WANDER, Vortex.Path.LINE,
                    Vortex.Path.CIRCLE}) {
                for (EventScale s : EventScale.values()) {
                    String what = shape.id() + "/" + path.id() + "/" + s;
                    EventDesign d = design("{\"name\":\"v\",\"motion\":\"vortex\",\"size\":\"" + s.name().toLowerCase()
                            + "\",\"shape\":\"" + shape.id() + "\",\"path\":\"" + path.id() + "\"}");
                    int ahead = Vortex.aim(shape, s);
                    BlockPos centre = new BlockPos(0, 64, -ahead);
                    EventReach reach = EventBuild.vortexReach(d, centre, YAW, null, null);
                    int life = Vortex.life(s, 0);
                    VortexPath p = EventBuild.vortexPaths(d, centre, YAW, null).get(0);
                    for (int t = 0; t <= Math.min(life, EventReach.CLIP_TICKS); t += 5) {
                        VortexPath.At at = p.at(t, life);
                        EventReach now = reach.frontAt(t);
                        assertTrue(at.x() >= now.x0() - 1e-6 && at.x() <= now.x1() + 1e-6
                                        && at.z() >= now.z0() - 1e-6 && at.z() <= now.z1() + 1e-6,
                                what + ": its foot at tick " + t + " (" + at + ") is out of the reach " + now);
                    }
                    assertTrue(reach.y1() - reach.y0() >= Vortex.tall(shape, s), what + ": as tall as it stands");
                    if (path == Vortex.Path.LINE) {
                        assertTrue(reach.tracked(), what + ": a swath is a tracking shot");
                        for (int t = 0; t <= EventReach.CLIP_TICKS; t += 10) {
                            EventReach now = reach.frontAt(t);
                            whole(what + " tick " + t, now, EventFraming.frame(now, 0.5, 0.5));
                        }
                    } else {
                        whole(what, reach, EventFraming.frame(reach, 0.5, 0.5));
                    }
                }
            }
        }
    }

    @Test
    void aSwathCrossesHisViewAndTheCameraSlidesWithIt() {
        EventDesign d = design("{\"name\":\"cat4\",\"motion\":\"vortex\",\"size\":\"gigantic\"}");
        BlockPos centre = new BlockPos(0, 64, -36);
        EventReach reach = EventBuild.vortexReach(d, centre, YAW, null, null);
        assertTrue(reach.tracked(), "a gigantic tornado's default path is a line, filmed as a front");
        // Yaw 180 looks north (-z), so his right is east (+x): the swath runs west to east across his view.
        double startX = reach.frontAt(0).centreX();
        double laterX = reach.frontAt(EventReach.CLIP_TICKS).centreX();
        assertTrue(laterX - startX > 20, "it travels left to right across his view: " + startX + " → " + laterX);
        assertTrue(Math.abs(reach.frontAt(0).centreZ() - (-35.5)) < 1e-6, "never toward him");
    }

    @Test
    void aWhirlpoolsReachGoesDownIntoTheWater() {
        EventDesign d = design("{\"name\":\"w\",\"motion\":\"vortex\",\"size\":\"big\",\"shape\":\"drain\"}");
        EventReach reach = EventBuild.vortexReach(d, new BlockPos(0, 62, -26), YAW, null, null);
        assertTrue(reach.y0() < 63 - 2, "the camera sees down the drain: " + reach.y0());
    }

    @Test
    void circlingABuildingRingsOutsideItsWalls() {
        EventDesign d = design("{\"name\":\"c\",\"motion\":\"vortex\",\"size\":\"medium\",\"path\":\"circle\"}");
        double[] castle = {-10, 64, -40, 10, 80, -20};   // 20 × 20, so 14 from its middle to a corner
        VortexPath p = EventBuild.vortexPaths(d, new BlockPos(0, 64, -30), YAW, castle).get(0);
        VortexPath.At mid = new VortexPath.At(0.5, -29.5);
        for (int t = 0; t < 300; t += 20) {
            assertTrue(p.at(t, 300).distanceTo(mid) > 14.2 + Vortex.size(EventScale.MEDIUM).top() * 0.5,
                    "it goes round the castle, never through it");
        }
    }
}
