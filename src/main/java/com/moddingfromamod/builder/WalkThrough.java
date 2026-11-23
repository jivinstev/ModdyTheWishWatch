package com.moddingfromamod.builder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Can a child WALK into it? </b>
 *
 * <p>The owner, grading the Builder's buildings without playing: <i>"do they actually have doors the
 * player can open and go thru?"</i> {@link BuildGeometry#sealedInterior()} answers a weaker question —
 * can AIR get in — so an upper storey with a window and no stairs, a doorway one block tall, or a
 * front step two blocks high all pass it while a child stands outside. This walks in instead.
 *
 * <h2>The walker</h2>
 * A player 1.8 tall (four half-blocks) and 0.6 wide, moving between the four neighbouring columns:
 * he walks up half a block, jumps up a whole block when there is headroom for it (a stair's own
 * half step needs none), drops any distance, climbs ladders, vines and scaffolding, swims, opens
 * wooden doors, trapdoors and gates, and stands on a closed trapdoor. An iron door is a wall — a hand
 * cannot open it. A bottom slab, a bed or a stair raises the floor; a fence or a wall is a block and a
 * half that nobody steps over. Heights are in HALF blocks, which is exact for every block above.
 *
 * <h2>Where it stands</h2>
 * {@link #of(BuildPlan)} judges the DESIGN on flat ground: the anchor plane is the top of the ground
 * (everything under {@code y = 0} the plan does not place is earth, and an air shape carves it, which
 * is how a bunker is dug), or the plan's own lowest layer when it starts above the plane — the same
 * rule {@link BuildSupport} uses. {@link #of(Cells, int, int, int, int, int, int)} judges a piece of a
 * real world, terrain and all, which is how a GameTest asks the same question of what the Builder put up
 * on a hill or in the sea.
 *
 * <p>The plan is composed exactly as {@link Builder} places it: later shapes overwrite, air empties.
 * Pure — no Minecraft imports — so it runs over every archived build in the unit suite.
 *
 * <h2>What it says</h2>
 * Every floor region (positions joined by a step of half a block or less) whose positions have a roof
 * over them, cover at least {@link #ROOM_MIN} columns and are two wide somewhere is a ROOM — not a niche,
 * a one-wide ledge under the eaves, or the water in a well. A room no walk from outside reaches
 * is reported with WHY ({@link Cause}) and WHERE — a compass word, its floor, and for a ground room the
 * wall cell whose removal would let him in. Information for the agent, never a refusal: a sealed turret
 * may be decoration.
 */
public final class WalkThrough {

    /** A floor region smaller than this many columns is a niche, a chimney or a pillar's hollow, not a room. */
    public static final int ROOM_MIN = 4;

    /** Above this many walker states the search is not run and the report says so; never a silent pass. */
    public static final long MAX_STATES = 8_000_000L;

    /** Columns of open ground around the building that the walk starts from. */
    static final int PAD = 2;

    /** How tall the walker is, in half blocks (1.8 rounds up to 2.0). */
    static final int BODY = 4;

    /** Why a room cannot be walked into. */
    public enum Cause {
        /** No way in at all, even for air: no doorway, or every opening is glass. */
        SEALED,
        /** Above anything he can climb to: an upper storey with no stairs or ladder. */
        NO_WAY_UP,
        /** The only way in is under two blocks tall: he could crawl it, not walk it. */
        LOW_OPENING,
        /** Air gets in, but the way in starts a step too high to climb, or behind something he cannot pass. */
        UNREACHABLE_STEP
    }

    /** One room, as a child would describe it to someone. Coordinates are the plan's (or the world's). */
    public record Room(int columns, int floorY, int minX, int maxX, int minZ, int maxZ,
                       boolean reached, Cause cause, int[] cut, String where) {

        /** Plain words: "the room at the north-west (x -7..-3, z 2..6, floor y 1)". */
        public String describe() {
            return "the room at " + where + " (x " + minX + ".." + maxX + ", z " + minZ + ".." + maxZ
                    + ", floor y " + floorY + ", " + columns + " blocks of floor)";
        }
    }

    /** What the walk found. {@code main} is the room with the most floor, or null when there is none. */
    public record Report(boolean tooBig, List<Room> rooms, Room main, int reachedStates) {

        /** The rooms no walk reaches, biggest first. */
        public List<Room> unreachable() {
            return rooms.stream().filter(r -> !r.reached()).toList();
        }

        /** True when the biggest room can be walked into (or there is no room to walk into). */
        public boolean mainWalkable() {
            return main == null || main.reached();
        }

        static Report big() {
            return new Report(true, List.of(), null, 0);
        }
    }

    /** A block at a position, or null for air. */
    public interface Cells {
        String at(int x, int y, int z);
    }

    // ── cell kinds ───────────────────────────────────────────────────────────────────────────────
    static final byte FREE = 0;
    static final byte FULL = 1;
    static final byte LOWER = 2;   // bottom slab, bed, a stair seen as a half step
    static final byte UPPER = 3;   // top slab
    static final byte TALL = 4;    // fence, wall: a block and a half
    static final byte CLIMB = 5;   // ladder, vine, scaffolding, water
    static final byte TRAP = 6;    // wooden trapdoor: open to pass, closed to stand on
    static final byte DOOR = 7;    // wooden door, fence gate: opened and walked through
    static final byte STAIR = 8;   // full for collision, but its half step needs no jump
    static final byte LAVA = 10;   // nobody walks in it or on it: a moat is crossed by its bridge (seen on film)
    static final byte WATER = 9;   // climbable like a ladder (he swims), but never a room's floor

    /** What one block is to a walking body. */
    static byte kindOf(String block) {
        if (block == null) {
            return FREE;
        }
        String b = BuildSupport.base(block);
        if (b.isEmpty() || BuildSupport.isAir(block)) {
            return FREE;
        }
        if (b.equals("iron_door") || b.equals("iron_trapdoor")) {
            return FULL;                                   // a hand cannot open either
        }
        if (b.endsWith("_door") || b.endsWith("_fence_gate")) {
            return DOOR;
        }
        if (b.endsWith("_trapdoor")) {
            return TRAP;
        }
        if (b.equals("water") || b.equals("bubble_column") || b.equals("kelp") || b.equals("kelp_plant")
                || b.equals("seagrass") || b.equals("tall_seagrass")) {
            return WATER;
        }
        if (b.equals("ladder") || b.equals("vine")
                || b.equals("scaffolding") || b.equals("twisting_vines")
                || b.equals("twisting_vines_plant") || b.equals("weeping_vines")
                || b.equals("weeping_vines_plant") || b.equals("cave_vines") || b.equals("cave_vines_plant")) {
            return CLIMB;
        }
        if (b.equals("lava")) {
            return LAVA;
        }
        if (b.endsWith("_slab")) {
            String type = BuildSupport.props(block).getOrDefault("type", "bottom");
            return type.equals("double") ? FULL : type.equals("top") ? UPPER : LOWER;
        }
        if (b.endsWith("_stairs")) {
            return "top".equals(BuildSupport.props(block).get("half")) ? FULL : STAIR;
        }
        if (b.endsWith("_bed")) {
            return LOWER;
        }
        if ((b.endsWith("_fence") || b.endsWith("_wall")) && !b.endsWith("_wall_torch")
                && !b.endsWith("_wall_sign") && !b.endsWith("_wall_banner") && !b.endsWith("_wall_hanging_sign")
                && !b.endsWith("_wall_head") && !b.endsWith("_wall_skull") && !b.endsWith("_wall_fan")) {
            return TALL;
        }
        if (b.endsWith("_carpet") || b.endsWith("_pressure_plate") || b.endsWith("_button")
                || b.endsWith("_sign") || b.endsWith("_banner") || b.endsWith("_torch") || b.equals("torch")
                || b.endsWith("_candle") || b.equals("candle") || b.endsWith("_sapling") || b.endsWith("_tulip")
                || b.endsWith("_head") || b.endsWith("_skull") || b.endsWith("_coral") || b.endsWith("_coral_fan")
                || b.equals("lantern") || b.equals("soul_lantern") || b.equals("flower_pot")
                || b.startsWith("potted_") || b.equals("lever") || b.equals("cobweb")) {
            return FREE;
        }
        return BuildSupport.blocksBody(block) ? FULL : FREE;
    }

    // ── the grid ─────────────────────────────────────────────────────────────────────────────────
    private final int x0;
    private final int z0;
    private final int w;
    private final int d;
    private final int yLo;      // lowest cell held; everything under it is solid
    private final int yHi;      // highest cell held; everything over it is open
    private final byte[] kinds; // [col * H + (y - yLo)]
    private final int hCells;
    private final int hLo;      // lowest half-height a body's feet can be at
    private final int nh;       // half positions per column
    private final int bx0;
    private final int bx1;
    private final int bz0;
    private final int bz1;
    private final int body;
    private boolean[] reached;
    /** Floor states of every room {@link #report} found, kept for {@link #roomSpace}. */
    private List<List<Integer>> roomMembers;

    private WalkThrough(int x0, int z0, int w, int d, int yLo, int yHi, byte[] kinds,
                        int bx0, int bx1, int bz0, int bz1, int body) {
        this.x0 = x0;
        this.z0 = z0;
        this.w = w;
        this.d = d;
        this.yLo = yLo;
        this.yHi = yHi;
        this.kinds = kinds;
        this.hCells = yHi - yLo + 1;
        this.hLo = 2 * yLo + 1;
        this.nh = 2 * yHi + 2 - hLo;
        this.bx0 = bx0;
        this.bx1 = bx1;
        this.bz0 = bz0;
        this.bz1 = bz1;
        this.body = body;
    }

    // ── building the grid ────────────────────────────────────────────────────────────────────────

    /** The plan as it ends up, AIR kept: an air shape under the ground plane digs the earth out. */
    static Map<Long, String> composeKeepingAir(BuildPlan plan) {
        Map<Long, String> out = new HashMap<>();
        if (plan == null) {
            return out;
        }
        for (BuildShape s : plan.shapes()) {
            String block = BuildSupport.isAir(s.block()) ? null : s.block();
            s.forEachBlock((x, y, z) -> out.put(ComposedPlan.key(x, y, z), block));
        }
        return out;
    }

    /**
     * The walker over a plan standing on flat ground, as {@link Builder} puts it up — with
     * {@link BuildDoors}' rewrite of its doors applied, because that is what a child walks through.
     */
    public static WalkThrough plan(BuildPlan plan) {
        if (plan == null) {
            return null;
        }
        Map<Long, String> cells = BuildDoors.applied(plan, BuildDoors.of(plan));
        BuildWaterEntry.apply(cells, BuildWaterEntry.fix(plan));
        BuildRepair.apply(cells, BuildRepair.of(plan));   //: as it is placed, one-block faults put right
        return over(cells, BODY);
    }

    /** The plan exactly as drawn, without the door rewrite: the "before" arm 's measurement. */
    public static WalkThrough planAsDrawn(BuildPlan plan) {
        return over(composeKeepingAir(plan), BODY);
    }

    static WalkThrough over(Map<Long, String> cells, int body) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Map.Entry<Long, String> e : cells.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            long k = e.getKey();
            minX = Math.min(minX, ComposedPlan.kx(k));
            minY = Math.min(minY, ComposedPlan.ky(k));
            minZ = Math.min(minZ, ComposedPlan.kz(k));
            maxX = Math.max(maxX, ComposedPlan.kx(k));
            maxY = Math.max(maxY, ComposedPlan.ky(k));
            maxZ = Math.max(maxZ, ComposedPlan.kz(k));
        }
        if (minX == Integer.MAX_VALUE) {
            return null;
        }
        // The top of the ground: the anchor plane, or the plan's lowest layer when it starts above it.
        final int groundTop = minY > 0 ? minY - 1 : -1;
        int lowest = Math.min(minY, groundTop);
        for (Map.Entry<Long, String> e : cells.entrySet()) {
            lowest = Math.min(lowest, ComposedPlan.ky(e.getKey()));   // a dug cellar counts too
        }
        final int finalMinY = lowest;
        Cells at = (x, y, z) -> {
            long k = ComposedPlan.key(x, y, z);
            if (cells.containsKey(k)) {
                return cells.get(k);
            }
            return y <= groundTop ? "minecraft:dirt" : null;
        };
        return build(at, minX - PAD, finalMinY - 1, minZ - PAD, maxX + PAD, maxY + 3, maxZ + PAD,
                minX, maxX, minZ, maxZ, body);
    }

    /**
     * The walker over a piece of a real world. The region should reach a little past the building on
     * every side and from below its floor to above its roof; the walk starts on the region's edge.
     * {@code bMinX..bMaxZ} is the building's own footprint, which is where rooms are looked for.
     */
    public static WalkThrough world(Cells cells, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                    int bMinX, int bMaxX, int bMinZ, int bMaxZ) {
        return build(cells, minX, minY, minZ, maxX, maxY, maxZ, bMinX, bMaxX, bMinZ, bMaxZ, BODY);
    }

    private static WalkThrough build(Cells cells, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                     int bx0, int bx1, int bz0, int bz1, int body) {
        int w = maxX - minX + 1;
        int d = maxZ - minZ + 1;
        int h = maxY - minY + 1;
        if ((long) w * d * h * 2 > MAX_STATES) {
            return new WalkThrough(minX, minZ, 0, 0, minY, maxY, new byte[0], bx0, bx1, bz0, bz1, body);
        }
        byte[] kinds = new byte[w * d * h];
        for (int cx = 0; cx < w; cx++) {
            for (int cz = 0; cz < d; cz++) {
                int base = (cx * d + cz) * h;
                for (int y = 0; y < h; y++) {
                    kinds[base + y] = kindOf(cells.at(minX + cx, minY + y, minZ + cz));
                }
            }
        }
        return new WalkThrough(minX, minZ, w, d, minY, maxY, kinds, bx0, bx1, bz0, bz1, body);
    }

    // ── the body ─────────────────────────────────────────────────────────────────────────────────

    private byte kind(int col, int y) {
        if (y < yLo) {
            return FULL;
        }
        if (y > yHi) {
            return FREE;
        }
        return kinds[col * hCells + (y - yLo)];
    }

    /** Is this half-block of this column taken by something a body cannot be in? */
    private boolean occ(int col, int half) {
        int y = Math.floorDiv(half, 2);
        boolean lower = (half & 1) == 0;
        byte k = kind(col, y);
        switch (k) {
            case FULL, TALL, STAIR, LAVA -> {
                return true;
            }
            case LOWER -> {
                if (lower) {
                    return true;
                }
            }
            case UPPER -> {
                if (!lower) {
                    return true;
                }
            }
            default -> { }
        }
        return lower && kind(col, y - 1) == TALL;
    }

    private boolean climb(int col, int y) {
        byte k = kind(col, y);
        return k == CLIMB || k == WATER;
    }

    private boolean freeSpan(int col, int from, int to) {
        for (int i = from; i <= to; i++) {
            if (occ(col, i)) {
                return false;
            }
        }
        return true;
    }

    /** Can a body stand (or hang, or swim) with its feet at this half-height in this column? */
    private boolean valid(int col, int h) {
        if (h < hLo || h >= hLo + nh) {
            return false;
        }
        if (!freeSpan(col, h, h + body - 1)) {
            return false;
        }
        return (occ(col, h - 1) && kind(col, Math.floorDiv(h - 1, 2)) != LAVA)
                || ((h & 1) == 0 && kind(col, h / 2 - 1) == TRAP)
                || climb(col, Math.floorDiv(h, 2))
                || climb(col, Math.floorDiv(h - 1, 2));
    }

    private boolean stairUnder(int col, int h) {
        return kind(col, Math.floorDiv(h - 1, 2)) == STAIR;
    }

    private int state(int col, int h) {
        return col * nh + (h - hLo);
    }

    private int colOf(int state) {
        return state / nh;
    }

    private int hOf(int state) {
        return state % nh + hLo;
    }

    private static final int[][] DIRS = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};

    /** Every state one move away. {@code sameFloor} keeps only moves of half a block or less, both ways. */
    private void moves(int s, boolean sameFloor, java.util.function.IntConsumer out) {
        int col = colOf(s);
        int h = hOf(s);
        int cx = col / d;
        int cz = col % d;
        if (!sameFloor) {
            // up a ladder, or swimming up
            if (climb(col, Math.floorDiv(h, 2)) && !occ(col, h + body) && valid(col, h + 1)) {
                out.accept(state(col, h + 1));
            }
            // down: a ladder, an opened trapdoor, a drop
            if (!occ(col, h - 1)) {
                for (int h2 = h - 1; h2 >= hLo && !occ(col, h2); h2--) {
                    if (valid(col, h2)) {
                        out.accept(state(col, h2));
                        break;
                    }
                }
            }
        }
        for (int[] dir : DIRS) {
            int nx = cx + dir[0];
            int nz = cz + dir[1];
            if (nx < 0 || nz < 0 || nx >= w || nz >= d) {
                continue;
            }
            int n = nx * d + nz;
            // level, or a step down, or a fall
            if (freeSpan(n, h, h + body - 1)) {
                for (int h2 = h; h2 >= hLo; h2--) {
                    if (h2 < h && occ(n, h2)) {
                        break;
                    }
                    if (valid(n, h2)) {
                        if (!sameFloor || h - h2 <= 1) {
                            out.accept(state(n, h2));
                        }
                        break;
                    }
                }
            }
            // a half step up
            if (!occ(col, h + body) && valid(n, h + 1)) {
                out.accept(state(n, h + 1));
            }
            // a whole block: a jump, or a stair's two half steps. Never out of the water: a swimmer gets onto what is
            // level with the surface, or half a block over it, and no higher (filmed: the dome's rim).
            boolean swimming = !occ(col, h - 1) && (kind(col, Math.floorDiv(h, 2)) == WATER
                    || kind(col, Math.floorDiv(h - 1, 2)) == WATER);
            if (!sameFloor && !swimming && !occ(col, h + body) && valid(n, h + 2)
                    && (stairUnder(n, h + 2) || !occ(col, h + body + 1))) {
                out.accept(state(n, h + 2));
            }
        }
    }

    /** Every state a walk from the edge of the region reaches. */
    private boolean[] flood() {
        boolean[] seen = new boolean[w * d * nh];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int cx = 0; cx < w; cx++) {
            for (int cz = 0; cz < d; cz++) {
                if (cx != 0 && cz != 0 && cx != w - 1 && cz != d - 1) {
                    continue;
                }
                int col = cx * d + cz;
                // the highest place to stand in each edge column: the top of the ground, or the water
                for (int h = hLo + nh - 1; h >= hLo; h--) {
                    if (valid(col, h)) {
                        int s = state(col, h);
                        if (!seen[s]) {
                            seen[s] = true;
                            queue.add(s);
                        }
                        break;
                    }
                }
            }
        }
        while (!queue.isEmpty()) {
            int s = queue.poll();
            moves(s, false, n -> {
                if (!seen[n]) {
                    seen[n] = true;
                    queue.add(n);
                }
            });
        }
        return seen;
    }

    /** Air reachable from the edge, as {@link BuildGeometry} asks it: doors and trapdoors let it in. */
    private boolean[] airFlood() {
        boolean[] seen = new boolean[w * d * hCells];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int cx = 0; cx < w; cx++) {
            for (int cz = 0; cz < d; cz++) {
                int col = cx * d + cz;
                for (int y = 0; y < hCells; y++) {
                    boolean edge = cx == 0 || cz == 0 || cx == w - 1 || cz == d - 1 || y == hCells - 1;
                    if (edge && airy(kinds[col * hCells + y])) {
                        seen[col * hCells + y] = true;
                        queue.add(col * hCells + y);
                    }
                }
            }
        }
        int[][] six = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int col = i / hCells;
            int y = i % hCells;
            int cx = col / d;
            int cz = col % d;
            for (int[] s : six) {
                int nx = cx + s[0];
                int ny = y + s[1];
                int nz = cz + s[2];
                if (nx < 0 || nz < 0 || ny < 0 || nx >= w || nz >= d || ny >= hCells) {
                    continue;
                }
                int j = (nx * d + nz) * hCells + ny;
                if (!seen[j] && airy(kinds[j])) {
                    seen[j] = true;
                    queue.add(j);
                }
            }
        }
        return seen;
    }

    private static boolean airy(byte k) {
        return k == FREE || k == CLIMB || k == WATER || k == TRAP || k == DOOR;
    }

    // ── the report ───────────────────────────────────────────────────────────────────────────────

    /** Walk a plan on flat ground, as it is put up, and say which rooms cannot be walked into. */
    public static Report of(BuildPlan plan) {
        WalkThrough walk = plan(plan);
        return walk == null ? new Report(false, List.of(), null, 0) : walk.report();
    }

    /** The same, of the plan as drawn (no door rewrite). */
    public static Report ofAsDrawn(BuildPlan plan) {
        WalkThrough walk = planAsDrawn(plan);
        return walk == null ? new Report(false, List.of(), null, 0) : walk.report();
    }

    /** Is this state's column inside the building's footprint, with something over its head? */
    private boolean coveredInside(int s) {
        int col = colOf(s);
        int x = x0 + col / d;
        int z = z0 + col % d;
        if (x < bx0 || x > bx1 || z < bz0 || z > bz1) {
            return false;
        }
        int h = hOf(s);
        // in water, or floating on it, is a pool, a moat or a well — not a room's floor
        if (kind(col, Math.floorDiv(h, 2)) == WATER || kind(col, Math.floorDiv(h - 1, 2)) == WATER) {
            return false;
        }
        for (int i = h + body; i < 2 * yHi + 2; i++) {
            if (occ(col, i)) {
                return true;
            }
        }
        return false;
    }

    /** States the walk from outside reaches; computed once. */
    boolean[] reached() {
        if (reached == null) {
            reached = flood();
        }
        return reached;
    }

    public Report report() {
        if (w == 0) {
            return Report.big();
        }
        boolean[] seen = reached();
        int reachedCount = 0;
        for (boolean b : seen) {
            if (b) {
                reachedCount++;
            }
        }
        // Floor regions, joined by moves of half a block or less in both directions.
        int states = w * d * nh;
        int[] region = new int[states];
        java.util.Arrays.fill(region, -1);
        List<List<Integer>> members = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int s = 0; s < states; s++) {
            if (region[s] >= 0 || !valid(colOf(s), hOf(s)) || !coveredInside(s)) {
                continue;
            }
            int id = members.size();
            List<Integer> m = new ArrayList<>();
            region[s] = id;
            queue.add(s);
            while (!queue.isEmpty()) {
                int a = queue.poll();
                m.add(a);
                moves(a, true, n -> {
                    if (region[n] < 0 && coveredInside(n)) {
                        region[n] = id;
                        queue.add(n);
                    }
                });
            }
            members.add(m);
        }
        boolean[] air = null;
        WalkThrough crawl = null;
        boolean[] crawled = null;
        List<Room> rooms = new ArrayList<>();
        List<List<Integer>> kept = new ArrayList<>();
        for (List<Integer> m : members) {
            java.util.Set<Integer> cols = new java.util.HashSet<>();
            boolean got = false;
            int minH = Integer.MAX_VALUE;
            int mx0 = Integer.MAX_VALUE;
            int mx1 = Integer.MIN_VALUE;
            int mz0 = Integer.MAX_VALUE;
            int mz1 = Integer.MIN_VALUE;
            for (int s : m) {
                int col = colOf(s);
                cols.add(col);
                got |= seen[s];
                minH = Math.min(minH, hOf(s));
                mx0 = Math.min(mx0, x0 + col / d);
                mx1 = Math.max(mx1, x0 + col / d);
                mz0 = Math.min(mz0, z0 + col % d);
                mz1 = Math.max(mz1, z0 + col % d);
            }
            if (cols.size() < ROOM_MIN || !hasSquare(cols)) {
                continue;                // a niche, a chimney, or a one-wide ledge under the eaves
            }
            int floorY = Math.floorDiv(minH, 2);
            Cause cause = null;
            int[] cut = null;
            if (!got) {
                if (air == null) {
                    air = airFlood();
                }
                boolean airGets = false;
                for (int s : m) {
                    int col = colOf(s);
                    int y = Math.floorDiv(hOf(s), 2) - yLo;
                    if (air[col * hCells + y] || (y + 1 < hCells && air[col * hCells + y + 1])) {
                        airGets = true;
                        break;
                    }
                }
                if (!airGets) {
                    cause = Cause.SEALED;
                } else {
                    if (crawl == null) {
                        crawl = new WalkThrough(x0, z0, w, d, yLo, yHi, kinds, bx0, bx1, bz0, bz1, 2);
                        crawled = crawl.flood();
                    }
                    boolean crawls = false;
                    for (int s : m) {
                        int cs = crawl.state(colOf(s), hOf(s));
                        if (crawled[cs]) {
                            crawls = true;
                            break;
                        }
                    }
                    cause = crawls ? Cause.LOW_OPENING
                            : floorY >= groundFloorY() + 2 ? Cause.NO_WAY_UP : Cause.UNREACHABLE_STEP;
                }
                if (cause != Cause.NO_WAY_UP) {
                    cut = cutFor(m, seen);
                }
            }
            kept.add(m);
            rooms.add(new Room(cols.size(), floorY, mx0, mx1, mz0, mz1, got, cause, cut,
                    compass((mx0 + mx1) / 2.0, (mz0 + mz1) / 2.0)));
        }
        roomMembers = kept;
        rooms.sort((a, b) -> Integer.compare(b.columns(), a.columns()));
        Room main = rooms.isEmpty() ? null : rooms.get(0);
        return new Report(false, List.copyOf(rooms), main, reachedCount);
    }

    /**
     * <b>The air of every room</b>: for each place a body can stand on a room's floor, the cells
     * from its feet up to the ceiling over it, as {@link ComposedPlan#key} in this walk's coordinates. The
     * same rooms {@link #report} names — a floor region with a roof over it, {@link #ROOM_MIN} columns, two
     * wide somewhere — so an open courtyard, a yard or a roof terrace is never in it. Reachable or not: a
     * room a tree stands in is still a room.
     */
    public java.util.Set<Long> roomSpace() {
        if (roomMembers == null) {
            report();
        }
        java.util.Set<Long> out = new java.util.HashSet<>();
        if (roomMembers == null) {
            return out;
        }
        for (List<Integer> m : roomMembers) {
            for (int s : m) {
                int col = colOf(s);
                int x = x0 + col / d;
                int z = z0 + col % d;
                int feet = Math.floorDiv(hOf(s), 2);
                for (int y = feet; y <= yHi; y++) {
                    byte k = kind(col, y);
                    if (k == FREE || k == CLIMB) {
                        out.add(ComposedPlan.key(x, y, z));
                    } else if (y > feet) {
                        break;                          // the ceiling
                    }
                }
            }
        }
        return out;
    }

    /**
     * Is the floor two wide anywhere — some column with a neighbour both along x and along z? A straight
     * one-wide strip is a ledge (the top of a porch roof under the eaves), not a room; a ring round a
     * lamp, an L or a corridor with a turn is a room.
     */
    private boolean hasSquare(java.util.Set<Integer> cols) {
        for (int col : cols) {
            int cx = col / d;
            int cz = col % d;
            boolean alongZ = (cz + 1 < d && cols.contains(col + 1)) || (cz > 0 && cols.contains(col - 1));
            boolean alongX = (cx + 1 < w && cols.contains(col + d)) || (cx > 0 && cols.contains(col - d));
            if (alongX && alongZ) {
                return true;
            }
        }
        return false;
    }

    /** The feet height of the ground outside, as a cell y: where the walk starts. */
    private int groundFloorY() {
        int best = Integer.MAX_VALUE;
        for (int cx = 0; cx < w; cx += Math.max(1, w - 1)) {
            for (int cz = 0; cz < d; cz++) {
                int col = cx * d + cz;
                for (int h = hLo + nh - 1; h >= hLo; h--) {
                    if (valid(col, h)) {
                        best = Math.min(best, Math.floorDiv(h, 2));
                        break;
                    }
                }
            }
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    /**
     * The wall cell to open: a solid block beside a room position, with a reached place to stand
     * just beyond it at about the same height. Thinnest wall first; null when nothing is that close.
     */
    private int[] cutFor(List<Integer> room, boolean[] seen) {
        for (int thick = 1; thick <= 2; thick++) {
            int[] best = null;
            int bestH = Integer.MAX_VALUE;
            for (int s : room) {
                int col = colOf(s);
                int h = hOf(s);
                int cx = col / d;
                int cz = col % d;
                for (int[] dir : DIRS) {
                    int fx = cx + dir[0] * (thick + 1);
                    int fz = cz + dir[1] * (thick + 1);
                    if (fx < 0 || fz < 0 || fx >= w || fz >= d) {
                        continue;
                    }
                    boolean wall = true;
                    for (int t = 1; t <= thick; t++) {
                        int mcol = (cx + dir[0] * t) * d + (cz + dir[1] * t);
                        if (freeSpan(mcol, h, h + body - 1)) {
                            wall = false;
                        }
                    }
                    if (!wall) {
                        continue;
                    }
                    int far = fx * d + fz;
                    for (int h2 = h - 2; h2 <= h + 2; h2++) {
                        if (h2 >= hLo && h2 < hLo + nh && seen[state(far, h2)] && h < bestH) {
                            bestH = h;
                            best = new int[]{x0 + cx + dir[0], Math.floorDiv(h, 2), z0 + cz + dir[1]};
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /** Where in the building, as a compass word relative to its middle (north is -z). */
    private String compass(double x, double z) {
        double cx = (bx0 + bx1) / 2.0;
        double cz = (bz0 + bz1) / 2.0;
        double sx = Math.max(1, (bx1 - bx0) / 6.0);
        double sz = Math.max(1, (bz1 - bz0) / 6.0);
        String ns = z < cz - sz ? "north" : z > cz + sz ? "south" : "";
        String ew = x < cx - sx ? "west" : x > cx + sx ? "east" : "";
        String both = ns.isEmpty() ? ew : ew.isEmpty() ? ns : ns + "-" + ew;
        return both.isEmpty() ? "the middle" : "the " + both;
    }

    /**
     * One horizontal slice, for a person reading why a room was flagged: {@code #} solid, {@code +} half
     * block, {@code H} climbable, {@code D} door or trapdoor, {@code .} open; a lower-case {@code o} where
     * a walk from outside stands with its feet in this layer, {@code x} where a body could stand and no
     * walk gets to. North (-z) is up.
     */
    public String layer(int y) {
        boolean[] seen = reached();
        StringBuilder sb = new StringBuilder("y=" + y + "  x " + x0 + ".." + (x0 + w - 1) + "\n");
        for (int cz = 0; cz < d; cz++) {
            for (int cx = 0; cx < w; cx++) {
                int col = cx * d + cz;
                char ch = switch (kind(col, y)) {
                    case FULL, TALL, STAIR -> '#';
                    case LAVA -> '~';
                    case LOWER, UPPER -> '+';
                    case CLIMB -> 'H';
                    case WATER -> '~';
                    case TRAP, DOOR -> 'D';
                    default -> '.';
                };
                for (int h = 2 * y; h <= 2 * y + 1; h++) {
                    if (h >= hLo && h < hLo + nh && valid(col, h)) {
                        ch = seen[state(col, h)] ? 'o' : 'x';
                        break;
                    }
                }
                sb.append(ch);
            }
            sb.append("  z=").append(z0 + cz).append('\n');
        }
        return sb.toString();
    }

    // ── a route, for a film ──────────────────────────────────────────────────────────────────────

    /** One place to stand on a route: the block column, the feet height in half blocks, and whether a room's middle. */
    public record Step(int x, int z, int halfY, boolean room) {
        public Step(int x, int z, int halfY) {
            this(x, z, halfY, false);
        }

        public double feetY() {
            return halfY / 2.0;
        }
    }

    /**
     * A walk that a filmed player can follow: in from outside to every room he can reach, nearest
     * first, each leg the shortest way the walker knows. Empty when there is nothing to walk into.
     */
    public List<Step> tour() {
        if (w == 0) {
            return List.of();
        }
        Report r = report();
        boolean[] seen = reached();
        List<int[]> targets = new ArrayList<>();       // {state}
        for (Room room : r.rooms()) {
            if (!room.reached()) {
                continue;
            }
            // the reached state nearest the room's middle, at its floor
            double mx = (room.minX() + room.maxX()) / 2.0;
            double mz = (room.minZ() + room.maxZ()) / 2.0;
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int s = 0; s < seen.length; s++) {
                if (!seen[s] || Math.floorDiv(hOf(s), 2) != room.floorY() || !coveredInside(s)) {
                    continue;
                }
                int col = colOf(s);
                int x = x0 + col / d;
                int z = z0 + col % d;
                if (x < room.minX() || x > room.maxX() || z < room.minZ() || z > room.maxZ()) {
                    continue;
                }
                double dd = (x - mx) * (x - mx) + (z - mz) * (z - mz);
                if (dd < bestD) {
                    bestD = dd;
                    best = s;
                }
            }
            if (best >= 0) {
                targets.add(new int[]{best});
            }
        }
        List<Step> out = new ArrayList<>();
        Integer at = null;
        while (!targets.isEmpty()) {
            int[] parent = bfs(at);
            int pick = -1;
            int pickLen = Integer.MAX_VALUE;
            for (int i = 0; i < targets.size(); i++) {
                int len = depth(parent, targets.get(i)[0]);
                if (len >= 0 && len < pickLen) {
                    pickLen = len;
                    pick = i;
                }
            }
            if (pick < 0) {
                break;
            }
            int goal = targets.remove(pick)[0];
            List<Integer> leg = new ArrayList<>();
            for (int s = goal; s >= 0; s = parent[s]) {
                leg.add(0, s);
                if (parent[s] == s) {
                    break;
                }
            }
            for (int i = (at == null ? 0 : 1); i < leg.size(); i++) {
                int s = leg.get(i);
                out.add(new Step(x0 + colOf(s) / d, z0 + colOf(s) % d, hOf(s), i == leg.size() - 1));
            }
            at = goal;
        }
        return out;
    }

    /** Breadth-first parents from one state (or from the whole edge when null); -1 unreached. */
    private int[] bfs(Integer from) {
        int[] parent = new int[w * d * nh];
        java.util.Arrays.fill(parent, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        if (from != null) {
            parent[from] = from;
            queue.add(from);
        } else {
            // start at the edge column straight out from the building's middle on its south side,
            // which is where a film begins; failing that, anywhere on the edge
            for (int cx = 0; cx < w; cx++) {
                for (int cz = 0; cz < d; cz++) {
                    if (cx != 0 && cz != 0 && cx != w - 1 && cz != d - 1) {
                        continue;
                    }
                    int col = cx * d + cz;
                    for (int h = hLo + nh - 1; h >= hLo; h--) {
                        if (valid(col, h)) {
                            int s = state(col, h);
                            parent[s] = s;
                            queue.add(s);
                            break;
                        }
                    }
                }
            }
        }
        while (!queue.isEmpty()) {
            int s = queue.poll();
            moves(s, false, n -> {
                if (parent[n] < 0) {
                    parent[n] = s;
                    queue.add(n);
                }
            });
        }
        return parent;
    }

    private static int depth(int[] parent, int s) {
        if (parent[s] < 0) {
            return -1;
        }
        int n = 0;
        while (parent[s] != s) {
            s = parent[s];
            n++;
        }
        return n;
    }

    // ── what the agent is told ───────────────────────────────────────────────────────────────────

    /** How many rooms one note names before it says "and N more". */
    public static final int MAX_SHOWN = 4;

    /**
     * Advice for the agent, or "" when every room can be walked into. Rooms that the SEALED check
     * already named are left out when {@code sealedAlreadyTold}, so the agent is not told twice.
     * Never a refusal: the last sentence says that leaving it is fine if it was meant.
     */
    public static String note(Report report, boolean sealedAlreadyTold) {
        if (report == null || report.tooBig()) {
            return "";
        }
        List<Room> tell = report.unreachable().stream()
                .filter(r -> !(sealedAlreadyTold && r.cause() == Cause.SEALED))
                .toList();
        if (tell.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nWALK-THROUGH — I walked in from outside the way he will "
                + "(two blocks tall, up a half step or a one-block jump, ladders and wooden doors yes, iron "
                + "doors no) and ");
        sb.append(tell.size() == 1 ? "one room cannot be reached on foot:" : tell.size() + " rooms cannot be "
                + "reached on foot:");
        int shown = 0;
        for (Room r : tell) {
            if (shown++ >= MAX_SHOWN) {
                sb.append("\n  - … and ").append(tell.size() - MAX_SHOWN).append(" more");
                break;
            }
            sb.append("\n  - ").append(r.describe()).append(" — ").append(why(r));
        }
        sb.append("\nFine if it is decoration (a solid turret, a sealed tower). If he should be able to go "
                + "in, fix it and build again.");
        return sb.toString();
    }

    private static String why(Room r) {
        String at = r.cut() == null ? "" : " Opening the wall at " + r.cut()[0] + "," + r.cut()[1] + ","
                + r.cut()[2] + " (two blocks tall: " + r.cut()[1] + " and " + (r.cut()[1] + 1)
                + ") would let him in.";
        return switch (r.cause()) {
            case SEALED -> "it has no way in at all." + at;
            case NO_WAY_UP -> "nothing climbs up to it: give it stairs, or a "
                    + "minecraft:ladder[facing=…] against a wall through a hole in its floor.";
            case LOW_OPENING -> "its way in is under two blocks tall, so he can only look through it." + at;
            case UNREACHABLE_STEP -> "its way in starts a step too high to climb or is blocked (a block "
                    + "and a half or more, a fence, an iron door)." + at;
        };
    }
}
