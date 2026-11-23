package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.item.IconSprites;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>A made-up item's LOOK, from the icon library</b> (a look that is only described has to be
 * taught, so the engine is given what each item looks like). The 204 drawn icons
 * ({@code items/icon-library.json}, the ones the Magician's {@code make_item} picks by name) are offered behind ONE
 * tile on "What is it?", <b>🎨 More looks…</b> (the usual path stays as long as it was):
 * its categories, then its icons. A look picks the item it goes on too ({@link #stockFor}): a hat is worn on the head,
 * a cape on the back, a cake is eaten, a smoothie drunk, a pickaxe mines; anything else is held and tapped, a wand.
 * Its words ({@code items/icon-words.json}, model-written and reviewed, {@code tools/gen-icon-words.py}) are what a
 * child calls it, never a word another pick that starts a make already owns, so "a cat" stays a cat.
 *
 * <p>Pure: {@code BuilderLooksTest}.
 */
public final class BuilderLooks {

    private BuilderLooks() {
    }

    /** The tile on "What is it?". */
    public static final String MORE_ID = "look:more";
    public static final String MORE_LABEL = "🎨 More looks…";
    public static final String MORE_ICON = "minecraft:painting";
    /** A category tile, and a look tile. */
    public static final String GROUP_PREFIX = "lookgroup:";
    public static final String PREFIX = "look:";
    /** The spec's opt, and the tree's slot. */
    public static final String SLOT = "itemlook";
    /** The power a look alone gets ("a crown" does nothing but look grand). */
    public static final String SHOW = "show";
    /** At most this many looks on one page; a bigger category is two groups. */
    static final int MOST = com.moddingfromamod.ui.PickPage.MAX_TILES - 1;

    public record Look(String id, String label, String group, String stock, List<String> words) {
        /**
         * the tile's picture is the look's own drawn icon, the same one the made item wears: a {@code wish:}
         * sprite on the carrier (MenuKit draws it through WatchMenu.iconStack), in its tile colour.
         */
        public String tile() {
            return com.moddingfromamod.marshal.WatchIcons.sprite(id, tileColour(id));
        }
    }

    public record Group(String id, String label, String tile, List<Look> looks) {
    }

    /** Each library category: its label on the tile and a vanilla item every edition draws. */
    private static final Map<String, String[]> CATEGORY = new LinkedHashMap<>();

    static {
        CATEGORY.put("magic & treasure", new String[] {"Hats, crowns & treasure", "minecraft:golden_helmet"});
        CATEGORY.put("food", new String[] {"Food & drinks", "minecraft:bread"});
        CATEGORY.put("sweets & toys", new String[] {"Sweets & toys", "minecraft:cake"});
        CATEGORY.put("vehicles & space", new String[] {"Vehicles & space", "minecraft:minecart"});
        CATEGORY.put("tech, music & sport", new String[] {"Gadgets, music & sport", "minecraft:jukebox"});
        CATEGORY.put("tools & gadgets", new String[] {"Tools", "minecraft:compass"});
        CATEGORY.put("party & nature", new String[] {"Party & nature", "minecraft:firework_rocket"});
        CATEGORY.put("animals", new String[] {"Animal toys", "minecraft:lead"});
        CATEGORY.put("creatures", new String[] {"Creatures & magic toys", "minecraft:dragon_head"});
    }

    static final Set<String> HEAD = Set.of("astronaut_helmet", "crown", "top_hat", "wizard_hat", "party_hat", "sunglasses",
            "headphones");
    static final Set<String> BACK = Set.of("cape", "backpack", "jetpack");
    static final Set<String> DRINK = Set.of("boba_tea", "juice_box", "milkshake", "potion", "smoothie");
    static final Set<String> NOT_FOOD = Set.of("frying_pan", "teapot", "toaster", "cookie_jar");

    /** The stock item a look goes on. */
    static String stockFor(String id, String category) {
        if (HEAD.contains(id)) {
            return "helmet";
        }
        if (BACK.contains(id)) {
            return "chestplate";
        }
        if (DRINK.contains(id)) {
            return "drink";
        }
        if (id.equals("pickaxe")) {
            return "pickaxe";
        }
        if (id.equals("shovel")) {
            return "shovel";
        }
        if ((category.equals("food") || id.equals("candy_cane") || id.equals("chocolate_bar") || id.equals("cotton_candy")
                || id.equals("cupcake") || id.equals("gummy_bear") || id.equals("ice_pop") || id.equals("lollipop")
                || id.equals("marshmallow") || id.equals("wrapped_candy") || id.equals("sundae")) && !NOT_FOOD.contains(id)) {
            return "snack";
        }
        return "wand";
    }

    private static volatile List<Group> groups;

    /** Every group, in the menu's order; a category of more than {@link #MOST} is two. */
    public static List<Group> groups() {
        List<Group> g = groups;
        if (g == null) {
            g = load();
            groups = g;
        }
        return g;
    }

    public static Look look(String id) {
        String bare = id == null ? "" : id.startsWith(PREFIX) ? id.substring(PREFIX.length()) : id;
        for (Group g : groups()) {
            for (Look l : g.looks()) {
                if (l.id().equals(bare)) {
                    return l;
                }
            }
        }
        return null;
    }

    public static Group group(String id) {
        String bare = id == null ? "" : id.startsWith(GROUP_PREFIX) ? id.substring(GROUP_PREFIX.length()) : id;
        for (Group g : groups()) {
            if (g.id().equals(bare)) {
                return g;
            }
        }
        return null;
    }

    /** Every look, in the menu's order. */
    public static List<Look> all() {
        List<Look> out = new ArrayList<>();
        groups().forEach(g -> out.addAll(g.looks()));
        return out;
    }

    private static List<Group> load() {
        Map<String, List<String>> words = words();
        Map<String, List<Look>> byCategory = new LinkedHashMap<>();
        CATEGORY.keySet().forEach(c -> byCategory.put(c, new ArrayList<>()));
        for (IconSprites.LibraryIcon icon : IconSprites.drawn()) {
            List<Look> in = byCategory.get(icon.category());
            if (in == null) {
                throw new IllegalStateException("icon " + icon.name() + " has a category the Builder does not list: "
                        + icon.category() + " (BuilderLooks.CATEGORY)");
            }
            in.add(new Look(icon.name(), label(icon.name()), "", stockFor(icon.name(), icon.category()),
                    words.getOrDefault(icon.name(), List.of())));
        }
        List<Group> out = new ArrayList<>();
        for (Map.Entry<String, List<Look>> e : byCategory.entrySet()) {
            List<Look> looks = e.getValue();
            looks.sort(java.util.Comparator.comparing(Look::label));
            String base = slug(e.getKey());
            String[] cat = CATEGORY.get(e.getKey());
            for (int i = 0, part = 0; i < looks.size(); i += MOST, part++) {
                String gid = part == 0 ? base : base + "_" + (part + 1);
                String glabel = part == 0 ? cat[0] : "More " + cat[0].toLowerCase(java.util.Locale.ROOT);
                List<Look> slice = new ArrayList<>();
                for (Look l : looks.subList(i, Math.min(looks.size(), i + MOST))) {
                    slice.add(new Look(l.id(), l.label(), gid, l.stock(), l.words()));
                }
                out.add(new Group(gid, glabel, cat[1], List.copyOf(slice)));
            }
        }
        return List.copyOf(out);
    }

    static String slug(String category) {
        return category.replaceAll("[^a-z]+", "_").replaceAll("^_|_$", "");
    }

    /**
     * A made-up item's name: "Speedy Wand" as before (BuilderItemPowers.name), and with a look the look is what it IS —
     * "Crown" with no power, "Speedy Crown" with one.
     */
    public static String name(BuilderSpec spec, BuilderItemPowers.Stock stock) {
        return name(stock == null ? "" : stock.id(), spec.opt("power"), spec.opt(BuilderTurnInto.TO_SLOT), spec.opt(SLOT));
    }

    public static String name(String stock, String power, String turnTo, String lookId) {
        Look look = look(lookId);
        BuilderItemPowers.Power w = BuilderItemPowers.power(power);
        if (look == null || w == null) {
            return BuilderItemPowers.name(stock, power, turnTo);
        }
        return w.id().equals(SHOW) ? look.label() : w.word() + " " + look.label();
    }

    /** "top_hat" → "Top Hat"; "t_rex" → "T Rex"; "tv" → "TV". */
    static String label(String id) {
        if (id.equals("tv")) {
            return "TV";
        }
        if (id.equals("ufo")) {
            return "UFO";
        }
        StringBuilder out = new StringBuilder();
        for (String w : id.split("_")) {
            out.append(out.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    private static volatile Map<String, Integer> colours;

    /**
     * the colour a look's tile is drawn in. A tinted icon is greyscale until dyed, so it takes its hand-picked
     * colour ({@code items/icon-tile-colours.json}); a fixed icon draws its own colours and the dye is ignored (white).
     */
    public static int tileColour(String id) {
        Map<String, Integer> c = colours;
        if (c == null) {
            c = loadColours();
            colours = c;
        }
        return c.getOrDefault(id, 0xFFFFFF);
    }

    static Map<String, Integer> loadColours() {
        try (InputStream in = BuilderLooks.class.getResourceAsStream("/items/icon-tile-colours.json")) {
            if (in == null) {
                return Map.of();
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("colours").entrySet()) {
                out.put(e.getKey(), Integer.parseInt(e.getValue().getAsString().substring(1), 16));
            }
            return Map.copyOf(out);
        } catch (java.io.IOException | RuntimeException e) {
            throw new IllegalStateException("items/icon-tile-colours.json is unreadable", e);
        }
    }

    private static Map<String, List<String>> words() {
        try (InputStream in = BuilderLooks.class.getResourceAsStream("/items/icon-words.json")) {
            if (in == null) {
                return Map.of();
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("words").entrySet()) {
                List<String> w = new ArrayList<>();
                e.getValue().getAsJsonArray().forEach(x -> w.add(x.getAsString()));
                out.put(e.getKey(), List.copyOf(w));
            }
            return out;
        } catch (java.io.IOException | RuntimeException e) {
            throw new IllegalStateException("items/icon-words.json is unreadable", e);
        }
    }
}
