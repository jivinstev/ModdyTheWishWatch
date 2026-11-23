package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** F: a shot with no subject looks at the wish's spot — never empty sky. */
class WishSpotShotTest {

    private static final double EYE = 1.62;

    /** The direction a pose looks, as a unit vector, in Minecraft's yaw/pitch convention. */
    private static double[] look(WishSpotShot.Pose p) {
        double y = Math.toRadians(p.yaw());
        double x = Math.toRadians(p.pitch());
        return new double[] { -Math.sin(y) * Math.cos(x), -Math.sin(x), Math.cos(y) * Math.cos(x) };
    }

    private static void assertLooksAtTheSpot(WishSpotShot.Pose p) {
        double[] v = look(p);
        double dx = p.aimX() - p.x();
        double dy = p.aimY() - (p.feetY() + EYE);
        double dz = p.aimZ() - p.z();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double cos = (v[0] * dx + v[1] * dy + v[2] * dz) / len;
        assertTrue(cos > 0.999, "the camera looks at its aim point, cos=" + cos + " " + p);
        assertTrue(p.pitch() > -10, "never pointed up into the sky: pitch " + p.pitch());
    }

    @Test
    void fromWhereHeWishedWhenThatIsASensibleDistance() {
        var p = WishSpotShot.atSpot(100, 64, 100, new double[] { 90.5, 65.62, 100.5, -90f }, EYE);
        assertEquals(90.5, p.x(), 1e-9);
        assertEquals(64.0, p.feetY(), 1e-9);
        assertEquals("from where he wished", p.how());
        assertEquals(64 + WishSpotShot.AIM_UP, p.aimY(), 1e-9);
        assertLooksAtTheSpot(p);
    }

    @Test
    void stoodBackAlongHisLookWhenHeWishedAtHisOwnFeet() {
        // yaw 0 looks +z (south): the camera stands NORTH of the spot, looking south at it.
        var p = WishSpotShot.atSpot(0, 70, 0, new double[] { 0.5, 71.62, 0.5, 0f }, EYE);
        assertEquals("stood back from the spot", p.how());
        assertEquals(0.5 - WishSpotShot.STANDOFF, p.z(), 1e-6);
        assertEquals(0.5, p.x(), 1e-6);
        assertTrue(p.feetY() + EYE > 70 + WishSpotShot.AIM_UP, "a little above, looking down");
        assertLooksAtTheSpot(p);
    }

    @Test
    void tooFarAwayAndNoRecordBothStandBack() {
        assertEquals("stood back from the spot",
                WishSpotShot.atSpot(0, 64, 0, new double[] { 200, 65.62, 0, 90f }, EYE).how());
        var none = WishSpotShot.atSpot(5, 64, -5, null, EYE);
        assertEquals("stood back from the spot", none.how());
        assertLooksAtTheSpot(none);
    }

    @Test
    void aTeleportIsShotFromHisOwnFeetAndFacing() {
        var p = WishSpotShot.fromHim(10, 64, 20, 45f, 10f, EYE);
        assertEquals(10, p.x(), 1e-9);
        assertEquals(64, p.feetY(), 1e-9);
        assertEquals(45f, p.yaw());
        assertEquals(10f, p.pitch());
        assertLooksAtTheSpot(p);
    }
}
