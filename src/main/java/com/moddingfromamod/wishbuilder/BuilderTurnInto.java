package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.content.ContentIndex;
import com.moddingfromamod.content.Found;
import com.moddingfromamod.datapack.PickList;
import com.moddingfromamod.datapack.TurnInto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>The Builder's "Turn things into…" power</b>: ONE item power, {@code turn_into}, with two
 * pages after it — <b>From</b> (what changes: several groups at once) and <b>To</b> (what it becomes: one). It replaced
 * an earlier issue's six fixed pairs (raw iron → iron, logs → charcoal, sand → glass, dirt → sand, cobblestone → gravel, stone →
 * cobblestone), which are now picks on these two pages, keeping their words.
 *
 * <p>The engine decides the MODE from the item (datapack/TurnInto): a wand or staff aims — the block he points at if it
 * is one of From, else one thing in his inventory; a sword changes the blocks round what it hits; a snowball the
 * blocks where it lands. So the pages offer only what this item can really do: a group with no block form (raw food)
 * is not offered on a sword or a snowball, and a To that this From and this item cannot make is not offered
 * ({@link #whyNot}) — make_item never refuses what the Builder offers.
 *
 * <p>{@link #facts} says the same things to the Apprentice's tree (BuilderTree), so its cheap check
 * ({@code Apprentice.problems}, and {@code tools/apprentice_lib.py}) refuses exactly what these pages refuse.
 *
 * <p>Pure: {@code BuilderTurnIntoTest} checks every offered (stock, From, To) against make_item's own reading
 * ({@code TurnIntoRecipe}).
 */
public final class BuilderTurnInto {

    private BuilderTurnInto() {
    }

    /** The power's id ({@link BuilderItemPowers#POWERS}). */
    public static final String POWER = "turn_into";
    /** The From group that is every block. */
    public static final String ANY_BLOCK = "any_block";
    /** Tile ids on the From and To pages, and the tree's entry ids. */
    public static final String FROM_PREFIX = "turnfrom:";
    public static final String TO_PREFIX = "turnto:";
    /** The tree's slot ids, and the spec's opts. */
    public static final String FROM_SLOT = "turn_from";
    public static final String TO_SLOT = "turn_to";

    /** The power tile's lore on a held item, and on a swung or thrown one. */
    public static final String LORE_HELD = "Point at a block, or tap the air for what's in your inventory.";
    public static final String LORE_HIT = "Changes the blocks round what it hits.";
    public static final String LORE_THROWN = "Changes the blocks where it lands.";

    /** The pages' titles: short enough for a Bedrock grid's header. */
    public static final String FROM_TITLE = "Turn what?";
    public static final String TO_TITLE = "Into what?";

    /**
     * One From group: its id and label, the tile drawn, the ids and tags make_item's FROM gets, whether it has a form
     * in the world ({@code block}) and in his inventory ({@code item}), and the words a child calls it. Plain material
     * words: the Apprentice reads one as From only where it is not what something turns INTO.
     */
    public record From(String id, String label, String tile, List<String> members, boolean block, boolean item,
                       List<String> words) {
    }

    /**
     * One To target: its id and label, the tile, what make_item's TO gets, and its words. A word with {@code >} picks
     * (the Apprentice's also-picks) names the whole change by itself ("a magic furnace", "midas"); the others are
     * material words, read as To only after "into", "to", "become"…
     */
    public record To(String id, String label, String tile, String param, String word, List<String> words) {
        public boolean furnace() {
            return TurnInto.isFurnace(param);
        }

        public boolean vanish() {
            return TurnInto.isVanish(param);
        }

        /** The block it sets, or null (the furnace: each From's own result; a found item with no block form). */
        public String blockId() {
            if (furnace()) {
                return null;
            }
            if (vanish()) {
                return "minecraft:air";
            }
            TurnInto.Material m = TurnInto.material(param);
            if (m != null) {
                return m.block();
            }
            return !found() || Found.is(ContentIndex.Kind.BLOCK, param) ? param : null;
        }

        /** The item it gives, or null (vanish: nothing to hold; the furnace: each From's own result). */
        public String itemId() {
            if (furnace() || vanish()) {
                return null;
            }
            TurnInto.Material m = TurnInto.material(param);
            if (m != null) {
                return m.item();
            }
            return !found() || Found.is(ContentIndex.Kind.ITEM, param) ? param : null;
        }

        /** found by name (the 🔍 Find, the Apprentice), not one of the page's own picks. */
        public boolean found() {
            return id.startsWith(FOUND);
        }
    }

    private static String also(String phrase, String from) {
        return phrase + ">power=itempower:" + POWER + (from == null ? "" : ">" + FROM_SLOT + "=" + FROM_PREFIX + from);
    }

    public static final List<From> FROM = List.of(
            new From(ANY_BLOCK, "Any block", "minecraft:bricks", List.of("any_block"), true, false,
                    List.of("any block", "anything", "everything", "every block", "all blocks", "blocks", "block", "stuff",
                            "things", "whatever", "everything i touch", "anything i touch", "whatever i touch")),
            new From("ores", "Ores & raw metal", "minecraft:raw_iron",
                    List.of("#minecraft:iron_ores", "#minecraft:gold_ores", "#minecraft:copper_ores", "minecraft:raw_iron",
                            "minecraft:raw_gold", "minecraft:raw_copper"), true, true,
                    List.of("ore", "ores", "raw iron", "raw gold", "raw copper", "raw metal", "iron ore", "gold ore",
                            "copper ore", "metal ore")),
            new From("logs", "Logs & wood", "minecraft:oak_log", List.of("#minecraft:logs"), true, true,
                    List.of("log", "logs", "wood", "wooden", "tree", "trees", "tree trunk")),
            new From("sand", "Sand", "minecraft:sand", List.of("#minecraft:sand"), true, true,
                    List.of("sand", "red sand", "sandy", "beach sand")),
            new From("stone", "Stone & cobblestone", "minecraft:cobblestone",
                    List.of("minecraft:stone", "minecraft:cobblestone"), true, true,
                    List.of("stone", "stones", "cobblestone", "cobble", "rock", "rocks", "boulders")),
            new From("dirt_grass", "Dirt & grass", "minecraft:dirt",
                    List.of("minecraft:dirt", "minecraft:grass_block", "minecraft:coarse_dirt", "minecraft:podzol"), true, true,
                    List.of("dirt", "grass", "ground", "grass block", "mud", "soil", "earth", "lawn")),
            new From("gravel", "Gravel", "minecraft:gravel", List.of("minecraft:gravel"), true, true,
                    List.of("gravel", "pebbles", "gravel blocks")),
            new From("clay", "Clay", "minecraft:clay_ball", List.of("minecraft:clay", "minecraft:clay_ball"), true, true,
                    List.of("clay", "clay ball", "clay balls", "clay block")),
            new From("raw_food", "Raw food", "minecraft:beef",
                    List.of("#minecraft:meat", "#minecraft:fishes", "minecraft:potato", "minecraft:kelp"), false, true,
                    List.of("raw food", "raw meat", "meat", "raw beef", "raw chicken", "raw pork", "raw fish", "fish",
                            "potato", "potatoes", "steak", "kelp")),
            new From("plants", "Plants & flowers", "minecraft:poppy",
                    List.of("#minecraft:flowers", "#minecraft:leaves", "#minecraft:saplings", "minecraft:short_grass",
                            "minecraft:cactus"), true, true,
                    List.of("plants", "plant", "flowers", "flower", "leaves", "leaf", "bushes", "saplings", "cactus")),
            new From("wool", "Wool", "minecraft:white_wool", List.of("#minecraft:wool"), true, true,
                    List.of("wool", "white wool", "sheep wool")),
            new From("netherrack", "Netherrack", "minecraft:netherrack", List.of("minecraft:netherrack"), true, true,
                    List.of("netherrack", "nether rack", "nether stone")),
            new From("ice_snow", "Ice & snow", "minecraft:snow_block",
                    List.of("minecraft:ice", "minecraft:packed_ice", "minecraft:snow_block", "minecraft:snow",
                            "minecraft:water"), true, true,
                    List.of("ice", "snow", "water", "packed ice", "snow blocks", "ice blocks")));

    public static final List<To> TO = List.of(
            new To("furnace", "What a furnace makes", "minecraft:furnace", "furnace", "Smelting",
                    List.of("what a furnace makes", "smelted", "cooked", "baked", "roasted", "brick", "bricks",
                            also("furnace", "ores"), also("magic furnace", "ores"), also("smelter", "ores"),
                            also("smelt", "ores"), also("smelts", "ores"), also("smelting", "ores"),
                            also("cooks ore", "ores"), also("melts iron", "ores"), also("raw iron into iron", "ores"),
                            also("cook", "raw_food"), also("cooks", "raw_food"), also("cooking", "raw_food"),
                            also("bake", "raw_food"), also("bakes", "raw_food"), also("roast", "raw_food"),
                            also("makes charcoal", "logs"), also("burns logs", "logs"), also("charcoal maker", "logs"))),
            new To("gold", "Gold", "minecraft:gold_ingot", "gold", "Midas",
                    List.of("gold", "gold block", "gold blocks", "gold ingot", "gold ingots", "solid gold",
                            also("midas", "any_block"), also("king midas", "any_block"), also("golden touch", "any_block"),
                            also("midas touch", "any_block"))),
            new To("iron", "Iron", "minecraft:iron_ingot", "iron", "Iron",
                    List.of("iron", "iron ingot", "iron ingots", "iron block", "iron blocks", "metal")),
            new To("copper", "Copper", "minecraft:copper_ingot", "copper", "Copper",
                    List.of("copper", "copper ingot", "copper ingots", "copper block")),
            new To("diamond", "Diamond", "minecraft:diamond", "diamond", "Diamond",
                    List.of("diamond", "diamonds", "diamond block", "diamond blocks")),
            new To("emerald", "Emerald", "minecraft:emerald", "emerald", "Emerald",
                    List.of("emerald", "emeralds", "emerald block", "emerald blocks")),
            new To("coal", "Coal", "minecraft:coal", "coal", "Coal",
                    List.of("coal", "charcoal", "coal block", "lumps of coal", also("coal maker", "logs"))),
            new To("glass", "Glass", "minecraft:glass", "minecraft:glass", "Glass",
                    List.of("glass", "glass blocks", "see through", also("glassmaker", "sand"), also("glass maker", "sand"),
                            also("makes glass", "sand"))),
            new To("stone", "Stone", "minecraft:stone", "minecraft:stone", "Stone",
                    List.of("stone", "stones", "smooth stone", "rock", "stone blocks")),
            new To("cobblestone", "Cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "Cobble",
                    List.of("cobblestone", "cobble", "cobbles", also("makes cobblestone", "stone"),
                            also("breaks stone", "stone"))),
            new To("sand", "Sand", "minecraft:sand", "minecraft:sand", "Desert",
                    List.of("sand", "sand blocks", also("desert maker", "dirt_grass"), also("sandifier", "dirt_grass"))),
            new To("dirt", "Dirt", "minecraft:dirt", "minecraft:dirt", "Dirt",
                    List.of("dirt", "mud", "soil", "dirt blocks")),
            new To("gravel", "Gravel", "minecraft:gravel", "minecraft:gravel", "Gravel",
                    List.of("gravel", "pebbles", also("rock crusher", "stone"), also("crushes cobblestone", "stone"),
                            also("makes gravel", "stone"), also("crushes rocks", "stone"))),
            new To("wool", "Wool", "minecraft:white_wool", "minecraft:white_wool", "Fluffy",
                    List.of("wool", "white wool", "fluffy", "fluff", "soft wool", "wool blocks", "sheep wool")),
            new To("planks", "Planks", "minecraft:oak_planks", "minecraft:oak_planks", "Plank",
                    List.of("planks", "plank", "wood planks", "wooden planks", "boards")),
            new To("ice", "Ice", "minecraft:ice", "minecraft:ice", "Frost",
                    List.of("ice", "ice blocks", "frozen", "solid ice")),
            new To("vanish", "Vanish", "minecraft:gunpowder", "vanish", "Vanishing",
                    List.of("nothing", "air", "thin air", also("vanish", null), also("vanishes", null),
                            also("disappear", null), also("disappears", null), also("go away", null),
                            also("goes away", null))));

    public static From from(String id) {
        String bare = id == null ? "" : id.startsWith(FROM_PREFIX) ? id.substring(FROM_PREFIX.length()) : id;
        if (bare.startsWith(FOUND)) {
            BuilderFind.Result r = BuilderFind.of(bare.substring(FOUND.length()));
            return r == null ? null : new From(FOUND + r.id(), r.name(), tile(r), List.of(r.id()), r.block(), r.item(),
                    List.of(r.name().toLowerCase(java.util.Locale.ROOT)));
        }
        for (From f : FROM) {
            if (f.id().equals(bare)) {
                return f;
            }
        }
        return null;
    }

    public static To to(String id) {
        String bare = id == null ? "" : id.startsWith(TO_PREFIX) ? id.substring(TO_PREFIX.length()) : id;
        if (bare.startsWith(FOUND)) {
            BuilderFind.Result r = BuilderFind.of(bare.substring(FOUND.length()));
            return r == null ? null : new To(FOUND + r.id(), r.name(), tile(r), r.id(), r.name(),
                    List.of(r.name().toLowerCase(java.util.Locale.ROOT)));
        }
        for (To t : TO) {
            if (t.id().equals(bare)) {
                return t;
            }
        }
        return null;
    }

    /**
     * a From or To found by name — the 🔍 Find's results, the Apprentice's material names — is
     * {@code found:<id>}, one installed block or item. It is made exactly as a pick of the page's own is: the same
     * {@link #whyNot}, the same make_item FROM and TO.
     */
    public static final String FOUND = "found:";

    /** The tile drawn for a found thing: itself, or paper for a block with no item to show. */
    static String tile(BuilderFind.Result r) {
        return r.item() ? r.id() : "minecraft:paper";
    }

    /** Every To id, in the page's order. */
    public static List<String> toIds() {
        return TO.stream().map(To::id).toList();
    }

    /** "ores,logs" (the spec's opt) → the groups, unknown ids dropped, order kept. */
    public static List<From> parse(String opt) {
        List<From> out = new ArrayList<>();
        if (opt == null) {
            return out;
        }
        for (String s : opt.split(",")) {
            From f = from(s.strip());
            if (f != null && !out.contains(f)) {
                out.add(f);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ what a group does in a furnace

    /** Does a furnace make anything of this group in the world (a block → a block)? */
    public static boolean smeltsInWorld(From f) {
        TurnInto.Smelting t = TurnInto.Smelting.load();
        return f.block() && f.members().stream().anyMatch(m -> !t.blockRows(TurnInto.isAnyBlock(m) ? TurnInto.ANY : m).isEmpty());
    }

    /** Does a furnace make anything of this group in his inventory? */
    public static boolean smeltsInBag(From f) {
        TurnInto.Smelting t = TurnInto.Smelting.load();
        return f.item() && f.members().stream().anyMatch(m -> !TurnInto.isAnyBlock(m) && !t.itemRows(m).isEmpty());
    }

    /** Does a furnace make something of this group (the To page's default is the furnace when every pick does)? */
    public static boolean smelts(From f) {
        return smeltsInWorld(f) || smeltsInBag(f);
    }

    // ------------------------------------------------------------------ what can be made

    /** The power tile's lore on an item of this trigger: how this item changes things. */
    public static String lore(String trigger) {
        return TurnInto.raycasts(trigger) ? LORE_HELD : "on_hit".equals(trigger) ? LORE_HIT : LORE_THROWN;
    }

    /** Does an item of this trigger (a catalogue shape) carry the power at all? */
    public static boolean fits(String trigger) {
        return "held".equals(trigger) || "on_hit".equals(trigger) || "on_throw_land".equals(trigger);
    }

    /** The From groups an item of this trigger can work on: a sword or a snowball changes blocks, so no raw food. */
    public static boolean offered(From f, String trigger) {
        return TurnInto.forShape(trigger) != TurnInto.Mode.BLOCKS || f.block();
    }

    /** At most this many ids and tags in one FROM (make_item's own limit). */
    public static final int MOST_MEMBERS = PickList.MAX;

    /**
     * Why the From page cannot take {@code adding} next to {@code picked}, in his words, or null. One check for the page
     * (a grey tile) and the press (refused).
     */
    public static String whyNotFrom(List<From> picked, From adding) {
        int n = adding.members().size();
        for (From f : picked) {
            n += f.members().size();
        }
        return n > MOST_MEMBERS ? "Too many kinds of thing at once" : null;
    }

    /**
     * Why this To cannot be made from {@code from} on an item of {@code trigger}, or null when make_item makes it. The
     * same reading as make_item's (TurnIntoRecipe + TurnInto.effective), from the groups' block and item forms.
     */
    public static String whyNot(String trigger, List<From> from, To to) {
        if (from.isEmpty()) {
            return "Pick what it turns first";
        }
        int members = 0;
        for (From f : from) {
            members += f.members().size();
        }
        if (members > MOST_MEMBERS) {
            return "Too many kinds of thing at once";
        }
        boolean blockSide;
        boolean itemSide;
        if (to.furnace()) {
            blockSide = from.stream().anyMatch(BuilderTurnInto::smeltsInWorld);
            itemSide = from.stream().anyMatch(BuilderTurnInto::smeltsInBag);
        } else {
            blockSide = to.blockId() != null && from.stream().anyMatch(From::block);
            itemSide = to.itemId() != null && from.stream().anyMatch(From::item);
        }
        if (TurnInto.effective(trigger, blockSide, itemSide) == null) {
            return to.furnace() ? "A furnace makes nothing of that here" : "That can't become " + to.label().toLowerCase(java.util.Locale.ROOT) + " here";
        }
        if (from.size() == 1 && from.get(0).members().size() == 1 && from.get(0).members().get(0).equals(to.blockId())) {
            return "It is that already";
        }
        return null;
    }

    /**
     * Does this group make the furnace the To page's default? When a furnace makes something of it, and it is not
     * {@link #ANY_BLOCK}: a furnace makes something of only a few blocks (sand, cobblestone,
     * stone…), so "Any block" smelting would silently ignore the rest. With it picked he chooses a To, and "Any block →
     * gold" is the obvious first make (the Apprentice's next fitting To).
     */
    public static boolean furnaceDefaults(From f) {
        return !f.id().equals(ANY_BLOCK) && smelts(f);
    }

    /** The default To: the furnace, when every From group {@link #furnaceDefaults}; else none (he picks). */
    public static boolean furnaceIsDefault(List<From> from) {
        return !from.isEmpty() && from.stream().allMatch(BuilderTurnInto::furnaceDefaults);
    }

    // ------------------------------------------------------------------ what make_item gets

    /** The block side's patch: the one block he points at, then a 3×3×3, then 5×5×5; a thrown or swung one starts at 3×3×3. */
    public static int radius(String trigger, int strength) {
        int s = Math.max(1, Math.min(3, strength));
        return TurnInto.raycasts(trigger) ? s - 1 : s;
    }

    /** make_item's params for this power: FROM (every group's ids), TO and RADIUS. */
    public static Map<String, String> params(String trigger, List<From> from, To to, int strength) {
        Map<String, String> p = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        from.forEach(f -> f.members().forEach(m -> {
            if (!ids.contains(m)) {
                ids.add(m);
            }
        }));
        p.put("FROM", String.join(",", ids));
        p.put("TO", to.param());
        p.put("RADIUS", String.valueOf(radius(trigger, strength)));
        return p;
    }

    /** "Midas Wand", "Smelting Staff", "Vanishing Snowball". */
    public static String name(BuilderItemPowers.Stock stock, To to) {
        return (to == null ? "Changing" : to.word()) + " " + (stock == null ? "Wand" : stock.noun());
    }

    /** The tree's facts for a From entry (the Apprentice's check reads them; {@code tools/apprentice_lib.py} too). */
    public static Map<String, String> facts(From f) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("block", f.block() ? "1" : "0");
        m.put("item", f.item() ? "1" : "0");
        m.put("smeltBlock", smeltsInWorld(f) ? "1" : "0");
        m.put("smeltItem", smeltsInBag(f) ? "1" : "0");
        m.put("smelts", smelts(f) ? "1" : "0");
        m.put("furnaceDefault", furnaceDefaults(f) ? "1" : "0");   //: never for any block
        m.put("members", String.valueOf(f.members().size()));
        if (f.members().size() == 1) {
            m.put("sole", f.members().get(0));
        }
        return m;
    }

    /** The tree's facts for a To entry. {@code defaultWhen}: the To page's default only when every From has that fact. */
    public static Map<String, String> facts(To t) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("cue", "into");   // how its material words are said: after "into" (the Apprentice reads them as To there)
        m.put("furnace", t.furnace() ? "1" : "0");
        m.put("block", t.blockId() == null ? "" : t.blockId());
        m.put("item", t.itemId() == null ? "" : t.itemId());
        if (t.furnace()) {
            m.put("defaultWhen", FROM_SLOT + ":furnaceDefault");
        }
        return m;
    }
}
