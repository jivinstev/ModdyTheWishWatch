package com.moddingfromamod.redstone;

import java.util.ArrayList;
import java.util.List;

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
            if (k > 0 && !passable(w, start.plus(in.dx() * (k - 1), 0, in.dz() * (k - 1)))
                    && !passable(w, start.plus(in.dx() * (k - 1), 1, in.dz() * (k - 1)))) {
                break;   // the ray went into something solid: never through a wall into the room beyond
            }
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
        // none: cut a new one where the ray met the wall (wantWidth wide), at the height the room inside is
        // walkable (the row the ray met may be the floor's own edge), if the wall is whole there
        int width = wantWidth == 1 ? 1 : 2;
        for (int dy : new int[] {0, 1, -1, 2}) {
            RsPos row = base.up(dy);
            RsPos inside = row.plus(in.dx(), 0, in.dz());
            if (passable(w, inside) && passable(w, inside.up(1)) && wall(w, inside.up(-1))) {
                base = row;
                break;
            }
        }
        for (int u = 0; u < width; u++) {
            for (int dy = 0; dy < 2; dy++) {
                if (!wall(w, base.plus(along.dx() * u, dy, along.dz() * u))) {
                    return Found.no("the wall met at " + base.x() + " " + base.y() + " " + base.z() + " is not whole "
                            + "there, and there is no doorway within " + SIDEWAYS + " blocks of it to use");
                }
            }
        }
        wallBlock = w.blockId(base);
        return new Found(new Frame(base, along, in), width, false, wallBlock, null);
    }

    static RsPos at(RsPos row, RsDir along, int u) {
        return row.plus(along.dx() * u, 0, along.dz() * u);
    }

    /**
     * A doorway column: walkable at both heights over a solid floor. A DOOR counts even with wall above it: a
     * building made with a one-block door shape has only the door's lower half (measured on a wished mansion), and
     * the piston door clears both cells anyway.
     */
    static boolean column(WorldView w, RsPos row, RsDir along, int u) {
        RsPos c = at(row, along, u);
        boolean door = w.blockId(c).endsWith("_door") || w.blockId(c.up(1)).endsWith("_door");
        return passable(w, c) && (passable(w, c.up(1)) || door) && wall(w, c.up(-1));
    }

    /**
     * The doorway of a BUILDING, from its box {@code {x0,y0,z0,x1,y1,z1}}: an opening on its ground storey (the
     * bottom four rows of a face), a real door preferred, on the face nearest {@code spot}; with none on any face,
     * a new one cut in the middle of the nearest face's walkable row. A building's spots can sit at the wrong height
     * (measured: a mansion's front spot at roof level, and a ray from it walked through the house to the back
     * wall), so the box decides, never the spot's height.
     */
    public static Found inBuilding(WorldView w, int[] box, RsPos spot, int wantWidth) {
        return inBuilding(w, box, spot, wantWidth, false);
    }

    /** As above; {@code newOpening} skips the existing doorways and cuts a new one (a secret passage). */
    public static Found inBuilding(WorldView w, int[] box, RsPos spot, int wantWidth, boolean newOpening) {
        return inBuilding(w, box, spot, wantWidth, newOpening, 4);
    }

    /**
     * As above, with {@code side}: the wall a new opening needs either side of it (4 for a piston door's pistons and
     * towers, 1 for an iron door, which needs only a frame).
     */
    public static Found inBuilding(WorldView w, int[] box, RsPos spot, int wantWidth, boolean newOpening, int side) {
        record Face(RsDir in, RsPos corner, int length) {
        }
        int x0 = box[0], y0 = box[1], z0 = box[2], x1 = box[3], z1 = box[5];
        // each face: the direction into the building, and its first cell along `along` (= in.clockwise())
        List<Face> faces = new ArrayList<>();
        faces.add(new Face(RsDir.SOUTH, new RsPos(x1, y0, z0), x1 - x0 + 1));   // north face, along = WEST
        faces.add(new Face(RsDir.NORTH, new RsPos(x0, y0, z1), x1 - x0 + 1));   // south face, along = EAST
        faces.add(new Face(RsDir.EAST, new RsPos(x0, y0, z0), z1 - z0 + 1));    // west face, along = SOUTH
        faces.add(new Face(RsDir.WEST, new RsPos(x1, y0, z1), z1 - z0 + 1));    // east face, along = NORTH
        // each face's REAL wall: a box can be wider than its walls (a roof overhang, a porch), so step in from the
        // box's face to the first layer that is mostly wall on its ground storey (measured: a modern house's
        // overhang put the search one block outside its walls, and a barn's box took in its fence, 6 out)
        List<Face> real = new ArrayList<>();
        for (Face f : faces) {
            Face use = f;
            int depth = Math.min(10, Math.max(box[3] - box[0], box[5] - box[2]) / 2);
            for (int k = 0; k <= depth; k++) {
                RsPos c = f.corner().plus(f.in().dx() * k, 0, f.in().dz() * k);
                RsDir along = f.in().clockwise();
                int walls = 0;
                for (int dy = 1; dy <= 2; dy++) {
                    for (int u = 0; u < f.length(); u++) {
                        if (wall(w, at(c.up(dy), along, u))) {
                            walls++;
                        }
                    }
                }
                if (walls * 100 >= 35 * 2 * f.length()) {      // a third of the two rows: glass walls count for little
                    use = new Face(f.in(), c, f.length());
                    break;
                }
            }
            real.add(use);
        }
        faces = real;
        double best = Double.MAX_VALUE;
        Found found = null;
        for (Face f : faces) {
            RsDir along = f.in().clockwise();
            for (int dy = 0; dy <= 3; dy++) {
                RsPos row = f.corner().up(dy);
                int u = 0;
                while (u < f.length()) {
                    if (!column(w, row, along, u)) {
                        u++;
                        continue;
                    }
                    int s0 = u;
                    while (u < f.length() && column(w, row, along, u)) {
                        u++;
                    }
                    int width = u - s0;
                    if (width > 2 || !wall(w, at(row, along, s0 - 1)) || !wall(w, at(row, along, u))) {
                        continue;
                    }
                    RsPos origin = at(row, along, s0);
                    boolean door = w.blockId(origin).endsWith("_door");
                    double mx = origin.x() + along.dx() * (width - 1) / 2.0, mz = origin.z() + along.dz() * (width - 1) / 2.0;
                    double score = Math.hypot(mx - spot.x(), mz - spot.z()) - (door ? 1000 : 0);
                    if (score < best) {
                        best = score;
                        found = new Found(new Frame(origin, along, f.in()), width, true,
                                w.blockId(at(row, along, s0 - 1)), null);
                    }
                }
            }
        }
        if (found != null && !newOpening) {
            return found;
        }
        // none (or a new one asked for): cut a new one in the nearest face, at the row the inside is walkable
        Face near = null;
        double nd = Double.MAX_VALUE;
        for (Face f : faces) {
            RsDir along = f.in().clockwise();
            double mx = f.corner().x() + along.dx() * (f.length() - 1) / 2.0;
            double mz = f.corner().z() + along.dz() * (f.length() - 1) / 2.0;
            double d = Math.hypot(mx - spot.x(), mz - spot.z());
            if (d < nd) {
                nd = d;
                near = f;
            }
        }
        int width = wantWidth == 1 ? 1 : 2;
        if (near.length() < width + 2 * side) {
            return Found.no("that building's wall is too short for this door: it needs " + (width + 2 * side)
                    + " blocks of wall" + (side > 1 ? " (an iron_door needs only " + (width + 2) + ")" : ""));
        }
        RsDir along = near.in().clockwise();
        int u = (near.length() - width) / 2;
        // nearest the spot along the face, keeping room for the towers (4 blocks each side)
        int su = (spot.x() - near.corner().x()) * along.dx() + (spot.z() - near.corner().z()) * along.dz();
        if (su >= side && su <= near.length() - width - side) {
            u = su;
        }
        for (int du : new int[] {0, 3, -3, 5, -5}) {
            int uu = u + du;
            if (uu < side || uu > near.length() - width - side) {
                continue;
            }
            for (int dy = 0; dy <= 3; dy++) {
                RsPos row = near.corner().up(dy);
                RsPos c = at(row, along, uu);
                RsPos c2 = at(row, along, uu + width - 1);
                RsPos inside = c.plus(near.in().dx(), 0, near.in().dz());
                if (wall(w, c) && wall(w, c.up(1)) && wall(w, c2) && wall(w, c2.up(1)) && passable(w, inside)
                        && passable(w, inside.up(1)) && wall(w, inside.up(-1))) {
                    return new Found(new Frame(c, along, near.in()), width, false, w.blockId(c), null);
                }
            }
        }
        for (int dy = 0; dy <= -1; dy++) {
            RsPos row = near.corner().up(dy);
            RsPos c = at(row, along, u);
            RsPos inside = c.plus(near.in().dx(), 0, near.in().dz());
            if (wall(w, c) && wall(w, c.up(1)) && passable(w, inside) && passable(w, inside.up(1))
                    && wall(w, inside.up(-1))) {
                return new Found(new Frame(c, along, near.in()), width, false, w.blockId(c), null);
            }
        }
        return Found.no("that building has no doorway and no wall with a walkable floor behind it to cut one in");
    }
}
