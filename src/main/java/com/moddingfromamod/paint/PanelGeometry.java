package com.moddingfromamod.paint;

/**
 * <b>Where the picture hangs, and which way up it is.</b>
 *
 * <p>A painted panel draws one quad across its own block face. That sounds like four coordinates and
 * no thinking, and it is exactly the sort of code that ships wrong: a flipped V renders the picture
 * upside down, a flipped U renders it mirrored, and <i>both</i> look completely plausible in a
 * screenshot of a symmetrical test image. This repo has shipped render code that was correct, wired,
 * mutation-tested and produced a picture of a forest — so the orientation decision lives here, in a
 * class with no Minecraft in it, where a unit test can hold it still.
 *
 * <p><b>The convention.</b> {@code (u,v) = (0,0)} is the picture's <b>top-left as a viewer standing
 * in front of the panel sees it</b>, {@code u} grows to that viewer's right, {@code v} grows
 * downward. That is the same convention as every image file, so "the top-left pixel of the PNG is
 * the top-left of the picture" needs no mental arithmetic anywhere else.
 *
 * <p>Pure — no Minecraft — so {@code PanelGeometryTest} can assert the whole thing without a game.
 */
public final class PanelGeometry {

    private PanelGeometry() {
    }

    /**
     * How far in front of the block face the picture floats, in <b>blocks</b>.
     *
     * <p>Drawn exactly on the face, the depth buffer cannot choose between the picture and the block
     * behind it, and the surface stipples and swims as the camera moves — the same defect
     * {@code FightingFaces} exists to fix for creature cubes, one unit system along. (That one works
     * in model units, where its {@code EPSILON} of 0.05 is 1/320 of a block; this is a little more
     * generous because a train car is routinely looked at from fifty blocks away, where depth
     * precision is coarser.)
     *
     * <p>Small enough to be invisible: 1/200 of a block is a third of a texel on a vanilla texture.
     */
    public static final float STANDOFF = 0.005f;

    /** A picture bigger than this is a mural, and murals are made of several panels. */
    public static final int MAX_SPAN = 16;

    /**
     * The four corners of the picture, in the block's own render space (the block occupies the unit
     * cube {@code [0,1]}), together with the normal and each corner's texture coordinate.
     *
     * <p>Corners are in the order <b>bottom-left, bottom-right, top-right, top-left</b> as seen from
     * the front — counter-clockwise, so the quad faces the viewer — which is the order Minecraft's
     * own quad emitters use.
     */
    public record Quad(float[][] corners, float[][] uvs, float[] normal) {
    }

    /**
     * Build the picture's quad.
     *
     * @param nx      the face's outward normal, x. One of -1, 0, 1.
     * @param ny      the face's outward normal, y.
     * @param nz      the face's outward normal, z.
     * @param widthBlocks  how many blocks wide the picture is; the panel is its LEFT-hand end as a
     *                     viewer in front of it sees it, so a wider picture grows to that viewer's
     *                     right.
     * @param heightBlocks how many blocks tall; the panel is the BOTTOM, so it grows upward. Both
     *                     directions are chosen so the panel block itself is the one you place and
     *                     the picture never grows through the ground.
     */
    public static Quad quad(int nx, int ny, int nz, int widthBlocks, int heightBlocks) {
        float[] n = normal(nx, ny, nz);
        int w = clampSpan(widthBlocks);
        int h = clampSpan(heightBlocks);

        // The viewer stands in front and looks AT the panel, so their forward is -n.
        float[] up = referenceUp(n);
        // right = forward x up, the same handedness Minecraft uses (facing north, east is on your
        // right). Computed from the viewer's forward so "the viewer's right" means what it says.
        float[] right = cross(new float[]{-n[0], -n[1], -n[2]}, up);

        // The face's own centre, nudged out of the block so it cannot fight with it.
        float[] centre = {
            0.5f + n[0] * (0.5f + STANDOFF),
            0.5f + n[1] * (0.5f + STANDOFF),
            0.5f + n[2] * (0.5f + STANDOFF),
        };

        // This panel block is the picture's bottom-LEFT block, so the picture extends (w-1) blocks
        // to the viewer's right and (h-1) blocks up from this one's own square.
        float[] bottomLeft = add(add(centre,
                scale(right, -0.5f)), scale(up, -0.5f));
        float[] bottomRight = add(bottomLeft, scale(right, w));
        float[] topLeft = add(bottomLeft, scale(up, h));
        float[] topRight = add(bottomRight, scale(up, h));

        return new Quad(
                new float[][]{bottomLeft, bottomRight, topRight, topLeft},
                // v grows downward, so the BOTTOM corners carry v = 1.
                new float[][]{{0, 1}, {1, 1}, {1, 0}, {0, 0}},
                n);
    }

    /** Clamp a span into something a panel can actually be. */
    public static int clampSpan(int blocks) {
        return Math.max(1, Math.min(MAX_SPAN, blocks));
    }

    /**
     * The "up" to measure the picture against.
     *
     * <p>For a wall panel that is simply world up. A floor or ceiling panel has no such luck — its
     * normal IS up — so it borrows the same fallback vanilla uses for flat items and lies with its
     * top toward north. Without this the cross product below is zero and every corner comes back
     * {@code NaN}, which draws nothing at all and reports nothing at all.
     */
    static float[] referenceUp(float[] n) {
        if (Math.abs(n[1]) > 0.5f) {
            return new float[]{0, 0, -1};
        }
        return new float[]{0, 1, 0};
    }

    /** A unit normal, defaulting to north for anything that isn't a direction. */
    static float[] normal(int nx, int ny, int nz) {
        int nonZero = (nx != 0 ? 1 : 0) + (ny != 0 ? 1 : 0) + (nz != 0 ? 1 : 0);
        if (nonZero != 1) {
            return new float[]{0, 0, -1};
        }
        return new float[]{Math.signum(nx), Math.signum(ny), Math.signum(nz)};
    }

    static float[] cross(float[] a, float[] b) {
        return new float[]{
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        };
    }

    static float[] add(float[] a, float[] b) {
        return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static float[] scale(float[] a, float k) {
        return new float[]{a[0] * k, a[1] * k, a[2] * k};
    }
}
