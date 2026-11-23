package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>What a building stands on</b>. Pure, no Minecraft: the world is asked through
 * {@link World}, one column at a time, so every row of the rule is a case in
 * {@code BuildFootingTest}; the Minecraft half is {@link BuildGround}.
 *
 * <p>A world that is mostly ocean settles a wish that misses the ground onto the sea
 * surface: in one measured session five of fifteen wishes landed over water and the carwash, the garage and
 * the lot all stood on it. The treehouse has the same question one level down — its trunk
 * and ladders stand on whatever ground is under the build. One placement check answers both: <i>what is under the parts designed to touch the ground?</i>
 * <ul>
 *   <li><b>ground a few blocks down</b> (a slope, a dip, a ledge) → a FOOTING: each column is
 *   filled down to the ground with blocks matching the terrain there, as vanilla villages do, at
 *   most {@link #FOOTING_CAP} deep;</li>
 *   <li><b>water</b> → an ISLAND, from the seabed (at most {@link #ISLAND_DEPTH} deep) up to sea
 *   level, with a {@link #SHORE}-block beach;</li>
 *   <li><b>a long drop</b> (a treetop, a cliff edge, the sky above the ground) → the build is SET
 *   DOWN on the ground below — never a tower of fill;</li>
 *   <li><b>lava</b> → a footing of obsidian, as {@code SafeSpot} gives a creature.</li>
 * </ul>
 *
 * <p>"Designed to touch the ground" is the plan's own {@code y = 0} layer: a garage's floor, a
 * treehouse's trunk and the feet of its ladders. Nothing is ever filled under a part designed to be
 * up high, so a treehouse keeps its height — the trunk carries it. A deliberately floated build is
 * exempt: the agent raised the anchor, or every part is above {@code y = 0} (a sky castle).
 */
public final class BuildFooting {

    /** A median gap this deep or less is a footing; deeper, and the build is set down instead. */
    public static final int FOOTING_DEPTH = 4;
    /** The most any one column is filled under the build. Deeper columns hang, and are reported. */
    public static final int FOOTING_CAP = 8;
    /** The most water an island fills, from the surface down. Deeper, its base stops short of the seabed. */
    public static final int ISLAND_DEPTH = 24;
    /** The beach around an island, in blocks. */
    public static final int SHORE = 2;
    /** How far down the world is asked. Below this, a column has {@link Under#NOTHING} under it. */
    public static final int SCAN = 64;

    /** Island blocks this close to the surface are sand (the beach); deeper ones are stone. */
    static final int SAND_LAYERS = 3;

    public static final String SAND = "minecraft:sand";
    public static final String STONE = "minecraft:stone";
    public static final String DIRT = "minecraft:dirt";
    public static final String OBSIDIAN = "minecraft:obsidian";

    private BuildFooting() {
    }

    /** What is under one column, looking down from the build's floor. */
    public enum Under {
        /** Something solid to stand on. */
        GROUND,
        /** Water, with a seabed (or not, within {@link #SCAN}) under it. */
        WATER,
        LAVA,
        /** Nothing within {@link #SCAN}: open air to the void, or a very long drop. */
        NOTHING,
        /** Not loaded: never read, never generated. Nothing is decided. */
        UNLOADED
    }

    /**
     * One column, as the rule reads it.
     *
     * @param top    the y of the first thing below the floor: the ground's top block, the water's
     *               surface block, the lava's surface block
     * @param bottom for {@link Under#WATER}: the y of the seabed's top block, or
     *               {@link Integer#MIN_VALUE} when the seabed is out of sight
     * @param block  the block id of the ground (or the seabed), for a footing that matches it
     * @param leaves true when the look down passed through leaves: the spot was in a treetop
     */
    public record Column(Under under, int top, int bottom, String block, boolean leaves) {

        public static Column ground(int top, String block) {
            return new Column(Under.GROUND, top, Integer.MIN_VALUE, block, false);
        }

        public static Column water(int surface, int seabed, String seabedBlock) {
            return new Column(Under.WATER, surface, seabed, seabedBlock, false);
        }

        public static Column lava(int surface) {
            return new Column(Under.LAVA, surface, Integer.MIN_VALUE, "minecraft:lava", false);
        }

        public static Column nothing() {
            return new Column(Under.NOTHING, Integer.MIN_VALUE, Integer.MIN_VALUE, "", false);
        }

        public static Column unloaded() {
            return new Column(Under.UNLOADED, Integer.MIN_VALUE, Integer.MIN_VALUE, "", false);
        }

        public Column throughLeaves() {
            return new Column(under, top, bottom, block, true);
        }
    }

    /** The world, as the rule needs it: what is under {@code (x, z)}, looking down from {@code fromY}. */
    public interface World {
        Column column(int x, int z, int fromY);
    }

    /** What was decided. */
    public enum Action {
        /** It stands on the ground as it is. */
        NONE,
        /** A deliberately floated build: left exactly where it was put. */
        FLOATED,
        /** Filled under, where it was put (a footing, an island, or both). */
        FILLED,
        /** Moved down onto the ground below, and filled under where it still needs it. */
        SET_DOWN,
        /** Nothing within reach below it to stand on; left where it was put. */
        NOTHING_BELOW,
        /** Part of it is over unloaded world; nothing was read and nothing done. */
        UNLOADED
    }

    /** One block to put under the build, in absolute coordinates. */
    public record Fill(int x, int y, int z, String block) {
    }

    /**
     * The decision.
     *
     * @param anchorY  the floor's y — {@code fromY} unless the build was set down
     * @param fills    the blocks to put under it, bottom first, so a sand block is never placed
     *                 before what holds it up
     * @param footing  columns under the build given a footing on ground
     * @param island   columns (under the build and its beach) filled from the water
     * @param lava     columns given an obsidian footing over lava
     * @param hanging  columns still over a drop deeper than {@link #FOOTING_CAP}
     * @param leaves   the look down went through a treetop
     */
    public record Decision(Action action, int anchorY, int dropped, List<Fill> fills, int footing,
                           int island, int lava, int hanging, boolean leaves) {

        public boolean moved() {
            return action == Action.SET_DOWN && dropped != 0;
        }

        static Decision of(Action a, int y) {
            return new Decision(a, y, 0, List.of(), 0, 0, 0, 0, false);
        }
    }

    // ── which columns are designed to touch the ground ───────────────────────────────────────

    /**
     * <b>The columns designed to touch the ground</b>, each {@code {x, z, base}} relative to the
     * anchor: every column in which the finished plan puts a block at or below {@code y = 0} (an air
     * shape carving a doorway is no block), and {@code base} is its LOWEST such block — so a build
     * that digs into the ground on purpose (a floating island's rock, the underwater dome's shell, a
     * sunken plaza) is measured from its real bottom, not from its floor. The columns those enclose
     * (the inside of a hollow trunk, a courtyard) are added at {@code base = 0}, so a ring of wall
     * does not stand around a hole. Empty when no part is that low: a sky castle.
     */
    public static List<int[]> footprint(BuildPlan plan) {
        // Later shapes overwrite earlier ones, so a column's blocks are read off the FINISHED plan.
        Map<Long, Map<Integer, String>> low = new HashMap<>();
        plan.forEachBlock((x, y, z, block) -> {
            if (y <= 0) {
                low.computeIfAbsent(key(x, z), k -> new HashMap<>()).put(y, block);
            }
        });
        Map<Long, Integer> base = new java.util.LinkedHashMap<>();
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (Map.Entry<Long, Map<Integer, String>> e : low.entrySet()) {
            int lowest = Integer.MAX_VALUE;
            for (Map.Entry<Integer, String> b : e.getValue().entrySet()) {
                if (!isAir(b.getValue())) {
                    lowest = Math.min(lowest, b.getKey());
                }
            }
            if (lowest == Integer.MAX_VALUE) {
                continue;
            }
            base.put(e.getKey(), lowest);
            int x = kx(e.getKey()), z = kz(e.getKey());
            x0 = Math.min(x0, x);
            x1 = Math.max(x1, x);
            z0 = Math.min(z0, z);
            z1 = Math.max(z1, z);
        }
        if (base.isEmpty()) {
            return List.of();
        }
        // Inside is what has the build on three sides or more, along its row and its column — not a
        // flood fill from outside, which one doorway in the bottom ring lets straight in. Three,
        // not four: a doorway's own column has the wall on one side only.
        Map<Integer, int[]> rowSpan = new HashMap<>(), colSpan = new HashMap<>();
        for (long k : base.keySet()) {
            int x = kx(k), z = kz(k);
            rowSpan.merge(z, new int[]{x, x}, (o, n) -> new int[]{Math.min(o[0], x), Math.max(o[1], x)});
            colSpan.merge(x, new int[]{z, z}, (o, n) -> new int[]{Math.min(o[0], z), Math.max(o[1], z)});
        }
        Set<Long> outside = new HashSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int[] r = rowSpan.get(z), c = colSpan.get(x);
                int sides = (r != null && x > r[0] ? 1 : 0) + (r != null && x < r[1] ? 1 : 0)
                        + (c != null && z > c[0] ? 1 : 0) + (c != null && z < c[1] ? 1 : 0);
                if (sides < 3) {
                    outside.add(key(x, z));
                }
            }
        }
        List<int[]> out = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                long k = key(x, z);
                Integer b = base.get(k);
                if (b != null) {
                    out.add(new int[]{x, z, b});
                } else if (!outside.contains(k)) {
                    out.add(new int[]{x, z, 0});
                }
            }
        }
        return out;
    }

    /** Is every part of this plan above {@code y = 0} — a sky castle, floated by design? */
    public static boolean allAboveGround(BuildPlan plan) {
        int[] min = {Integer.MAX_VALUE};
        plan.forEachBlock((x, y, z, block) -> {
            if (!isAir(block)) {
                min[0] = Math.min(min[0], y);
            }
        });
        return min[0] != Integer.MAX_VALUE && min[0] > 0;
    }

    // ── the decision ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>The rule.</b>
     *
     * @param footprint the columns designed to touch the ground, {@code {x, z, base}} relative to
     *                  the anchor ({@link #footprint})
     * @param floated   the agent raised the anchor on purpose (an {@code offsetY} above zero, or
     *                  {@code conjure_at}'s {@code up})
     * @param mayMove   false for a rebuild where it already stands: it is filled under, never moved
     */
    public static Decision decide(World w, int ax, int ay, int az, List<int[]> footprint,
                                  boolean floated, boolean mayMove) {
        if (floated) {
            return Decision.of(Action.FLOATED, ay);
        }
        if (footprint == null || footprint.isEmpty()) {
            return Decision.of(Action.NONE, ay);
        }
        List<Column> cols = new ArrayList<>(footprint.size());
        int leafy = 0;
        for (int[] c : footprint) {
            Column col = w.column(ax + c[0], az + c[1], ay + c[2]);
            if (col == null || col.under() == Under.UNLOADED) {
                return Decision.of(Action.UNLOADED, ay);
            }
            cols.add(col);
            if (col.leaves()) {
                leafy++;
            }
        }
        boolean leaves = leafy * 2 >= cols.size();
        // The typical gap — the LOWER median, so an overhang over a cliff edge with most of the
        // build on the clifftop stays on the clifftop, rather than being dropped into the cliff.
        List<Integer> gaps = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            gaps.add(gap(cols.get(i), ay + footprint.get(i)[2]));
        }
        Collections.sort(gaps);
        int median = gaps.get((gaps.size() - 1) / 2);
        int floorY = ay;
        if (median > FOOTING_DEPTH && mayMove) {
            if (median == Integer.MAX_VALUE) {
                return new Decision(Action.NOTHING_BELOW, ay, 0, List.of(), 0, 0, 0, cols.size(), leaves);
            }
            // Set down onto the ground below: the median of where each column would rest, so a
            // tree's own trunk under one corner does not hold the whole house up in the canopy.
            List<Integer> rest = new ArrayList<>();
            for (int i = 0; i < cols.size(); i++) {
                Column c = cols.get(i);
                if (c.under() != Under.NOTHING) {
                    rest.add(c.top() + 1 - footprint.get(i)[2]);
                }
            }
            Collections.sort(rest);
            floorY = Math.min(ay, rest.get(rest.size() / 2));
        }
        List<Fill> fills = new ArrayList<>();
        int footing = 0, island = 0, lava = 0, hanging = 0;
        Set<Long> under = new HashSet<>();
        Set<Long> beach = new HashSet<>();
        for (int i = 0; i < cols.size(); i++) {
            int x = ax + footprint.get(i)[0], z = az + footprint.get(i)[1];
            int bottom = floorY + footprint.get(i)[2];      // the column's lowest block
            under.add(key(x, z));
            Column c = cols.get(i);
            int g = gap(c, bottom);
            if (c.under() != Under.NOTHING && c.top() > bottom - 1) {
                continue;       // set down past it: this column is sunk into what it stood over
            }
            if (g <= 0 && c.under() == Under.GROUND) {
                continue;       // standing on it, or sunk into a slope: nothing to fill
            }
            if (c.under() == Under.NOTHING || g > FOOTING_CAP) {
                // A cliff lip or a sea cliff: left as it is and reported. A stub of fill hanging in
                // the air would be the tower of fill that was ruled out, only shorter.
                hanging++;
                continue;
            }
            switch (c.under()) {
                case GROUND -> {
                    footing++;
                    String m = material(c.block());
                    for (int y = c.top() + 1; y <= bottom - 1; y++) {
                        fills.add(new Fill(x, y, z, m));
                    }
                }
                case LAVA -> {
                    lava++;
                    for (int y = c.top(); y <= bottom - 1; y++) {
                        fills.add(new Fill(x, y, z, OBSIDIAN));
                    }
                }
                case WATER -> {
                    island++;
                    islandColumn(fills, x, z, c, bottom - 1);
                    if (footprint.get(i)[2] == 0) {
                        beach.add(key(x, z));       // a floor at the sea: a beach round it
                    }
                }
                default -> {
                }
            }
        }
        // The beach: water columns around the island, filled to sea level, never above it.
        if (!beach.isEmpty()) {
            Set<Long> shore = new LinkedHashSet<>();
            for (long k : beach) {
                int x = kx(k), z = kz(k);
                for (int dx = -SHORE; dx <= SHORE; dx++) {
                    for (int dz = -SHORE; dz <= SHORE; dz++) {
                        if (dx * dx + dz * dz > SHORE * SHORE + 1) {
                            continue;
                        }
                        long n = key(x + dx, z + dz);
                        if (!under.contains(n)) {
                            shore.add(n);
                        }
                    }
                }
            }
            for (long k : shore) {
                int x = kx(k), z = kz(k);
                Column c = w.column(x, z, floorY);
                if (c == null || c.under() != Under.WATER || c.top() > floorY - 1
                        || floorY - 1 - c.top() > FOOTING_CAP) {
                    continue;
                }
                island++;
                islandColumn(fills, x, z, c, c.top());
            }
        }
        fills.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y())
                : a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
        Action a = floorY != ay ? Action.SET_DOWN : fills.isEmpty() ? Action.NONE : Action.FILLED;
        return new Decision(a, floorY, ay - floorY, List.copyOf(fills), footing, island, lava, hanging,
                leaves);
    }

    /** An island column: the water from its surface down to the seabed (capped), then up to {@code topY}. */
    private static void islandColumn(List<Fill> fills, int x, int z, Column c, int topY) {
        int deepest = Math.max(c.top() - ISLAND_DEPTH + 1,
                c.bottom() == Integer.MIN_VALUE ? Integer.MIN_VALUE : c.bottom() + 1);
        for (int y = deepest; y <= topY; y++) {
            fills.add(new Fill(x, y, z, y > c.top() - SAND_LAYERS ? SAND : STONE));
        }
    }

    /**
     * How far below a column's lowest block its support is: 0 when it rests on it (or is sunk into
     * it), {@link Integer#MAX_VALUE} for nothing at all. For water and lava it is the air above the
     * surface — 0 is a floor right on the sea, which stays where it is and gets its island.
     */
    static int gap(Column c, int floorY) {
        if (c.under() == Under.NOTHING) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, floorY - 1 - c.top());
    }

    // ── matching the terrain ─────────────────────────────────────────────────────────────────

    private static final Set<String> KEEP = Set.of(
            "stone", "deepslate", "andesite", "diorite", "granite", "tuff", "calcite", "cobblestone",
            "mossy_cobblestone", "sandstone", "red_sandstone", "terracotta", "netherrack",
            "end_stone", "blackstone", "basalt", "mud", "clay", "snow_block", "dirt", "sand",
            "red_sand", "gravel", "soul_sand", "soul_soil", "packed_mud", "dripstone_block");
    private static final Set<String> SOIL = Set.of(
            "grass_block", "podzol", "mycelium", "dirt_path", "farmland", "rooted_dirt",
            "coarse_dirt", "moss_block");

    /**
     * <b>The footing's block, from the ground it stands on</b> — so it reads as the hillside rather
     * than as a foundation: soil under grass, the stone it is on, sand on a beach. Anything that is
     * not terrain (a log, a child's own planks) gets dirt.
     */
    public static String material(String ground) {
        String id = ground == null ? "" : ground.strip().toLowerCase(Locale.ROOT);
        String path = id.startsWith("minecraft:") ? id.substring("minecraft:".length())
                : id.contains(":") ? "" : id;
        if (SOIL.contains(path)) {
            return DIRT;
        }
        if (KEEP.contains(path) || path.endsWith("_terracotta")) {
            return "minecraft:" + path;
        }
        return DIRT;
    }

    // ── what is said ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>What Moddy says</b> when the build moved, in {@code SafeSpot.told}'s style — or null when
     * nothing moved. E.g. <i>"Your treehouse is down on the ground — the spot you picked was up in
     * the treetops!"</i>
     */
    public static String told(String name, Decision d) {
        if (d == null || !d.moved()) {
            return null;
        }
        String what = name == null || name.isBlank() ? "building" : name.strip();
        String where = d.dropped() <= 12 ? "just below" : "down on the ground below";
        String because = d.leaves() ? "the spot you picked was up in the treetops!"
                : "the spot you picked was up in the air with nothing under it!";
        return "Your " + what + " is " + where + " — " + because;
    }

    /** One clause for the agent's tool result, so its announcement says the right thing; "" for none. */
    public static String forTheAgent(Decision d, int x, int y, int z) {
        return forTheAgent(d, x, y, z, List.of());
    }

    /**
     * The same, with what else placing it did to the ground — {@link BuildClearing}'s cut into a hill
     * — said in the same sentence, because it is the same kind of thing: part of the build, given
     * back when the building is taken down.
     */
    public static String forTheAgent(Decision d, int x, int y, int z, List<String> alsoDone) {
        List<String> said = new ArrayList<>();
        if (d == null) {
            d = Decision.of(Action.NONE, y);
        }
        switch (d.action()) {
            case SET_DOWN -> said.add(String.format(Locale.ROOT,
                    "the spot was %d block(s) above the ground%s, so I set it down on the ground below, "
                            + "at %d %d %d, and told him where", d.dropped(),
                    d.leaves() ? " (up in a tree)" : "", x, y, z));
            case NOTHING_BELOW -> said.add("⚠ there is nothing under it to stand on within "
                    + SCAN + " blocks, so it was left where it was put");
            default -> {
            }
        }
        if (d.island() > 0) {
            said.add("it was over water, so I built a little island under it with a beach around it");
        }
        if (d.footing() > 0) {
            said.add("the ground dipped under " + d.footing() + " column(s) of its base, so I filled "
                    + "them down to the ground to match the terrain");
        }
        if (d.lava() > 0) {
            said.add("it was over lava, so it stands on an obsidian footing");
        }
        if (d.hanging() > 0 && d.action() != Action.NOTHING_BELOW) {
            said.add("⚠ " + d.hanging() + " column(s) of its base are still over a drop deeper than "
                    + FOOTING_CAP + " blocks");
        }
        for (String also : alsoDone) {
            if (also != null && !also.isEmpty()) {
                said.add(also);
            }
        }
        if (said.isEmpty()) {
            return "";
        }
        return " (" + capital(String.join("; ", said)) + ". These are part of the build: removing it "
                + "takes them away too and puts the ground back, so there is no need to build foundations "
                + "or dig of your own.)";
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean isAir(String block) {
        String b = block == null ? "" : block.strip().toLowerCase(Locale.ROOT);
        return b.equals("air") || b.equals("minecraft:air") || b.endsWith(":cave_air")
                || b.endsWith(":void_air") || b.equals("cave_air") || b.equals("void_air");
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    static int kx(long k) {
        return (int) (k >> 32);
    }

    static int kz(long k) {
        return (int) k;
    }
}
