package com.moddingfromamod.builder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>Can every part of a building stand where it was put? </b>
 *
 * <p>A ladder needs a wall behind it, a torch needs a floor, sand and scaffolding need something
 * under them. Minecraft enforces every one of those silently: scaffolding with nothing under it
 * turns into a falling block the tick it is placed, sand drops, and a ladder with no wall behind
 * it pops off the first time anything next to it changes. The agent cannot see its own building,
 * so nothing it can do tells it that a column it called a ladder will not climb.
 *
 * <p>Measured before this existed (an earlier issue's census, 61 plans): the library's own <i>Secret
 * Treehouse</i> climbed on three scaffolding columns, two of them standing in mid-air the moment
 * the house was aimed at anything but flat ground; the <i>Underwater Dome</i>'s 22-tall ladder had
 * nothing behind 7 of its cells; and the instruction the agent is given ("cut a hole through each
 * floor and put a ladder in it") builds a ladder in the middle of a hole — no wall, and a bare
 * {@code minecraft:ladder} faces north whatever is around it.
 *
 * <h2>About the DESIGN, never the placement</h2>
 * Everything is judged against the plan's own blocks. A block on the plan's <b>lowest layer</b>
 * is taken to rest on the ground, because what is under the build is a question about WHERE it
 * was put, and that is an earlier issue's footing rule, not this class's. So a clean template is always
 * clean, wherever it lands.
 *
 * <h2>Lenient on purpose</h2>
 * "Is that face sturdy?" is Minecraft's question and this class has no Minecraft. It names the
 * blocks that are certainly NOT a full face (air, liquids, leaves, panes, fences, slabs,
 * plants, other attachments…) and treats everything else as one. A false alarm costs the agent a
 * turn over a building that was fine; a miss costs what it cost before this existed. Unsure goes
 * to "fine".
 *
 * <p>Pure and Minecraft-free: all of it is a unit test, and {@code StarterSupportSweepTest} runs it
 * over every starter and every real build.
 */
public final class BuildSupport {

    /** How many problems the note spells out. The count is always given whole. */
    public static final int MAX_SHOWN = 6;

    /** Scaffolding holds up to this many blocks sideways from a supported piece. */
    static final int SCAFFOLD_REACH = 6;

    /** What kind of support a block needs. */
    public enum Need { WALL, FLOOR, BELOW_SOLID, SCAFFOLD, EXIT }

    /** One block that cannot stand. Coordinates are the plan's own offsets. */
    public record Problem(int x, int y, int z, String block, Need need, String shape) {
        public String describe() {
            String what = switch (need) {
                case WALL -> "has no full block behind it (a " + base(block)
                        + " hangs on the block on the side it faces AWAY from)";
                case FLOOR -> "has nothing to stand on";
                case BELOW_SOLID -> "has nothing under it, so it falls";
                case SCAFFOLD -> "has nothing under it within " + SCAFFOLD_REACH
                        + " blocks, so it falls";
                case EXIT -> "is the TOP of a ladder with a solid block right above it, so "
                        + "nobody can climb off it — his head hits the ceiling two blocks short";
            };
            return base(block) + " at dx" + x + " dy" + y + " dz" + z
                    + (shape == null || shape.isEmpty() ? "" : " (" + shape + ")") + " " + what;
        }
    }

    /** The result of {@link #autoFace}: the plan with facings filled in, and what was done. */
    public record Faced(BuildPlan plan, List<String> turned, List<String> unbacked) {
    }

    private BuildSupport() {
    }

    // ===================== block classes =====================

    private static final Set<String> GRAVITY = Set.of(
            "sand", "red_sand", "gravel", "suspicious_sand", "suspicious_gravel", "anvil",
            "chipped_anvil", "damaged_anvil", "dragon_egg");

    /** Hangs on a wall, by its {@code facing}: the support is on the opposite side. */
    private static final Set<String> WALL_HUNG = Set.of(
            "ladder", "wall_torch", "soul_wall_torch", "redstone_wall_torch", "tripwire_hook");

    /** Faces a block with no facing given would default to (Minecraft's own default: north). */
    private static final String DEFAULT_FACING = "north";

    /** Free-standing things that need a floor to stand on. */
    private static final Set<String> FLOOR_ONLY = Set.of(
            "torch", "soul_torch", "redstone_torch", "redstone_wire", "repeater", "comparator",
            "rail", "powered_rail", "detector_rail", "activator_rail", "snow", "short_grass",
            "tall_grass", "fern", "large_fern", "dandelion", "poppy", "blue_orchid", "allium",
            "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
            "oxeye_daisy", "cornflower", "lily_of_the_valley", "torchflower", "wither_rose",
            "sunflower", "lilac", "rose_bush", "peony", "dead_bush", "sweet_berry_bush",
            "brown_mushroom", "red_mushroom", "sugar_cane", "cactus", "bamboo", "lily_pad",
            "pink_petals", "wildflowers", "leaf_litter");

    /** Blocks whose face a ladder cannot hang on — not a full, sturdy side. */
    private static final Set<String> NOT_FULL = Set.of(
            "air", "cave_air", "void_air", "water", "lava", "glass_pane", "iron_bars", "chain",
            "iron_chain", "ladder", "scaffolding", "lantern", "soul_lantern", "lever", "cobweb",
            "vine", "flower_pot", "bell", "end_rod", "lightning_rod", "chest", "trapped_chest",
            "ender_chest", "campfire", "soul_campfire", "cake", "conduit", "bamboo", "cactus",
            "snow", "hopper", "brewing_stand", "enchanting_table", "anvil", "chipped_anvil",
            "damaged_anvil", "pointed_dripstone", "dragon_egg", "fire", "soul_fire", "barrier",
            "light", "structure_void", "bubble_column", "kelp", "kelp_plant", "seagrass",
            "tall_seagrass", "lectern", "grindstone", "stonecutter", "dirt_path", "farmland",
            "daylight_detector", "sculk_sensor", "decorated_pot", "candle");

    /** Never a floor either: you fall through, or it is not there. */
    private static final Set<String> NO_FLOOR = Set.of(
            "air", "cave_air", "void_air", "water", "lava", "fire", "soul_fire", "light",
            "structure_void", "bubble_column", "short_grass", "tall_grass", "fern", "snow",
            "seagrass", "tall_seagrass", "kelp", "kelp_plant", "vine", "cobweb");

    /** The block's id without namespace or state: {@code minecraft:oak_door[half=upper]} → oak_door. */
    static String base(String block) {
        if (block == null) {
            return "";
        }
        String b = block.toLowerCase(Locale.ROOT).trim();
        int bracket = b.indexOf('[');
        if (bracket >= 0) {
            b = b.substring(0, bracket);
        }
        int colon = b.indexOf(':');
        return colon >= 0 ? b.substring(colon + 1) : b;
    }

    /** {@code [a=b,c=d]} → map; empty when there is no state. */
    static Map<String, String> props(String block) {
        Map<String, String> out = new LinkedHashMap<>();
        if (block == null) {
            return out;
        }
        int open = block.indexOf('[');
        int close = block.lastIndexOf(']');
        if (open < 0 || close <= open) {
            return out;
        }
        for (String kv : block.substring(open + 1, close).split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                out.put(kv.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                        kv.substring(eq + 1).trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    static boolean isAir(String block) {
        String b = base(block);
        return b.equals("air") || b.equals("cave_air") || b.equals("void_air");
    }

    static boolean isGravity(String block) {
        String b = base(block);
        return GRAVITY.contains(b) || b.endsWith("_concrete_powder");
    }

    /** Is this a block whose side a ladder or a wall torch can hang on? */
    static boolean isFullFace(String block) {
        if (block == null) {
            return false;
        }
        String b = base(block);
        if (NOT_FULL.contains(b)) {
            return false;
        }
        if (b.endsWith("_slab")) {
            return "double".equals(props(block).get("type"));
        }
        return !(b.endsWith("_leaves") || b.endsWith("_pane") || b.endsWith("_fence")
                || b.endsWith("_fence_gate") || b.endsWith("_wall") || b.endsWith("_door")
                || b.endsWith("_trapdoor") || b.endsWith("_carpet") || b.endsWith("_button")
                || b.endsWith("_sign") || b.endsWith("_banner") || b.endsWith("_bed")
                || b.endsWith("_pressure_plate") || b.endsWith("_torch") || b.equals("torch")
                || b.endsWith("_candle") || b.endsWith("_sapling") || b.endsWith("_head")
                || b.endsWith("_skull") || b.endsWith("_rail") || b.equals("rail")
                || b.endsWith("_coral") || b.endsWith("_coral_fan") || b.endsWith("_bars")
                || b.endsWith("_chain") || b.endsWith("_lantern") || b.endsWith("_shelf")
                || FLOOR_ONLY.contains(b));
    }

    /** Can something stand on top of this? Far more lenient than a full face: a slab is a floor. */
    static boolean isFloor(String block) {
        if (block == null) {
            return false;
        }
        String b = base(block);
        if (NO_FLOOR.contains(b)) {
            return false;
        }
        return !(FLOOR_ONLY.contains(b) || b.endsWith("_carpet") || b.endsWith("_pressure_plate")
                || b.endsWith("_button") || b.endsWith("_sapling") || b.equals("torch")
                || b.endsWith("_torch") || b.equals("ladder"));
    }

    /** Things a body walks or climbs straight through. */
    private static final Set<String> PASSABLE = Set.of(
            "air", "cave_air", "void_air", "water", "ladder", "vine", "scaffolding", "light",
            "structure_void", "cobweb", "snow", "lever", "redstone_wire", "rail", "powered_rail",
            "detector_rail", "activator_rail", "tripwire", "tripwire_hook", "short_grass",
            "tall_grass", "fern", "large_fern", "seagrass", "tall_seagrass", "kelp", "kelp_plant",
            "sugar_cane", "bubble_column", "fire", "soul_fire");

    /** Does this block stop a player's body (his head under a ceiling, his feet on a floor)? */
    static boolean blocksBody(String block) {
        if (block == null) {
            return false;
        }
        String b = base(block);
        if (PASSABLE.contains(b) || b.equals("torch") || b.endsWith("_torch")
                || b.endsWith("_door") || b.endsWith("_trapdoor") || b.endsWith("_fence_gate")
                || b.endsWith("_carpet") || b.endsWith("_pressure_plate") || b.endsWith("_button")
                || b.endsWith("_sign") || b.endsWith("_banner") || b.endsWith("_sapling")
                || b.endsWith("_tulip")) {
            return false;
        }
        return !(FLOOR_ONLY.contains(b) && !b.equals("cactus") && !b.equals("bamboo"));
    }

    /**
     * The top cell of every ladder column whose climber's head would hit a block: the two cells
     * above the top must let a body through, or he climbs into a ceiling and can go no further.
     * Measured 's smoke: two of five real grants ran a ladder up a trunk or a shaft into
     * the floor above it with no hole, and a player stopped two blocks short of the top.
     */
    static List<Problem> blockedTops(Composed c) {
        List<Problem> out = new ArrayList<>();
        for (Map.Entry<Long, String> e : c.blocks.entrySet()) {
            if (!base(e.getValue()).equals("ladder")) {
                continue;
            }
            long k = e.getKey();
            int x = kx(k);
            int y = ky(k);
            int z = kz(k);
            String above = c.at(x, y + 1, z);
            if (above != null && base(above).equals("ladder")) {
                continue;                       // not a top
            }
            if (blocksBody(above) || blocksBody(c.at(x, y + 2, z))) {
                out.add(new Problem(x, y, z, e.getValue(), Need.EXIT, c.shapeOf.get(k)));
            }
        }
        return out;
    }

    /** What a block needs, or null when it stands on its own. */
    static Need needOf(String block) {
        String b = base(block);
        if (b.isEmpty() || isAir(block)) {
            return null;
        }
        if (b.equals("scaffolding")) {
            return Need.SCAFFOLD;
        }
        if (isGravity(block)) {
            return Need.BELOW_SOLID;
        }
        if (WALL_HUNG.contains(b) || b.endsWith("_wall_sign") || b.endsWith("_wall_banner")
                || b.endsWith("_wall_hanging_sign")) {
            return Need.WALL;
        }
        if (b.equals("lever") || b.endsWith("_button")) {
            String face = props(block).getOrDefault("face", "wall");
            return face.equals("floor") ? Need.FLOOR : face.equals("ceiling") ? null : Need.WALL;
        }
        if (FLOOR_ONLY.contains(b) || b.endsWith("_door") || b.endsWith("_carpet")
                || b.endsWith("_pressure_plate") || b.endsWith("_sapling") || b.endsWith("_candle")
                || b.equals("candle") || b.endsWith("_tulip")
                || (b.endsWith("_sign") && !b.endsWith("_hanging_sign"))
                || (b.endsWith("_banner"))) {
            return Need.FLOOR;
        }
        return null;
    }

    /** Unit step of a horizontal facing, {x, z}; null for anything else. */
    static int[] step(String facing) {
        return switch (facing == null ? "" : facing) {
            case "north" -> new int[]{0, -1};
            case "south" -> new int[]{0, 1};
            case "east" -> new int[]{1, 0};
            case "west" -> new int[]{-1, 0};
            default -> null;
        };
    }

    private static final String[] FACINGS = {"north", "south", "east", "west"};

    // ===================== composing the plan =====================

    /** The plan as it ends up — later shapes overwrite, air empties — keyed by position. */
    static final class Composed {
        final Map<Long, String> blocks = new HashMap<>();
        final Map<Long, String> shapeOf = new HashMap<>();
        int minY = Integer.MAX_VALUE;

        String at(int x, int y, int z) {
            return blocks.get(key(x, y, z));
        }
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    static int kx(long k) {
        return sign21((int) (k >>> 42));
    }

    static int ky(long k) {
        return sign21((int) ((k >>> 21) & 0x1FFFFF));
    }

    static int kz(long k) {
        return sign21((int) (k & 0x1FFFFF));
    }

    private static int sign21(int v) {
        return (v & 0x100000) != 0 ? v | ~0x1FFFFF : v;
    }

    static Composed compose(BuildPlan plan) {
        Composed c = new Composed();
        for (BuildShape s : plan.shapes()) {
            String block = s.block();
            boolean air = isAir(block);
            String name = s.name();
            s.forEachBlock((x, y, z) -> {
                long k = key(x, y, z);
                if (air) {
                    c.blocks.remove(k);
                    c.shapeOf.remove(k);
                } else {
                    c.blocks.put(k, block);
                    c.shapeOf.put(k, name);
                }
            });
        }
        for (long k : c.blocks.keySet()) {
            c.minY = Math.min(c.minY, ky(k));
        }
        return c;
    }

    // ===================== the check =====================

    /** Every block in the plan that cannot stand where it was put. Empty for a sound building. */
    public static List<Problem> check(BuildPlan plan) {
        List<Problem> out = new ArrayList<>();
        if (plan == null || plan.shapes().isEmpty()) {
            return out;
        }
        Composed c = compose(plan);
        List<Long> scaffolds = new ArrayList<>();
        for (Map.Entry<Long, String> e : c.blocks.entrySet()) {
            long k = e.getKey();
            String block = e.getValue();
            Need need = needOf(block);
            if (need == null) {
                continue;
            }
            int x = kx(k);
            int y = ky(k);
            int z = kz(k);
            boolean ground = y == c.minY;          // the lowest layer rests on the ground
            boolean ok = switch (need) {
                case WALL -> backed(c, x, y, z, block);
                case FLOOR -> ground || isFloor(c.at(x, y - 1, z))
                        || upperDoorHalf(c, x, y, z, block);
                case BELOW_SOLID -> ground || isFloor(c.at(x, y - 1, z));
                case SCAFFOLD -> {
                    scaffolds.add(k);
                    yield true;                    // decided below, all together
                }
                case EXIT -> true;                 // asked of ladder tops, below
            };
            if (!ok) {
                out.add(new Problem(x, y, z, block, need, c.shapeOf.get(k)));
            }
        }
        out.addAll(unheldScaffolding(c, scaffolds, true));
        out.addAll(blockedTops(c));
        out.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y())
                : a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
        return out;
    }

    /** The top half of a door stands on its own bottom half. */
    private static boolean upperDoorHalf(Composed c, int x, int y, int z, String block) {
        if (!base(block).endsWith("_door") || !"upper".equals(props(block).get("half"))) {
            return false;
        }
        String below = c.at(x, y - 1, z);
        return below != null && base(below).equals(base(block));
    }

    /** Is the block a wall-hung thing hangs on actually there? */
    static boolean backed(Composed c, int x, int y, int z, String block) {
        String facing = props(block).getOrDefault("facing", DEFAULT_FACING);
        int[] d = step(facing);
        if (d == null) {
            return true;                            // not a horizontal facing; not ours to judge
        }
        return isFullFace(c.at(x - d[0], y, z - d[1]));
    }

    /**
     * Scaffolding stands if it sits on something, or on scaffolding that does, or reaches one
     * within {@link #SCAFFOLD_REACH} steps sideways through other scaffolding — Minecraft's own
     * distance rule. On the lowest layer it rests on the ground.
     */
    private static List<Problem> unheldScaffolding(Composed c, List<Long> scaffolds,
                                                   boolean groundHolds) {
        Map<Long, Integer> dist = new HashMap<>();
        // Bottom up, so a column inherits its foot's answer.
        scaffolds.sort((a, b) -> Integer.compare(ky(a), ky(b)));
        java.util.Set<Long> all = new java.util.HashSet<>(scaffolds);
        int i = 0;
        while (i < scaffolds.size()) {
            int y = ky(scaffolds.get(i));
            int j = i;
            ArrayDeque<Long> queue = new ArrayDeque<>();
            for (; j < scaffolds.size() && ky(scaffolds.get(j)) == y; j++) {
                long k = scaffolds.get(j);
                int x = kx(k);
                int z = kz(k);
                long below = key(x, y - 1, z);
                Integer d = null;
                if (y == c.minY) {
                    d = groundHolds ? 0 : null;
                } else if (all.contains(below)) {
                    d = dist.get(below);
                } else if (isFloor(c.blocks.get(below))) {
                    d = 0;
                }
                if (d != null) {
                    dist.put(k, d);
                    queue.add(k);
                }
            }
            // Spread sideways within this layer.
            while (!queue.isEmpty()) {
                long k = queue.poll();
                int d = dist.get(k);
                if (d >= SCAFFOLD_REACH) {
                    continue;
                }
                int x = kx(k);
                int z = kz(k);
                for (int[] s : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    long n = key(x + s[0], y, z + s[1]);
                    if (all.contains(n) && (!dist.containsKey(n) || dist.get(n) > d + 1)) {
                        dist.put(n, d + 1);
                        queue.add(n);
                    }
                }
            }
            i = j;
        }
        List<Problem> out = new ArrayList<>();
        for (long k : scaffolds) {
            if (!dist.containsKey(k)) {
                out.add(new Problem(kx(k), ky(k), kz(k), c.blocks.get(k), Need.SCAFFOLD,
                        c.shapeOf.get(k)));
            }
        }
        return out;
    }

    /**
     * Scaffolding that stands ONLY because the ground is exactly under the build's lowest layer.
     *
     * <p>Not a design fault — the plan is sound on flat ground — so it is not in {@link #check}.
     * But a climbing column of scaffolding from the ground up is the library treehouse's whole
     * defect: wished onto a tree or a slope its foot is in the air, and the column falls.
     * What is under the build is an earlier issue's question; whether to climb on scaffolding at all is this
     * one's, so the cells are counted and the agent is told to use a ladder on a wall instead.
     */
    public static int groundOnlyScaffolding(BuildPlan plan) {
        if (plan == null || plan.shapes().isEmpty()) {
            return 0;
        }
        Composed c = compose(plan);
        List<Long> scaffolds = new ArrayList<>();
        for (Map.Entry<Long, String> e : c.blocks.entrySet()) {
            if (base(e.getValue()).equals("scaffolding")) {
                scaffolds.add(e.getKey());
            }
        }
        if (scaffolds.isEmpty()) {
            return 0;
        }
        int withGround = scaffolds.size() - unheldScaffolding(c, new ArrayList<>(scaffolds), true).size();
        int without = scaffolds.size() - unheldScaffolding(c, new ArrayList<>(scaffolds), false).size();
        return withGround - without;
    }

    /** At least this many ground-only scaffolding cells is a climbing column, worth a word. */
    public static final int GROUND_COLUMN = 3;

    // ===================== auto-facing =====================

    /**
     * A ladder or wall torch that names NO facing is turned toward the wall beside it.
     *
     * <p>Timid, like {@link BuildLighting}: only a shape whose block names no {@code facing} at
     * all, never one the agent faced itself; the facing chosen is the one that leaves the most of
     * the shape's cells with a full block behind them, and only if that is at least one. A shape
     * with no wall beside it anywhere is left exactly as it was and reported.
     */
    public static Faced autoFace(BuildPlan plan) {
        List<String> turned = new ArrayList<>();
        List<String> unbacked = new ArrayList<>();
        if (plan == null || plan.shapes().isEmpty()) {
            return new Faced(plan, turned, unbacked);
        }
        Composed c = null;
        List<BuildShape> out = new ArrayList<>(plan.shapes().size());
        for (int i = 0; i < plan.shapes().size(); i++) {
            BuildShape s = plan.shapes().get(i);
            String b = base(s.block());
            boolean hung = b.equals("ladder") || b.equals("wall_torch")
                    || b.equals("soul_wall_torch") || b.equals("redstone_wall_torch");
            if (!hung || props(s.block()).containsKey("facing")) {
                out.add(s);
                continue;
            }
            if (c == null) {
                c = compose(plan);
            }
            final Composed comp = c;
            String best = null;
            int bestCount = 0;
            for (String f : FACINGS) {
                int[] d = step(f);
                int[] count = {0};
                s.forEachBlock((x, y, z) -> {
                    if (isFullFace(comp.at(x - d[0], y, z - d[1]))) {
                        count[0]++;
                    }
                });
                if (count[0] > bestCount) {
                    bestCount = count[0];
                    best = f;
                }
            }
            String label = s.name() == null || s.name().isEmpty() ? "shape " + i : s.name();
            if (best == null) {
                unbacked.add(label);
                out.add(s);
                continue;
            }
            String faced = s.block().contains("[")
                    ? s.block().replace("[", "[facing=" + best + ",")
                    : s.block() + "[facing=" + best + "]";
            out.add(new BuildShape(s.kind(), s.dx(), s.dy(), s.dz(), s.width(), s.height(),
                    s.depth(), faced, s.hollow(), s.anchor(), s.name(), s.gen()));
            turned.add(label + " → facing=" + best);
        }
        return turned.isEmpty() ? new Faced(plan, turned, unbacked)
                : new Faced(plan.withShapes(out), turned, unbacked);
    }

    /**
     * What {@code finish_building} says when a building it is about to reveal will not stand or
     * cannot be climbed, or null when every one is sound. Measured 's smoke: told at
     * {@code build_structure} time that the top of its ladder hung on nothing, the agent revealed
     * anyway — so the reveal itself asks once more, the way it does for a machine nobody fed.
     */
    public static String holdReveal(java.util.Map<String, BuildPlan> builds) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, BuildPlan> e : builds.entrySet()) {
            List<Problem> problems = check(e.getValue());
            if (problems.isEmpty()) {
                continue;
            }
            sb.append(sb.length() == 0 ? "" : " ").append("'").append(e.getKey()).append("':")
              .append(note(null, problems).replace("\n\n⚠ CANNOT STAND —", ""));
        }
        if (sb.length() == 0) {
            return null;
        }
        return "Not yet — part of what you built will not stand or cannot be climbed, and he will "
                + "find out the first time he tries. " + sb + " If it truly cannot be fixed, call "
                + "this again and tell him honestly what does not work.";
    }

    // ===================== what the agent is told =====================

    /** What to tell the agent, or "" when everything stands. Never silent about a change it made. */
    public static String note(Faced faced, List<Problem> problems) {
        return note(faced, problems, 0);
    }

    /** As {@link #note(Faced, List)}, plus the scaffolding that stands only on the ground. */
    public static String note(Faced faced, List<Problem> problems, int groundOnlyScaffolding) {
        StringBuilder sb = new StringBuilder();
        if (groundOnlyScaffolding >= GROUND_COLUMN) {
            sb.append("\n\n⚠ ").append(groundOnlyScaffolding).append(" scaffolding blocks stand only "
                    + "because the ground is exactly under the bottom of the building. Wished onto a "
                    + "tree, a slope or water, their foot is in the air and the whole column falls. "
                    + "To climb, use minecraft:ladder[facing=…] against a wall or a post instead.");
        }
        if (faced != null && !faced.turned().isEmpty()) {
            sb.append("\n\nI turned ").append(faced.turned().size())
              .append(faced.turned().size() == 1 ? " ladder/wall torch" : " ladders/wall torches")
              .append(" that named no facing toward the wall beside it (")
              .append(String.join(", ", faced.turned()))
              .append("). A ladder hangs on the block it faces AWAY from: "
                      + "minecraft:ladder[facing=south] hangs on the wall to its NORTH. Name the "
                      + "facing yourself next time.");
        }
        if (problems == null || problems.isEmpty()) {
            return sb.toString();
        }
        long ladders = problems.stream().filter(p -> base(p.block()).equals("ladder")
                && p.need() == Need.WALL).count();
        sb.append("\n\n⚠ CANNOT STAND — ").append(problems.size()).append(" block")
          .append(problems.size() == 1 ? "" : "s")
          .append(" of this building will fall or pop off in the world, though they look placed: ");
        int shown = 0;
        for (Problem p : problems) {
            if (shown++ >= MAX_SHOWN) {
                sb.append("; … and ").append(problems.size() - MAX_SHOWN).append(" more");
                break;
            }
            sb.append(shown == 1 ? "" : "; ").append(p.describe());
        }
        sb.append(".");
        if (ladders > 0) {
            sb.append(" A ladder a child cannot climb is worse than none: put every ladder cell "
                    + "AGAINST a solid wall, with facing pointing AWAY from that wall "
                    + "(minecraft:ladder[facing=south] needs the wall on its NORTH side). In a hole "
                    + "through a floor, put the ladder against one edge of the hole, not in the "
                    + "middle.");
        }
        if (problems.stream().anyMatch(p -> p.need() == Need.EXIT)) {
            sb.append(" A ladder must END where he can step off: carry it up THROUGH the floor or "
                    + "roof above it (the ladder takes that block's place, still against its wall) "
                    + "so its top is level with the floor he walks onto, with two blocks of air "
                    + "above it.");
        }
        if (problems.stream().anyMatch(p -> p.need() == Need.SCAFFOLD
                || p.need() == Need.BELOW_SOLID)) {
            sb.append(" Sand, gravel, concrete powder and scaffolding fall unless something is "
                    + "directly under them; to climb, use a ladder on a wall instead of a "
                    + "scaffolding column.");
        }
        sb.append(" Fix it by re-sending those shapes with the same names.");
        return sb.toString();
    }
}
