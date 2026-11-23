package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.builder.BuildDoors;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>A building, composed</b>: a TYPE of building (a house, a tower, a castle…), its size and colours (the tune
 * dialog's), and WHAT GOES OUTSIDE it (a flower garden, lamp posts, a path to the door, a pool, a flag, or nothing).
 *
 * <p>Each type is a building body the Builder already ships (its {@code buildings/*} preset's shapes), so every result is
 * walkable and stands exactly as that body does ({@code WalkThroughCorpusTest}, {@code BuildSupportSweepTest}). A
 * ready-made IS its type with nothing outside, so every one is a composition ({@code BuilderBuildingComposableTest}).
 * What goes outside is added AROUND the footprint, never into it, so it cannot close a doorway: {@code BuilderBuildingsTest}
 * walks every (type, outside, size) and checks it stands. A type that does not stand on the ground (an island in the
 * sky, a ship, a dome under the sea) has nothing outside. One pick for now: a page of several waits 's picks row.
 * Pure.
 */
public final class BuilderBuildings {

    private BuilderBuildings() {
    }

    /**
     * A type: its preset (the body), its tile's words and picture, whether it stands on the ground, and whether it is a
     * WORKING build (a farm plot, an animal pen, a tree nursery, a monster cave): plain blocks laid out so they
     * work as vanilla does (farmland by its water, grass inside a fence, saplings in the light, a room dark enough for
     * monsters). A working build comes at one size and has nothing outside: scaling or a garden round it would stop
     * it working ({@link BuilderSizes#FIXED}).
     */
    public record Type(String id, String label, String tile, boolean grounded, boolean working) {
        public Type(String id, String label, String tile, boolean grounded) {
            this(id, label, tile, grounded, false);
        }

        public String preset() {
            return "buildings/" + id;
        }

        /** Does what goes outside go round it? Only a type on the ground that is not a working build. */
        public boolean takesOutside() {
            return grounded && !working;
        }
    }

    public static final List<Type> TYPES = List.of(
            new Type("cozy_house", "A cozy house", "minecraft:oak_door", true),
            new Type("mansion", "A mansion", "minecraft:quartz_block", true),
            new Type("wizard_tower", "A wizard tower", "minecraft:purpur_pillar", true),
            new Type("lava_moat_castle", "A castle", "minecraft:lava_bucket", true),
            new Type("village_shop", "A village shop", "minecraft:emerald", true),
            new Type("small_village", "A village", "minecraft:bell", true),
            new Type("mushroom_village", "A mushroom village", "minecraft:red_mushroom_block", true),
            new Type("red_barn", "A red barn", "minecraft:hay_block", true),
            new Type("farm", "A farm", "minecraft:wheat", true),
            new Type("lighthouse", "A lighthouse", "minecraft:sea_lantern", true),
            new Type("jungle_temple", "A temple", "minecraft:mossy_cobblestone", true),
            new Type("secret_treehouse", "A treehouse", "minecraft:oak_leaves", true),
            new Type("underground_base", "An underground base", "minecraft:iron_trapdoor", true),
            new Type("hedge_maze", "A maze", "minecraft:azalea_leaves", true),
            new Type("playground", "A playground", "minecraft:oak_slab", true),
            new Type("stone_well", "A well", "minecraft:water_bucket", true),
            new Type("pirate_ship", "A pirate ship", "minecraft:oak_boat", false),
            new Type("floating_island", "A floating island", "minecraft:grass_block", false),
            new Type("underwater_dome", "An underwater dome", "minecraft:glass", false),
            // the working builds, last so no older tile moves. Each is what any survival player builds early on.
            new Type("farm_plot", "A farm plot", "minecraft:wooden_hoe", true, true),
            new Type("animal_pen", "An animal pen", "minecraft:lead", true, true),
            new Type("tree_nursery", "A tree nursery", "minecraft:oak_sapling", true, true),
            new Type("monster_cave", "A monster cave", "minecraft:skeleton_skull", true, true));

    /** What goes outside: laid out from the front door, outside the footprint (see {@link #shapes}). */
    public record Outside(String id, String label, String tile, List<String> words) {
    }

    public static final String NOTHING = "nothing";

    public static final List<Outside> OUTSIDE = List.of(
            new Outside(NOTHING, "Nothing outside", "minecraft:barrier", List.of("just the building", "nothing else", "on its own")),
            new Outside("garden", "A flower garden", "minecraft:poppy", List.of("garden", "flowers", "flower garden", "flower beds", "garden patch")),
            new Outside("lamps", "Lamp posts", "minecraft:lantern", List.of("lamp posts", "lamps", "lights outside", "street lamps")),
            new Outside("path", "A path to the door", "minecraft:cobblestone", List.of("path", "a path", "walkway", "path to the door", "front path")),
            new Outside("pool", "A pool", "minecraft:water_bucket", List.of("pool", "swimming pool", "pond", "a pool outside")),
            new Outside("flag", "A flag", "minecraft:red_banner", List.of("flag", "a flag", "flagpole", "banner")));

    public static Type type(String id) {
        String bare = id == null ? "" : id.substring(id.indexOf(':') + 1).replaceFirst("^buildings/", "");
        return TYPES.stream().filter(t -> t.id().equals(bare)).findFirst().orElse(null);
    }

    public static Outside outside(String id) {
        String bare = id == null ? "" : id.substring(id.indexOf(':') + 1);
        return OUTSIDE.stream().filter(o -> o.id().equals(bare)).findFirst().orElse(null);
    }

    /**
     * follow-up: what goes outside is SEVERAL things (a garden AND lamp posts), stored as their ids in
     * {@link #OUTSIDE}'s order, comma-joined ("garden,lamps"), or {@link #NOTHING}. One id alone is the old form and
     * reads the same. Unknown ids are kept, so a caller can refuse them by name.
     */
    public static List<String> parse(String outside) {
        List<String> out = new ArrayList<>();
        if (outside == null || outside.isBlank()) {
            return out;
        }
        for (String part : outside.split(",")) {
            String id = part.strip();
            id = id.substring(id.indexOf(':') + 1);
            if (!id.isEmpty() && !id.equals(NOTHING) && !out.contains(id)) {
                out.add(id);
            }
        }
        out.sort(java.util.Comparator.comparingInt(id -> {
            int i = OUTSIDE.indexOf(outside(id));
            return i < 0 ? Integer.MAX_VALUE : i;
        }));
        return out;
    }

    /** The stored form of {@code ids}: comma-joined in {@link #OUTSIDE}'s order, or {@link #NOTHING} for none. */
    public static String join(java.util.Collection<String> ids) {
        List<String> p = parse(String.join(",", ids));
        return p.isEmpty() ? NOTHING : String.join(",", p);
    }

    /** May all of {@code outside} go round {@code t}? Every id known, every one fitting the type. */
    public static boolean fits(Type t, String outside) {
        List<Outside> ok = fitting(t);
        return t != null && parse(outside).stream().allMatch(id -> outside(id) != null && ok.contains(outside(id)));
    }

    /** "a flower garden and lamp posts", for a note; empty for nothing. */
    public static String says(String outside) {
        List<String> words = new ArrayList<>();
        parse(outside).forEach(id -> words.add(outside(id) == null ? id : outside(id).label().toLowerCase(java.util.Locale.ROOT)));
        if (words.size() <= 1) {
            return words.isEmpty() ? "" : words.get(0);
        }
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.get(words.size() - 1);
    }

    /** What may go outside {@code t}: nothing at all for a type that does not stand on the ground, or a working build. */
    public static List<Outside> fitting(Type t) {
        return t == null ? List.of() : t.takesOutside() ? OUTSIDE : List.of(outside(NOTHING));
    }

    /** Is {@code spec} a composed building (a type picked; no ready-made)? */
    public static boolean composed(BuilderSpec spec) {
        return spec.kind() == BuilderSpec.Kind.BUILDING && spec.from().isEmpty() && type(spec.opt("type")) != null;
    }

    /** The opts a composed spec carries. */
    public static java.util.Map<String, String> opts(String type, String outside) {
        java.util.Map<String, String> o = new java.util.LinkedHashMap<>();
        Type t = type(type);
        o.put("type", "preset:" + (t == null ? type : t.preset()));   // a preset id, so a world's allowlist still applies
        o.put("outside", join(parse(outside)));
        return o;
    }

    // ------------------------------------------------------------------ what goes outside, as shapes

    /**
     * The shapes that put {@code outside} round a building whose (already scaled) shapes are {@code body}, laid out from
     * its FRONT DOOR: the path runs from the door straight out to a T; the garden is a bed
     * beside the house on one side and the pool on the other; the flag stands behind, on the pool's side; the lamps
     * stand at the four corners. Nothing but the path is put on the door's side, so nothing closes the door or the
     * path. Everything is at ground level (dy 0, on a dy −1 bed) and outside the footprint, except where the path runs
     * IN over open ground to a door set back inside it (a village's). Empty for nothing.
     *
     * <p>: every one is a GENERATED shape ({@code "gen": true}), outside {@link com.moddingfromamod.builder.BuildPlan#MAX_SHAPES}.
     * That cap is about what an agent describes; these are laid out by the engine and bounded by {@link #OUTSIDE} itself
     * ({@link #MOST_SHAPES} at most, everything picked), so on a big body (a mansion, a hedge maze) they were the shapes
     * past 64 and were cut.
     */
    public static JsonArray shapes(String outside, JsonArray body) {
        JsonArray out = new JsonArray();
        int[] b = footprint(body);
        Front front = null;
        for (String id : parse(outside)) {
            front = front == null ? front(body, b) : front;
            for (JsonElement e : one(id, b, front)) {
                e.getAsJsonObject().addProperty("gen", true);
                out.add(e);
            }
        }
        return out;
    }

    /**
     * the most shapes everything outside lays round any building — five for the garden, eight for the lamps, four for
     * the path, three for the pool, two for the flag. It is what {@link #shapes} can add, whatever the body, and
     * {@code BuilderBuildingComposableTest} holds it to the real count.
     */
    public static final int MOST_SHAPES = 22;

    /**
     * Where a building is entered: the outward direction of the wall its front door is in ({@code fx, fz}, one of them
     * ±1), the door's cell, and the body's blocks as they stand (doors hung). The front door is the lowest door, at the
     * ground storey, nearest the outside — the one he walks in by (as {@code BuildSpots.FRONT} reads it), found after
     * {@link BuildDoors} has hung the Builder's doors in their doorways, so it is the door he sees. No door at the
     * ground: the south side, in the middle (the {@code front} spot's own default). {@code tier}: 0 a doorway, door, gate
     * or hatch; 1 stairs or a ladder up; 2 a gap in the outer wall; 3 none (the south side).
     */
    record Front(int fx, int fz, int doorX, int doorY, int doorZ, int tier, Map<Long, String> cells) {
        /** Clockwise from the front (the {@code beside} side). */
        int rx() {
            return -fz;
        }

        int rz() {
            return fx;
        }
    }

    static Front front(JsonArray body, int[] b) {
        JsonObject o = new JsonObject();
        o.addProperty("id", "outside");
        o.add("shapes", body);
        BuildPlan plan = BuildPlan.fromJson(o);
        Map<Long, String> c = plan == null ? Map.of() : BuildDoors.applied(plan, BuildDoors.of(plan));
        // Candidates, best first: {tier, distance to the outside, x, y, z}. Tier 0: a doorway, door, gate or hatch the
        // body draws at the ground; 1: stairs or a ladder up from the ground (a temple's steps, a treehouse's ladder);
        // 2: a gap in its outer wall (a maze's way in). Only at the ground (dy −1…2): a door to a balcony is not a way in.
        int[] best = null;
        for (JsonElement e : body) {
            BuildShape sh = BuildShape.fromJson(e.getAsJsonObject());
            if (sh == null || sh.dy() > 2 || sh.dy() + sh.height() - 1 < -1) {
                continue;
            }
            String block = sh.block().replaceFirst("\\[.*", "");
            String name = sh.name().toLowerCase(java.util.Locale.ROOT);
            boolean air = isAir(block);
            int tier = (air && name.matches(".*(door|gate|entrance|entry|hatch).*") && !name.contains("trap"))
                    || block.endsWith("_door") || block.endsWith("_fence_gate") || block.endsWith("_trapdoor") ? 0
                    : block.endsWith("ladder") || block.endsWith("_stairs") ? 1 : -1;
            if (tier < 0) {
                continue;
            }
            int[][] pick = {null};
            int bottom = Math.max(-1, sh.dy());
            sh.forEachBlock((x, y, z) -> {
                if (y != bottom) {
                    return;
                }
                int[] here = {tier, edge(b, x, z), x, y, z};
                if (pick[0] == null || java.util.Arrays.compare(here, pick[0]) < 0) {
                    pick[0] = here;
                }
            });
            if (pick[0] != null && (best == null || java.util.Arrays.compare(pick[0], best) < 0)) {
                best = pick[0];
            }
        }
        if (best == null) {
            best = gap(c, b);
        }
        if (best == null) {
            return new Front(0, 1, (b[0] + b[2]) / 2, 0, b[3], 3, c);
        }
        int tier = best[0];
        int x = best[2];
        int y = best[3];
        int z = best[4];
        int toW = x - b[0];
        int toE = b[2] - x;
        int toN = z - b[1];
        int toS = b[3] - z;
        int min = Math.min(Math.min(toW, toE), Math.min(toN, toS));
        if (min == toS) {
            return new Front(0, 1, x, y, z, tier, c);
        }
        if (min == toN) {
            return new Front(0, -1, x, y, z, tier, c);
        }
        return min == toE ? new Front(1, 0, x, y, z, tier, c) : new Front(-1, 0, x, y, z, tier, c);
    }

    private static int edge(int[] b, int x, int z) {
        return Math.min(Math.min(x - b[0], b[2] - x), Math.min(z - b[1], b[3] - z));
    }

    /**
     * A way in through the outer wall: a cell on the footprint's edge, open at the ground and above it, with wall
     * beside it along the edge. The south edge first, then north, east, west; on an edge, the cell nearest its middle.
     */
    private static int[] gap(Map<Long, String> c, int[] b) {
        int[][] edges = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        for (int[] e : edges) {
            int[] best = null;
            boolean alongX = e[1] != 0;
            int lo = alongX ? b[0] : b[1];
            int hi = alongX ? b[2] : b[3];
            int fixed = alongX ? (e[1] > 0 ? b[3] : b[1]) : (e[0] > 0 ? b[2] : b[0]);
            for (int a = lo + 1; a < hi; a++) {
                int x = alongX ? a : fixed;
                int z = alongX ? fixed : a;
                boolean open = c.get(key(x, 0, z)) == null && c.get(key(x, 1, z)) == null;
                int px = alongX ? x - 1 : x;
                int pz = alongX ? z : z - 1;
                int nx = alongX ? x + 1 : x;
                int nz = alongX ? z : z + 1;
                boolean walled = c.get(key(px, 0, pz)) != null || c.get(key(nx, 0, nz)) != null;
                if (open && walled) {
                    int[] here = {2, Math.abs(2 * a - lo - hi), x, 0, z};
                    if (best == null || java.util.Arrays.compare(here, best) < 0) {
                        best = here;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /**
     * The building's own frame: {@code r} along the front wall (clockwise is +), {@code f} out of it (0 is the front
     * edge of the footprint, the back edge is negative), the door's column at r = 0. World x, z of (r, f).
     */
    private static int wx(Front fr, int[] b, int r, int f) {
        int ox = fr.fz() != 0 ? fr.doorX() : (fr.fx() > 0 ? b[2] : b[0]);
        return ox + r * fr.rx() + f * fr.fx();
    }

    private static int wz(Front fr, int[] b, int r, int f) {
        int oz = fr.fz() != 0 ? (fr.fz() > 0 ? b[3] : b[1]) : fr.doorZ();
        return oz + r * fr.rz() + f * fr.fz();
    }

    /** A box over the rectangle r0..r1 × f0..f1 of the frame, from {@code y} up {@code h}. */
    private static JsonObject rect(Front fr, int[] b, String name, String block, int r0, int r1, int f0, int f1, int y, int h,
                                   boolean hollow) {
        int xa = wx(fr, b, r0, f0);
        int xb = wx(fr, b, r1, f1);
        int za = wz(fr, b, r0, f0);
        int zb = wz(fr, b, r1, f1);
        return box(name, block, Math.min(xa, xb), y, Math.min(za, zb), Math.abs(xa - xb) + 1, h, Math.abs(za - zb) + 1, hollow);
    }

    /** The footprint in the frame: {rMin, rMax, fBack} (the front edge is f = 0). */
    private static int[] local(Front fr, int[] b) {
        int ox = wx(fr, b, 0, 0);
        int oz = wz(fr, b, 0, 0);
        int rMin = Integer.MAX_VALUE;
        int rMax = Integer.MIN_VALUE;
        int fBack = 0;
        for (int[] corner : new int[][] {{b[0], b[1]}, {b[2], b[1]}, {b[0], b[3]}, {b[2], b[3]}}) {
            int r = (corner[0] - ox) * fr.rx() + (corner[1] - oz) * fr.rz();
            int f = (corner[0] - ox) * fr.fx() + (corner[1] - oz) * fr.fz();
            rMin = Math.min(rMin, r);
            rMax = Math.max(rMax, r);
            fBack = Math.min(fBack, f);
        }
        return new int[] {rMin, rMax, fBack};
    }

    /** How far the path may run IN, over open ground, to a door set back inside the footprint. */
    static final int PATH_IN_MOST = 16;
    /** How far the path runs out of the front, and the T across its end. */
    static final int PATH_OUT = 6;
    static final int TEE_HALF = 4;

    /** One thing outside, laid out from the front {@code fr}. */
    private static JsonArray one(String outside, int[] b, Front fr) {
        JsonArray out = new JsonArray();
        int x0 = b[0] - 2;
        int z0 = b[1] - 2;
        int w = b[2] - b[0] + 5;
        int d = b[3] - b[1] + 5;
        int[] l = local(fr, b);
        int rMin = l[0];
        int rMax = l[1];
        int fBack = l[2];
        int fMid = fBack / 2;
        switch (outside) {
            case "garden" -> {
                // A bed beside the house, on the side counter-clockwise from the front (the pool takes the other):
                // three rows along the wall, poppies, a furrow, dandelions, inside a stone kerb.
                int along = Math.max(5, Math.min(9, 1 - fBack));
                int f0 = fMid - along / 2;
                int f1 = f0 + along - 1;
                int rOut = rMin - 7;
                int rIn = rMin - 3;
                out.add(rect(fr, b, "garden_bed", "minecraft:grass_block", rOut, rIn, f0, f1, -1, 1, false));
                out.add(rect(fr, b, "garden_kerb", "minecraft:stone_brick_slab", rOut, rIn, f0, f1, 0, 1, true));
                out.add(rect(fr, b, "garden_row_poppies", "minecraft:poppy", rIn - 1, rIn - 1, f0 + 1, f1 - 1, 0, 1, false));
                out.add(rect(fr, b, "garden_furrow", "minecraft:coarse_dirt", rIn - 2, rIn - 2, f0 + 1, f1 - 1, -1, 1, false));
                out.add(rect(fr, b, "garden_row_dandelions", "minecraft:dandelion", rIn - 3, rIn - 3, f0 + 1, f1 - 1, 0, 1, false));
            }
            case "lamps" -> {
                int[][] corners = {{x0, z0}, {x0 + w - 1, z0}, {x0, z0 + d - 1}, {x0 + w - 1, z0 + d - 1}};
                int i = 0;
                for (int[] c : corners) {
                    out.add(box("lamp_post_" + i, "minecraft:oak_fence", c[0], 0, c[1], 1, 2, 1, false));
                    out.add(box("lamp_" + i, "minecraft:lantern", c[0], 2, c[1], 1, 1, 1, false));
                    i++;
                }
            }
            case "path" -> {
                // Three wide from the door straight out, then a T across its end. Each strip runs in as far as the
                // ground is open (to the door itself when it is set back), and stops at the first block of the body.
                int doorF = (fr.doorX() - wx(fr, b, 0, 0)) * fr.fx() + (fr.doorZ() - wz(fr, b, 0, 0)) * fr.fz();
                for (int r = -1; r <= 1; r++) {
                    int in = 1;
                    while (in > 1 - PATH_IN_MOST && in - 1 > doorF && open(fr, b, r, in - 1)) {
                        in--;   // never into the doorway itself, nor past it into the house
                    }
                    out.add(rect(fr, b, "path_" + (r + 1), "minecraft:dirt_path", r, r, in, PATH_OUT, -1, 1, false));
                }
                out.add(rect(fr, b, "path_tee", "minecraft:dirt_path", -TEE_HALF, TEE_HALF, PATH_OUT + 1, PATH_OUT + 2, -1, 1, false));
            }
            case "pool" -> {
                // Beside it, on the clockwise side: a raised pool ON the ground (floor at -1, a rim round water at 0).
                // Never below -1: a pool dug deeper moved the build's base down, and the body's ground floor then
                // stood on nothing.
                out.add(rect(fr, b, "pool_floor", "minecraft:stone_bricks", rMax + 4, rMax + 10, fMid - 2, fMid + 2, -1, 1, false));
                out.add(rect(fr, b, "pool_rim", "minecraft:stone_bricks", rMax + 4, rMax + 10, fMid - 2, fMid + 2, 0, 1, true));
                out.add(rect(fr, b, "pool_water", "minecraft:water", rMax + 5, rMax + 9, fMid - 1, fMid + 1, 0, 1, false));
            }
            case "flag" -> {
                // Behind, on the pool's side, its flag flying out away from the house.
                out.add(rect(fr, b, "flag_pole", "minecraft:oak_fence", rMax + 3, rMax + 3, fBack - 2, fBack - 2, 0, 6, false));
                out.add(rect(fr, b, "flag", "minecraft:red_wool", rMax + 4, rMax + 6, fBack - 2, fBack - 2, 4, 2, false));
            }
            default -> throw new IllegalArgumentException("nothing called '" + outside + "' goes outside a building");
        }
        return out;
    }

    /** Is the ground at (r, f) open: no block of the body from the ground up past the door's head? */
    private static boolean open(Front fr, int[] b, int r, int f) {
        int x = wx(fr, b, r, f);
        int z = wz(fr, b, r, f);
        for (int y = -1; y <= Math.max(1, fr.doorY() + 2); y++) {
            if (fr.cells().get(key(x, y, z)) != null) {
                return false;
            }
        }
        return true;
    }

    // ComposedPlan's key, which BuildDoors.applied is keyed by.
    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    private static int kx(long k) {
        return (int) ((k >> 40) & 0xFFFFF) - 512;
    }

    private static int ky(long k) {
        return (int) ((k >> 20) & 0xFFFFF) - 512;
    }

    private static int kz(long k) {
        return (int) (k & 0xFFFFF) - 512;
    }

    /** The footprint of the solid shapes: {minX, minZ, maxX, maxZ}, as drawn (anchors honoured). */
    static int[] footprint(JsonArray body) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (JsonElement e : body) {
            BuildShape s = BuildShape.fromJson(e.getAsJsonObject());
            if (s == null || isAir(s.block())) {
                continue;
            }
            minX = Math.min(minX, s.originX());
            minZ = Math.min(minZ, s.originZ());
            maxX = Math.max(maxX, s.originX() + s.width() - 1);
            maxZ = Math.max(maxZ, s.originZ() + s.depth() - 1);
        }
        if (minX == Integer.MAX_VALUE) {
            return new int[] {0, 0, 0, 0};
        }
        return new int[] {minX, minZ, maxX, maxZ};
    }

    private static boolean isAir(String block) {
        String b = block.replaceFirst("\\[.*", "").replaceFirst("^minecraft:", "");
        return b.equals("air") || b.equals("cave_air") || b.equals("void_air");
    }

    private static JsonObject box(String name, String block, int x, int y, int z, int w, int h, int d, boolean hollow) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "box");
        o.addProperty("name", name);
        o.addProperty("block", block);
        o.addProperty("dx", x);
        o.addProperty("dy", y);
        o.addProperty("dz", z);
        o.addProperty("width", w);
        o.addProperty("height", h);
        o.addProperty("depth", d);
        o.addProperty("hollow", hollow);
        return o;
    }

    /** Every type's preset id, for the tests and the allowlist. */
    public static List<String> presets() {
        List<String> out = new ArrayList<>();
        TYPES.forEach(t -> out.add(t.preset()));
        return out;
    }
}
