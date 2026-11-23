package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>Supplies: a bundle of a vanilla thing</b> (epic): wood, stone, seeds, torches, iron, food and the
 * everyday work blocks, as REAL vanilla items in his bag. It lowers to {@code give_items} only (which caps its own
 * count), so it is nothing a Magician's wish worker could not already do.
 *
 * <p>General, never for one world: "give me some wood / torches / a furnace" is among the commonest survival wishes
 * anywhere. A world only sets LIMITS ({@code crafters.builder.supplies}, read into {@link Limits}): which
 * {@link Category categories} are offered, the most per make, and whether the lava bucket is offered.
 *
 * <p>The menus: KIND → "What do you need?" (a category) → "Which one?" (skipped for a category of one) → the tune
 * dialog's "How many" (a slider whose top is the item's own most and the world's, never past a stack). Pure:
 * {@code BuilderSuppliesTest}.
 */
public final class BuilderSupplies {

    private BuilderSupplies() {
    }

    /** A category: its page tile and the words that name it. */
    public record Category(String id, String label, String tile, List<String> words) {
    }

    public static final List<Category> CATEGORIES = List.of(
            new Category("wood", "Wood and saplings", "minecraft:oak_log", List.of("wood and saplings", "trees", "lumber")),
            new Category("stone", "Stone and dirt", "minecraft:cobblestone", List.of("stone and dirt", "rocks", "ground blocks")),
            new Category("farming", "Farming", "minecraft:wheat_seeds", List.of("farming", "farm stuff", "growing")),
            new Category("light", "Light", "minecraft:torch", List.of("light", "lights", "lighting")),
            new Category("metal", "Metal and fuel", "minecraft:iron_ingot", List.of("metal and fuel", "metal", "fuel")),
            new Category("food", "Food", "minecraft:apple", List.of("food", "snacks to eat", "something to eat")),
            new Category("work", "Work blocks", "minecraft:crafting_table", List.of("work blocks", "workshop blocks", "useful blocks")));

    /**
     * One supply: the vanilla item, its category, the tile's words ("Oak logs"), the most one make hands over (before
     * the world's own most), how many the dialog starts at, and the words a child calls it by.
     */
    public record Supply(String id, String item, String category, String label, int most, int dflt, List<String> words) {
        /** The catalogue id a spec names it by ({@code supply:oak_log}). */
        public String catalogueId() {
            return id.startsWith(FOUND_PREFIX) ? id : "supply:" + id;
        }
    }

    /**
     *: any installed item, found by name ({@code content/Found}, the Magician's own search), is a supply
     * too: {@code item:<namespace:path>}, in no category, a stack at most, one unless he says how many.
     */
    public static final String FOUND_PREFIX = "item:";
    public static final String FOUND_CATEGORY = "found";

    /** Is this an installed item found by name (not one of the listed supplies)? */
    public static boolean found(String id) {
        return id != null && id.startsWith(FOUND_PREFIX) && supply(id) != null;
    }

    /** The lava bucket's id. */
    public static final String LAVA = "lava_bucket";

    private static Supply s(String id, String category, String label, int most, int dflt, String... words) {
        return new Supply(id, "minecraft:" + id, category, label, most, dflt, List.of(words));
    }

    public static final List<Supply> ALL = List.of(
            s("oak_log", "wood", "Oak logs", 64, 16, "wood", "logs", "oak logs", "log", "oak wood", "tree trunks"),
            s("oak_sapling", "wood", "Oak saplings", 16, 4, "sapling", "saplings", "oak sapling", "baby tree", "tree to plant"),
            s("oak_planks", "wood", "Oak planks", 64, 32, "planks", "wooden planks", "oak planks", "plank", "boards"),
            s("stick", "wood", "Sticks", 64, 16, "sticks", "stick", "twigs"),
            s("cobblestone", "stone", "Cobblestone", 64, 32, "cobblestone", "cobble", "stone", "cobblestones"),
            s("dirt", "stone", "Dirt", 64, 32, "dirt", "soil", "mud", "dirt blocks"),
            s("sand", "stone", "Sand", 64, 16, "sand", "sand blocks", "beach sand"),
            s("gravel", "stone", "Gravel", 64, 16, "gravel", "pebbles", "gravel blocks"),
            s("pointed_dripstone", "stone", "Pointed dripstone", 4, 1, "pointed dripstone", "dripstone", "stalactite", "drip stone"),
            s("wheat_seeds", "farming", "Wheat seeds", 64, 16, "seeds", "wheat seeds", "seed", "seeds for my farm"),
            s("wheat", "farming", "Wheat", 64, 16, "wheat", "hay", "grain"),
            s("bone_meal", "farming", "Bone meal", 64, 16, "bone meal", "bonemeal", "fertiliser", "fertilizer", "plant food"),
            s("sugar_cane", "farming", "Sugar cane", 64, 8, "sugar cane", "sugarcane", "cane", "sugar canes"),
            s("torch", "light", "Torches", 64, 16, "torches", "torch", "lights for the dark", "flaming sticks"),
            s("coal", "metal", "Coal", 64, 16, "coal", "lumps of coal", "coal lumps"),
            s("charcoal", "metal", "Charcoal", 64, 16, "charcoal", "char coal", "burnt wood"),
            s("raw_iron", "metal", "Raw iron", 64, 8, "raw iron", "iron ore", "unsmelted iron", "iron chunks"),
            s("iron_ingot", "metal", "Iron ingots", 64, 8, "iron", "iron ingots", "iron ingot", "ingots", "iron bars"),
            s("apple", "food", "Apples", 64, 8, "apples", "apple", "red apples"),
            s("bread", "food", "Bread", 64, 8, "bread", "loaf", "loaves", "loaf of bread"),
            s("carrot", "food", "Carrots", 64, 8, "carrots", "carrot", "veggies"),
            s("crafting_table", "work", "Crafting table", 2, 1, "crafting table", "workbench", "crafting bench", "craft table"),
            s("furnace", "work", "Furnace", 2, 1, "furnace", "smelter", "smelting furnace"),
            s("chest", "work", "Chest", 2, 1, "chest", "storage chest", "box to keep things"),
            s("cauldron", "work", "Cauldron", 1, 1, "cauldron", "big pot", "witch pot"),
            s("composter", "work", "Composter", 1, 1, "composter", "compost bin", "compost"),
            s("bucket", "work", "Bucket", 2, 1, "bucket", "empty bucket", "pail"),
            s("water_bucket", "work", "Water bucket", 2, 1, "water bucket", "bucket of water", "bucket full of water"),
            s(LAVA, "work", "Lava bucket", 1, 1, "lava bucket", "bucket of lava", "bucket full of lava"));

    /** The most any world may set per make: {@code give_items}' own cap. */
    public static final int CEILING = 256;

    /**
     * A world's limits ({@code crafters.builder.supplies}): the categories offered, the most per make, and whether the
     * lava bucket is offered. {@link #DEFAULT} is every category and a stack at most.
     */
    public record Limits(Set<String> categories, int most) {
        public Limits {
            categories = Set.copyOf(categories);
        }

        public static final Limits DEFAULT = new Limits(ids(), 64);

        private static Set<String> ids() {
            Set<String> out = new java.util.LinkedHashSet<>();
            CATEGORIES.forEach(c -> out.add(c.id()));
            return out;
        }

        /** Is {@code s} offered under these limits (its category)? Lava is offered everywhere. */
        public boolean offers(Supply s) {
            return s != null && (categories.contains(s.category()) || (s.category().equals(FOUND_CATEGORY) && !categories.isEmpty()));
        }

        /**
         * {@code {"categories":[…],"most":n}} over {@code fallback}; throws naming a bad value.
         */
        public static Limits fromJson(JsonObject j, Limits fallback) {
            if (j == null) {
                return fallback;
            }
            Set<String> cats = new java.util.LinkedHashSet<>(fallback.categories());
            if (j.has("categories")) {
                cats.clear();
                for (var e : j.getAsJsonArray("categories")) {
                    String c = e.getAsString();
                    if (category(c) == null) {
                        throw new IllegalArgumentException("crafters.builder.supplies.categories: no category '" + c + "'");
                    }
                    cats.add(c);
                }
            }
            int most = j.has("most") ? j.get("most").getAsInt() : fallback.most();
            if (most < 1 || most > CEILING) {
                throw new IllegalArgumentException("crafters.builder.supplies.most is 1-" + CEILING + ", not " + most);
            }
            if (j.has("lava")) {
                throw new IllegalArgumentException("crafters.builder.supplies.lava is gone: lava is offered in every world");
            }
            return new Limits(cats, most);
        }
    }

    public static Category category(String id) {
        String b = bare(id);
        return CATEGORIES.stream().filter(c -> c.id().equals(b)).findFirst().orElse(null);
    }

    public static Supply supply(String id) {
        if (id != null && id.startsWith(FOUND_PREFIX)) {
            String item = id.substring(FOUND_PREFIX.length());
            String name = com.moddingfromamod.content.Found.name(com.moddingfromamod.content.ContentIndex.Kind.ITEM, item);
            return name == null ? null : new Supply(id, item, FOUND_CATEGORY, name, 64, 1, List.of());
        }
        String b = bare(id);
        return ALL.stream().filter(x -> x.id().equals(b)).findFirst().orElse(null);
    }

    private static String bare(String id) {
        return id == null ? "" : id.substring(id.indexOf(':') + 1);
    }

    /** The supplies of a category, in the menu's order. */
    public static List<Supply> in(String category) {
        String b = bare(category);
        return ALL.stream().filter(x -> x.category().equals(b)).toList();
    }

    /** The most one make of {@code s} hands over on a world of {@code limits}: its own most and the world's. */
    public static int top(Supply s, Limits limits) {
        return Math.max(1, Math.min(s.most(), limits.most()));
    }

    /**
     * The slider's step: one at a time, to the top ("three cakes" is three, not the nearest eight).
     * It was eight steps to the top (8, 16 … 64); the slider is the same control, only finer, on both editions.
     */
    public static int step(int top) {
        return 1;
    }

    /** The slider's highest value: the top, rounded down to a whole step. */
    public static int sliderTop(int top) {
        int st = step(top);
        return st * Math.max(1, top / st);
    }

    /** {@code n} as the dialog would set it: a whole step, between one step and the slider's top. */
    public static int fit(int n, int top) {
        int st = step(top);
        int hi = sliderTop(top);
        int v = Math.max(st, Math.min(hi, n));
        return st == 1 ? v : Math.max(st, (v / st) * st);
    }

    /** Is {@code spec} a supplies make? */
    public static boolean is(BuilderSpec spec) {
        return spec.kind() == BuilderSpec.Kind.SUPPLIES;
    }

    /** How many a supplies spec hands over ({@code opts.amount}; its supply's default when absent). */
    public static int amount(BuilderSpec spec) {
        Supply s = supply(spec.from());
        String a = spec.opt("amount").strip();
        if (a.isEmpty()) {
            return s == null ? 1 : s.dflt();
        }
        try {
            return Integer.parseInt(a);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'amount' is not a whole number: " + a);
        }
    }

    /** "16 oak logs", "a crafting table", "2 furnaces": what a make hands over, in words. */
    public static String words(Supply s, int n) {
        String l = s.label().toLowerCase(java.util.Locale.ROOT);
        if (s.category().equals(FOUND_CATEGORY)) {   //: "16 golden apples", "a pair of shears", "The Last Sword"
            return English.said(s.label(), n, !s.item().startsWith("minecraft:"));
        }
        if (n == 1) {
            return English.withArticle(ONE.getOrDefault(s.id(), l), 1);
        }
        return n + " " + MANY.getOrDefault(s.id(), l);
    }

    /** Is what this make hands over said as several ("Here are 16 oak logs!", "Here are some oak planks!")? */
    public static boolean many(BuilderSpec spec) {
        Supply s = supply(spec.from());
        return amount(spec) != 1 || s != null && s.category().equals(FOUND_CATEGORY) && English.saidAsMany(s.label());
    }

    /** A label that is already plural, said for one ("Oak logs" → "oak log"). */
    private static final Map<String, String> ONE = Map.ofEntries(
            Map.entry("oak_log", "oak log"), Map.entry("oak_sapling", "oak sapling"), Map.entry("oak_planks", "oak plank"),
            Map.entry("stick", "stick"), Map.entry("wheat_seeds", "wheat seed"), Map.entry("torch", "torch"),
            Map.entry("iron_ingot", "iron ingot"), Map.entry("apple", "apple"), Map.entry("carrot", "carrot"),
            Map.entry("cobblestone", "cobblestone block"), Map.entry("dirt", "dirt block"), Map.entry("sand", "sand block"),
            Map.entry("gravel", "gravel block"), Map.entry("bread", "loaf of bread"), Map.entry("coal", "lump of coal"),
            Map.entry("charcoal", "lump of charcoal"), Map.entry("raw_iron", "raw iron"), Map.entry("wheat", "wheat"),
            Map.entry("bone_meal", "bone meal"), Map.entry("sugar_cane", "sugar cane"));

    /** A label that is singular, said for several ("Crafting table" → "crafting tables"). */
    private static final Map<String, String> MANY = Map.ofEntries(
            Map.entry("crafting_table", "crafting tables"), Map.entry("furnace", "furnaces"), Map.entry("chest", "chests"),
            Map.entry("cauldron", "cauldrons"), Map.entry("composter", "composters"), Map.entry("bucket", "buckets"),
            Map.entry("water_bucket", "water buckets"), Map.entry("lava_bucket", "lava buckets"),
            Map.entry("pointed_dripstone", "pointed dripstone"));

    /** The opts a supplies spec carries: how many. */
    public static Map<String, String> opts(int amount) {
        Map<String, String> o = new LinkedHashMap<>();
        o.put("amount", String.valueOf(amount));
        return o;
    }

    /** The {@code give_items} arguments for a supplies spec. Throws, naming it, for one that is not a supply. */
    public static JsonObject args(BuilderSpec spec) {
        Supply s = supply(spec.from());
        if (s == null || !(Catalogue.prefix(spec.from()).equals("supply") || found(spec.from()))) {
            throw new IllegalArgumentException("supplies need a supply, not '" + spec.from() + "'");
        }
        int n = amount(spec);
        if (n < 1 || n > Math.min(s.most(), CEILING)) {
            throw new IllegalArgumentException("supplies of " + s.label().toLowerCase(java.util.Locale.ROOT) + " are 1-"
                    + s.most() + " at once, not " + n);
        }
        JsonObject a = new JsonObject();
        a.addProperty("item", s.item());
        a.addProperty("count", n);
        a.addProperty("why", "Here " + (n == 1 ? "is " : "are ") + words(s, n) + " for you!");
        return a;
    }

    /** Every supply id, for the reviewed list. */
    public static List<String> catalogueIds() {
        List<String> out = new ArrayList<>();
        ALL.forEach(x -> out.add(x.catalogueId()));
        return out;
    }
}
