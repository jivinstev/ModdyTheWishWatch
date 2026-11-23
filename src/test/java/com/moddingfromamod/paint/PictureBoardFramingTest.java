package com.moddingfromamod.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.moddingfromamod.builder.BuildFraming;
import com.moddingfromamod.wish.reveal.RevealFrame;
import org.junit.jupiter.api.Test;

/**
 * <b>: a picture is framed by its BOARD, never by the child's eye.</b>
 *
 * <p>The archived BlockyBob grant hung an 8 × 16 board and the finish photograph came back as
 * everything below the chest: the shutter fired from where the child stood, pitch 0. This table
 * asks the one question that photograph failed, for every size class and every facing: from the
 * front pose, is EVERY corner of the board inside the lens — and is the camera square in front of
 * it, eye level with its middle, looking straight at it?
 */
class PictureBoardFramingTest {

    /** wide × tall: square, wide, tall 1:2, very tall (the BlockyBob board), a single block. */
    private static final int[][] SIZES = {{4, 4}, {8, 4}, {4, 8}, {8, 16}, {1, 1}, {16, 2}};
    private static final String[] FACINGS = {"north", "south", "east", "west"};

    @Test
    void theWholeBoardIsInTheFrontFrameForEverySizeAndFacing() {
        for (int[] size : SIZES) {
            for (String facing : FACINGS) {
                String what = size[0] + "x" + size[1] + " facing " + facing;
                RevealFrame board = RevealFrame.forPicture(facing, 100, 64, -40, size[0], size[1]);
                List<BuildFraming.Pose> poses = PictureFraming.poses(board);
                assertEquals(3, poses.size(), what);
                BuildFraming.Pose front = poses.get(0);
                assertEquals("front", front.name(), what);

                double eyeX = front.x(), eyeY = front.y() + PictureFraming.eyeHeight(), eyeZ = front.z();
                // SQUARE-ON: eye level with the board's middle, not tilted, on its centre line.
                assertEquals(board.cy(), eyeY, 1e-9, what + ": eye level with the middle of the board");
                assertEquals(0f, front.pitch(), 1e-6, what + ": looks straight, not up");
                double ox = eyeX - board.cx(), oz = eyeZ - board.cz();
                double out = ox * board.fx() + oz * board.fz();
                int[] r = board.right();
                double aside = ox * r[0] + oz * r[1];
                assertTrue(out > 0, what + ": in FRONT of the picture, not behind its wall");
                assertEquals(0, aside, 1e-9, what + ": on the board's centre line");
                assertEquals(PictureFraming.yawToward(-ox, -oz), front.yaw(), 0.01f, what + ": looks at the middle");

                // EVERY CORNER IN THE LENS. The camera looks along -facing; a corner u to the right
                // and v up of the middle sits at atan(u/out) across and atan(v/out) up.
                double halfW = size[0] / 2.0, halfH = size[1] / 2.0;
                double across = Math.atan(halfW / out), up = Math.atan(halfH / out);
                assertTrue(up < PictureFraming.halfVerticalFov(),
                        what + ": top and bottom edges inside the frame (" + Math.toDegrees(up) + "°)");
                assertTrue(across < PictureFraming.halfHorizontalFov(),
                        what + ": left and right edges inside the frame (" + Math.toDegrees(across) + "°)");
                // …and the board is not a speck: its binding dimension fills at least half the lens.
                double fill = Math.max(Math.tan(up) / Math.tan(PictureFraming.halfVerticalFov()),
                        Math.tan(across) / Math.tan(PictureFraming.halfHorizontalFov()));
                assertTrue(fill > 0.5 || out == 3.0, what + ": fills " + fill + " of the frame");
            }
        }
    }

    @Test
    void theBlockyBobBoardIsShotFromItsMiddleNotFromAChildsEye() {
        // The archived record: facing north, bottom-left cell -5,-60,5, 8 wide, 15 tall as hung.
        RevealFrame board = RevealFrame.forPicture("north", -5, -60, 5, 8, 15);
        BuildFraming.Pose front = PictureFraming.poses(board).get(0);
        double eye = front.y() + PictureFraming.eyeHeight();
        assertEquals(-60 + 7.5, eye, 1e-9, "the eye is half way up the board");
        assertTrue(eye - (-60 + 1.62) > 5.5, "a child's eye was ~6 blocks lower, and the head was cut off");
    }

    @Test
    void aPanelBoardMovedOutIsTheSameFrameShiftedForward() {
        RevealFrame hung = RevealFrame.forPicture("east", 0, 70, 0, 2, 4);
        RevealFrame panel = hung.outBy(0.5);
        BuildFraming.Pose a = PictureFraming.poses(hung).get(0);
        BuildFraming.Pose b = PictureFraming.poses(panel).get(0);
        assertEquals(a.x() + 0.5, b.x(), 1e-9);
        assertEquals(a.z(), b.z(), 1e-9);
        assertEquals(hung.boardWide(), 2, 1e-9);
        assertEquals(hung.boardTall(), 4, 1e-9);
    }

    @Test
    void noBoardNoPoses() {
        assertTrue(PictureFraming.poses((RevealFrame) null).isEmpty());
    }
}
