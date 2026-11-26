package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>The Builder's colour schemes</b>: a pick in the tune dialog, turned into the
 * {@code recolour} argument {@code sculpt_creature} already takes with {@code start_from}. The only
 * Builder-owned catalogue, because no tool has a notion of a "scheme"; it lowers to plain colours, so
 * the tool sees nothing new (principle 6).
 *
 * <p><b>How a scheme lands on a body it has never seen.</b> A ready-made's parts are grouped by their
 * ORIGINAL colour and the groups ranked by volume: the biggest colour becomes the scheme's main, the
 * next its accent, the third its trim. Anything after that (small details) and every FACE part keep
 * their colour, so a recoloured cat still has its eyes. Pure; {@code SchemesTest} runs it over every
 * starter creature.
 */
public final class Schemes {

    private Schemes() {
    }

    /**
     * One scheme: three colours (a creature's parts, a picture's rectangles) and the three BLOCKS a building
     * is rebuilt in — main, accent and trim in both, by the same rank rule.
     */
    public record Scheme(String id, String label, String main, String accent, String trim,
                         String mainBlock, String accentBlock, String trimBlock, boolean skin,
                         List<String> words) {
        /** A scheme that is not a skin. {@code words} is what a child calls it: the Apprentice wish box matches typed words to these. */
        public Scheme(String id, String label, String main, String accent, String trim,
                      String mainBlock, String accentBlock, String trimBlock, List<String> words) {
            this(id, label, main, accent, trim, mainBlock, accentBlock, trimBlock, false, words);
        }

        /**
         * The colour (or block) for the {@code rank}-th biggest original, or null to keep it. A scheme repaints the
         * three biggest; a SKIN ("made of ice") repaints every one, cycling its three shades, so nothing of
         * the old colours is left but the face.
         */
        String forRank(int rank, List<String> of) {
            return rank < 3 ? of.get(rank) : skin ? of.get(rank % 3) : null;
        }

        public List<String> colours() {
            return List.of(main, accent, trim);
        }

        public List<String> blocks() {
            return List.of(mainBlock, accentBlock, trimBlock);
        }
    }

    /**
     * ONE colour, all over — "a red cat" is red. A solid repaints every part (as a skin does)
     * in its colour and two close shades of it, so eyes and trim still read; a building is rebuilt in its three blocks.
     * The plain colour words are theirs; a three-tone scheme keeps its theme words and its pairs ("red and orange").
     */
    static Scheme solid(String id, String label, String hex, String mainBlock, String accentBlock, String trimBlock,
                        String... words) {
        return new Scheme(id, label, hex, shade(hex, 1.25), shade(hex, 0.75), mainBlock, accentBlock, trimBlock, true,
                List.of(words));
    }

    /** {@code hex} lighter (factor over 1) or darker (under 1), each channel clamped. */
    static String shade(String hex, double f) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        java.util.function.IntUnaryOperator c = v -> (int) Math.max(0, Math.min(255, f >= 1 ? v + (255 - v) * (f - 1) : v * f));
        return String.format("#%02X%02X%02X", c.applyAsInt(r), c.applyAsInt(g), c.applyAsInt(b));
    }

    public static final List<Scheme> SOLIDS = List.of(
            solid("white", "White", "#F2F2F2", "minecraft:white_concrete", "minecraft:white_wool", "minecraft:snow_block",
                    "white", "snow white", "pure white", "white coloured", "white colored"),
            solid("silver", "Silver", "#C0C6CC", "minecraft:light_gray_concrete", "minecraft:iron_block", "minecraft:light_gray_wool",
                    "silver", "silvery", "chrome", "silver coloured", "silver colored"),
            solid("grey", "Grey", "#7A7F85", "minecraft:gray_concrete", "minecraft:stone", "minecraft:gray_wool",
                    "grey", "gray", "stone grey", "grey coloured", "grey colored"),
            solid("black", "Black", "#26272B", "minecraft:black_concrete", "minecraft:black_wool", "minecraft:blackstone",
                    "black", "jet black", "pitch black", "black coloured", "black colored"),
            solid("red", "Red", "#D32F2F", "minecraft:red_concrete", "minecraft:red_wool", "minecraft:red_terracotta",
                    "red", "bright red", "crimson", "scarlet", "cherry red", "red coloured", "red colored"),
            solid("dark_red", "Dark red", "#8B1A1A", "minecraft:red_nether_bricks", "minecraft:red_terracotta", "minecraft:nether_wart_block",
                    "dark red", "maroon", "burgundy", "dark red coloured", "dark red colored"),
            solid("orange", "Orange", "#F57C00", "minecraft:orange_concrete", "minecraft:orange_wool", "minecraft:orange_terracotta",
                    "orange", "bright orange", "orange coloured", "orange colored"),
            solid("yellow", "Yellow", "#FBC02D", "minecraft:yellow_concrete", "minecraft:yellow_wool", "minecraft:yellow_terracotta",
                    "yellow", "bright yellow", "lemon yellow", "yellow coloured", "yellow colored"),
            solid("lime", "Lime", "#7CB342", "minecraft:lime_concrete", "minecraft:lime_wool", "minecraft:lime_terracotta",
                    "lime", "lime green", "neon green", "lime coloured", "lime colored"),
            solid("green", "Green", "#2E7D32", "minecraft:green_concrete", "minecraft:green_wool", "minecraft:green_terracotta",
                    "green", "dark green", "bright green", "green coloured", "green colored"),
            solid("teal", "Teal", "#00897B", "minecraft:cyan_concrete", "minecraft:cyan_wool", "minecraft:cyan_terracotta",
                    "teal", "turquoise", "cyan", "aqua", "teal coloured", "teal colored"),
            solid("light_blue", "Light blue", "#4FC3F7", "minecraft:light_blue_concrete", "minecraft:light_blue_wool",
                    "minecraft:light_blue_terracotta", "light blue", "sky blue", "baby blue", "light blue coloured", "light blue colored"),
            solid("blue", "Blue", "#1E5BD8", "minecraft:blue_concrete", "minecraft:blue_wool", "minecraft:blue_terracotta",
                    "blue", "bright blue", "blue coloured", "blue colored"),
            solid("navy", "Navy", "#1A237E", "minecraft:blue_terracotta", "minecraft:blue_concrete", "minecraft:black_concrete",
                    "navy", "navy blue", "dark blue", "navy coloured", "navy colored"),
            solid("purple", "Purple", "#7B1FA2", "minecraft:purple_concrete", "minecraft:purple_wool", "minecraft:purple_terracotta",
                    "purple", "violet", "lavender", "purple coloured", "purple colored"),
            solid("pink", "Pink", "#F06292", "minecraft:pink_concrete", "minecraft:pink_wool", "minecraft:pink_terracotta",
                    "pink", "hot pink", "bright pink", "pink coloured", "pink colored"),
            solid("brown", "Brown", "#6D4C41", "minecraft:brown_concrete", "minecraft:brown_wool", "minecraft:brown_terracotta",
                    "brown", "dark brown", "brown coloured", "brown colored"));

    /**
     * each solid's three-tone LEAD — the scheme a building takes for one colour word ("a red house" is the Lava
     * scheme, its three blocks led by red), unless he says all/just/only/solid. A solid with none stays solid.
     */
    public static final Map<String, String> LEAD = Map.ofEntries(
            Map.entry("white", "cloud"), Map.entry("silver", "cloud"), Map.entry("grey", "cloud"), Map.entry("red", "lava"),
            Map.entry("dark_red", "lava"), Map.entry("orange", "sunset"), Map.entry("yellow", "gold"), Map.entry("lime", "slime"),
            Map.entry("green", "forest"), Map.entry("teal", "ocean"), Map.entry("light_blue", "ice"), Map.entry("blue", "ocean"),
            Map.entry("navy", "ocean"), Map.entry("purple", "royal"), Map.entry("pink", "candy"), Map.entry("brown", "forest"));

    /** the colours a three-tone scheme is made of, so two colour words find the closest ("red and orange" → Sunset). */
    public static final Map<String, List<String>> HUES = Map.ofEntries(
            Map.entry("ocean", List.of("blue", "light_blue", "white", "navy", "teal")),
            Map.entry("sunset", List.of("orange", "yellow", "red", "pink", "purple")),
            Map.entry("forest", List.of("green", "brown", "lime")),
            Map.entry("candy", List.of("pink", "light_blue", "white")),
            Map.entry("royal", List.of("purple", "gold", "white", "yellow")),
            Map.entry("ice", List.of("light_blue", "white", "blue")),
            Map.entry("lava", List.of("red", "orange", "black", "dark_red")),
            Map.entry("crystal", List.of("purple", "pink")),
            Map.entry("slime", List.of("lime", "green")),
            Map.entry("cloud", List.of("white", "grey", "silver")),
            Map.entry("gold", List.of("gold", "yellow")));

    /** Is this scheme one colour all over (a solid, or Gold)? */
    public static boolean solid(String id) {
        return "gold".equals(id) || SOLIDS.stream().anyMatch(s -> s.id().equals(id));
    }

    public static final List<Scheme> THREE_TONE = List.of(
            new Scheme("ocean", "Ocean", "#2E7DD1", "#7FD6E8", "#F2F7FA",
                    "minecraft:prismarine_bricks", "minecraft:dark_prismarine", "minecraft:light_blue_concrete", List.of("ocean", "sea colours", "water colours", "ocean colours", "ocean colors", "sea colors", "like the sea", "like the ocean", "sea water", "ocean water")),
            new Scheme("sunset", "Sunset", "#F26B38", "#FFC94A", "#B5367A",
                    "minecraft:orange_terracotta", "minecraft:yellow_concrete", "minecraft:magenta_terracotta", List.of("sunset", "warm colours", "yellow and orange", "red and orange", "orange and pink", "sunset colours", "sunset colors", "sun goes down", "like the sunset")),
            new Scheme("forest", "Forest", "#3F8A3A", "#8C5A2B", "#C9E27A",
                    "minecraft:green_terracotta", "minecraft:spruce_planks", "minecraft:lime_concrete", List.of("forest", "camo", "leafy", "green and brown", "jungle colours", "forest colours", "forest colors")),
            new Scheme("candy", "Candy", "#FF8FC8", "#B9F2FF", "#FFFFFF",
                    "minecraft:pink_concrete", "minecraft:light_blue_concrete", "minecraft:white_concrete", List.of("candy", "pastel", "bubblegum", "pink and blue", "candy colours", "candy colors")),
            new Scheme("royal", "Royal", "#5B2C9F", "#F2C230", "#FFFFFF",
                    "minecraft:purple_concrete", "minecraft:gold_block", "minecraft:quartz_block", List.of("royal", "fancy", "purple and gold", "king colours", "royal colours", "royal colors", "fit for a king", "like a king")),
            // ── SKINS: "made of" a material. Every colour goes, in the material's three shades; a
            // building is rebuilt in its blocks. No block that hurts or moves him (no magma, no real lava, no
            // slime floor that bounces him off a tower).
            new Scheme("ice", "Ice", "#9FD8F5", "#5FA8E0", "#EAF7FF",
                    "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:snow_block", true, List.of("ice", "icy", "frozen", "frosty", "made of ice", "snowy", "ice colours", "ice colors", "looks frozen")),
            new Scheme("lava", "Lava", "#E8501C", "#FF9A1F", "#5A1A0C",
                    "minecraft:orange_concrete", "minecraft:shroomlight", "minecraft:red_nether_bricks", true, List.of("lava", "magma", "molten", "made of lava", "fiery", "nether", "like the nether", "nether colours", "red and black", "lava colours", "lava colors")),
            new Scheme("crystal", "Crystal", "#B48CF2", "#E3C9FF", "#7A4FC9",
                    "minecraft:amethyst_block", "minecraft:purple_stained_glass", "minecraft:quartz_block", true, List.of("crystal", "amethyst", "gem", "jewel", "made of crystal", "crystal colours", "crystal colors", "gem colours", "gem colors")),
            new Scheme("slime", "Slime", "#7BD24E", "#B6F07A", "#3F8A2A",
                    "minecraft:lime_concrete", "minecraft:lime_stained_glass", "minecraft:green_concrete", true, List.of("slime", "slimy", "gooey", "made of slime", "slime colours", "slime colors")),
            new Scheme("cloud", "Cloud", "#FFFFFF", "#DDE7F0", "#B8C6D4",
                    "minecraft:white_wool", "minecraft:snow_block", "minecraft:light_gray_wool", true, List.of("cloud", "fluffy", "made of clouds", "black and white", "cloudy", "clouds", "like clouds", "like a cloud", "cloud colours", "cloud colors")),
            new Scheme("gold", "Gold", "#F5C518", "#FFE680", "#B8860B",
                    "minecraft:gold_block", "minecraft:yellow_concrete", "minecraft:raw_gold_block", true, List.of("gold", "golden", "made of gold", "shiny gold", "gold colours", "gold colors")));

    /** Every colour the menus offer: the solids first, then the three-tone schemes. */
    public static final List<Scheme> ALL = java.util.stream.Stream.concat(THREE_TONE.stream(), SOLIDS.stream()).toList();   // the three-tone first, as they were; the solids after

    /** Words that mark a part as part of the face, whose colour a scheme never changes. */
    static final List<String> FACE = List.of("eye", "pupil", "iris", "mouth", "teeth", "tooth", "tongue",
            "nose", "nostril", "brow", "lash", "fang", "beak", "cheek", "smile", "lip");

    // ── a world's own palette, inline (#178): no scheme is registered, so the player's list and tree never change ──

    /**
     * The Builder spec opt a world's own make carries its colours in ({@code BuilderMake.forWorld} only; a player's
     * spec has it taken off by {@link BuilderAllowlist#capped(BuilderSpec)}): {@code "#RRGGBB,#RRGGBB,#RRGGBB"} (main,
     * accent, trim), optionally {@code ";block,block,block"} for a building, each block one the core's own schemes
     * already build in. Documented in {@code docs/EXTENDING.md}.
     */
    public static final String PALETTE_OPT = "palette";

    private static final java.util.regex.Pattern HEX = java.util.regex.Pattern.compile("#[0-9A-Fa-f]{6}");

    /** Every block the core's own schemes build in: the reviewed set ("nothing that hurts or moves him"). */
    public static java.util.Set<String> reviewedBlocks() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Scheme s : ALL) {
            out.add(s.mainBlock());
            out.add(s.accentBlock());
            out.add(s.trimBlock());
        }
        out.remove(null);
        return out;
    }

    /**
     * {@code palette} read, or null when it is not one: exactly three {@code #RRGGBB} colours, and, after a {@code ;},
     * exactly three blocks from {@link #reviewedBlocks()}. Without blocks, a building made with it keeps its own.
     */
    public static Scheme parsePalette(String palette) {
        if (palette == null || palette.isBlank()) {
            return null;
        }
        String[] halves = palette.strip().split(";", -1);
        if (halves.length > 2) {
            return null;
        }
        String[] c = halves[0].split(",", -1);
        if (c.length != 3) {
            return null;
        }
        for (int i = 0; i < 3; i++) {
            c[i] = c[i].strip();
            if (!HEX.matcher(c[i]).matches()) {
                return null;
            }
        }
        String[] b = {null, null, null};
        if (halves.length == 2) {
            b = halves[1].split(",", -1);
            if (b.length != 3) {
                return null;
            }
            java.util.Set<String> ok = reviewedBlocks();
            for (int i = 0; i < 3; i++) {
                b[i] = b[i].strip();
                if (!ok.contains(b[i])) {
                    return null;
                }
            }
        }
        return new Scheme("palette", "", c[0].toUpperCase(Locale.ROOT), c[1].toUpperCase(Locale.ROOT),
                c[2].toUpperCase(Locale.ROOT), b[0], b[1], b[2], List.of());
    }

    /**
     * The scheme {@code spec} is made in: its inline {@link #PALETTE_OPT} when it carries one (a world's own make),
     * else its named scheme ({@link #byId}), else null. The one way the lowering asks.
     */
    public static Scheme resolve(BuilderSpec spec) {
        if (spec == null) {
            return null;
        }
        String palette = spec.opt(PALETTE_OPT);
        if (!palette.isEmpty()) {
            return parsePalette(palette);
        }
        return byId(spec.scheme());
    }

    public static Scheme byId(String id) {
        if (id == null) {
            return null;
        }
        String w = Catalogue.prefix(id).equals("scheme") ? Catalogue.bare(id) : id;
        for (Scheme s : ALL) {
            if (s.id().equals(w.toLowerCase(Locale.ROOT))) {
                return s;
            }
        }
        return null;
    }

    /**
     * The {@code recolour} object for {@code parts} (a sculpt's part list) in {@code scheme}: part name
     * → new colour. Empty when there is nothing to repaint.
     */
    public static JsonObject recolour(JsonArray parts, Scheme scheme) {
        JsonObject out = new JsonObject();
        if (parts == null || scheme == null) {
            return out;
        }
        Map<String, Double> volumeByColour = new LinkedHashMap<>();
        for (JsonElement e : parts) {
            JsonObject p = e.getAsJsonObject();
            if (isFace(p) || !p.has("colour")) {
                continue;
            }
            volumeByColour.merge(colourOf(p), volume(p), Double::sum);
        }
        List<String> ranked = new ArrayList<>(volumeByColour.keySet());
        // Biggest first; ties keep the order they first appeared in, so the result is stable.
        ranked.sort((a, b) -> Double.compare(volumeByColour.get(b), volumeByColour.get(a)));
        Map<String, String> remap = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            String to = scheme.forRank(i, scheme.colours());
            if (to != null) {
                remap.put(ranked.get(i), to);
            }
        }
        for (JsonElement e : parts) {
            JsonObject p = e.getAsJsonObject();
            if (isFace(p) || !p.has("colour") || !p.has("name")) {
                continue;
            }
            String target = remap.get(colourOf(p));
            if (target != null) {
                out.addProperty(p.get("name").getAsString(), target);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ buildings and pictures

    /**
     * Blocks a scheme never repaints: openings, light, liquids and the natural world a building sits in or
     * is built from on purpose (a treehouse's leaves and trunk stay a tree).
     */
    static boolean keepsItsBlock(String block) {
        String b = block == null ? "" : block.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        return b.equals("air") || b.contains("glass") || b.equals("glowstone") || b.contains("lantern")
                || b.contains("torch") || b.equals("shroomlight") || b.equals("water") || b.equals("lava")
                || b.contains("leaves") || b.endsWith("_log") || b.endsWith("_wood") || b.equals("grass_block")
                || b.contains("dirt") || b.equals("podzol") || b.equals("mycelium") || b.equals("sand")
                || b.equals("gravel") || b.equals("stone") || b.equals("ladder") || b.contains("door");
    }

    /**
     * A building's shapes in {@code scheme}: block → new block, ranked by the volume each block takes (a hollow
     * shape counts its shell). Biggest → the scheme's main block, then accent, then trim; the rest stay.
     */
    public static Map<String, String> reblock(JsonArray shapes, Scheme scheme) {
        Map<String, Double> volume = new LinkedHashMap<>();
        for (JsonElement e : shapes) {
            JsonObject s = e.getAsJsonObject();
            String block = s.has("block") ? s.get("block").getAsString() : "";
            if (keepsItsBlock(block)) {
                continue;
            }
            volume.merge(block, shapeVolume(s), Double::sum);
        }
        List<String> ranked = new ArrayList<>(volume.keySet());
        ranked.sort((a, b) -> Double.compare(volume.get(b), volume.get(a)));
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            String to = scheme.forRank(i, scheme.blocks());
            if (to != null) {
                out.put(ranked.get(i), to);
            }
        }
        return out;
    }

    static double shapeVolume(JsonObject s) {
        double w = num(s, "width"), h = num(s, "height"), d = num(s, "depth");
        double v = w * h * d;
        if (s.has("hollow") && s.get("hollow").getAsBoolean()) {
            v -= Math.max(0, w - 2) * Math.max(0, h - 2) * Math.max(0, d - 2);
        }
        return Math.max(v, 0);
    }

    /** A picture's rectangles in {@code scheme}: colour → new colour, ranked by AREA. */
    public static Map<String, String> recolourRects(JsonArray rects, Scheme scheme) {
        Map<String, Double> area = new LinkedHashMap<>();
        for (JsonElement e : rects) {
            JsonObject r = e.getAsJsonObject();
            if (r.has("colour")) {
                area.merge(r.get("colour").getAsString().toUpperCase(Locale.ROOT), num(r, "w") * num(r, "h"), Double::sum);
            }
        }
        List<String> ranked = new ArrayList<>(area.keySet());
        ranked.sort((a, b) -> Double.compare(area.get(b), area.get(a)));
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            String to = scheme.forRank(i, scheme.colours());
            if (to != null) {
                out.put(ranked.get(i), to);
            }
        }
        return out;
    }

    private static double num(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0;
    }

    static boolean isFace(JsonObject p) {
        String n = p.has("name") ? p.get("name").getAsString().toLowerCase(Locale.ROOT) : "";
        for (String w : FACE) {
            if (n.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static String colourOf(JsonObject p) {
        return p.get("colour").getAsString().toUpperCase(Locale.ROOT);
    }

    private static double volume(JsonObject p) {
        if (!p.has("from") || !p.has("to")) {
            return 0;
        }
        JsonArray f = p.getAsJsonArray("from");
        JsonArray t = p.getAsJsonArray("to");
        double v = 1;
        for (int i = 0; i < 3; i++) {
            v *= Math.abs(t.get(i).getAsDouble() - f.get(i).getAsDouble());
        }
        return v;
    }
}
