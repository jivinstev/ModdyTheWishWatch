package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * <b>The spots every building has without anybody marking them</b> (for every wish class) — {@code top}, {@code inside}, the four
 * {@code sides} and {@code around}, computed from the building's own blocks, beside the
 * {@link BuildSpots#FRONT} it already had.
 *
 * <p>A child names things — "a dragon on my roof", "cars in my garage", "a rainbow over my house",
 * "a meteor hits my castle" — and before this a building had exactly one place anything knew about
 * unless the agent had marked a spot when it was built. Every real case measured targeted a
 * building, and the agent cannot go back and mark a spot on a castle a child built yesterday.
 *
 * <ul>
 *   <li><b>{@code top}</b> — standing on its highest part nearest its middle (a roof, a keep's top,
 *   a tower's floor). Columns within a quarter of the building's height of the very top count, so a
 *   castle's courtyard is not its "top" and a pitched roof's ridge is.</li>
 *   <li><b>{@code inside}</b>, {@code inside_2} … — the floor centre of each ROOM: air with a floor
 *   under it, two blocks of headroom, a roof somewhere above, and walls on three sides or on two
 *   opposite ones (a garage with its door open, a carwash open at both ends). Lowest storey first,
 *   biggest first within a storey. Not {@code BuildGeometry.sealedInterior}, which finds only rooms
 *   with NO way in — exactly the rooms a creature should never be put in.</li>
 *   <li><b>{@code north|east|south|west}</b> — on the ground, {@value BuildSpots#FRONT_GAP} blocks
 *   outside the middle of that wall, facing away from it.</li>
 *   <li><b>{@code around}</b> — the middle of its footprint at ground level, and a RING
 *   ({@link #aroundRadius}) just outside its widest wall: what "a tornado around my base" and "a
 *   herd around the castle" stand on. Never a point to put one thing on: that is the foundations.</li>
 * </ul>
 *
 * <p>Pure — no Minecraft imports — so every rule is a row in {@code BuildAutoSpotsTest}.
 */
public final class BuildAutoSpots {

    public static final String TOP = "top";
    public static final String INSIDE = "inside";
    public static final String AROUND = "around";
    public static final List<String> SIDES = List.of("north", "east", "south", "west");

    /** More rooms than this and the rest are noise (a lighthouse's bands). */
    public static final int MAX_ROOMS = 6;
    /** A room is at least this many floor cells: a 2x2 cupboard is not somewhere to put a car. */
    public static final int MIN_ROOM_CELLS = 4;
    /** How much of a room's edge must be wall: a garage with its door open is 0.8, a pavilion 0.2. */
    public static final double MIN_WALLED = 0.6;
    /** Past this many cells in the bounding box the room scan is skipped (192k-block builds exist). */
    static final long MAX_SCAN = 4_000_000L;

    private BuildAutoSpots() {
    }

    /** Every automatic spot, in the order {@code inspect_build} lists them (front is not here). */
    public static List<BuildSpot> of(BuildPlan plan) {
        return of(BuildSpots.Cells.of(plan));
    }

    static List<BuildSpot> of(BuildSpots.Cells c) {
        List<BuildSpot> out = new ArrayList<>();
        if (c.empty()) {
            return out;
        }
        BuildSpot top = top(c);
        if (top != null) {
            out.add(top);
        }
        out.addAll(rooms(c));
        out.addAll(sides(c));
        out.add(around(c));
        return out;
    }

    // ── top ──────────────────────────────────────────────────────────────────────────────────

    /** Where something stands on the building's highest part, nearest its middle. Null when empty. */
    static BuildSpot top(BuildSpots.Cells c) {
        Map<Long, Integer> tops = new java.util.HashMap<>();
        for (Map.Entry<Long, String> e : c.at.entrySet()) {
            if (BuildSpots.passable(e.getValue())) {
                continue;
            }
            long k = e.getKey();
            int x = (int) (k >> 42), y = (int) (k << 22 >> 43), z = (int) (k << 43 >> 43);
            tops.merge(col(x, z), y, Math::max);
        }
        if (tops.isEmpty()) {
            return null;
        }
        int highest = tops.values().stream().max(Integer::compare).orElseThrow();
        int height = c.y1 - c.y0 + 1;
        int floor = highest - Math.max(2, height / 4);
        double cx = (c.x0 + c.x1) / 2.0, cz = (c.z0 + c.z1) / 2.0;
        long best = 0;
        double bestScore = Double.MAX_VALUE;
        boolean found = false;
        for (Map.Entry<Long, Integer> e : tops.entrySet()) {
            int t = e.getValue();
            if (t < floor) {
                continue;
            }
            int x = colX(e.getKey()), z = colZ(e.getKey());
            // Flat beats a spike: a column whose eight neighbours share its height is a roof to
            // stand on, a lone pinnacle is not. Higher beats lower by a little.
            int flat = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Integer n = tops.get(col(x + dx, z + dz));
                    if ((dx != 0 || dz != 0) && n != null && n == t) {
                        flat++;
                    }
                }
            }
            double score = Math.hypot(x - cx, z - cz) - 0.6 * flat - 0.25 * (t - floor);
            if (!found || score < bestScore || (score == bestScore && e.getKey() < best)) {
                best = e.getKey();
                bestScore = score;
                found = true;
            }
        }
        return new BuildSpot(TOP, colX(best), tops.get(best) + 1, colZ(best), "");
    }

    private static long col(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int colX(long col) {
        return (int) (col >> 32);
    }

    private static int colZ(long col) {
        return (int) col;
    }

    // ── inside ───────────────────────────────────────────────────────────────────────────────

    /** The floor centre of each room, lowest storey first. Empty for an open or huge building. */
    static List<BuildSpot> rooms(BuildSpots.Cells c) {
        int w = c.x1 - c.x0 + 1, h = c.y1 - c.y0 + 1, d = c.z1 - c.z0 + 1;
        if ((long) w * h * d > MAX_SCAN || h < 3) {
            return List.of();
        }
        boolean[] solid = new boolean[w * h * d];
        for (Map.Entry<Long, String> e : c.at.entrySet()) {
            if (BuildSpots.passable(e.getValue())) {
                continue;
            }
            long k = e.getKey();
            int x = (int) (k >> 42) - c.x0, y = (int) (k << 22 >> 43) - c.y0, z = (int) (k << 43 >> 43) - c.z0;
            solid[(y * d + z) * w + x] = true;
        }
        // A wall somewhere along each axis direction, and a roof somewhere above: one sweep each.
        boolean[] roof = new boolean[w * h * d], west = new boolean[w * h * d], east = new boolean[w * h * d],
                north = new boolean[w * h * d], south = new boolean[w * h * d];
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean seen = false;
                for (int y = h - 1; y >= 0; y--) {
                    int i = (y * d + z) * w + x;
                    roof[i] = seen;
                    seen |= solid[i];
                }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                boolean seen = false;
                for (int x = 0; x < w; x++) {
                    int i = (y * d + z) * w + x;
                    west[i] = seen;
                    seen |= solid[i];
                }
                seen = false;
                for (int x = w - 1; x >= 0; x--) {
                    int i = (y * d + z) * w + x;
                    east[i] = seen;
                    seen |= solid[i];
                }
            }
            for (int x = 0; x < w; x++) {
                boolean seen = false;
                for (int z = 0; z < d; z++) {
                    int i = (y * d + z) * w + x;
                    north[i] = seen;
                    seen |= solid[i];
                }
                seen = false;
                for (int z = d - 1; z >= 0; z--) {
                    int i = (y * d + z) * w + x;
                    south[i] = seen;
                    seen |= solid[i];
                }
            }
        }
        boolean[] floor = new boolean[w * h * d];
        for (int y = 0; y + 1 < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    int i = (y * d + z) * w + x;
                    int up = ((y + 1) * d + z) * w + x;
                    // Its floor is a block of the building, or the ground under its lowest storey.
                    boolean under = y == 0 || solid[((y - 1) * d + z) * w + x];
                    if (solid[i] || solid[up] || !under || !roof[up]) {
                        continue;
                    }
                    int walls = (west[i] ? 1 : 0) + (east[i] ? 1 : 0) + (north[i] ? 1 : 0) + (south[i] ? 1 : 0);
                    floor[i] = walls >= 3 || (west[i] && east[i]) || (north[i] && south[i]);
                }
            }
        }
        record Room(int y, int cells, int x, int z) {
        }
        List<Room> found = new ArrayList<>();
        boolean[] seen = new boolean[w * h * d];
        int[] queue = new int[w * d];
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    int start = (y * d + z) * w + x;
                    if (!floor[start] || seen[start]) {
                        continue;
                    }
                    // One storey's room: floor cells joined north/south/east/west at one height.
                    int head = 0, tail = 0;
                    queue[tail++] = start;
                    seen[start] = true;
                    List<int[]> cells = new ArrayList<>();
                    long sx = 0, sz = 0;
                    while (head < tail) {
                        int i = queue[head++];
                        int cx = i % w, cz = (i / w) % d;
                        cells.add(new int[]{cx, cz});
                        sx += cx;
                        sz += cz;
                        int[][] n = {{cx - 1, cz}, {cx + 1, cz}, {cx, cz - 1}, {cx, cz + 1}};
                        for (int[] p : n) {
                            if (p[0] < 0 || p[0] >= w || p[1] < 0 || p[1] >= d) {
                                continue;
                            }
                            int j = (y * d + p[1]) * w + p[0];
                            if (floor[j] && !seen[j]) {
                                seen[j] = true;
                                queue[tail++] = j;
                            }
                        }
                    }
                    if (cells.size() < MIN_ROOM_CELLS) {
                        continue;
                    }
                    // WALLED, not merely roofed: most of the room's edge must be wall at foot or head
                    // height. The per-cell rays above are fooled by a roof on four posts — a cell on
                    // its edge "sees" a post at each end and reads as a carwash — and this is what a
                    // room has and a pavilion does not.
                    int edges = 0, walled = 0;
                    for (int[] p : cells) {
                        int[][] n = {{p[0] - 1, p[1]}, {p[0] + 1, p[1]}, {p[0], p[1] - 1}, {p[0], p[1] + 1}};
                        for (int[] q : n) {
                            boolean in = q[0] >= 0 && q[0] < w && q[1] >= 0 && q[1] < d;
                            if (in && floor[(y * d + q[1]) * w + q[0]]) {
                                continue;
                            }
                            edges++;
                            if (in && (solid[(y * d + q[1]) * w + q[0]]
                                    || solid[((y + 1) * d + q[1]) * w + q[0]])) {
                                walled++;
                            }
                        }
                    }
                    if (walled < MIN_WALLED * edges) {
                        continue;
                    }
                    // The cell of the room nearest its middle — a cell OF the room, so an L-shaped
                    // hall's spot is on its floor and not in the corner wall its centroid falls in.
                    double mx = (double) sx / cells.size(), mz = (double) sz / cells.size();
                    int[] mid = cells.stream().min(Comparator.<int[]>comparingDouble(
                            p -> Math.hypot(p[0] - mx, p[1] - mz)).thenComparingInt(p -> p[0])
                            .thenComparingInt(p -> p[1])).orElseThrow();
                    found.add(new Room(y, cells.size(), mid[0], mid[1]));
                }
            }
        }
        found.sort(Comparator.comparingInt(Room::y).thenComparing(Comparator.comparingInt(Room::cells).reversed()));
        List<BuildSpot> out = new ArrayList<>();
        for (Room r : found) {
            if (out.size() >= MAX_ROOMS) {
                break;
            }
            String name = out.isEmpty() ? INSIDE : INSIDE + "_" + (out.size() + 1);
            out.add(new BuildSpot(name, c.x0 + r.x(), c.y0 + r.y(), c.z0 + r.z(), ""));
        }
        return out;
    }

    // ── sides and around ─────────────────────────────────────────────────────────────────────

    /** Outside the middle of each wall, on the ground, facing out. */
    static List<BuildSpot> sides(BuildSpots.Cells c) {
        int midX = (int) Math.floor((c.x0 + c.x1) / 2.0), midZ = (int) Math.floor((c.z0 + c.z1) / 2.0);
        int g = BuildSpots.FRONT_GAP;
        return List.of(
                new BuildSpot("north", midX, c.y0, c.z0 - g, "north"),
                new BuildSpot("east", c.x1 + g, c.y0, midZ, "east"),
                new BuildSpot("south", midX, c.y0, c.z1 + g, "south"),
                new BuildSpot("west", c.x0 - g, c.y0, midZ, "west"));
    }

    /** The middle of the footprint at ground level — the centre of {@link #aroundRadius}'s ring. */
    static BuildSpot around(BuildSpots.Cells c) {
        return new BuildSpot(AROUND, (int) Math.floor((c.x0 + c.x1) / 2.0), c.y0,
                (int) Math.floor((c.z0 + c.z1) / 2.0), "");
    }

    /**
     * How far from {@link #around}'s centre the ring runs: just outside the building's corners, so
     * nothing put around it lands in a wall. Measured from the block CENTRE the spot names.
     */
    public static double aroundRadius(BuildPlan plan) {
        return aroundRadius(BuildSpots.Cells.of(plan));
    }

    static double aroundRadius(BuildSpots.Cells c) {
        if (c.empty()) {
            return BuildSpots.FRONT_GAP + 1;
        }
        double hx = (c.x1 - c.x0 + 1) / 2.0, hz = (c.z1 - c.z0 + 1) / 2.0;
        return Math.ceil(Math.hypot(hx, hz)) + BuildSpots.FRONT_GAP;
    }

    /**
     * <b>Where a {@code wide}×{@code tall} picture hangs on one wall of a building</b> ("paint
     * a flag on my castle's wall") — relative to the anchor, the picture's BOTTOM-LEFT block as a
     * viewer sees it (the block just in front of the wall, which is what {@code paint_surface}'s
     * x/y/z name), facing out of that wall.
     *
     * <p>A picture is flat and every block of it needs wall right behind it, so the rectangle is only
     * placed where the wall's face is flat across all of it: for each column and row the first solid
     * block in from that side must be at the same depth. Tried from the middle of the wall outward,
     * from one block above its foot upward — eye height for him first. Null when no flat patch that
     * size exists (a castle all towers and gaps); the caller then says so and asks for a smaller one.
     *
     * @param side north, east, south or west
     */
    public static BuildSpot wallFace(BuildPlan plan, String side, int wide, int tall) {
        BuildSpots.Cells c = BuildSpots.Cells.of(plan);
        if (c.empty() || wide < 1 || tall < 1) {
            return null;
        }
        // u runs along the wall to the VIEWER's right; depth runs into the wall from outside.
        boolean ns = side.equals("north") || side.equals("south");
        int uLo = ns ? c.x0 : c.z0, uHi = ns ? c.x1 : c.z1;
        int span = uHi - uLo + 1;
        if (span < wide || c.y1 - c.y0 + 1 < tall + 1) {
            return null;
        }
        int mid = uLo + (span - wide) / 2;
        for (int y = c.y0 + 1; y + tall - 1 <= c.y1; y++) {
            for (int k = 0; k <= span - wide; k++) {
                // middle first, then alternately either side of it
                int off = (k % 2 == 0 ? 1 : -1) * ((k + 1) / 2);
                int start = mid + off;
                if (start < uLo || start + wide - 1 > uHi) {
                    continue;
                }
                Integer depth = null;
                boolean flat = true;
                for (int u = start; u < start + wide && flat; u++) {
                    for (int v = y; v < y + tall && flat; v++) {
                        Integer d = firstSolid(c, side, u, v);
                        if (d == null || (depth != null && !depth.equals(d))) {
                            flat = false;
                        } else {
                            depth = d;
                        }
                    }
                }
                if (!flat) {
                    continue;
                }
                // The block in front of the face, at the viewer's bottom-left.
                return switch (side) {
                    case "north" -> new BuildSpot("wall_north", start + wide - 1, y, depth - 1, "north");
                    case "south" -> new BuildSpot("wall_south", start, y, depth + 1, "south");
                    case "east" -> new BuildSpot("wall_east", depth + 1, y, start + wide - 1, "east");
                    default -> new BuildSpot("wall_west", depth - 1, y, start, "west");
                };
            }
        }
        return null;
    }

    /** The coordinate of the first solid block met coming IN from {@code side} along column u, row y. */
    private static Integer firstSolid(BuildSpots.Cells c, String side, int u, int y) {
        switch (side) {
            case "north" -> {
                for (int z = c.z0; z <= c.z1; z++) {
                    if (c.solid(u, y, z)) {
                        return z;
                    }
                }
            }
            case "south" -> {
                for (int z = c.z1; z >= c.z0; z--) {
                    if (c.solid(u, y, z)) {
                        return z;
                    }
                }
            }
            case "east" -> {
                for (int x = c.x1; x >= c.x0; x--) {
                    if (c.solid(x, y, u)) {
                        return x;
                    }
                }
            }
            default -> {
                for (int x = c.x0; x <= c.x1; x++) {
                    if (c.solid(x, y, u)) {
                        return x;
                    }
                }
            }
        }
        return null;
    }

    /** Which compass word a side spot's facing is, given the front's: the one turned clockwise. */
    static String clockwise(String facing) {
        return switch (facing) {
            case "north" -> "east";
            case "east" -> "south";
            case "south" -> "west";
            case "west" -> "north";
            default -> "east";
        };
    }

    static String opposite(String facing) {
        return switch (facing) {
            case "north" -> "south";
            case "east" -> "west";
            case "south" -> "north";
            case "west" -> "east";
            default -> "north";
        };
    }
}
