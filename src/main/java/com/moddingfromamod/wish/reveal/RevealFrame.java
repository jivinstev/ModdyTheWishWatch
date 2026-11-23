package com.moddingfromamod.wish.reveal;

/**
 * <b>Where a picture's reveal frame is drawn, and which way it faces</b>. Pure — no
 * Minecraft — so every facing is unit-tested.
 *
 * <p>The first cut drew the frame as a fixed 7 × 5 rectangle in the X-Y plane, centred on the
 * pillar. A picture hung by {@code PictureCommand.hangBoard} faces the child, so whenever he stood
 * east or west of the spot the frame was 90° off the picture it revealed. Now the
 * frame is traced in the picture's own plane, from its placement record: {@code right} is the
 * facing turned counter-clockwise (the {@code hangBoard} convention, so column 0 is the picture's
 * left edge as he looks at it) and {@code up} is up.
 *
 * <p>Facings are the lower-case names Minecraft's {@code Direction.getName()} writes into a
 * {@code PaintRecord}: north is −Z, south +Z, west −X, east +X.
 *
 * @param cx    the centre of the frame, in the plane of the picture's cell centres (half a block in
 *              front of the board, so a bead never sinks into the wall)
 * @param fx    the facing's X (the way the picture looks)
 * @param fz    the facing's Z
 * @param halfW half the frame's width, along {@code right}
 * @param halfH half the frame's height
 */
public record RevealFrame(double cx, double cy, double cz, int fx, int fz, double halfW, double halfH) {

    /** The frame when there is no picture record to read: the first cut's 7 × 5. */
    public static final double FALLBACK_W = 7, FALLBACK_H = 5;

    /** Beads sit half a block outside the picture's edge, so the frame goes AROUND it. */
    static final double MARGIN = 0.5;

    /** {x, z} of a horizontal facing name, or null for anything else. */
    static int[] vector(String facing) {
        if (facing == null) {
            return null;
        }
        return switch (facing.toLowerCase(java.util.Locale.ROOT)) {
            case "north" -> new int[]{0, -1};
            case "south" -> new int[]{0, 1};
            case "west" -> new int[]{-1, 0};
            case "east" -> new int[]{1, 0};
            default -> null;
        };
    }

    /**
     * The frame around a hung picture. {@code x,y,z} is its bottom-left cell as the record stores it
     * (the cell the frames hang in, in front of the board), {@code wide × tall} its size in blocks.
     * Null when the facing is not a horizontal one or the size is empty — the caller falls back.
     */
    public static RevealFrame forPicture(String facing, int x, int y, int z, int wide, int tall) {
        int[] f = vector(facing);
        if (f == null || wide <= 0 || tall <= 0) {
            return null;
        }
        int rx = f[1], rz = -f[0];                     // counter-clockwise: north → west → south → east
        double across = (wide - 1) / 2.0;
        return new RevealFrame(x + 0.5 + rx * across, y + 0.5 + (tall - 1) / 2.0, z + 0.5 + rz * across,
                f[0], f[1], wide / 2.0 + MARGIN, tall / 2.0 + MARGIN);
    }

    /**
     * The frame when no picture record is found: the first cut's 7 × 5 at {@code (cx,cy,cz)}, but
     * turned to face the child, who stands {@code (dx,dz)} away from it — the way {@code hangBoard}
     * turns the board itself.
     */
    public static RevealFrame facingViewer(double cx, double cy, double cz, double dx, double dz) {
        int[] f = vector(facingToward(dx, dz));
        return new RevealFrame(cx, cy, cz, f[0], f[1], FALLBACK_W / 2, FALLBACK_H / 2);
    }

    /** The horizontal facing nearest to {@code (dx,dz)}; south when there is no direction at all. */
    public static String facingToward(double dx, double dz) {
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? "east" : "west";
        }
        return dz < 0 ? "north" : "south";
    }

    /**
     * The same frame moved {@code d} blocks out along its facing — for a picture on our own panel
     * block, whose face is the front of a whole block rather than a map on the wall behind the cell.
     */
    public RevealFrame outBy(double d) {
        return new RevealFrame(cx + fx * d, cy, cz + fz * d, fx, fz, halfW, halfH);
    }

    /**
     * The same frame with its bottom edge lifted clear of a floor at {@code floorY} — a board hung
     * by {@code hangBoard} stands ON the ground, and the first film showed the bottom row of beads
     * buried in the grass. The bottom beads then sit just above the floor, in front of the
     * picture's lowest row; the top and sides are unchanged. A frame already clear is returned as is.
     */
    public RevealFrame sitOn(double floorY) {
        double bottom = cy - halfH, want = floorY + BEAD / 2;
        if (bottom >= want) {
            return this;
        }
        double top = cy + halfH;
        return new RevealFrame(cx, (top + want) / 2, cz, fx, fz, halfW, (top - want) / 2);
    }

    /** A bead's size, which the floor rule keeps above ground. */
    static final double BEAD = 0.7;

    /**
     * The picture's own width in blocks — the frame less its margin. Only meaningful on a frame from
     * {@link #forPicture} (optionally {@link #outBy}), never on one {@link #sitOn} has trimmed: the
     * camera frames the BOARD, so it is handed the board, not the beads.
     */
    public double boardWide() {
        return 2 * (halfW - MARGIN);
    }

    /** The picture's own height in blocks; see {@link #boardWide}. */
    public double boardTall() {
        return 2 * (halfH - MARGIN);
    }

    /** The way to the frame's right as he faces it: {x, z}. */
    public int[] right() {
        return new int[]{fz, -fx};
    }

    /** The facing's name. */
    public String facing() {
        return fx == 1 ? "east" : fx == -1 ? "west" : fz == -1 ? "north" : "south";
    }

    /** How many beads trace it: about one a block of edge, so a big picture is not a dotted line. */
    public int beads() {
        double per = 4 * (halfW + halfH);
        return (int) Math.max(20, Math.min(64, Math.round(per)));
    }

    /**
     * The world point a fraction {@code f} of the way round the frame, starting at the bottom-left
     * corner and going along the bottom first.
     */
    public double[] edge(double f) {
        double w = 2 * halfW, h = 2 * halfH, d = (f - Math.floor(f)) * 2 * (w + h);
        double u, v;
        if (d < w) {
            u = -halfW + d;
            v = -halfH;
        } else if ((d -= w) < h) {
            u = halfW;
            v = -halfH + d;
        } else if ((d -= h) < w) {
            u = halfW - d;
            v = halfH;
        } else {
            d -= w;
            u = -halfW;
            v = halfH - d;
        }
        int[] r = right();
        return new double[]{cx + r[0] * u, cy + v, cz + r[1] * u};
    }
}
