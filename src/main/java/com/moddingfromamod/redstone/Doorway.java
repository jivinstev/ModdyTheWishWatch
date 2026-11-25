package com.moddingfromamod.redstone;

/**
 * Where a door goes: from a spot outside a wall and the direction into the building, finds the wall line and the
 * doorway in it. An existing opening 1 or 2 wide and at least 2 tall is used as it is (its own door, if it has one,
 * is replaced); with none near, a new 2-wide opening is cut where the spot looks at the wall.
 *
 * <p>Pure: it reads only a {@link WorldView}, so every case is a unit test.
 */
public final class Doorway {

    /** How far along the ray a wall is looked for. */
    public static final int REACH = 14;
    /** How far either side of the ray, along the wall, an existing opening is looked for. */
    public static final int SIDEWAYS = 4;
    /** How far above or below the spot the doorway's floor may be. */
    public static final int STEP = 2;

    /**
     * The answer: the frame of the door (origin = its bottom-left opening cell, +w into the building), its width,
     * whether the opening already existed, and the wall's own block. {@code why} names the refusal when there is none.
     */
    public record Found(Frame frame, int width, boolean existing, String wallBlock, String why) {
        public boolean ok() {
            return why == null;
        }

        static Found no(String why) {
            return new Found(null, 0, false, null, why);
        }
    }

    private Doorway() {
    }

    /** A cell a person walks through: air, a door, a plant, a torch; never a solid block or a fluid. */
    static boolean passable(WorldView w, RsPos p) {
        WorldView.Kind k = w.kind(p);
        if (k == WorldView.Kind.AIR) {
            return true;
        }
        if (k != WorldView.Kind.OTHER) {
            return false;
        }
        String id = w.blockId(p);
        return id.endsWith("_door") || !w.conductor(p);
    }

    /** A cell that is part of a wall: a solid block (built or natural). */
    static boolean wall(WorldView w, RsPos p) {
        WorldView.Kind k = w.kind(p);
        return (k == WorldView.Kind.SOLID || k == WorldView.Kind.NATURAL || k == WorldView.Kind.LOCKED)
                && w.conductor(p);
    }

    /**
     * Finds the doorway seen from {@code start} (feet height, outside) looking {@code in}.
     */
    public static Found find(WorldView w, RsPos start, RsDir in, int wantWidth) {
        RsDir along = in.clockwise();
        for (int k = 0; k <= REACH; k++) {
            RsPos c = start.plus(in.dx() * k, 0, in.dz() * k);
            for (int dy = 0; Math.abs(dy) <= STEP; dy = dy <= 0 ? -dy + 1 : -dy) {
                RsPos base = c.up(dy);
                // a wall is met from open air: the cell before it on the ray is walkable at both heights
                RsPos before = base.plus(-in.dx(), 0, -in.dz());
                if (k == 0 || !passable(w, before) || !passable(w, before.up(1)) || !isWallLine(w, base, along)) {
                    continue;
                }
                return inWall(w, base, in, along, wantWidth);
            }
        }
        return Found.no("there is no wall within " + REACH + " blocks of that spot, looking " + in.id()
                + "; a piston door goes in a wall");
    }

    /**
     * A wall line at {@code base}: at BOTH heights, at least two thirds of the cells either side are wall (the ground
     * alone is one height, never a wall line), and the ground under it is solid.
     */
    static boolean isWallLine(WorldView w, RsPos base, RsDir along) {
        for (int dy = 0; dy < 2; dy++) {
            int walls = 0, total = 0;
            for (int u = -SIDEWAYS; u <= SIDEWAYS; u++) {
                total++;
                if (wall(w, base.plus(along.dx() * u, dy, along.dz() * u))) {
                    walls++;
                }
            }
            if (walls * 3 < total * 2) {
                return false;
            }
        }
        return wall(w, base.up(-1));
    }

    static Found inWall(WorldView w, RsPos base, RsDir in, RsDir along, int wantWidth) {
        // an opening: a run of columns whose two cells are passable, a solid floor under, wall on both ends
        int bestU = Integer.MIN_VALUE, bestW = 0, bestDy = 0;
        for (int dy = -1; dy <= 1; dy++) {
            RsPos row = base.up(dy);
            int u = -SIDEWAYS;
            while (u <= SIDEWAYS) {
                if (!column(w, row, along, u)) {
                    u++;
                    continue;
                }
                int s = u;
                while (u <= SIDEWAYS + 2 && column(w, row, along, u)) {
                    u++;
                }
                int width = u - s;
                boolean closed = wall(w, at(row, along, s - 1)) && wall(w, at(row, along, u));
                if (closed && (bestU == Integer.MIN_VALUE || Math.abs(s) < Math.abs(bestU))) {
                    bestU = s;
                    bestW = width;
                    bestDy = dy;
                }
            }
        }
        String wallBlock = w.blockId(at(base, along, bestU == Integer.MIN_VALUE ? 0 : bestU - 1));
        if (bestU != Integer.MIN_VALUE) {
            if (bestW > 2) {
                return Found.no("the doorway there is " + bestW + " wide; a piston door fills 1 or 2");
            }
            RsPos origin = at(base.up(bestDy), along, bestU);
            return new Found(new Frame(origin, along, in), bestW, true, wallBlock, null);
        }
        // none: cut a new one where the ray met the wall (wantWidth wide), if the wall is whole there
        int width = wantWidth == 1 ? 1 : 2;
        for (int u = 0; u < width; u++) {
            for (int dy = 0; dy < 2; dy++) {
                if (!wall(w, base.plus(along.dx() * u, dy, along.dz() * u))) {
                    return Found.no("the wall there is not whole, and there is no doorway near to use");
                }
            }
        }
        wallBlock = w.blockId(base);
        return new Found(new Frame(base, along, in), width, false, wallBlock, null);
    }

    static RsPos at(RsPos row, RsDir along, int u) {
        return row.plus(along.dx() * u, 0, along.dz() * u);
    }

    static boolean column(WorldView w, RsPos row, RsDir along, int u) {
        RsPos c = at(row, along, u);
        return passable(w, c) && passable(w, c.up(1)) && wall(w, c.up(-1));
    }
}
