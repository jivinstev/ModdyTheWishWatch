package com.moddingfromamod.builder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PROTOTYPE support (docs/BUILD_CIRCULATION_PLAN.md): the walker and the composition over a shape LIST of any
 * length. A stair run regenerated one tread per block of rise can pass {@link BuildPlan#MAX_SHAPES}; the design's
 * answer is one declared part, and until that exists the measurement must not be cut off at 64 shapes.
 */
public final class WalkProbe {

    private WalkProbe() {
    }

    public static long key(int x, int y, int z) {
        return ComposedPlan.key(x, y, z);
    }

    public static int kx(long k) {
        return ComposedPlan.kx(k);
    }

    public static int kz(long k) {
        return ComposedPlan.kz(k);
    }

    public static int ky(long k) {
        return ComposedPlan.ky(k);
    }

    /** The shapes composed in order, air kept as null (as {@code WalkThrough.composeKeepingAir}). */
    public static Map<Long, String> compose(List<BuildShape> shapes) {
        Map<Long, String> out = new HashMap<>();
        for (BuildShape s : shapes) {
            String block = BuildSupport.isAir(s.block()) ? null : s.block();
            s.forEachBlock((x, y, z) -> out.put(ComposedPlan.key(x, y, z), block));
        }
        return out;
    }

    /** The blocks {@code plan} ends up as, composed as the builder places it (air absent), keyed as {@link #key}. */
    public static Map<Long, String> cells(BuildPlan plan) {
        Map<Long, String> out = new HashMap<>();
        ComposedPlan.of(plan).forEach((x, y, z, block) -> out.put(ComposedPlan.key(x, y, z), block));
        return out;
    }

    /** {@link BuildDoors}' rewrite laid over a composed map. */
    public static void doors(Map<Long, String> cells, BuildDoors.Fixed fixed) {
        for (Map.Entry<Long, String> e : fixed.cells().entrySet()) {
            cells.put(e.getKey(), BuildSupport.isAir(e.getValue()) ? null : e.getValue());
        }
    }

    public static WalkThrough.Report walk(Map<Long, String> cells) {
        WalkThrough w = WalkThrough.over(cells, WalkThrough.BODY);
        return w == null ? new WalkThrough.Report(false, List.of(), null, 0) : w.report();
    }

    public static String dump(Map<Long, String> cells, int y0, int y1) {
        WalkThrough w = WalkThrough.over(cells, WalkThrough.BODY);
        StringBuilder out = new StringBuilder();
        for (WalkThrough.Room r : w.report().rooms()) {
            out.append(r.reached() ? "reached " : "NOT     ").append(r.describe()).append('\n');
        }
        for (int y = y0; y <= y1; y++) {
            out.append("y=").append(y).append('\n').append(w.layer(y)).append('\n');
        }
        return out.toString();
    }

    /** Does the walk from outside stand in one of the four cells beside this block, at its height? */
    public static boolean standsBeside(Map<Long, String> cells, int x, int y, int z) {
        WalkThrough w = WalkThrough.over(new java.util.HashMap<>(cells), WalkThrough.BODY);
        String[] lines = w.layer(y).split("\n");
        // "y=0  x -18..19" then one row per z: "<cells>  z=<z>"
        int minX = Integer.parseInt(lines[0].replaceAll(".*x (-?\\d+)\\.\\..*", "$1"));
        for (String line : lines) {
            int at = line.lastIndexOf("  z=");
            if (at < 0) {
                continue;
            }
            int rz = Integer.parseInt(line.substring(at + 4).trim());
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int i = x + d[0] - minX;
                if (rz == z + d[1] && i >= 0 && i < at && line.charAt(i) == 'o') {
                    return true;
                }
            }
        }
        return false;
    }
}
