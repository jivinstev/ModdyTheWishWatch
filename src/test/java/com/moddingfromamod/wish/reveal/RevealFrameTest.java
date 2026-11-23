package com.moddingfromamod.wish.reveal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * The picture reveal's frame lies in the PICTURE's plane, around the picture, for every facing
 *. The bug it pins: the frame was always drawn in the X-Y plane, so a picture facing east or
 * west got a frame edge-on to it, 90° off.
 */
class RevealFrameTest {

    private static final double EPS = 1e-9;

    /** Every cell of a {@code wide × tall} picture hung as hangBoard and MapArtPanels hang it. */
    private static double[][] cellCentres(Direction facing, int x, int y, int z, int wide, int tall) {
        Direction right = facing.getCounterClockWise();
        double[][] out = new double[wide * tall][];
        int i = 0;
        for (int col = 0; col < wide; col++) {
            for (int row = 0; row < tall; row++) {
                out[i++] = new double[]{x + 0.5 + right.getStepX() * col, y + 0.5 + row,
                        z + 0.5 + right.getStepZ() * col};
            }
        }
        return out;
    }

    @ParameterizedTest
    @ValueSource(strings = {"north", "south", "east", "west"})
    void theFrameLiesInThePicturesPlaneAndGoesAroundIt(String name) {
        Direction facing = Direction.byName(name);
        int x = 10, y = 64, z = -7, wide = 8, tall = 6;   // the live case's 8 × 6
        RevealFrame f = RevealFrame.forPicture(name, x, y, z, wide, tall);
        assertNotNull(f);
        assertEquals(name, f.facing());
        // right is Minecraft's own counter-clockwise neighbour, the hangBoard convention
        Direction right = facing.getCounterClockWise();
        assertArrayEquals(new int[]{right.getStepX(), right.getStepZ()}, f.right(), name);

        double[][] cells = cellCentres(facing, x, y, z, wide, tall);
        // the plane: every bead is exactly as far along the facing as the picture's cells are
        double plane = cells[0][0] * facing.getStepX() + cells[0][2] * facing.getStepZ();
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minV = minU, maxV = maxU;
        for (int k = 0; k < f.beads(); k++) {
            double[] p = f.edge(k / (double) f.beads());
            assertEquals(plane, p[0] * facing.getStepX() + p[2] * facing.getStepZ(), EPS,
                    name + ": a bead left the picture's plane (the 90° bug)");
            double u = p[0] * right.getStepX() + p[2] * right.getStepZ();
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, p[1]);
            maxV = Math.max(maxV, p[1]);
        }
        // around it: every cell's whole block face is inside the frame, with a margin
        for (double[] c : cells) {
            double u = c[0] * right.getStepX() + c[2] * right.getStepZ();
            assertTrue(u - 0.5 > minU && u + 0.5 < maxU, name + ": a column is outside the frame");
            assertTrue(c[1] - 0.5 > minV && c[1] + 0.5 < maxV, name + ": a row is outside the frame");
        }
        // and not much bigger than it: the frame is the picture's size, not a fixed 7 × 5
        assertEquals(wide + 1.0, maxU - minU, 0.01, name + ": frame width");
        assertEquals(tall + 1.0, maxV - minV, 0.01, name + ": frame height");
    }

    @Test
    void anEastFacingFrameRunsAlongZNotX() {
        RevealFrame f = RevealFrame.forPicture("east", 0, 0, 0, 4, 3);
        double[] a = f.edge(0), b = f.edge(0.1);
        assertEquals(a[0], b[0], EPS, "an east-facing frame's bottom edge moved in X");
        assertTrue(Math.abs(a[2] - b[2]) > 0.5, "an east-facing frame's bottom edge did not run along Z");
    }

    @ParameterizedTest
    @ValueSource(strings = {"north", "south", "east", "west"})
    void withNoRecordItStillFacesTheChild(String name) {
        Direction toChild = Direction.byName(name);
        // the child stands 6 blocks off in that direction, a little to one side
        RevealFrame f = RevealFrame.facingViewer(0.5, 70, 0.5, toChild.getStepX() * 6 + 1, toChild.getStepZ() * 6 + 1);
        assertEquals(name, f.facing());
        assertEquals(RevealFrame.FALLBACK_W / 2, f.halfW(), EPS);
    }

    @Test
    void anUnknownFacingIsNoFrameSoTheCallerFallsBack() {
        assertNull(RevealFrame.forPicture("", 0, 0, 0, 2, 2));
        assertNull(RevealFrame.forPicture("up", 0, 0, 0, 2, 2));
        assertNull(RevealFrame.forPicture("north", 0, 0, 0, 0, 2));
    }

    @Test
    void aBigPictureGetsMoreBeads() {
        assertTrue(RevealFrame.forPicture("north", 0, 0, 0, 12, 8).beads()
                > RevealFrame.forPicture("north", 0, 0, 0, 2, 2).beads());
    }

    /** The first film: a board on the ground had its bottom row of beads buried in the grass. */
    @Test
    void aPictureStandingOnTheGroundKeepsItsBottomEdgeAboveIt() {
        RevealFrame f = RevealFrame.forPicture("east", 0, 64, 0, 4, 3).sitOn(64);
        double lowest = Double.MAX_VALUE, highest = -Double.MAX_VALUE;
        for (int k = 0; k < f.beads(); k++) {
            double y = f.edge(k / (double) f.beads())[1];
            lowest = Math.min(lowest, y);
            highest = Math.max(highest, y);
        }
        assertTrue(lowest - RevealFrame.BEAD / 2 >= 64 - 1e-9, "a bead is in the ground: " + lowest);
        assertEquals(64 + 3 + 0.5, highest, 1e-9, "the top must not move");
        RevealFrame high = RevealFrame.forPicture("east", 0, 70, 0, 4, 3);
        assertEquals(high, high.sitOn(64), "a frame already clear of the floor is unchanged");
    }
}
