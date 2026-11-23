package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;

/**
 * <b>A simple building, built from parts</b>: a house, a cottage, a tower, a barn or a
 * shop, generated from a small template: 1 to 3 FLOORS (each storey 3 high, a ladder up the back wall through a hole in
 * each floor), a ROOF (flat, pointed, sloped or a dome, all solid so no attic is a room nobody can reach), the DOOR on the
 * side he picks, and WINDOWS (none, one a wall, or lots). Never a ready-made's bespoke shapes: those stay whole-body types
 * ({@link BuilderBuildings}), because a template cannot rebuild the cozy house's yard or the wizard tower's stairs exactly.
 *
 * <p>Every combination is walked and checked to stand in Gate A ({@code BuilderHousesTest}), by name. No count is capped
 * by hand: the limits are the template's own (three floors, the four roofs, the four sides). Pure.
 */
public final class BuilderHouses {

    private BuilderHouses() {
    }

    /** A simple type: its footprint, the blocks it is made of, and the kid's words. */
    public record Simple(String id, String label, String noun, String tile, int width, int depth, String wall, String floor,
                         String roof, List<String> words) {
    }

    public static final List<Simple> SIMPLE = List.of(
            new Simple("house", "A house", "House", "minecraft:oak_planks", 7, 7, "minecraft:oak_planks", "minecraft:spruce_planks",
                    "minecraft:dark_oak_planks", List.of("house", "home", "my house", "a house to live in", "two storey house")),
            new Simple("cottage", "A cottage", "Cottage", "minecraft:cobblestone", 5, 7, "minecraft:cobblestone", "minecraft:oak_planks",
                    "minecraft:spruce_planks", List.of("cottage", "cabin", "hut", "little house")),
            new Simple("tower", "A tower", "Tower", "minecraft:stone_bricks", 5, 5, "minecraft:stone_bricks", "minecraft:oak_planks",
                    "minecraft:deepslate_tiles", List.of("tower", "lookout tower", "watchtower", "tall tower")),
            new Simple("barn", "A barn", "Barn", "minecraft:red_terracotta", 9, 11, "minecraft:red_terracotta", "minecraft:oak_planks",
                    "minecraft:dark_oak_planks", List.of("barn", "stable", "shed", "big shed")),
            new Simple("shop", "A shop", "Shop", "minecraft:emerald", 7, 9, "minecraft:birch_planks", "minecraft:oak_planks",
                    "minecraft:green_terracotta", List.of("shop", "store", "market", "stall")));

    /** The roofs: id → label, tile. */
    public static final Map<String, String[]> ROOFS = new java.util.LinkedHashMap<>();
    /** The windows: id → label, tile. */
    public static final Map<String, String[]> WINDOWS = new java.util.LinkedHashMap<>();
    /** The kid's words for each roof and each windows pick. */
    public static final Map<String, List<String>> PART_WORDS = Map.of(
            "pointed", List.of("pointed roof", "pointy roof", "triangle roof"),
            "sloped", List.of("sloped roof", "slanted roof", "gable roof"),
            "flat", List.of("flat roof", "rooftop", "roof terrace"),
            "dome", List.of("dome", "domed roof", "round roof"),
            "some", List.of("windows", "some windows", "a few windows"),
            "lots", List.of("lots of windows", "many windows", "glass walls"),
            "none", List.of("no windows", "without windows", "windowless"));
    /** The door's side: id → label. */
    public static final Map<String, String> DOORS = new java.util.LinkedHashMap<>();

    static {
        ROOFS.put("pointed", new String[] {"A pointed roof", "minecraft:spruce_stairs"});
        ROOFS.put("sloped", new String[] {"A sloped roof", "minecraft:oak_stairs"});
        ROOFS.put("flat", new String[] {"A flat roof", "minecraft:smooth_stone"});   // the slab has no Bedrock picture (a star)
        ROOFS.put("dome", new String[] {"A dome", "minecraft:white_concrete"});
        WINDOWS.put("some", new String[] {"Some windows", "minecraft:glass_pane"});
        WINDOWS.put("lots", new String[] {"Lots of windows", "minecraft:glass"});
        WINDOWS.put("none", new String[] {"No windows", "minecraft:stone"});
        DOORS.put("front", "At the front");
        DOORS.put("back", "At the back");
        DOORS.put("left", "On the left");
        DOORS.put("right", "On the right");
    }

    public static final int TALLEST = 3;   // the template's own: a fourth storey's ladder would pass 12 blocks

    public static Simple simple(String id) {
        String bare = id == null ? "" : id.substring(id.indexOf(':') + 1);
        return SIMPLE.stream().filter(s -> s.id().equals(bare)).findFirst().orElse(null);
    }

    /** Is {@code spec} a simple building built from parts? */
    public static boolean composed(BuilderSpec spec) {
        return spec.kind() == BuilderSpec.Kind.BUILDING && spec.from().isEmpty() && spec.opt("type").startsWith("simple:")
                && simple(spec.opt("type")) != null;
    }

    /** The opts a simple building carries. */
    public static Map<String, String> opts(String type, int floors, String roof, String door, String windows, String outside) {
        Map<String, String> o = new java.util.LinkedHashMap<>();
        o.put("type", "simple:" + simple(type).id());
        o.put("floors", String.valueOf(floors));
        o.put("roof", roof);
        o.put("door", door);
        o.put("windows", windows);
        o.put("outside", BuilderBuildings.join(BuilderBuildings.parse(outside)));
        return o;
    }

    /** {@code spec}'s shapes (a simple building's spec), its floors defaulting to one. */
    public static JsonArray shapes(BuilderSpec spec) {
        int floors = spec.opt("floors").isEmpty() ? 1 : Integer.parseInt(spec.opt("floors").strip());
        return shapes(simple(spec.opt("type")), floors, or(spec.opt("roof"), "pointed"), or(spec.opt("door"), "front"),
                or(spec.opt("windows"), "some"));
    }

    private static String or(String v, String dflt) {
        return v == null || v.isEmpty() ? dflt : v;
    }

    /** "A two-storey house" is still a House: the noun he gets by default. */
    public static String name(Simple s) {
        return s == null ? "Building" : s.noun();
    }

    /**
     * The shapes, centre-anchored on the footprint, base at dy 0 (floor at −1): the body, the roof, then every room
     * cleared again, the floors and their ladder holes, the ladder, the door and the windows last, so nothing a later
     * shape draws can close a room, a hole or a doorway.
     */
    public static JsonArray shapes(Simple s, int floors, String roof, String door, String windows) {
        if (s == null || floors < 1 || floors > TALLEST || !ROOFS.containsKey(roof) || !DOORS.containsKey(door)
                || !WINDOWS.containsKey(windows)) {
            throw new IllegalArgumentException("a " + (s == null ? "building" : s.id()) + " cannot be " + floors + " floors, "
                    + roof + " roof, door " + door + ", " + windows + " windows");
        }
        int w = s.width();
        int d = s.depth();
        // Each storey: 3 of air and a floor above it. A TOWER (one floor at 4 tall on a 5-wide base was "a pointy
        // house") has storeys of 4 of air and always one more of them than its floors, a lookout room on top, with a
        // ring under the eaves and a roof shorter than its shaft: one floor is a 10-tall shaft on a 5-wide base.
        boolean tower = s.id().equals("tower");
        int st = tower ? 5 : 4;
        int levels = tower ? floors + 1 : floors;
        int h = st * levels;
        int west = -(w / 2);
        int north = -(d / 2);
        JsonArray out = new JsonArray();
        out.add(box("floor", s.floor(), 0, -1, 0, w, 1, d, false, true));
        out.add(box("walls", s.wall(), 0, 0, 0, w, h, d, true, true));
        switch (roof) {
            case "flat" -> out.add(box("roof", s.roof(), 0, h, 0, w, 1, d, false, true));
            case "pointed" -> {
                int side = Math.max(w, d) + 2;
                out.add(shape("pyramid", "roof", s.roof(), 0, h, 0, w + 2, tower ? w : side / 2 + 1, d + 2, false));
            }
            case "sloped" -> {
                // A gable, ridge running front to back: each layer two narrower, the overhang one each side.
                for (int i = 0, wide = w + 2; wide > 0; i++, wide -= 2) {
                    out.add(box("roof_" + i, s.roof(), 0, h + i, 0, wide, 1, d + 2, false, true));
                }
            }
            case "dome" -> out.add(shape("sphere", "roof", s.roof(), 0, h - w / 2, 0, w, w, d, false));
            default -> throw new IllegalArgumentException(roof);
        }
        if (tower) {
            out.add(box("parapet", s.wall(), 0, h - 1, 0, w + 2, 1, d + 2, true, true));   // a ring under the eaves
        }
        for (int k = 0; k < levels; k++) {
            out.add(box("room_" + k, "minecraft:air", 0, st * k, 0, w - 2, st - 1, d - 2, false, true));
            out.add(box("ceiling_" + k, s.floor(), 0, st * k + st - 1, 0, w - 2, 1, d - 2, false, true));
        }
        // The ladder: up the back (north) wall, one in from the west wall, through a hole in every floor but the top.
        int lx = west + 1;
        int lz = north + 1;
        if (levels > 1) {
            out.add(corner("ladder", "minecraft:ladder[facing=south]", lx, 0, lz, 1, st * (levels - 1), 1));
        }
        // The door: a doorway two high in the middle of the side he picked (the Builder hangs a real door in it).
        int[] dp = doorAt(s, door);
        out.add(corner("door", "minecraft:air", dp[0], 0, dp[1], 1, 2, 1));
        // Windows, one block up in every storey: one in the middle of each wall, or every other block along it.
        if (!windows.equals("none")) {
            for (int k = 0; k < levels; k++) {
                int y = st * k + 1;
                for (int[] c : windowCells(s, windows)) {
                    boolean overDoor = k == 0 && c[0] == dp[0] && c[1] == dp[1];
                    boolean behindLadder = c[0] == lx && c[1] == north;
                    if (!overDoor && !behindLadder) {
                        out.add(corner("window", "minecraft:glass_pane", c[0], y, c[1], 1, 1, 1));
                    }
                }
            }
        }
        return out;
    }

    /** The doorway's cell, in the wall on that side, in the middle of it. */
    static int[] doorAt(Simple s, String side) {
        int west = -(s.width() / 2);
        int north = -(s.depth() / 2);
        int east = west + s.width() - 1;
        int south = north + s.depth() - 1;
        return switch (side) {
            case "back" -> new int[] {west + s.width() / 2 + 1, north};   // beside the ladder, never behind it
            case "left" -> new int[] {west, 0};
            case "right" -> new int[] {east, 0};
            default -> new int[] {0, south};
        };
    }

    static List<int[]> windowCells(Simple s, String windows) {
        int west = -(s.width() / 2);
        int north = -(s.depth() / 2);
        int east = west + s.width() - 1;
        int south = north + s.depth() - 1;
        List<int[]> out = new java.util.ArrayList<>();
        if (windows.equals("some")) {
            out.add(new int[] {0, north});
            out.add(new int[] {0, south});
            out.add(new int[] {west, 0});
            out.add(new int[] {east, 0});
            return out;
        }
        for (int x = west + 2; x <= east - 2; x += 2) {
            out.add(new int[] {x, north});
            out.add(new int[] {x, south});
        }
        for (int z = north + 2; z <= south - 2; z += 2) {
            out.add(new int[] {west, z});
            out.add(new int[] {east, z});
        }
        return out;
    }

    private static JsonObject box(String name, String block, int x, int y, int z, int w, int h, int d, boolean hollow,
                                  boolean centre) {
        JsonObject o = shape("box", name, block, x, y, z, w, h, d, hollow);
        if (!centre) {
            o.remove("anchor");
        }
        return o;
    }

    private static JsonObject corner(String name, String block, int x, int y, int z, int w, int h, int d) {
        return box(name, block, x, y, z, w, h, d, false, false);
    }

    private static JsonObject shape(String kind, String name, String block, int x, int y, int z, int w, int h, int d,
                                    boolean hollow) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("name", name);
        o.addProperty("block", block);
        o.addProperty("anchor", "centre");
        o.addProperty("dx", x);
        o.addProperty("dy", y);
        o.addProperty("dz", z);
        o.addProperty("width", w);
        o.addProperty("height", h);
        o.addProperty("depth", d);
        o.addProperty("hollow", hollow);
        return o;
    }
}
