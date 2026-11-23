package com.moddingfromamod.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * <b>Gate A for the painted panel's orientation.</b>
 *
 * <p>Upside-down and mirrored are the two ways a textured quad goes wrong, and neither announces
 * itself: a screenshot of a flipped picture is a perfectly good screenshot. So the convention is
 * pinned here rather than left to a human squinting at a photograph — the Gate C run then only has
 * to prove the wiring, not the arithmetic.
 *
 * <p><b>The expected "right" vectors below are derived from the compass, not from
 * {@link PanelGeometry}.</b> Re-deriving them with the same cross product would make this test a
 * tautology that passes whichever way the formula is wrong.
 */
class PanelGeometryTest {

    /** How far the quad's plane sits from the block's centre along its own normal. */
    private static final float PLANE = 0.5f + PanelGeometry.STANDOFF;

    private static final int BL = 0;
    private static final int BR = 1;
    private static final int TR = 2;
    private static final int TL = 3;

    /**
     * Each horizontal facing, with the direction a viewer standing in front of it calls "right".
     *
     * <p>Worked from the compass by hand: to look at a NORTH-facing panel you stand north of it and
     * look south, and facing south your right hand points WEST (-X). To look at an EAST-facing panel
     * you look west, and facing west your right hand points NORTH (-Z).
     */
    private static final int[][] FACINGS = {
        //  nx, ny, nz,   rightX, rightY, rightZ
        {0, 0, -1, -1, 0, 0},   // panel faces north  -> viewer's right is west
        {0, 0, 1, 1, 0, 0},     // panel faces south  -> viewer's right is east
        {1, 0, 0, 0, 0, -1},    // panel faces east   -> viewer's right is north
        {-1, 0, 0, 0, 0, 1},    // panel faces west   -> viewer's right is south
    };

    @Test
    void everyCornerSitsInFrontOfItsOwnBlockFace() {
        // Behind the face the picture is inside the block and invisible; exactly ON it, the depth
        // buffer cannot choose and the surface stipples.
        for (int[] f : FACINGS) {
            PanelGeometry.Quad q = PanelGeometry.quad(f[0], f[1], f[2], 1, 1);
            for (float[] c : q.corners()) {
                float depth = (c[0] - 0.5f) * q.normal()[0]
                        + (c[1] - 0.5f) * q.normal()[1]
                        + (c[2] - 0.5f) * q.normal()[2];
                assertEquals(PLANE, depth, 1e-5,
                        "corner " + str(c) + " is not on the picture plane for normal " + str(q.normal()));
                assertTrue(PanelGeometry.STANDOFF > 0, "the standoff must be positive or it will z-fight");
            }
        }
    }

    @Test
    void uGrowsToTheViewersRightAndVGrowsDownward() {
        // A flipped U is a mirrored picture; a flipped V is an upside-down one. Both render happily.
        for (int[] f : FACINGS) {
            PanelGeometry.Quad q = PanelGeometry.quad(f[0], f[1], f[2], 1, 1);
            float[] expectedRight = {f[3], f[4], f[5]};

            float[] alongU = sub(q.corners()[BR], q.corners()[BL]);
            assertTrue(dot(alongU, expectedRight) > 0.9,
                    "u must grow to the viewer's right " + str(expectedRight)
                            + " but grows " + str(alongU));

            // v = 1 at the bottom corners, v = 0 at the top, so v grows downward.
            assertEquals(1f, q.uvs()[BL][1], 1e-6);
            assertEquals(1f, q.uvs()[BR][1], 1e-6);
            assertEquals(0f, q.uvs()[TL][1], 1e-6);
            assertEquals(0f, q.uvs()[TR][1], 1e-6);
            assertTrue(q.corners()[TL][1] > q.corners()[BL][1],
                    "the v=0 corners must be the HIGHER ones, or the picture hangs upside down");

            assertEquals(0f, q.uvs()[BL][0], 1e-6);
            assertEquals(1f, q.uvs()[BR][0], 1e-6);
        }
    }

    @Test
    void theQuadFacesTheViewerRatherThanTheWall() {
        // Counter-clockwise seen from the front. The renderer draws with no culling so this cannot
        // make the picture vanish -- it decides which way the surface is LIT, which is the half of
        // "correctly oriented and lit" a photograph of a bright day would not notice.
        for (int[] f : FACINGS) {
            PanelGeometry.Quad q = PanelGeometry.quad(f[0], f[1], f[2], 1, 1);
            float[] cross = PanelGeometry.cross(
                    sub(q.corners()[BR], q.corners()[BL]),
                    sub(q.corners()[TL], q.corners()[BL]));
            assertTrue(dot(cross, q.normal()) > 0,
                    "winding is backwards for normal " + str(q.normal()));
        }
    }

    @Test
    void aOneByOnePictureCoversExactlyItsOwnBlockFace() {
        // The panel you place is the picture you get. If the quad were centred on the block centre
        // instead of its face, or sized 2 instead of 1, this is what would say so.
        for (int[] f : FACINGS) {
            PanelGeometry.Quad q = PanelGeometry.quad(f[0], f[1], f[2], 1, 1);
            for (float[] c : q.corners()) {
                for (int axis = 0; axis < 3; axis++) {
                    boolean alongNormal = Math.abs(q.normal()[axis]) > 0.5f;
                    if (alongNormal) {
                        continue;
                    }
                    assertTrue(c[axis] >= -1e-5 && c[axis] <= 1 + 1e-5,
                            "corner " + str(c) + " leaves its own block on axis " + axis);
                }
            }
            assertEquals(1.0, edge(q, BL, BR), 1e-5, "width should be one block");
            assertEquals(1.0, edge(q, BL, TL), 1e-5, "height should be one block");
        }
    }

    @Test
    void aBiggerPictureGrowsRightAndUpFromThePanelYouPlaced() {
        // Growing DOWN would bury the bottom of a mural in the ground, and growing left would put
        // it somewhere other than where the block was placed. Both are silent: the picture is there,
        // just not where anyone asked for it.
        for (int[] f : FACINGS) {
            PanelGeometry.Quad one = PanelGeometry.quad(f[0], f[1], f[2], 1, 1);
            PanelGeometry.Quad big = PanelGeometry.quad(f[0], f[1], f[2], 4, 3);

            assertEquals(4.0, edge(big, BL, BR), 1e-5);
            assertEquals(3.0, edge(big, BL, TL), 1e-5);
            // The bottom-left corner does not move: it is anchored on the block you placed.
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(one.corners()[BL][axis], big.corners()[BL][axis], 1e-5,
                        "the anchor corner moved when the picture grew");
            }
            assertTrue(big.corners()[TL][1] > one.corners()[TL][1], "it should grow upward");
            float[] grew = sub(big.corners()[BR], one.corners()[BR]);
            assertTrue(dot(grew, new float[]{f[3], f[4], f[5]}) > 0.9,
                    "it should grow to the viewer's right");
        }
    }

    @Test
    void spansAreClampedRatherThanTrusted() {
        // These come from an agent acting on a child's description, so 0 and 9999 are both a
        // sentence away. A zero-width quad draws nothing and reports nothing.
        assertEquals(1, PanelGeometry.clampSpan(0));
        assertEquals(1, PanelGeometry.clampSpan(-7));
        assertEquals(PanelGeometry.MAX_SPAN, PanelGeometry.clampSpan(9999));
        assertEquals(4, PanelGeometry.clampSpan(4));

        PanelGeometry.Quad q = PanelGeometry.quad(0, 0, -1, 0, -3);
        assertEquals(1.0, edge(q, BL, BR), 1e-5);
        assertEquals(1.0, edge(q, BL, TL), 1e-5);
    }

    @Test
    void aFloorOrCeilingPanelStillProducesARealQuad() {
        // Up and down are the case where "which way is up" has no answer, and the cross product
        // collapses to zero -- every corner NaN, nothing drawn, nothing logged.
        for (int[] n : new int[][]{{0, 1, 0}, {0, -1, 0}}) {
            PanelGeometry.Quad q = PanelGeometry.quad(n[0], n[1], n[2], 1, 1);
            for (float[] c : q.corners()) {
                for (float v : c) {
                    assertTrue(Float.isFinite(v), "NaN corner for normal " + str(n));
                }
            }
            assertEquals(1.0, edge(q, BL, BR), 1e-5);
            assertEquals(1.0, edge(q, BL, TL), 1e-5);
        }
    }

    @Test
    void nonsenseNormalsDegradeToARealFacingRatherThanNothing() {
        // A blockstate read back from damaged NBT, or a diagonal somebody thought would work.
        for (int[] n : new int[][]{{0, 0, 0}, {1, 1, 0}, {1, 1, 1}}) {
            PanelGeometry.Quad q = PanelGeometry.quad(n[0], n[1], n[2], 1, 1);
            float len = (float) Math.sqrt(dot(q.normal(), q.normal()));
            assertEquals(1.0, len, 1e-5, "the normal must stay a unit direction");
            assertEquals(1.0, edge(q, BL, BR), 1e-5);
        }
    }

    @Test
    void theSameFacingAlwaysProducesTheSameQuad() {
        PanelGeometry.Quad a = PanelGeometry.quad(0, 0, -1, 2, 2);
        PanelGeometry.Quad b = PanelGeometry.quad(0, 0, -1, 2, 2);
        for (int i = 0; i < 4; i++) {
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(a.corners()[i][axis], b.corners()[i][axis], 0f);
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static double edge(PanelGeometry.Quad q, int from, int to) {
        float[] d = sub(q.corners()[to], q.corners()[from]);
        return Math.sqrt(dot(d, d));
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static String str(float[] v) {
        return "(" + v[0] + "," + v[1] + "," + v[2] + ")";
    }

    private static String str(int[] v) {
        return "(" + v[0] + "," + v[1] + "," + v[2] + ")";
    }
}
