package com.moddingfromamod.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.event.shape.FallShape;
import com.moddingfromamod.event.shape.StampShape;
import org.junit.jupiter.api.Test;

/** impact_stamp and falling_body (E2): the rules that make a crater a crater and a meteor visible. */
class StampAndFallTest {

    @Test
    void aCraterIsAboutFiveToOneOnItsDiameterFromBothSides() {
        for (int r = 4; r <= 64; r += 5) {
            StampShape c = new StampShape(StampShape.Form.CRATER, r);
            double ratio = (2.0 * r) / c.depth();
            assertTrue(ratio >= 4.0 && ratio <= 7.0,
                    "radius " + r + ": diameter/depth " + ratio + " — a dinner plate is a target painted"
                            + " on the grass, a shaft is a mineshaft");
        }
    }

    @Test
    void aCraterHasAFlatFloorARimOutsideTheHoleAndAHotMiddle() {
        StampShape c = new StampShape(StampShape.Form.CRATER, 20);
        assertEquals(-c.depth(), c.deltaAt(0));
        assertEquals(-c.depth(), c.deltaAt(20 * StampShape.FLOOR_FRACTION - 0.1), "flat floor");
        assertTrue(c.deltaAt(19.5) > c.deltaAt(10), "the walls come up toward the lip");
        assertTrue(c.deltaAt(21) > 0, "the rim is raised OUTSIDE the hole");
        assertEquals(0, c.deltaAt(c.outerRadius() + 1));
        assertEquals(StampShape.Role.CORE, c.roleAt(2));
        assertEquals(StampShape.Role.RIM, c.roleAt(22));
    }

    @Test
    void aDigNeverGoesThroughTheBottomOfTheWorld() {
        assertEquals(8, StampShape.clampDig(8, 30));
        assertEquals(2, StampShape.clampDig(8, 3), "shallower, with a floor, rather than a hole into the void");
        assertEquals(0, StampShape.clampDig(8, 1));
    }

    @Test
    void aConeHasALipAndABowlAndSlopesToNothing() {
        StampShape v = new StampShape(StampShape.Form.CONE, 22);
        int lip = v.deltaAt(22 * StampShape.MOUTH_FRACTION);
        assertTrue(lip > v.deltaAt(0), "a bowl in the top, not a point");
        assertTrue(lip > v.deltaAt(15), "and it slopes down outside");
        assertEquals(0, v.deltaAt(23));
        assertTrue(v.deltaAt(0) >= 1);
    }

    @Test
    void aMoundIsASmoothDome() {
        StampShape m = new StampShape(StampShape.Form.MOUND, 12);
        assertEquals(m.height(), m.deltaAt(0));
        int prev = Integer.MAX_VALUE;
        for (int d = 0; d <= 12; d++) {
            assertTrue(m.deltaAt(d) <= prev);
            prev = m.deltaAt(d);
        }
        assertEquals(0, m.deltaAt(12));
    }

    @Test
    void aCraterOpensFromTheMiddleAndAConeBuildsUp() {
        StampShape c = new StampShape(StampShape.Form.CRATER, 20);
        assertTrue(c.revealed(1, 0, 0.1) && !c.revealed(20, 0, 0.1));
        StampShape v = new StampShape(StampShape.Form.CONE, 20);
        assertTrue(v.revealed(10, 1, 0.1) && !v.revealed(10, v.height(), 0.1));
        assertTrue(v.revealed(10, v.height(), 1.0));
    }

    @Test
    void aFallingBodyAlwaysComesDownWhereHeCanSeeIt() {
        FallShape.Point target = new FallShape.Point(0, 64, 0);
        for (int range = FallShape.MIN_AIM; range <= FallShape.MAX_AIM; range += 8) {
            for (int deg = 0; deg < 360; deg += 30) {
                double a = Math.toRadians(deg);
                FallShape.Point viewer = new FallShape.Point(Math.cos(a) * range, 70, Math.sin(a) * range);
                FallShape.Point start = FallShape.start(viewer, target);
                assertTrue(FallShape.inSight(viewer, start, target),
                        "range " + range + " bearing " + deg + ": part of the fall is out of sight");
                double towards = (start.x() - target.x()) * (viewer.x() - target.x())
                        + (start.z() - target.z()) * (viewer.z() - target.z());
                assertTrue(towards < 0, "it comes in from beyond the target, toward him, never over his head");
                assertTrue(FallShape.ticks(start, target) >= FallShape.MIN_FALL_TICKS,
                        "range " + range + ": a fall over in under 1.25 s is gone before he has found it");
                double pitch = Math.toDegrees(Math.atan2(start.y() - viewer.y(),
                        Math.hypot(start.x() - viewer.x(), start.z() - viewer.z())));
                assertTrue(pitch < FallShape.MAX_COMFORTABLE_PITCH, "he never has to crane his neck straight up: " + pitch);
            }
        }
    }

    @Test
    void aCratersRimStandsUpWhereHeCanSeeIt() {
        for (int r : new int[]{10, 16, 24}) {
            StampShape c = new StampShape(StampShape.Form.CRATER, r);
            assertTrue(c.height() >= 2, "radius " + r + ": a one-block rim reads as a line on the grass");
            assertTrue(c.deltaAt(r + 0.5) >= 2, "radius " + r + ": the rim is raised just outside the hole");
        }
    }

    @Test
    void aimIsKeptInRange() {
        assertEquals(FallShape.MIN_AIM, FallShape.aimRange(5), 1e-9);
        assertEquals(FallShape.MAX_AIM, FallShape.aimRange(500), 1e-9);
    }

    @Test
    void theBodyIsAHollowShell() {
        assertTrue(FallShape.shell(3, 0, 0, 3));
        assertTrue(!FallShape.shell(0, 0, 0, 3), "hollow: a solid ball is twice the writes for the same outside");
    }
}
