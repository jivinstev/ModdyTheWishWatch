package com.moddingfromamod.redstone;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a control stands when the child named the door's own place (a lever "by the castle door"): beside the
 * path, never in it, a few steps out, on the side he named. A pressure plate is the exception: it goes IN the path,
 * where he will step. Each answer is a list of ground blocks to try in order; the planner takes the first that fits.
 *
 * <p>Pure, like the rest of the planner.
 */
public final class ControlSpots {

    /** A spot this close to the doorway (in blocks) means "at the door". */
    public static final int NEAR = 5;

    private ControlSpots() {
    }

    /** Which side of the door's wall {@code p} is on: negative outside, positive inside, 0 in the wall. */
    public static int side(Frame f, RsPos p) {
        RsPos o = f.origin();
        return (p.x() - o.x()) * f.in().dx() + (p.z() - o.z()) * f.in().dz();
    }

    /** True when {@code p} is within {@link #NEAR} of the doorway's middle, horizontally. */
    public static boolean nearDoor(Frame f, int width, RsPos p) {
        RsPos o = f.origin();
        double mx = o.x() + f.along().dx() * (width - 1) / 2.0, mz = o.z() + f.along().dz() * (width - 1) / 2.0;
        return Math.hypot(p.x() - mx, p.z() - mz) <= NEAR;
    }

    /** Ground blocks (the block the control stands ON) to try, for a control at the door on {@code side}. */
    public static List<RsPos> atDoor(Frame f, int width, String kind, int side) {
        int s = side < 0 ? -1 : 1;
        List<RsPos> out = new ArrayList<>();
        if (kind.equals("pressure_plate")) {
            for (int w = 2; w <= 3; w++) {
                for (int u = 0; u < width; u++) {
                    out.add(f.at(u, -1, s * w));
                }
            }
            return out;
        }
        // beside the path: left of the doorway first, then right, two to four steps out
        for (int w = 3; w <= 4; w++) {
            out.add(f.at(-2, -1, s * w));
            out.add(f.at(width + 1, -1, s * w));
        }
        out.add(f.at(-3, -1, s * 2));
        out.add(f.at(width + 2, -1, s * 2));
        return out;
    }

    /** Ground blocks to try for a control at a spot of its own: the spot, then the ring round it. */
    public static List<RsPos> around(RsPos ground) {
        List<RsPos> out = new ArrayList<>();
        out.add(ground);
        int[][] ring = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int[] d : ring) {
            out.add(ground.plus(d[0], 0, d[1]));
        }
        return out;
    }
}
