package com.moddingfromamod.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Gate A 's placement shot: the building and the thing put at it, together, in frame. */
class PlaceShotTest {

    private static final int[] HALL = {100, 64, 200, 108, 69, 208};

    /** Is (px,py,pz) inside the view of a camera with this pose (a cone of the vertical FOV)? */
    private static boolean inView(PlaceShot.Pose p, double px, double py, double pz) {
        double ex = p.x(), ey = p.y() + PlaceShot.EYE, ez = p.z();
        double yaw = Math.toRadians(p.yaw()), pitch = Math.toRadians(p.pitch());
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double vx = px - ex, vy = py - ey, vz = pz - ez;
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double cos = (vx * fx + vy * fy + vz * fz) / len;
        return cos >= Math.cos(Math.toRadians(PlaceShot.HALF_FOV));
    }

    @Test
    void theWholeHallAndTheThingOnItsRoofAreInTheFrameFromHisSide() {
        PlaceShot.Pose p = PlaceShot.framing(HALL, 104.5, 70, 204.5, 104.5, 240);
        assertTrue(p.z() > 209, "from his (south) side: " + p);
        assertTrue(p.y() + PlaceShot.EYE > 70, "from above the roof, looking down at it: " + p);
        assertTrue(p.pitch() > 0, "looking down");
        for (double[] c : new double[][]{{100, 64, 200}, {109, 64, 209}, {100, 70, 209}, {109, 70, 200}, {104.5, 71, 204.5}}) {
            assertTrue(inView(p, c[0], c[1], c[2]), "corner " + c[0] + "," + c[1] + "," + c[2] + " is out of frame: " + p);
        }
    }

    @Test
    void aThingBesideItIsFramedWithIt() {
        PlaceShot.Pose p = PlaceShot.framing(HALL, 114.5, 64, 204.5, Double.NaN, Double.NaN);
        assertTrue(inView(p, 114.5, 65, 204.5) && inView(p, 100, 64, 200) && inView(p, 109, 70, 209), p.toString());
    }

    @Test
    void aThingOnATallTowerIsFramedWithTheTopOfItNotAllOfIt() {
        int[] tower = {0, 64, 0, 8, 103, 8};                // 40 tall
        PlaceShot.Pose whole = PlaceShot.framing(new int[]{0, 64, 0, 8, 70, 8}, 4.5, 104, 4.5, 4.5, 60);
        PlaceShot.Pose p = PlaceShot.framing(tower, 4.5, 104, 4.5, 4.5, 60);
        double d = Math.hypot(p.x() - 4.5, p.z() - 4.5);
        assertTrue(d < 40, "close enough that the thing on top is not a speck: " + d);
        assertTrue(inView(p, 4.5, 105, 4.5) && inView(p, 0, 95, 0), "the top and its thing are in frame: " + p);
        assertTrue(whole != null);
    }

    @Test
    void anEditedBuildingIsFramedWhole() {
        int[] hall = {0, 64, 0, 12, 94, 8};                 // a hall with a lighthouse on it
        PlaceShot.Pose p = PlaceShot.whole(hall, 6.5, 80);
        assertTrue(inView(p, 6.5, 94, 4.5) && inView(p, 0, 64, 0) && inView(p, 13, 64, 9),
                "its foot and the new part on top are both in frame: " + p);
    }

    @Test
    void lookingPointsTheCameraAtItsSubject() {
        PlaceShot.Pose p = PlaceShot.looking(0, 66, 0, 0, 66, 10);
        assertEquals(0f, p.yaw(), 1e-4, "south is yaw 0");
        assertEquals(0f, p.pitch(), 1e-4);
        assertEquals(66 - PlaceShot.EYE, p.y(), 1e-9, "a pose is the FEET");
        PlaceShot.Pose down = PlaceShot.looking(0, 70, 0, 0, 60, 10);
        assertEquals(45f, down.pitch(), 1e-3, "positive pitch looks down");
    }
}
