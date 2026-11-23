package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>Doors a child can open, put right as the building goes up.</b>
 *
 * <p>The scan behind: only 25 of 818 buildings placed any door block, and <b>55 of their 67 door
 * cells were malformed</b> — a {@code minecraft:oak_door} shape two tall with no {@code half}, which is
 * two LOWER halves; or an {@code iron_door}, which no hand opens (3 of the 4 builds that used one left
 * the room sealed). Neither needs the agent's judgement, so neither costs it a turn: this is a pure
 * rewrite of individual cells, applied by {@link Builder} as it places the plan and by every checker
 * that reads a plan, never written into the saved design. An edit re-renders the same rewrite from the
 * new plan, and {@link Builder#clearBuild} takes down exactly the cells this added.
 *
 * <h2>Three fixes, each only where the rule is unambiguous</h2>
 * <ol>
 *   <li><b>Door halves.</b> A vertical run of the same door with no {@code half} becomes lower, upper,
 *   lower, upper… from the bottom, with a {@code facing} across the wall it stands in (pointing in, the
 *   way a child walking in would have placed it) and paired hinges for a double door. A lone half with
 *   empty space above gets its upper half; the odd top cell of a three-tall run becomes air (a lone
 *   lower half pops off the first time anything next to it changes).</li>
 *   <li><b>Iron doors become wood</b>, the wood matching the wall, unless a button, lever, pressure
 *   plate or redstone is within two blocks — then the iron door is a mechanism and is left alone.</li>
 *   <li><b>A doorway on the ground floor gets a door</b> — only a 1- or 2-wide opening, 2 or 3 tall,
 *   with a solid jamb on each side and a lintel over it, in the outside wall of a roofed room; never in
 *   glass, never in water, never in a building whose name says animals or vehicles walk through it
 *   (a pen, stable, barn, garage, zoo…), and only when the wall matches a wood
 *   ({@link #woodFor}).</li>
 * </ol>
 */
public final class BuildDoors {

    private BuildDoors() {
    }

    /** What was changed, counted; {@code cells} is the rewrite itself, keyed like {@link ComposedPlan}. */
    public record Fixed(Map<Long, String> cells, int halves, int faced, int ironSwapped, int autoDoors,
                        int dropped) {

        public boolean isEmpty() {
            return cells.isEmpty();
        }

        static Fixed none() {
            return new Fixed(Map.of(), 0, 0, 0, 0, 0);
        }
    }

    /** Names that say something other than a person walks through the opening. */
    static final List<String> THROUGH_TRAFFIC = List.of("pen", "stable", "barn", "garage", "zoo", "coop",
            "kennel", "corral", "paddock", "hangar", "aviary", "pasture", "farm", "pig", "cow", "sheep",
            "horse", "chicken", "animal", "car", "train", "station", "dock", "boat", "arena", "tunnel");

    private static final String[] WOODS = {"dark_oak", "pale_oak", "spruce", "birch", "jungle", "acacia",
            "mangrove", "cherry", "bamboo", "crimson", "warped", "oak"};

    /**
     * The door that matches a wall block, or null when no rule says: wood → the same wood; stone and
     * deepslate → spruce; quartz, white and sandstone → birch; brick and terracotta → dark oak.
     */
    public static String woodFor(String block) {
        String b = BuildSupport.base(block);
        if (b.isEmpty()) {
            return null;
        }
        if (b.contains("red_sandstone")) {
            return "acacia";
        }
        for (String w : WOODS) {
            if (b.startsWith(w + "_") || b.contains("_" + w + "_") || b.equals(w)
                    || b.startsWith("stripped_" + w + "_")) {
                if (w.equals("oak") && b.contains("dark_oak")) {
                    continue;
                }
                return w;
            }
        }
        if (b.contains("quartz") || b.startsWith("white_") || b.contains("sandstone") || b.contains("calcite")
                || b.equals("snow_block") || b.contains("diorite") || b.contains("end_stone")) {
            return "birch";
        }
        if (b.contains("brick") && !b.contains("stone_brick") && !b.contains("deepslate")
                && !b.contains("blackstone") && !b.contains("tuff") && !b.contains("end_stone")) {
            return "dark_oak";
        }
        if (b.contains("terracotta")) {
            return "dark_oak";
        }
        if (b.contains("stone") || b.contains("deepslate") || b.contains("cobble") || b.contains("andesite")
                || b.contains("granite") || b.contains("tuff") || b.contains("basalt") || b.equals("bedrock")
                || b.contains("blackstone") || b.equals("obsidian") || b.contains("mud")) {
            return "spruce";
        }
        return null;
    }

    private static boolean isDoor(String block) {
        String b = BuildSupport.base(block);
        return b.endsWith("_door");
    }

    private static boolean isRedstoneControl(String block) {
        String b = BuildSupport.base(block);
        return b.endsWith("_button") || b.equals("lever") || b.endsWith("_pressure_plate")
                || b.startsWith("redstone") || b.equals("repeater") || b.equals("comparator")
                || b.equals("observer") || b.equals("daylight_detector") || b.equals("tripwire_hook")
                || b.equals("target");
    }

    private static boolean isGlass(String block) {
        String b = BuildSupport.base(block);
        return b.contains("glass") || b.endsWith("_bars") || b.equals("iron_bars");
    }

    /** A jamb or lintel: a full, solid block that is not glass, a door or a fence. */
    private static boolean frame(String block) {
        return block != null && BuildSupport.isFullFace(block) && !isGlass(block)
                && !BuildSupport.isAir(block) && !isDoor(block);
    }

    /** The rewrite for this plan: what {@link Builder} places over the plan's own blocks. */
    public static Fixed of(BuildPlan plan) {
        if (plan == null || plan.shapes().isEmpty()) {
            return Fixed.none();
        }
        Map<Long, String> c = new HashMap<>();
        Set<Long> planned = new java.util.HashSet<>();
        for (BuildShape s : plan.shapes()) {
            String block = BuildSupport.isAir(s.block()) ? null : s.block();
            s.forEachBlock((x, y, z) -> {
                long k = ComposedPlan.key(x, y, z);
                planned.add(k);
                if (block == null) {
                    c.remove(k);
                } else {
                    c.put(k, block);
                }
            });
        }
        if (c.isEmpty()) {
            return Fixed.none();
        }
        int[] box = bounds(c);
        Map<Long, String> out = new LinkedHashMap<>();
        int[] counts = new int[5];
        fixDoors(c, box, out, counts);
        autoDoors(plan, c, box, out, counts);
        return new Fixed(out, counts[0], counts[1], counts[2], counts[3], counts[4]);
    }

    /** The plan's blocks with the rewrite over them (null = air): what actually stands. */
    public static Map<Long, String> applied(BuildPlan plan, Fixed fixed) {
        Map<Long, String> c = WalkThrough.composeKeepingAir(plan);
        if (fixed != null) {
            for (Map.Entry<Long, String> e : fixed.cells().entrySet()) {
                c.put(e.getKey(), BuildSupport.isAir(e.getValue()) ? null : e.getValue());
            }
        }
        return c;
    }

    private static int[] bounds(Map<Long, String> c) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (long k : c.keySet()) {
            int x = ComposedPlan.kx(k);
            int y = ComposedPlan.ky(k);
            int z = ComposedPlan.kz(k);
            b[0] = Math.min(b[0], x);
            b[1] = Math.min(b[1], y);
            b[2] = Math.min(b[2], z);
            b[3] = Math.max(b[3], x);
            b[4] = Math.max(b[4], y);
            b[5] = Math.max(b[5], z);
        }
        return b;
    }

    private static String at(Map<Long, String> c, int x, int y, int z) {
        return c.get(ComposedPlan.key(x, y, z));
    }

    // ── 1 and 2: the door blocks the plan already has ───────────────────────────────────────────

    private static void fixDoors(Map<Long, String> c, int[] box, Map<Long, String> out, int[] counts) {
        // Bottom cells of every vertical run of the same door base.
        List<long[]> bottoms = new ArrayList<>();
        for (Map.Entry<Long, String> e : c.entrySet()) {
            if (!isDoor(e.getValue())) {
                continue;
            }
            long k = e.getKey();
            String below = at(c, ComposedPlan.kx(k), ComposedPlan.ky(k) - 1, ComposedPlan.kz(k));
            if (below == null || !BuildSupport.base(below).equals(BuildSupport.base(e.getValue()))) {
                bottoms.add(new long[]{k});
            }
        }
        double cx = (box[0] + box[3]) / 2.0;
        double cz = (box[2] + box[5]) / 2.0;
        for (long[] bk : bottoms) {
            long k = bk[0];
            int x = ComposedPlan.kx(k);
            int y = ComposedPlan.ky(k);
            int z = ComposedPlan.kz(k);
            String first = c.get(k);
            String base = BuildSupport.base(first);
            List<String> run = new ArrayList<>();
            for (int yy = y; ; yy++) {
                String b = at(c, x, yy, z);
                if (b == null || !BuildSupport.base(b).equals(base)) {
                    break;
                }
                run.add(b);
            }
            boolean allFormed = run.stream().allMatch(b -> BuildSupport.props(b).containsKey("half"));
            boolean ironToWood = base.equals("iron_door") && !nearControl(c, x, y, z, run.size());
            if (allFormed && !ironToWood && run.size() == 2
                    && "lower".equals(BuildSupport.props(run.get(0)).get("half"))
                    && "upper".equals(BuildSupport.props(run.get(1)).get("half"))) {
                continue;                                     // a proper door the agent made itself
            }
            Map<String, String> props = new LinkedHashMap<>(BuildSupport.props(first));
            String facing = props.get("facing");
            if (facing == null || BuildSupport.step(facing) == null) {
                facing = facingFor(c, x, y, z, cx, cz);
                counts[1]++;
            }
            String hinge = props.containsKey("hinge") ? props.get("hinge") : hingeFor(c, x, y, z, base, facing);
            String id = ironToWood ? "minecraft:" + ironWood(c, x, y, z, run.size()) + "_door"
                    : first.contains("[") ? first.substring(0, first.indexOf('[')) : first;
            if (ironToWood) {
                counts[2]++;
            }
            boolean malformed = !allFormed || run.size() != 2;
            if (malformed) {
                counts[0]++;
            }
            int n = run.size();
            for (int i = 0; i < n; i++) {
                boolean upper = (i % 2) == 1;
                long key = ComposedPlan.key(x, y + i, z);
                if (!upper && i == n - 1) {
                    // a lower half with nothing of its own above it
                    String above = at(c, x, y + i + 1, z);
                    if (above == null && !out.containsKey(ComposedPlan.key(x, y + i + 1, z))) {
                        out.put(key, door(id, facing, "lower", hinge, props));
                        out.put(ComposedPlan.key(x, y + i + 1, z), door(id, facing, "upper", hinge, props));
                    } else if (i > 0) {
                        out.put(key, "minecraft:air");      // the odd top of a run: a lintel of air
                        counts[4]++;
                    } else {
                        out.put(key, door(id, facing, "lower", hinge, props));
                    }
                    continue;
                }
                out.put(key, door(id, facing, upper ? "upper" : "lower", hinge, props));
            }
        }
    }

    private static String door(String id, String facing, String half, String hinge, Map<String, String> props) {
        StringBuilder sb = new StringBuilder(id).append("[facing=").append(facing).append(",half=").append(half)
                .append(",hinge=").append(hinge);
        for (Map.Entry<String, String> e : props.entrySet()) {
            String k = e.getKey();
            if (!k.equals("facing") && !k.equals("half") && !k.equals("hinge")) {
                sb.append(',').append(k).append('=').append(e.getValue());
            }
        }
        return sb.append(']').toString();
    }

    private static boolean nearControl(Map<Long, String> c, int x, int y, int z, int height) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= height + 1; dy++) {
                    String b = at(c, x + dx, y + dy, z + dz);
                    if (b != null && isRedstoneControl(b)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The wood of the wall an iron door stands in; spruce when the wall says nothing. */
    private static String ironWood(Map<Long, String> c, int x, int y, int z, int height) {
        Map<String, Integer> votes = new HashMap<>();
        for (int dy = 0; dy <= height; dy++) {
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                String b = at(c, x + d[0], y + dy, z + d[1]);
                String w = b == null || !frame(b) ? null : woodFor(b);
                if (w != null) {
                    votes.merge(w, 1, Integer::sum);
                }
            }
        }
        return votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse("spruce");
    }

    /**
     * Across the wall: with solid neighbours east and west the wall runs along x and the door faces
     * north or south, toward the building's middle (a child walking IN placed it); otherwise along z.
     */
    static String facingFor(Map<Long, String> c, int x, int y, int z, double cx, double cz) {
        boolean ew = frame(at(c, x - 1, y, z)) || frame(at(c, x + 1, y, z))
                || isDoor(at(c, x - 1, y, z)) || isDoor(at(c, x + 1, y, z));
        boolean ns = frame(at(c, x, y, z - 1)) || frame(at(c, x, y, z + 1))
                || isDoor(at(c, x, y, z - 1)) || isDoor(at(c, x, y, z + 1));
        boolean alongX = ew && !ns || (ew == ns && Math.abs(z - cz) >= Math.abs(x - cx));
        if (alongX) {
            return z <= cz ? "south" : "north";
        }
        return x <= cx ? "east" : "west";
    }

    /** The side a player facing this way has on his left: north→west, south→east, east→north, west→south. */
    private static int[] left(String facing) {
        return switch (facing) {
            case "north" -> new int[]{-1, 0};
            case "south" -> new int[]{1, 0};
            case "east" -> new int[]{0, -1};
            default -> new int[]{0, 1};
        };
    }

    /** A double door's two leaves hinge on their outer edges; a single door on its left. */
    private static String hingeFor(Map<Long, String> c, int x, int y, int z, String base, String facing) {
        int[] l = left(facing);
        String onLeft = at(c, x + l[0], y, z + l[1]);
        String onRight = at(c, x - l[0], y, z - l[1]);
        if (onRight != null && BuildSupport.base(onRight).equals(base)
                && !(onLeft != null && BuildSupport.base(onLeft).equals(base))) {
            return "left";                       // the leaf on the left of a pair
        }
        if (onLeft != null && BuildSupport.base(onLeft).equals(base)) {
            return "right";
        }
        return "left";
    }

    // ── 3: a door for a doorway the plan left open ─────────────────────────────────────────────

    private static boolean throughTraffic(BuildPlan plan) {
        StringBuilder words = new StringBuilder();
        words.append(plan.id()).append(' ').append(plan.name());
        for (BuildShape s : plan.shapes()) {
            words.append(' ').append(s.name());
        }
        String all = words.toString().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        for (String w : all.split("[^a-z]+")) {
            for (String t : THROUGH_TRAFFIC) {
                if (w.equals(t) || w.equals(t + "s") || w.equals(t + "es")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean water(String b) {
        String base = BuildSupport.base(b);
        return base.equals("water") || base.equals("bubble_column") || base.contains("seagrass")
                || base.contains("kelp") || "true".equals(BuildSupport.props(b).get("waterlogged"));
    }

    /** Is there anything over this column above y (a roof), within the plan? */
    private static boolean covered(Map<Long, String> c, int x, int y, int z, int top) {
        for (int yy = y; yy <= top; yy++) {
            String b = at(c, x, yy, z);
            if (b != null && BuildSupport.blocksBody(b)) {
                return true;
            }
        }
        return false;
    }

    private static boolean open(Map<Long, String> c, int x, int y, int z) {
        String b = at(c, x, y, z);
        return b == null || !BuildSupport.blocksBody(b);
    }

    private static void autoDoors(BuildPlan plan, Map<Long, String> c, int[] box, Map<Long, String> out,
                                  int[] counts) {
        if (throughTraffic(plan)) {
            return;
        }
        int groundTop = box[1] > 0 ? box[1] - 1 : -1;
        Set<Long> used = new java.util.HashSet<>(out.keySet());
        for (int y = groundTop + 1; y <= groundTop + 2; y++) {
            for (int x = box[0]; x <= box[3]; x++) {
                for (int z = box[2]; z <= box[5]; z++) {
                    for (int axis = 0; axis < 2; axis++) {
                        tryDoorway(plan, c, box, out, counts, used, x, y, z, axis == 0, groundTop);
                    }
                }
            }
        }
    }

    /**
     * An opening whose first (lowest, smallest-coordinate) cell is (x,y,z), in a wall running along x
     * ({@code alongX}) or along z. Places a door when every condition in the class comment holds.
     */
    private static void tryDoorway(BuildPlan plan, Map<Long, String> c, int[] box, Map<Long, String> out,
                                   int[] counts, Set<Long> used, int x, int y, int z, boolean alongX,
                                   int groundTop) {
        int ax = alongX ? 1 : 0;
        int az = alongX ? 0 : 1;
        int bx = alongX ? 0 : 1;      // across the wall
        int bz = alongX ? 1 : 0;
        // must start just past a jamb, and be empty, standing on something
        if (!frame(at(c, x - ax, y, z - az)) || !frame(at(c, x - ax, y + 1, z - az))) {
            return;
        }
        if (at(c, x, y, z) != null || used.contains(ComposedPlan.key(x, y, z))) {
            return;
        }
        String floor = at(c, x, y - 1, z);
        if (!(y - 1 <= groundTop && floor == null) && (floor == null || !BuildSupport.isFloor(floor))) {
            return;
        }
        // width: empty cells along the wall until the far jamb
        int width = 0;
        while (width < 3 && at(c, x + ax * width, y, z + az * width) == null) {
            width++;
        }
        if (width < 1 || width > 2 || !frame(at(c, x + ax * width, y, z + az * width))) {
            return;
        }
        // height: the same for every column, 2 or 3, closed by a solid lintel; jambs as tall as the opening
        int height = -1;
        for (int i = 0; i < width; i++) {
            int px = x + ax * i;
            int pz = z + az * i;
            int hh = 0;
            while (hh < 5 && at(c, px, y + hh, pz) == null) {
                hh++;
            }
            if (height < 0) {
                height = hh;
            } else if (height != hh) {
                return;
            }
            if (!frame(at(c, px, y + hh, pz))) {
                return;
            }
        }
        if (height < 2 || height > 3) {
            return;
        }
        for (int dy = 0; dy < height; dy++) {
            if (!frame(at(c, x - ax, y + dy, z - az)) || !frame(at(c, x + ax * width, y + dy, z + az * width))) {
                return;
            }
        }
        // one side inside (roofed), the other outside (open to the sky within a few steps — a roof's eaves
        // overhang the door), both open to walk in
        int top = box[4];
        int sideIn = 0;
        int sideOut = 0;
        for (int s : new int[]{1, -1}) {
            for (int i = 0; i < width; i++) {
                int px = x + bx * s + ax * i;
                int pz = z + bz * s + az * i;
                if (!open(c, px, y, pz) || !open(c, px, y + 1, pz)) {
                    return;
                }
            }
            if (reachesSky(c, x, y, z, bx * s, bz * s, top)) {
                if (sideOut != 0) {
                    return;                          // open both ways: a gap in a garden wall, an arch
                }
                sideOut = s;
            } else if (covered(c, x + bx * s, y + height, z + bz * s, top)) {
                sideIn = s;
            }
        }
        if (sideIn == 0 || sideOut == 0) {
            return;
        }
        // never in or by water
        for (int i = -1; i <= width; i++) {
            for (int s = -1; s <= 1; s++) {
                for (int dy = -1; dy <= height; dy++) {
                    String b = at(c, x + ax * i + bx * s, y + dy, z + az * i + bz * s);
                    if (b != null && water(b)) {
                        return;
                    }
                }
            }
        }
        // the room behind it has walls: not a porch, a gazebo or a covered walk
        if (!walled(c, x + bx * sideIn, y, z + bz * sideIn, ax, az, bx * sideIn, bz * sideIn, box)) {
            return;
        }
        String wood = woodFor(at(c, x - ax, y, z - az));
        String wood2 = woodFor(at(c, x + ax * width, y, z + az * width));
        if (wood == null || (wood2 != null && !wood2.equals(wood))) {
            return;
        }
        String facing = sideIn > 0 ? (alongX ? "south" : "east") : (alongX ? "north" : "west");
        String id = "minecraft:" + wood + "_door";
        for (int i = 0; i < width; i++) {
            int px = x + ax * i;
            int pz = z + az * i;
            String hinge = width == 1 ? "left" : leftCell(facing, i, ax, az) ? "left" : "right";
            out.put(ComposedPlan.key(px, y, pz), door(id, facing, "lower", hinge, Map.of()));
            out.put(ComposedPlan.key(px, y + 1, pz), door(id, facing, "upper", hinge, Map.of()));
            used.add(ComposedPlan.key(px, y, pz));
            used.add(ComposedPlan.key(px, y + 1, pz));
        }
        counts[3]++;
    }

    /** In a 2-wide opening, is cell i (0 = smaller coordinate) the one on the left of a child facing in? */
    private static boolean leftCell(String facing, int i, int ax, int az) {
        int[] l = left(facing);
        int along = l[0] * ax + l[1] * az;       // +1 when "left" is toward bigger coordinates
        return along > 0 ? i == 1 : i == 0;
    }

    /** Walking straight out through the opening, is there open sky within {@link #SKY_STEPS} cells? */
    static final int SKY_STEPS = 4;

    private static boolean reachesSky(Map<Long, String> c, int x, int y, int z, int dx, int dz, int top) {
        for (int k = 1; k <= SKY_STEPS; k++) {
            int px = x + dx * k;
            int pz = z + dz * k;
            if (!open(c, px, y, pz) || !open(c, px, y + 1, pz)) {
                return false;
            }
            if (!covered(c, px, y + 2, pz, top)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The room behind the doorway has walls: looking left, right and straight in from just inside, at
     * both heights of a body, each way meets something solid inside the building's bounds.
     */
    private static boolean walled(Map<Long, String> c, int x, int y, int z, int ax, int az, int inX, int inZ,
                                  int[] box) {
        for (int dy = 0; dy <= 1; dy++) {
            for (int[] d : new int[][]{{ax, az}, {-ax, -az}, {inX, inZ}}) {
                boolean hit = false;
                for (int px = x, pz = z; px >= box[0] && px <= box[3] && pz >= box[2] && pz <= box[5];
                     px += d[0], pz += d[1]) {
                    if (!open(c, px, y + dy, pz) || isDoor(at(c, px, y + dy, pz))) {
                        hit = true;                  // a wall, or a door in one
                        break;
                    }
                }
                if (!hit) {
                    return false;
                }
            }
        }
        return true;
    }

    // ── what the agent is told ─────────────────────────────────────────────────────────────────

    /** One line saying what was changed as it went up, or "" when nothing was. Never silent. */
    public static String note(Fixed f) {
        if (f == null || f.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (f.halves() > 0) {
            parts.add(f.halves() + (f.halves() == 1 ? " door" : " doors") + " given proper lower and upper halves"
                    + " (a door shape two tall with no half is two bottom halves)");
        }
        if (f.ironSwapped() > 0) {
            parts.add(f.ironSwapped() + " iron " + (f.ironSwapped() == 1 ? "door" : "doors")
                    + " made wooden (no hand opens an iron door without a button)");
        }
        if (f.autoDoors() > 0) {
            parts.add(f.autoDoors() + (f.autoDoors() == 1 ? " doorway" : " doorways") + " given a matching door");
        }
        if (parts.isEmpty()) {
            return "";
        }
        return "\n\nDOORS — as it went up: " + String.join("; ", parts) + ". Your saved design is unchanged; "
                + "to put a door yourself, give a door shape height 2 and I will set its halves.";
    }
}
