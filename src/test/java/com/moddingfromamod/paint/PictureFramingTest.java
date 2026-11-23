package com.moddingfromamod.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.moddingfromamod.builder.BuildFraming;
import org.junit.jupiter.api.Test;

class PictureFramingTest {

    @Test
    void theFirstFrameStandsSquareInFrontLookingAtTheMiddle() {
        // Faces north, so the viewer stands to the north (-z) looking south, and the board grows to
        // that viewer's right, which is WEST (-x).
        List<BuildFraming.Pose> p = PictureFraming.poses(10, 64, 20, "north", 3, 3);
        assertEquals(3, p.size());
        BuildFraming.Pose front = p.get(0);
        assertEquals("front", front.name());
        assertEquals(0f, front.yaw(), 0.01f, "looking south");
        assertEquals(9.5, front.x(), 1e-9, "centred on the middle column (x 10, 9, 8)");
        assertTrue(front.z() < 20, "in front of the picture, not behind the wall");
        assertEquals(64 + 1.5 - 1.62, front.y(), 1e-9, "the eye level with the middle of the board");
    }

    @Test
    void everyFrameLooksAtTheMiddleFromEveryFacing() {
        for (String facing : new String[]{"north", "south", "east", "west"}) {
            double[] c = PictureFraming.centre(0, 70, 0, facing, 4, 2);
            for (BuildFraming.Pose q : PictureFraming.poses(0, 70, 0, facing, 4, 2)) {
                float want = PictureFraming.yawToward(c[0] - q.x(), c[2] - q.z());
                assertEquals(want, q.yaw(), 0.01f, facing + " " + q.name());
            }
        }
    }

    @Test
    void aBiggerBoardIsShotFromFurtherAway() {
        double small = far(PictureFraming.poses(0, 64, 0, "south", 1, 1).get(0));
        double big = far(PictureFraming.poses(0, 64, 0, "south", 16, 16).get(0));
        assertTrue(big > small * 3, "a 16x16 board must fit the frame: " + small + " vs " + big);
    }

    @Test
    void aFacingThatIsNotAWallHasNoPoses() {
        assertTrue(PictureFraming.poses(0, 64, 0, "up", 1, 1).isEmpty());
    }

    private static double far(BuildFraming.Pose p) {
        return Math.abs(p.z());
    }
}
