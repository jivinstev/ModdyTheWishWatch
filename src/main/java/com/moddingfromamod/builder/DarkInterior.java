package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>Is it dark in there? Asked of the plan, before the reveal.</b>
 *
 * <p>an earlier issue's archived grant ({@code corpus/wish-runs/2026-10-04-build-spots-1355}) put a boss on a
 * gold throne inside a lava-moat castle and photographed him: the picture came back black but for
 * his outline. The castle HAD lights: {@link BuildLighting} had hung four lanterns, on the outer
 * wall's lid at y=12, one floor ABOVE the throne room's ceiling, because the throne room's floor
 * cells counted as "lit" by a lava moat the island had overwritten. Every check said lit. The room
 * a child walks into, and the one the camera stood in, had light 0.
 *
 * <p>So this does not ask "is there a light near here", which is the question {@link BuildLighting}
 * asks. (That one was also asked of the shapes as drawn; both now read one
 * {@link ComposedPlan}.) It estimates the <b>block light</b> the way Minecraft spreads it:
 * from every light block that survives in the composed plan, one level less per block, stopped by
 * any block light cannot pass. Then it reads that light at every floor cell under a roof and at
 * every named spot, and reports the dark ones back to the agent from {@code build_structure},
 * the same way {@code SculptCritique} reports a creature back before the reveal: <b>the agent
 * cannot see its own building</b>, and a dark room is a fact it cannot otherwise learn.
 *
 * <p>Block light only, deliberately. A roofed room is lit by day near its doorways and black at
 * night everywhere its own lights do not reach, and monsters spawn by block light alone. Sky
 * light would excuse exactly the rooms a child plays in after dark.
 *
 * <p>An estimate, and said to be one: non-full blocks are sorted into "light passes" and "light
 * stops" by name, and slabs and stairs count as stopping it, so a doubtful room reads darker
 * rather than lighter. The cost of that error is one lantern.
 *
 * <p>Pure and Minecraft-free, so all of it is a unit test ({@code DarkInteriorTest}).
 */
public final class DarkInterior {

    /** Light at or below this is "dark": the issue's "light 0–3", a smear in a photograph. */
    public static final int DARK_MAX = 3;

    /**
     * A dark patch smaller than this is a closet, an alcove or the gap behind a pillar. Not worth a
     * turn of the agent's, and saying it would bury the room that matters.
     */
    public static final int MIN_DARK_FLOOR = 6;

    /** Past this many cells in the bounding box it is not estimated, and the note says so. */
    public static final int MAX_VOLUME = 4_000_000;

    /** How many dark rooms one note names before it stops being an instruction. */
    static final int MAX_AREAS_SHOWN = 3;

    private static final String AIR = "minecraft:air";

    /**
     * A dark patch of floor.
     *
     * @param floor    how many floor cells it covers
     * @param x        a cell in it, nearest its middle — where to put a light
     * @param brightest the most light any cell of it gets (still {@code <= DARK_MAX})
     */
    public record Area(int floor, int x, int y, int z, int brightest) {
    }

    /** A named spot under a roof that is dark. */
    public record DarkSpot(String name, int dx, int dy, int dz, int light) {
    }

    /**
     * @param roofedFloor floor cells under a roof, walled on four sides — the inside
     * @param darkFloor   how many of them are at {@code <= DARK_MAX}
     * @param areas       the dark patches of at least {@link #MIN_DARK_FLOOR}, biggest first
     * @param spots       named spots inside and dark
     * @param tooBig      not estimated at all (past {@link #MAX_VOLUME}); nothing else is known
     */
    public record Report(int roofedFloor, int darkFloor, List<Area> areas, List<DarkSpot> spots,
                         boolean tooBig) {
        /** Is there anything to tell the agent? */
        public boolean dark() {
            return !areas.isEmpty() || !spots.isEmpty();
        }

        static Report none() {
            return new Report(0, 0, List.of(), List.of(), false);
        }
    }

    private DarkInterior() {
    }

    // ── what gives light, and what light passes through ─────────────────────────────────────────

    /** Light blocks whose level does not depend on their state. */
    private static final Map<String, Integer> EMITS = Map.ofEntries(
            Map.entry("lantern", 15), Map.entry("glowstone", 15), Map.entry("sea_lantern", 15),
            Map.entry("shroomlight", 15), Map.entry("ochre_froglight", 15),
            Map.entry("verdant_froglight", 15), Map.entry("pearlescent_froglight", 15),
            Map.entry("jack_o_lantern", 15), Map.entry("beacon", 15), Map.entry("conduit", 15),
            Map.entry("lava", 15), Map.entry("fire", 15), Map.entry("end_gateway", 15),
            Map.entry("torch", 14), Map.entry("wall_torch", 14), Map.entry("end_rod", 14),
            Map.entry("soul_lantern", 10), Map.entry("soul_torch", 10),
            Map.entry("soul_wall_torch", 10), Map.entry("soul_fire", 10),
            Map.entry("crying_obsidian", 10), Map.entry("redstone_torch", 7),
            Map.entry("redstone_wall_torch", 7), Map.entry("enchanting_table", 7),
            Map.entry("ender_chest", 7), Map.entry("glow_lichen", 7), Map.entry("sea_pickle", 6),
            Map.entry("amethyst_cluster", 5), Map.entry("large_amethyst_bud", 4),
            Map.entry("magma_block", 3), Map.entry("medium_amethyst_bud", 2),
            Map.entry("small_amethyst_bud", 1), Map.entry("brewing_stand", 1));

    /**
     * How much light this block gives once placed, honouring the state the plan asked for.
     *
     * <p>Several light blocks are placed OFF: a candle, a redstone lamp and a copper bulb are unlit
     * unless the plan says {@code [lit=true]}. Counting them as lights is how a room of unlit
     * candles reads as lit.
     */
    public static int emission(String block) {
        if (block == null) {
            return 0;
        }
        String b = block.toLowerCase(Locale.ROOT);
        String props = "";
        int bracket = b.indexOf('[');
        if (bracket >= 0) {
            props = b.substring(bracket);
            b = b.substring(0, bracket);
        }
        int colon = b.indexOf(':');
        String path = colon >= 0 ? b.substring(colon + 1) : b;
        boolean litOn = props.contains("lit=true");
        boolean litOff = props.contains("lit=false");
        if (path.equals("campfire")) {
            return litOff ? 0 : 15;            // placed lit
        }
        if (path.equals("soul_campfire")) {
            return litOff ? 0 : 10;
        }
        if (path.equals("redstone_lamp") || path.endsWith("copper_bulb")) {
            return litOn ? 15 : 0;             // placed off
        }
        if (path.equals("candle") || path.endsWith("_candle")) {
            if (!litOn) {
                return 0;
            }
            int n = intProp(props, "candles", 1);
            return Math.min(15, 3 * Math.max(1, n));
        }
        if (path.endsWith("candle_cake")) {
            return litOn ? 3 : 0;
        }
        if (path.equals("light")) {
            return intProp(props, "level", 15);
        }
        Integer fixed = EMITS.get(path);
        return fixed == null ? 0 : fixed;
    }

    private static int intProp(String props, String key, int fallback) {
        int at = props.indexOf(key + "=");
        if (at < 0) {
            return fallback;
        }
        int i = at + key.length() + 1;
        int j = i;
        while (j < props.length() && Character.isDigit(props.charAt(j))) {
            j++;
        }
        try {
            return Integer.parseInt(props.substring(i, j));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Names (after the namespace) whose block light passes through, by containment. */
    private static final List<String> PASSES = List.of(
            "glass", "_pane", "carpet", "lantern", "torch", "candle", "fence", "iron_bars", "chain",
            "end_rod", "leaves", "door", "sign", "banner", "button", "pressure_plate", "ladder",
            "rail", "flower", "sapling", "tulip", "orchid", "allium", "bluet", "daisy", "poppy",
            "dandelion", "cornflower", "lily", "rose_bush", "peony", "lilac", "fern", "grass",
            "vine", "cobweb", "scaffolding", "lever", "tripwire", "skull", "head", "flower_pot",
            "chest", "bed", "cake", "anvil", "bell", "brewing_stand", "enchanting_table",
            "hopper", "cauldron", "lectern", "campfire", "amethyst", "sea_pickle", "lichen",
            "water", "ice", "slime_block", "honey_block", "beacon", "conduit", "fire", "_wall",
            "barrier", "light", "redstone_wire", "repeater", "comparator", "snow", "kelp",
            "seagrass", "coral_fan", "mushroom", "bamboo", "sugar_cane", "cactus", "azalea");

    /** Blocks containing a PASSES word that nonetheless stop light. */
    private static final Set<String> STOPS = Set.of(
            "glowstone", "sea_lantern", "redstone_lamp", "jack_o_lantern", "snow_block",
            "light_gray_wool", "light_blue_wool", "light_gray_concrete", "light_blue_concrete",
            "light_gray_terracotta", "light_blue_terracotta", "packed_ice", "blue_ice",
            "mushroom_block", "brown_mushroom_block", "red_mushroom_block", "mushroom_stem",
            "grass_block", "bamboo_block", "bamboo_planks", "bamboo_mosaic", "bedrock",
            "budding_amethyst");

    /**
     * Does block light travel through this block? Air does; everything solid and full does not.
     * Wool, concrete and terracotta in light colours are full blocks despite the word "light".
     */
    static boolean passesLight(String block) {
        if (block == null || AIR.equals(block)) {
            return true;
        }
        String b = block.toLowerCase(Locale.ROOT);
        int bracket = b.indexOf('[');
        if (bracket >= 0) {
            b = b.substring(0, bracket);
        }
        int colon = b.indexOf(':');
        String path = colon >= 0 ? b.substring(colon + 1) : b;
        boolean fullBlock = path.endsWith("_block") && !path.equals("slime_block")
                && !path.equals("honey_block");
        if (STOPS.contains(path) || fullBlock || path.endsWith("_wool") || path.endsWith("_concrete")
                || path.endsWith("terracotta") || path.endsWith("_planks")) {
            return false;
        }
        for (String w : PASSES) {
            if (path.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** A thin thing a child stands IN rather than on: carpet, a rail, a plate, a flower. */
    private static boolean standsIn(String block) {
        if (block == null) {
            return true;
        }
        String b = block.toLowerCase(Locale.ROOT);
        return b.contains("carpet") || b.contains("pressure_plate") || b.contains("rail")
                || b.contains("torch") || b.contains("button") || b.contains("redstone_wire")
                || b.endsWith(":snow") || b.contains("flower") || b.contains("short_grass")
                || b.contains("fern") || b.contains("sapling");
    }

    private static boolean isLiquid(String block) {
        return block != null && (block.startsWith("minecraft:water") || block.startsWith("minecraft:lava"));
    }

    // ── the estimate ─────────────────────────────────────────────────────────────────────────────

    /** Estimate the plan's own light and find what is dark inside it. Null plan or empty: none. */
    public static Report of(BuildPlan plan) {
        return of(ComposedPlan.of(plan));
    }

    /**
     * The same, from a building already composed ({@link ComposedPlan}): later shapes overwrite and
     * air removes, so a lava moat the island overwrites is NOT a light. That is the room.
     */
    public static Report of(ComposedPlan built) {
        if (built == null || built.plan() == null || built.isEmpty()) {
            return Report.none();
        }
        BuildPlan plan = built.plan();
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        built.forEach((x, y, z, block) -> {
            lo[0] = Math.min(lo[0], x);
            lo[1] = Math.min(lo[1], y);
            lo[2] = Math.min(lo[2], z);
            hi[0] = Math.max(hi[0], x);
            hi[1] = Math.max(hi[1], y);
            hi[2] = Math.max(hi[2], z);
        });
        int minX = lo[0], minY = lo[1], minZ = lo[2], maxX = hi[0], maxY = hi[1], maxZ = hi[2];
        Grid g = new Grid(minX, minY, minZ, maxX, maxY, maxZ);
        if ((long) g.w * g.h * g.d > MAX_VOLUME) {
            return new Report(0, 0, List.of(), List.of(), true);
        }
        // Cell kinds: 0 empty, 1 a block light passes, 2 a block that stops it. Plus "can stand on".
        byte[] kind = new byte[g.size()];
        boolean[] floorUnder = new boolean[g.size()];
        boolean[] thin = new boolean[g.size()];
        @SuppressWarnings("unchecked")
        List<Integer>[] buckets = new List[16];
        for (int i = 0; i < 16; i++) {
            buckets[i] = new ArrayList<>();
        }
        byte[] light = new byte[g.size()];
        built.forEach((x, y, z, b) -> {
            int i = g.idx(x, y, z);
            kind[i] = (byte) (passesLight(b) ? 1 : 2);
            thin[i] = standsIn(b);
            floorUnder[i] = !isLiquid(b) && !standsIn(b);
            int em = emission(b);
            if (em > 0 && em > light[i]) {
                light[i] = (byte) em;
                buckets[em].add(i);
            }
        });
        // Spread, brightest first, one level per block, never into a block that stops light.
        int[][] six = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int lvl = 15; lvl >= 2; lvl--) {
            List<Integer> q = buckets[lvl];
            for (int qi = 0; qi < q.size(); qi++) {
                int i = q.get(qi);
                if (light[i] != lvl) {
                    continue;                 // a brighter path got here first
                }
                int x = g.x(i), y = g.y(i), z = g.z(i);
                for (int[] o : six) {
                    int nx = x + o[0], ny = y + o[1], nz = z + o[2];
                    if (!g.in(nx, ny, nz)) {
                        continue;
                    }
                    int n = g.idx(nx, ny, nz);
                    if (kind[n] == 2 || light[n] >= lvl - 1) {
                        continue;
                    }
                    light[n] = (byte) (lvl - 1);
                    buckets[lvl - 1].add(n);
                }
            }
        }
        // The inside: a cell a child stands in, with a roof over him and walls all round.
        boolean[] dark = new boolean[g.size()];
        int roofed = 0;
        int darkCount = 0;
        for (int z = g.minZ; z <= g.maxZ; z++) {
            for (int y = g.minY + 1; y <= g.maxY - 1; y++) {
                for (int x = g.minX; x <= g.maxX; x++) {
                    int i = g.idx(x, y, z);
                    if (!inside(g, kind, floorUnder, thin, x, y, z)) {
                        continue;
                    }
                    roofed++;
                    if (light[i] <= DARK_MAX) {
                        dark[i] = true;
                        darkCount++;
                    }
                }
            }
        }
        List<Area> areas = areas(g, dark, light);
        List<DarkSpot> spots = new ArrayList<>();
        for (BuildSpot s : plan.spots()) {
            int x = s.dx(), y = s.dy(), z = s.dz();
            if (!g.in(x, y, z) || !roofedAndWalled(g, kind, x, y, z)) {
                continue;
            }
            int l = light[g.idx(x, y, z)];
            if (l <= DARK_MAX) {
                spots.add(new DarkSpot(s.name(), x, y, z, l));
            }
        }
        return new Report(roofed, darkCount, areas, spots, false);
    }

    private static boolean inside(Grid g, byte[] kind, boolean[] floorUnder, boolean[] thin,
                                  int x, int y, int z) {
        int i = g.idx(x, y, z);
        if (kind[i] != 0 && !thin[i]) {
            return false;                     // a block, not a place to stand
        }
        int below = g.idx(x, y - 1, z);
        if (kind[below] == 0 || !floorUnder[below]) {
            return false;                     // nothing to stand on
        }
        int head = g.idx(x, y + 1, z);
        if (kind[head] != 0 && !thin[head]) {
            return false;                     // no headroom
        }
        return roofedAndWalled(g, kind, x, y, z);
    }

    /**
     * A roof somewhere overhead and a wall in at least three of the four directions, scanning to
     * the edge of the building — docs/CAMERA.md §5: how wide a room is says nothing about whether
     * you are in it.
     *
     * <p>Three, not four: an earlier issue's throne faces its door, so the line from the throne runs out
     * through the keep door and the castle gate and finds no wall. With four the very spot this
     * check exists for was "outside". A porch roof or an archway has two walls at most.
     */
    private static boolean roofedAndWalled(Grid g, byte[] kind, int x, int y, int z) {
        boolean roof = false;
        for (int yy = y + 1; yy <= g.maxY && !roof; yy++) {
            roof = kind[g.idx(x, yy, z)] == 2;
        }
        if (!roof) {
            return false;
        }
        int walls = (wall(g, kind, x, y, z, 1, 0) ? 1 : 0) + (wall(g, kind, x, y, z, -1, 0) ? 1 : 0)
                + (wall(g, kind, x, y, z, 0, 1) ? 1 : 0) + (wall(g, kind, x, y, z, 0, -1) ? 1 : 0);
        return walls >= 3;
    }

    private static boolean wall(Grid g, byte[] kind, int x, int y, int z, int dx, int dz) {
        for (int cx = x + dx, cz = z + dz; g.in(cx, y, cz); cx += dx, cz += dz) {
            if (kind[g.idx(cx, y, cz)] != 0) {
                return true;
            }
        }
        return false;
    }

    /** Connected dark floor (a step up or down still joins), biggest first. */
    private static List<Area> areas(Grid g, boolean[] dark, byte[] light) {
        List<Area> out = new ArrayList<>();
        boolean[] seen = new boolean[g.size()];
        for (int start = 0; start < dark.length; start++) {
            if (!dark[start] || seen[start]) {
                continue;
            }
            List<Integer> cells = new ArrayList<>();
            seen[start] = true;
            cells.add(start);
            for (int ci = 0; ci < cells.size(); ci++) {
                int c = cells.get(ci);
                int x = g.x(c), y = g.y(c), z = g.z(c);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0, 0}}) {
                        int nx = x + o[0], ny = y + dy, nz = z + o[1];
                        if ((o[0] == 0 && o[1] == 0 && dy == 0) || !g.in(nx, ny, nz)) {
                            continue;
                        }
                        int n = g.idx(nx, ny, nz);
                        if (dark[n] && !seen[n]) {
                            seen[n] = true;
                            cells.add(n);
                        }
                    }
                }
            }
            if (cells.size() < MIN_DARK_FLOOR) {
                continue;
            }
            double sx = 0, sy = 0, sz = 0;
            int brightest = 0;
            for (int c : cells) {
                sx += g.x(c);
                sy += g.y(c);
                sz += g.z(c);
                brightest = Math.max(brightest, light[c]);
            }
            double mx = sx / cells.size(), my = sy / cells.size(), mz = sz / cells.size();
            int best = cells.stream().min(Comparator.comparingDouble(c -> sq(g.x(c) - mx)
                    + sq(g.y(c) - my) + sq(g.z(c) - mz))).orElse(start);
            out.add(new Area(cells.size(), g.x(best), g.y(best), g.z(best), brightest));
        }
        out.sort(Comparator.comparingInt(Area::floor).reversed());
        return out;
    }

    private static double sq(double v) {
        return v * v;
    }

    // ── what the agent is told ───────────────────────────────────────────────────────────────────

    /**
     * The note for {@code build_structure}, or "" when nothing inside is dark. A spot first,
     * because it is where a creature was asked to stand and the camera will look.
     */
    public static String note(Report r) {
        if (r == null) {
            return "";
        }
        if (r.tooBig()) {
            return "\n\nLIGHT — it is too big for me to work out how dark it is inside, so nobody "
                    + "has checked; give every roofed room lights of its own.";
        }
        if (!r.dark()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nDARK INSIDE — ");
        List<String> parts = new ArrayList<>();
        for (DarkSpot s : r.spots()) {
            parts.add("the '" + s.name() + "' spot at " + s.dx() + "," + s.dy() + "," + s.dz()
                    + " is at light " + s.light());
        }
        int shown = 0;
        for (Area a : r.areas()) {
            if (shown++ >= MAX_AREAS_SHOWN) {
                parts.add("and " + (r.areas().size() - MAX_AREAS_SHOWN) + " more dark room(s)");
                break;
            }
            parts.add("a " + a.floor() + "-block stretch of floor around " + a.x() + "," + a.y()
                    + "," + a.z() + " gets light " + (a.brightest() == 0 ? "0" : "0–" + a.brightest()));
        }
        sb.append(String.join("; ", parts)).append(". ");
        if (r.roofedFloor() > 0) {
            sb.append(r.darkFloor()).append(" of the ").append(r.roofedFloor())
              .append(" floor blocks under its roof are at light 0–").append(DARK_MAX)
              .append(" from the building's own lights: a child standing there sees almost "
                      + "nothing, and monsters spawn in the dark. ");
        }
        sb.append("Light drops one level per block and stops at a solid block, so a lantern "
                + "above the ceiling or across a long hall does not reach. Put lights IN each of "
                + "those rooms — lanterns, glowstone, sea lanterns or candles[lit=true], in the "
                + "floor, walls or ceiling within about 8 blocks of where he stands — and build "
                + "again. Judge the lighting from this note, not from the photograph: a camera "
                + "client sees in the dark, and the child does not.");
        return sb.toString();
    }

    // ── the grid ─────────────────────────────────────────────────────────────────────────────────

    private static final class Grid {
        final int minX, minY, minZ, maxX, maxY, maxZ, w, h, d;

        Grid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.w = maxX - minX + 1;
            this.h = maxY - minY + 1;
            this.d = maxZ - minZ + 1;
        }

        int size() {
            return w * h * d;
        }

        boolean in(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        int idx(int x, int y, int z) {
            return (x - minX) + w * ((y - minY) + h * (z - minZ));
        }

        int x(int i) {
            return minX + i % w;
        }

        int y(int i) {
            return minY + (i / w) % h;
        }

        int z(int i) {
            return minZ + i / (w * h);
        }
    }
}
