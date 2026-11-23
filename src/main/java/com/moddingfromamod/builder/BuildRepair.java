package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>One-block faults in a building's ways in, put right as it is built</b> (where there
 * is exactly one small right fix, the engine repairs it, it does not only advise).
 *
 * <p>Real agent builds are often one block off: a doorway cut one block tall, or through one block of a two-block
 * wall; a ladder that stops a rung under the floor it leads to, or under a floor with no hole. {@link WalkThrough}
 * already finds the room nobody can reach and tells the agent. This tries the small fixes the agent's own shapes
 * point at and <b>keeps one only when the walk says so</b>: the room becomes reachable, and no floor that was
 * reachable is lost. A fix is at most {@link #MOST_CELLS} cells. A room with no opening at all is left alone: it may
 * be a solid turret, and that stays advice.
 *
 * <p>A placement rewrite, like {@link BuildDoors} and {@link BuildWaterEntry}: laid over the plan as it is placed,
 * never saved into it, and told to the agent. Pure. {@code -Ptestprops=buildrepair=off} is the A/B seam.
 */
public final class BuildRepair {

    /** The most cells one fix changes. */
    public static final int MOST_CELLS = 4;
    /** The most fixes on one building. */
    public static final int MOST_FIXES = 6;
    /** The most trial walks spent on one building. */
    static final int MOST_TRIALS = 60;
    /** A trial walks the whole building: a very big one gets fewer (trials x cells stays under this), never under 6. */
    static final long TRIAL_BUDGET = 4_000_000L;

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty("moddingfromamod.buildrepair"));
    }

    /** One accepted fix: its kind, the cells it sets (null is air) and where. */
    public record Fix(String kind, Map<Long, String> cells, int x, int y, int z) {
    }

    public record Fixed(Map<Long, String> cells, List<Fix> fixes) {
        public boolean isEmpty() {
            return fixes.isEmpty();
        }

        static Fixed none() {
            return new Fixed(Map.of(), List.of());
        }
    }

    private BuildRepair() {
    }

    /** The key {@link Fixed#cells} uses for a plan cell. */
    public static long key(int x, int y, int z) {
        return ComposedPlan.key(x, y, z);
    }

    /** A door's top half, from its bottom half. */
    static String upperHalf(String door) {
        if (door.contains("half=lower")) {
            return door.replace("half=lower", "half=upper");
        }
        return door.contains("[") ? door.replace("[", "[half=upper,") : door + "[half=upper]";
    }

    /** The fixes for this plan as it would be placed (doors and water entries already laid over it). */
    public static Fixed of(BuildPlan plan) {
        if (plan == null || !enabled()) {
            return Fixed.none();
        }
        Object[] was = last;   // one build asks several times (the placing, the walk, the taking down): walk it once
        if (was != null && was[0] == plan) {
            return (Fixed) was[1];
        }
        Map<Long, String> cells = BuildDoors.applied(plan, BuildDoors.of(plan));
        BuildWaterEntry.apply(cells, BuildWaterEntry.fix(plan));
        Fixed f = compute(cells);
        last = new Object[] {plan, f};
        return f;
    }

    private static volatile Object[] last;

    /** Lay the fixes over composed cells. */
    public static void apply(Map<Long, String> cells, Fixed fixed) {
        if (fixed != null) {
            cells.putAll(fixed.cells());
        }
    }

    private record Score(boolean ok, long reached, long unreached, WalkThrough.Report report) {
    }

    private static Score score(Map<Long, String> cells) {
        WalkThrough w = WalkThrough.over(cells, WalkThrough.BODY);
        if (w == null) {
            return new Score(false, 0, 0, null);
        }
        WalkThrough.Report r = w.report();
        if (r.tooBig()) {
            return new Score(false, 0, 0, r);
        }
        long reached = 0;
        long unreached = 0;
        for (WalkThrough.Room room : r.rooms()) {
            if (room.reached()) {
                reached += room.columns();
            } else {
                unreached += room.columns();
            }
        }
        return new Score(true, reached, unreached, r);
    }

    static Fixed compute(Map<Long, String> start) {
        Score now = score(start);
        if (!now.ok() || now.unreached() == 0) {
            return Fixed.none();
        }
        Map<Long, String> cells = new HashMap<>(start);
        Map<Long, String> changed = new LinkedHashMap<>();
        List<Fix> fixes = new ArrayList<>();
        int trials = 0;
        int most = (int) Math.max(6, Math.min(MOST_TRIALS, TRIAL_BUDGET / Math.max(1, start.size())));
        boolean progress = true;
        while (progress && fixes.size() < MOST_FIXES && now.unreached() > 0) {
            progress = false;
            search:
            for (WalkThrough.Room room : now.report().unreachable()) {
                for (Fix c : candidates(cells, room)) {
                    if (trials++ >= most) {
                        break search;
                    }
                    Map<Long, String> trial = new HashMap<>(cells);
                    trial.putAll(c.cells());
                    Score s = score(trial);
                    if (s.ok() && s.unreached() < now.unreached() && s.reached() >= now.reached()
                            && s.reached() + s.unreached() >= now.reached() + now.unreached()) {
                        cells = trial;
                        changed.putAll(c.cells());
                        fixes.add(c);
                        now = s;
                        progress = true;
                        break search;
                    }
                }
            }
        }
        return fixes.isEmpty() ? Fixed.none() : new Fixed(changed, List.copyOf(fixes));
    }

    // ── candidates ───────────────────────────────────────────────────────────────────────────────

    private static List<Fix> candidates(Map<Long, String> cells, WalkThrough.Room room) {
        List<Fix> out = new ArrayList<>();
        int[] cut = room.cut();
        if (cut != null) {
            String why = intent(cells, cut);
            if (why != null) {
                Map<Long, String> c = new LinkedHashMap<>();
                String feet = cells.get(ComposedPlan.key(cut[0], cut[1], cut[2]));
                for (int dy = 0; dy <= 1; dy++) {
                    if (solid(cells, cut[0], cut[1] + dy, cut[2])) {
                        // Over a door one block tall goes the door's top half, not a hole.
                        c.put(ComposedPlan.key(cut[0], cut[1] + dy, cut[2]), dy == 1
                                && WalkThrough.kindOf(feet) == WalkThrough.DOOR && BuildSupport.base(feet).endsWith("_door")
                                ? upperHalf(feet) : null);
                    }
                }
                if (!c.isEmpty()) {
                    out.add(new Fix(why, c, cut[0], cut[1], cut[2]));
                }
            }
        }
        if (room.cause() == WalkThrough.Cause.LOW_OPENING) {
            out.addAll(lowDoorways(cells, room));
        }
        out.addAll(ladders(cells, room));
        return out;
    }

    private static boolean solid(Map<Long, String> cells, int x, int y, int z) {
        byte k = WalkThrough.kindOf(cells.get(ComposedPlan.key(x, y, z)));
        return k != WalkThrough.FREE && k != WalkThrough.CLIMB && k != WalkThrough.DOOR && k != WalkThrough.TRAP
                && k != WalkThrough.WATER;
    }

    private static boolean cutAir(Map<Long, String> cells, int x, int y, int z) {
        long k = ComposedPlan.key(x, y, z);
        return cells.containsKey(k) && cells.get(k) == null;
    }

    /**
     * Did the agent mean an opening where the walker says one cut would let him in? Three signs, each from the
     * agent's own shapes: the opening is there and one block tall; a doorway was cut into the wall beside it and
     * stops a block short (a recess one wide, walled on both sides); or a door hangs against it. Null: no sign.
     */
    static String intent(Map<Long, String> cells, int[] cut) {
        int x = cut[0];
        int y = cut[1];
        int z = cut[2];
        if (solid(cells, x, y, z) != solid(cells, x, y + 1, z)) {
            return "doorway made two blocks tall";
        }
        for (int[] s : SIDES) {
            int nx = x + s[0];
            int nz = z + s[1];
            byte k = WalkThrough.kindOf(cells.get(ComposedPlan.key(nx, y, nz)));
            byte k1 = WalkThrough.kindOf(cells.get(ComposedPlan.key(nx, y + 1, nz)));
            if (k == WalkThrough.DOOR || k1 == WalkThrough.DOOR) {
                return "wall behind a door opened";
            }
            // A recess: cut air one wide (walled on both sides across the way through), feet and head.
            if (cutAir(cells, nx, y, nz) && cutAir(cells, nx, y + 1, nz)
                    && solid(cells, nx + s[1], y, nz + s[0]) && solid(cells, nx - s[1], y, nz - s[0])
                    && solid(cells, nx + s[1], y + 1, nz + s[0]) && solid(cells, nx - s[1], y + 1, nz - s[0])) {
                return "doorway cut all the way through";
            }
        }
        return null;
    }

    /**
     * The commonest fault of all: a doorway cut two tall from the GROUND into a room whose floor is one block up
     * (a hollow box brings its own floor), so at the room's floor the opening is one block tall. The opening in the
     * room's wall, open at his feet and solid at his head, gets its head block taken out.
     */
    private static List<Fix> lowDoorways(Map<Long, String> cells, WalkThrough.Room room) {
        List<Fix> out = new ArrayList<>();
        int y = room.floorY();
        for (int x = room.minX() - 2; x <= room.maxX() + 2; x++) {
            for (int z = room.minZ() - 2; z <= room.maxZ() + 2; z++) {
                boolean inside = x >= room.minX() && x <= room.maxX() && z >= room.minZ() && z <= room.maxZ();
                if (inside || solid(cells, x, y, z) || !solid(cells, x, y + 1, z)) {
                    continue;
                }
                // In a wall: solid on both sides across the way through, at his feet.
                boolean alongX = solid(cells, x - 1, y, z) && solid(cells, x + 1, y, z);
                boolean alongZ = solid(cells, x, y, z - 1) && solid(cells, x, y, z + 1);
                if (!alongX && !alongZ) {
                    continue;
                }
                Map<Long, String> c = new LinkedHashMap<>();
                String feet = cells.get(ComposedPlan.key(x, y, z));
                String under = cells.get(ComposedPlan.key(x, y - 1, z));
                if (WalkThrough.kindOf(feet) == WalkThrough.DOOR && WalkThrough.kindOf(under) == WalkThrough.DOOR) {
                    // A door hangs in it, its lower half sunk in the floor: the whole door goes up one, the floor closes.
                    String floor = cells.get(ComposedPlan.key(alongX ? x - 1 : x, y - 1, alongX ? z : z - 1));
                    c.put(ComposedPlan.key(x, y + 1, z), feet);
                    c.put(ComposedPlan.key(x, y, z), under);
                    c.put(ComposedPlan.key(x, y - 1, z), floor);
                    out.add(new Fix("door raised to the room's floor", c, x, y, z));
                    c = new LinkedHashMap<>();
                }
                if (WalkThrough.kindOf(feet) == WalkThrough.DOOR && BuildSupport.base(feet).endsWith("_door")) {
                    // A door one block tall: its top half goes over it. Half a door alone breaks at the first update
                    // and leaves an open hole (a creature kept in the hall walked out of one, in Gate B).
                    c.put(ComposedPlan.key(x, y + 1, z), upperHalf(feet));
                    out.add(new Fix("door made two blocks tall", c, x, y + 1, z));
                    continue;
                }
                c.put(ComposedPlan.key(x, y + 1, z), null);
                out.add(new Fix("doorway raised to the room's floor", c, x, y + 1, z));
            }
        }
        return out;
    }

    /**
     * A ladder near the room that stops short: carried on up (through a floor with no hole, if that is what stops
     * it) or on down, one to three rungs. Nearest the room first.
     */
    private static List<Fix> ladders(Map<Long, String> cells, WalkThrough.Room room) {
        // Ladder columns: (x,z) → y → block.
        Map<Long, TreeMap<Integer, String>> cols = new HashMap<>();
        for (Map.Entry<Long, String> e : cells.entrySet()) {
            if (e.getValue() != null && BuildSupport.base(e.getValue()).equals("ladder")) {
                long k = e.getKey();
                cols.computeIfAbsent(ComposedPlan.key(ComposedPlan.kx(k), 0, ComposedPlan.kz(k)), q -> new TreeMap<>())
                        .put(ComposedPlan.ky(k), e.getValue());
            }
        }
        List<Fix> out = new ArrayList<>();
        List<long[]> order = new ArrayList<>();
        for (Long k : cols.keySet()) {
            int x = ComposedPlan.kx(k);
            int z = ComposedPlan.kz(k);
            int dx = Math.max(0, Math.max(room.minX() - x, x - room.maxX()));
            int dz = Math.max(0, Math.max(room.minZ() - z, z - room.maxZ()));
            if (dx + dz <= 3) {
                order.add(new long[] {dx + dz, k});
            }
        }
        order.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        for (long[] o : order) {
            TreeMap<Integer, String> col = cols.get(o[1]);
            int x = ComposedPlan.kx(o[1]);
            int z = ComposedPlan.kz(o[1]);
            int top = col.lastKey();
            int bottom = col.firstKey();
            String rung = col.get(top);
            // Up: only a ladder that already climbs to just under this room's floor.
            for (int n = 1; n <= 3 && top >= room.floorY() - 4 && top < room.floorY(); n++) {
                Map<Long, String> c = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) {
                    c.put(ComposedPlan.key(x, top + i, z), rung);
                }
                out.add(new Fix("ladder carried up to the floor it leads to", c, x, top + 1, z));
            }
            for (int n = 1; n <= 3; n++) {
                Map<Long, String> c = new LinkedHashMap<>();
                boolean open = true;
                for (int i = 1; i <= n; i++) {
                    open &= !solid(cells, x, bottom - i, z);
                    c.put(ComposedPlan.key(x, bottom - i, z), rung);
                }
                if (open) {
                    out.add(new Fix("ladder carried down to the floor it starts from", c, x, bottom - n, z));
                }
            }
        }
        return out;
    }

    // ── what the agent is told ───────────────────────────────────────────────────────────────────

    public static String note(Fixed f) {
        if (f == null || f.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nI FIXED " + f.fixes().size() + " small thing"
                + (f.fixes().size() == 1 ? "" : "s") + " so he can walk into every room these lead to (the plan you sent is "
                + "kept as you wrote it; this is how it was placed):");
        for (Fix x : f.fixes()) {
            sb.append("\n  - ").append(x.kind()).append(" at ").append(x.x()).append(',').append(x.y()).append(',')
                    .append(x.z()).append(" (").append(x.cells().size()).append(x.cells().size() == 1 ? " block)" : " blocks)");
        }
        sb.append("\nSend the shapes that way next time and nothing needs fixing.");
        return sb.toString();
    }
}
