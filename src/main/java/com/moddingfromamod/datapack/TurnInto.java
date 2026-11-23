package com.moddingfromamod.datapack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>{@code turn_into}: ONE effect for "this turns X into Y", whose MODE the agent never
 * chooses.</b>
 *
 * <p>It replaces two effects that split one wish by where the X happened to be: {@code convert}
 * (a thing in his BAG becomes another thing) and {@code change_block} (a BLOCK in the world becomes
 * another block). A child does not make that split — "a wand that turns stone into gold" is the
 * same sentence whether he points it at a stone block or carries cobblestone — so the agent had to
 * guess, and guessed wrong in both directions (measured "turns dirt into grass" going to
 * convert). Now {@code make_item} derives the mode from the item's SHAPE, which is what decides
 * whether there is anything to point at:
 *
 * <table>
 *   <caption>The mode table</caption>
 *   <tr><th>shape</th><th>mode</th><th>why</th></tr>
 *   <tr><td>machine</td><td>INVENTORY</td><td>he pulls a handle; nothing is aimed, what he carries goes in</td></tr>
 *   <tr><td>held, on_use_done</td><td>AIM</td><td>both walk his line of sight: the first solid block he points
 *       at decides — one that matches FROM changes, anything else (another block, the air) works on his bag.
 *       NEVER both in one use</td></tr>
 *   <tr><td>thrown, arrow, on_throw_land, on_hit, on_shot, on_block, on_consume, on_mine, placed,
 *       magic_block</td><td>BLOCKS</td><td>each has a PLACE (where it lands, the beam's end, the block he hit,
 *       a few steps ahead, around the thing he put down) and no moment of "use it on what I carry"</td></tr>
 *   <tr><td>worn_head/chest/legs/feet</td><td>BLOCKS</td><td>a worn thing runs on a CLOCK; in his bag it would
 *       eat a whole stack while he walks, round his feet it is the frost-walker wish ("boots that turn
 *       grass into snow"), which is what change_block on a worn shape already did</td></tr>
 * </table>
 *
 * <p>The mode then narrows by what the arguments can do: AIM with a TO that is not a block (his Hot
 * Coffee) never looks at blocks; AIM with nothing to take from his bag ({@code any_block}, or
 * {@code minecraft:air} as TO) never looks in it. A shape whose only mode is impossible is refused
 * at make time with a sentence the agent can act on ({@link #sideRefusal}).
 *
 * <p>Pure, like {@link ChangeBlock}: the mode, the two forms of a material, the furnace table and
 * every command line are string decisions, unit-tested in {@code TurnIntoTest}. What a reference
 * MEANS (a made item, a real id) is resolved by {@code agent.tools.TurnIntoRecipe}, which has the
 * item store.
 */
public final class TurnInto {

    private TurnInto() {
    }

    /** The effect id in {@code items/catalogue.json}. */
    public static final String EFFECT = "turn_into";

    /** The two effects it replaced; both are aliases of it now (and of nothing else). */
    public static final List<String> OLD_IDS = List.of("convert", "change_block");

    /** What {@code FROM = any_block} means: the curated tag, never a chest, bedrock or a portal. */
    public static final String ANY = ChangeBlock.ANY;

    /** The objective the inventory side counts on — convert's, kept so old and new packs agree. */
    public static final String COUNTER = "mfam_conv";

    public enum Mode { INVENTORY, BLOCKS, AIM }

    /** Does this shape walk his line of sight to the exact block he points at? */
    public static boolean raycasts(String shape) {
        return "held".equals(shape) || "on_use_done".equals(shape);
    }

    /** The mode a shape gives, before the arguments narrow it (the class note's table). */
    public static Mode forShape(String shape) {
        if (ItemCatalogue.MACHINE.equals(shape)) {
            return Mode.INVENTORY;
        }
        return raycasts(shape) ? Mode.AIM : Mode.BLOCKS;
    }

    /**
     * The mode this item really runs in, given which sides its FROM and TO make possible, or null
     * when neither the shape's mode nor its fall-back can do anything ({@link #sideRefusal} says why).
     */
    public static Mode effective(String shape, boolean blockSide, boolean itemSide) {
        return switch (forShape(shape)) {
            case INVENTORY -> itemSide ? Mode.INVENTORY : null;
            case BLOCKS -> blockSide ? Mode.BLOCKS : null;
            case AIM -> blockSide && itemSide ? Mode.AIM
                    : itemSide ? Mode.INVENTORY : blockSide ? Mode.BLOCKS : null;
        };
    }

    /** Why this item can do nothing, in the agent's terms — the sentence for {@code effective == null}. */
    public static String sideRefusal(String shape, boolean fromHasBlock, boolean fromHasItem,
                                     boolean toHasBlock, boolean toHasItem, boolean furnace) {
        Mode m = forShape(shape);
        if (m == Mode.INVENTORY) {
            if (!fromHasItem) {
                return "turn_into on a machine works on what he CARRIES — he pulls the handle and one "
                        + "thing from his inventory goes in. FROM names only blocks in the world "
                        + "(any_block is every ordinary block he could point at, never a thing in his "
                        + "bag), so the machine would take nothing. Name the items it takes, like "
                        + "minecraft:cobblestone or the id make_item handed back for something he made.";
            }
            return furnace
                    ? "turn_into's TO is furnace, and a furnace makes nothing from any of the FROM items, "
                    + "so the machine would never give anything. Name things that smelt (raw iron, sand, "
                    + "a log) or name what comes out."
                    : "turn_into's TO has no form he can carry (minecraft:air is 'make it vanish', a "
                    + "block-only thing), so the machine would hand over nothing. Name an item.";
        }
        if (m == Mode.BLOCKS) {
            if (!toHasBlock) {
                return "turn_into on a " + shape + " thing changes BLOCKS where it lands or hits, and TO "
                        + (furnace ? "is furnace, which makes no block from any FROM here (sand → glass "
                        + "and cobblestone → stone do; a log or raw iron do not)"
                        : "has no block form — it is something he holds or eats, not a block") + ". Name a "
                        + "block for TO, or make it a held thing (or a machine) so it works on what he "
                        + "carries instead.";
            }
            return "turn_into on a " + shape + " thing changes BLOCKS where it lands or hits, and FROM "
                    + "names no block (only things he carries, like an item he made). Name the blocks "
                    + "it changes — a block id, a block tag like #minecraft:logs, or any_block — or "
                    + "make it a held thing or a machine so it works on what he carries.";
        }
        return "turn_into can do nothing with these: FROM and TO share no side — neither a block it "
                + "could change where he points nor a thing in his inventory it could turn into another. "
                + "Name blocks for both (a block id, a tag, any_block → a block), or items for both.";
    }

    // ── what the agent may type ───────────────────────────────────────────────────────────────────

    private static final Set<String> ANY_WORDS = Set.of("any_block", "any", "anything", "*",
            "any block", "every block", "#moddingfromamod:changeable");
    private static final Set<String> FURNACE_WORDS = Set.of("furnace", "smelted", "smelt", "smelts",
            "smelting", "what a furnace makes", "furnace result", "cooked in a furnace");
    private static final Set<String> VANISH_WORDS = Set.of("minecraft:air", "air", "nothing",
            "vanish", "disappear", "gone");

    public static boolean isAnyBlock(String raw) {
        return raw != null && ANY_WORDS.contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isFurnace(String raw) {
        return raw != null && FURNACE_WORDS.contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isVanish(String raw) {
        return raw != null && VANISH_WORDS.contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    private static final Pattern TAG = Pattern.compile("#[a-z0-9_.-]+:[a-z0-9_./-]+");

    public static boolean isTag(String raw) {
        return raw != null && TAG.matcher(raw.trim().toLowerCase(Locale.ROOT)).matches();
    }

    /**
     * FROM as a list: a JSON array ({@code ["minecraft:stone","#minecraft:logs"]}), or comma
     * separated. Trimmed, empties and repeats dropped, order kept — the order is which one a machine
     * takes first.
     */
    public static List<String> list(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return List.of();
        }
        String s = raw.trim();
        if (s.startsWith("[")) {
            try {
                for (JsonElement e : JsonParser.parseString(s).getAsJsonArray()) {
                    String t = e.getAsString().trim();
                    if (!t.isEmpty()) {
                        out.add(t);
                    }
                }
                return List.copyOf(out);
            } catch (RuntimeException notJson) {
                s = s.substring(1, s.endsWith("]") ? s.length() - 1 : s.length()).replace("\"", "");
            }
        }
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    // ── the two forms of one material ─────────────────────────────────────────────────────────────

    /** A material a child names by one word that is a BLOCK one way and an ITEM the other. */
    public record Material(String name, String block, String item) {
    }

    /**
     * gold is a gold block when it is set into the world and a gold ingot when it is put in his
     * hand. Only materials whose block and item really differ; glass, stone and sand are one id
     * both ways and need no row.
     */
    public static final List<Material> MATERIALS = List.of(
            m("gold", "gold_block", "gold_ingot"),
            m("iron", "iron_block", "iron_ingot"),
            m("diamond", "diamond_block", "diamond"),
            m("emerald", "emerald_block", "emerald"),
            m("copper", "copper_block", "copper_ingot"),
            m("coal", "coal_block", "coal"),
            m("netherite", "netherite_block", "netherite_ingot"),
            m("lapis", "lapis_block", "lapis_lazuli"),
            m("redstone", "redstone_block", "redstone"),
            m("quartz", "quartz_block", "quartz"),
            m("amethyst", "amethyst_block", "amethyst_shard"),
            m("slime", "slime_block", "slime_ball"),
            m("snow", "snow_block", "snowball"),
            m("glowstone", "glowstone", "glowstone_dust"),
            m("bone", "bone_block", "bone"),
            m("dried_kelp", "dried_kelp_block", "dried_kelp"),
            m("raw_iron", "raw_iron_block", "raw_iron"),
            m("raw_gold", "raw_gold_block", "raw_gold"),
            m("raw_copper", "raw_copper_block", "raw_copper"),
            m("hay", "hay_block", "wheat"),
            m("melon", "melon", "melon_slice"));

    private static Material m(String name, String block, String item) {
        return new Material(name, "minecraft:" + block, "minecraft:" + item);
    }

    /**
     * The material this word or id names, or null. {@code gold}, {@code minecraft:gold},
     * {@code minecraft:gold_block} and {@code minecraft:gold_ingot} are all gold.
     */
    public static Material material(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        String bare = s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
        if (bare.contains(":")) {
            return null;
        }
        for (Material mat : MATERIALS) {
            if (mat.name().equals(bare) || mat.block().equals("minecraft:" + bare)
                    || mat.item().equals("minecraft:" + bare)) {
                return mat;
            }
        }
        return null;
    }

    /** Is this a bare material WORD (no namespace that is not minecraft, not an id of its own)? */
    public static boolean isMaterialWord(String raw) {
        Material mat = material(raw);
        if (mat == null) {
            return false;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        String bare = s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
        return mat.name().equals(bare) && !mat.block().equals("minecraft:" + bare)
                && !mat.item().equals("minecraft:" + bare);
    }

    /**
     * What the game has, asked of a real id: is it a block, is it an item, is that a tag. The live
     * answer is in {@code MakeItemTool}; {@link #ASSUME} is the off-server one.
     */
    public interface Kinds {
        boolean isBlock(String id);

        boolean isItem(String id);

        boolean isBlockTag(String tag);

        boolean isItemTag(String tag);
    }

    private static final Set<String> NOT_ITEMS = Set.of("minecraft:air", "minecraft:cave_air",
            "minecraft:void_air", "minecraft:water", "minecraft:lava", "minecraft:fire");

    /**
     * Off-server: every id is a block unless it is a material's item form (a gold ingot), and an item
     * unless it is air, water, lava or fire. Named, like {@link RegistryExists#ALWAYS}, so a test that
     * relies on it says so.
     */
    public static final Kinds ASSUME = new Kinds() {
        @Override
        public boolean isBlock(String id) {
            Material mat = material(id);
            return mat == null || !mat.item().equals(id) || mat.block().equals(id);
        }

        @Override
        public boolean isItem(String id) {
            return !NOT_ITEMS.contains(id);
        }

        @Override
        public boolean isBlockTag(String tag) {
            return true;
        }

        @Override
        public boolean isItemTag(String tag) {
            return true;
        }
    };

    // ── resolved ends, as the renderer takes them ──────────────────────────────────────────────────

    /**
     * One FROM, resolved: the block it matches in the world (an id or a tag, or null), the predicate
     * that finds it in his inventory (or null), what to call it, and the plain id a furnace is asked
     * about (null for a made item, which no furnace knows).
     */
    public record From(String name, String block, String item, String plainItem) {
    }

    /**
     * TO, resolved: the blocks it sets (a pick list, null when it has no block form), the give
     * arguments (null when nothing can be held: air), what to call it, how a list is picked, and
     * whether it is the furnace.
     */
    public record To(String name, List<String> blocks, List<String> gives, String pick, boolean furnace) {
        public static To smelter() {
            return new To("what a furnace makes", null, null, null, true);
        }
    }

    // ── the furnace ───────────────────────────────────────────────────────────────────────────────

    /** The committed table, generated from 1.21.8's own recipes by tools/gen-smelting-table.py. */
    public record Smelting(Map<String, String> items, Map<String, String> blocks,
                           Map<String, List<String>> itemTags, Map<String, List<String>> blockTags) {

        private static Smelting loaded;

        public static synchronized Smelting load() {
            if (loaded == null) {
                JsonObject o = resource("/items/smelting.json");
                loaded = new Smelting(map(o.getAsJsonObject("items")), map(o.getAsJsonObject("blocks")),
                        lists(o.getAsJsonObject("item_tags")), lists(o.getAsJsonObject("block_tags")));
            }
            return loaded;
        }

        /** Every block a FROM matches, each with the block a furnace makes of it (only those that have one). */
        public Map<String, String> blockRows(String fromBlock) {
            Map<String, String> out = new LinkedHashMap<>();
            List<String> members;
            if (ANY.equals(fromBlock)) {
                members = changeable(this);
            } else if (fromBlock.startsWith("#")) {
                members = blockTags.getOrDefault(fromBlock, List.of());
            } else {
                members = List.of(fromBlock);
            }
            for (String b : members) {
                String to = blocks.get(b);
                if (to != null) {
                    out.put(b, to);
                }
            }
            return out;
        }

        /** Every item a FROM's inventory form stands for, each with what a furnace makes of it. */
        public Map<String, String> itemRows(String plainItem) {
            Map<String, String> out = new LinkedHashMap<>();
            if (plainItem == null) {
                return out;
            }
            List<String> members = plainItem.startsWith("#")
                    ? itemTags.getOrDefault(plainItem, List.of()) : List.of(plainItem);
            for (String i : members) {
                String to = items.get(i);
                if (to != null) {
                    out.put(i, to);
                }
            }
            return out;
        }
    }

    private static List<String> changeableCache;

    /** The members of #moddingfromamod:changeable that a furnace changes, read from our own tag. */
    static synchronized List<String> changeable(Smelting t) {
        if (changeableCache == null) {
            List<String> out = new ArrayList<>();
            JsonObject tag = resource("/data/moddingfromamod/tags/block/changeable.json");
            for (JsonElement e : tag.getAsJsonArray("values")) {
                String v = e.isJsonObject() ? e.getAsJsonObject().get("id").getAsString() : e.getAsString();
                if (v.startsWith("#")) {
                    out.addAll(t.blockTags().getOrDefault(v, List.of()));
                } else {
                    out.add(v);
                }
            }
            changeableCache = List.copyOf(new LinkedHashSet<>(out));
        }
        return changeableCache;
    }

    private static JsonObject resource(String path) {
        try (InputStream in = TurnInto.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("no " + path + " on the classpath");
            }
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read " + path + ": " + e, e);
        }
    }

    private static Map<String, String> map(JsonObject o) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : o.keySet()) {
            out.put(k, o.get(k).getAsString());
        }
        return out;
    }

    private static Map<String, List<String>> lists(JsonObject o) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String k : o.keySet()) {
            List<String> v = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray(k)) {
                v.add(e.getAsString());
            }
            out.put(k, v);
        }
        return out;
    }

    // ── the commands ─────────────────────────────────────────────────────────────────────────────

    /** One change in the world: every block matching {@code from} near here becomes {@code to}. */
    record BlockRow(String from, String to) {
    }

    /** One thing in his bag: take one {@code take}, then run {@code give} (already-rendered lines). */
    record BagRow(String take, String give, String inName, String outName) {
    }

    /** What a resolved FROM/TO renders to: the effect body's lines and the helper functions. */
    public record Plan(Mode mode, String acts, String helpers, boolean blockSide, boolean itemSide,
                       List<String> skipped, List<String> takes) {
    }

    /**
     * Render one {@code turn_into}. Never null: a plan whose mode is null is one that can do nothing,
     * and the caller refuses it with {@link #sideRefusal}.
     *
     * @param radius only read when the plan touches blocks
     */
    public static Plan plan(String shape, List<From> from, To to, int radius, Smelting table) {
        List<BlockRow> blockRows = new ArrayList<>();
        List<String> fromBlocks = new ArrayList<>();
        if (to.furnace()) {
            Map<String, String> rows = new LinkedHashMap<>();
            for (From f : from) {
                if (f.block() != null) {
                    rows.putAll(table.blockRows(f.block()));
                }
            }
            for (Map.Entry<String, String> r : chainSafe(rows)) {
                blockRows.add(new BlockRow(r.getKey(), r.getValue()));
                fromBlocks.add(r.getKey());
            }
        } else if (to.blocks() != null) {
            for (From f : from) {
                if (f.block() != null) {
                    fromBlocks.add(f.block());
                }
            }
        }
        boolean blockSide = !fromBlocks.isEmpty();

        List<BagRow> bag = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> takes = new ArrayList<>();
        if (to.furnace()) {
            for (From f : from) {
                if (f.item() == null) {
                    continue;
                }
                Map<String, String> rows = table.itemRows(f.plainItem());
                if (rows.isEmpty()) {
                    skipped.add(f.item() + "\u0000" + f.name());
                    continue;
                }
                for (Map.Entry<String, String> r : rows.entrySet()) {
                    bag.add(new BagRow(r.getKey(), "    /give @s " + r.getValue() + " 1",
                            readable(r.getKey()), readable(r.getValue())));
                    takes.add(readable(r.getKey()));
                }
            }
        } else if (to.gives() != null) {
            List<String> gives = new ArrayList<>();
            for (String g : to.gives()) {
                gives.add("give @s " + g + " 1");
            }
            String giveLines = PickList.render(gives, to.pick());
            for (From f : from) {
                if (f.item() != null) {
                    bag.add(new BagRow(f.item(), giveLines, f.name(), to.name()));
                    takes.add(f.name());
                }
            }
        }
        boolean itemSide = !bag.isEmpty();
        Mode mode = effective(shape, blockSide, itemSide);
        if (mode == null) {
            return new Plan(null, "", "", blockSide, itemSide, skipped, takes);
        }

        StringBuilder helpers = new StringBuilder();
        StringBuilder extra = new StringBuilder();   // the helpers' own helpers, appended after them
        List<String> acts = new ArrayList<>();
        boolean zap = mode != Mode.INVENTORY;
        boolean fromBag = mode != Mode.BLOCKS;
        if (zap) {
            // The lines first: zapLines adds helper FUNCTIONS of its own, which must land after this
            // function's `end`, never inside its body (a nested function is a CBScript syntax error).
            String zapBody = zapLines(to, fromBlocks, blockRows, radius, extra);
            helpers.append("\nfunction turn_into_zap()\n")
                    .append(zapBody)
                    .append("\n    /particle minecraft:happy_villager ~ ~ ~ 0.4 0.4 0.4 0.01 12 force")
                    .append("\n    /playsound minecraft:block.amethyst_block.chime master @a ~ ~ ~ 1 1.2")
                    .append("\nend\n");
        }
        if (fromBag) {
            String take = bagLines(bag, skipped, takes, extra);
            helpers.append("\nfunction turn_into_bag()\n")
                    .append(take)
                    .append("\nend\n");
        }
        if (mode == Mode.INVENTORY) {
            acts.add("    /function $namespace:turn_into_bag");
        } else if (mode == Mode.BLOCKS && !raycasts(shape)) {
            // The shape already stands this at the place it acts (where it landed, the beam's end).
            acts.add("    /function $namespace:turn_into_zap");
        } else if (mode == Mode.BLOCKS) {
            // Held with nothing he could carry: the exact block he points at, or nothing.
            for (String l : ChangeBlock.aimLines("$namespace:turn_into_zap")) {
                acts.add("    " + l);
            }
        } else {
            // AIM: the first solid block he points at decides, and only ONE side ever runs.
            helpers.append("\nfunction turn_into_aim()\n");
            for (String b : fromBlocks) {
                helpers.append("    /execute if block ~ ~ ~ ").append(b)
                        .append(" run tag @s add mfam_ti_block\n");
            }
            helpers.append("    /execute if entity @s[tag=mfam_ti_block] run function $namespace:turn_into_zap\n")
                    .append("end\n");
            acts.add("    /tag @s remove mfam_ti_block");
            for (String l : ChangeBlock.aimLines("$namespace:turn_into_aim")) {
                acts.add("    " + l);
            }
            acts.add("    /execute if entity @s[tag=!mfam_ti_block] run function $namespace:turn_into_bag");
            acts.add("    /tag @s remove mfam_ti_block");
        }
        helpers.append(extra);
        return new Plan(mode, String.join("\n", acts), helpers.toString(), blockSide, itemSide,
                skipped.stream().map(s -> s.substring(s.indexOf('\u0000') + 1)).toList(), takes);
    }

    /** Is this plan's place one the SHAPE chooses (true) rather than one it raycasts to itself? */
    public static boolean shapeAims(Plan plan, String shape) {
        return plan.mode() == Mode.BLOCKS && !raycasts(shape);
    }

    private static String fill(int r, String to, String from) {
        return "fill ~-" + r + " ~-" + r + " ~-" + r + " ~" + r + " ~" + r + " ~" + r + " " + to
                + " replace " + from;
    }

    private static String zapLines(To to, List<String> fromBlocks, List<BlockRow> furnaceRows, int r,
                                   StringBuilder helpers) {
        if (to.furnace()) {
            List<String> out = new ArrayList<>();
            for (BlockRow row : furnaceRows) {
                out.add("    /" + fill(r, row.to(), row.from()));
            }
            return String.join("\n", out);
        }
        List<String> each = new ArrayList<>();
        for (int k = 0; k < to.blocks().size(); k++) {
            String t = to.blocks().get(k);
            if (fromBlocks.size() == 1) {
                each.add(fill(r, t, fromBlocks.get(0)));
            } else {
                helpers.append("\nfunction turn_into_to_").append(k).append("()\n");
                for (String f : fromBlocks) {
                    helpers.append("    /").append(fill(r, t, f)).append('\n');
                }
                helpers.append("end\n");
                each.add("function $namespace:turn_into_to_" + k);
            }
        }
        return PickList.render(each, to.pick());
    }

    private static String bagLines(List<BagRow> bag, List<String> skipped, List<String> takes,
                                   StringBuilder helpers) {
        List<String> out = new ArrayList<>();
        // ITS COUNTER, at the moment it is used — not only in `reset` (an earlier issue's fix), which runs when the
        // pack LOADS: measured in Gate B, a held turn_into used in a world whose load had not run it said
        // "You need Stone" to a child holding stone, because `store result` into a missing objective
        // fails the whole line. Adding an objective that exists is harmless.
        out.add("    /scoreboard objectives add " + COUNTER + " dummy");
        out.add("    /tag @s remove mfam_ti_done");
        for (int k = 0; k < bag.size(); k++) {
            BagRow row = bag.get(k);
            // ONE OPERATION, NOT TWO (convert's note): `clear` reports how many it took, so
            // the test and the take cannot disagree. A tag, never the score, says which one fired,
            // so the next row cannot take a second thing in the same use.
            out.add("    /execute if entity @s[tag=!mfam_ti_done] store result score @s " + COUNTER
                    + " run clear @s " + row.take() + " 1");
            out.add("    /execute if entity @s[tag=!mfam_ti_done] if score @s " + COUNTER
                    + " matches 1.. run tag @s add mfam_ti_take");
            out.add("    /execute if entity @s[tag=mfam_ti_take] run function $namespace:turn_into_got_" + k);
            out.add("    /tag @s[tag=mfam_ti_take] add mfam_ti_done");
            out.add("    /tag @s remove mfam_ti_take");
            // GIVING AFTER TAKING (convert's yield note): the clear above happened first by construction.
            helpers.append("\nfunction turn_into_got_").append(k).append("()\n")
                    .append(row.give()).append('\n')
                    .append("    /tellraw @s [{\"text\":\"").append(row.inName())
                    .append("\",\"color\":\"gray\"},{\"text\":\" → \",\"color\":\"white\"},{\"text\":\"")
                    .append(row.outName()).append("\",\"color\":\"green\",\"bold\":true}]\n")
                    .append("    /playsound minecraft:block.brewing_stand.brew player @s ~ ~ ~ 1 1.4\n")
                    .append("    /particle minecraft:happy_villager ~ ~1 ~ 0.4 0.4 0.4 0.01 12 force @s\n")
                    .append("end\n");
        }
        for (String s : skipped) {
            // A thing a furnace makes nothing of: he is told so rather than "you need".
            String pred = s.substring(0, s.indexOf('\u0000'));
            String name = s.substring(s.indexOf('\u0000') + 1);
            out.add("    /execute if entity @s[tag=!mfam_ti_done] if items entity @s container.* " + pred
                    + " run tellraw @s [{\"text\":\"A furnace makes nothing from \",\"color\":\"yellow\"},"
                    + "{\"text\":\"" + name + "\",\"color\":\"white\",\"bold\":true}]");
            out.add("    /execute if entity @s[tag=!mfam_ti_done] if items entity @s container.* " + pred
                    + " run tag @s add mfam_ti_done");
        }
        out.add("    /execute if entity @s[tag=!mfam_ti_done] run function $namespace:turn_into_needs");
        out.add("    /tag @s remove mfam_ti_done");
        helpers.append("\nfunction turn_into_needs()\n")
                .append("    /tellraw @s [{\"text\":\"You need \",\"color\":\"yellow\"},{\"text\":\"")
                .append(either(takes)).append("\",\"color\":\"white\",\"bold\":true},{\"text\":\"")
                .append(" in your inventory to use this!\",\"color\":\"yellow\"}]\n")
                .append("    /playsound minecraft:entity.villager.no player @s ~ ~ ~ 1 1\n")
                .append("end\n");
        return String.join("\n", out);
    }

    /** "A", "A or B", "A, B or C" — and past six, the first five and "or another". */
    public static String either(List<String> names) {
        List<String> n = new ArrayList<>(new LinkedHashSet<>(names));
        if (n.isEmpty()) {
            return "something";
        }
        if (n.size() > 6) {
            n = new ArrayList<>(n.subList(0, 5));
            n.add("another one it takes");
        }
        if (n.size() == 1) {
            return n.get(0);
        }
        return String.join(", ", n.subList(0, n.size() - 1)) + " or " + n.get(n.size() - 1);
    }

    /**
     * Furnace rows in an order where no block is changed TWICE in one use: cobblestone → stone and
     * stone → smooth stone, run in that order, would turn cobblestone into smooth stone. So a row
     * whose input another row PRODUCES runs first.
     */
    static List<Map.Entry<String, String>> chainSafe(Map<String, String> rows) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        int guard = rows.size() + 1;
        while (done.size() < rows.size() && guard-- > 0) {
            for (Map.Entry<String, String> r : rows.entrySet()) {
                if (done.contains(r.getKey())) {
                    continue;
                }
                // ready when nothing still to run would turn something INTO this row's output
                // block... i.e. this row's output is not an input still waiting.
                boolean outputStillAnInput = rows.containsKey(r.getValue()) && !done.contains(r.getValue())
                        && !r.getValue().equals(r.getKey());
                if (!outputStillAnInput) {
                    out.add(Map.entry(r.getKey(), r.getValue()));
                    done.add(r.getKey());
                }
            }
        }
        for (Map.Entry<String, String> r : rows.entrySet()) {
            if (!done.contains(r.getKey())) {
                out.add(Map.entry(r.getKey(), r.getValue()));   // a cycle: keep its order
            }
        }
        return out;
    }

    /** minecraft:raw_iron → "Raw Iron". */
    public static String readable(String id) {
        if (id == null || id.isBlank()) {
            return "it";
        }
        String path = id.startsWith("#") ? id.substring(1) : id;
        path = path.contains(":") ? path.substring(path.indexOf(':') + 1) : path;
        path = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        StringBuilder b = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return (id.startsWith("#") ? "any " : "") + (b.length() == 0 ? id : b.toString());
    }

    // ── the old names (an earlier issue's migration) ────────────────────────────────────────────────────────

    /**
     * Rename one effect's parameters from the shape {@code convert} or {@code change_block} took to
     * {@code turn_into}'s, in place. INPUT → FROM and OUTPUT → TO (convert); BLOCK → TO (change_block;
     * FROM and RADIUS keep their names). An old value WINS over a new one already there, because the
     * only way both are present is an edit that sent the old name over a design that has the new.
     * A convert carried no RADIUS: one is added for the shapes whose block side needs it (0 where he
     * aims exactly, 1 elsewhere) so the design stays complete, and a machine never reads it.
     *
     * @return whether anything changed
     */
    public static boolean renameParams(JsonObject p, String shape) {
        if (p == null) {
            return false;
        }
        boolean wasConvert = p.has("INPUT") || p.has("OUTPUT");
        boolean changed = move(p, "INPUT", "FROM") | move(p, "OUTPUT", "TO") | move(p, "BLOCK", "TO");
        if (wasConvert && !p.has("RADIUS") && shape != null && !ItemCatalogue.MACHINE.equals(shape)) {
            p.addProperty("RADIUS", raycasts(shape) ? "0" : "1");
            changed = true;
        }
        return changed;
    }

    private static boolean move(JsonObject p, String from, String to) {
        if (!p.has(from)) {
            return false;
        }
        JsonElement v = p.remove(from);
        p.add(to, v);
        return true;
    }

    /** {@link #renameParams} over a Map, for the tool's own parsed params. */
    public static boolean renameParams(Map<String, String> p, String shape) {
        JsonObject o = new JsonObject();
        p.forEach(o::addProperty);
        boolean changed = renameParams(o, shape);
        if (changed) {
            p.clear();
            for (String k : o.keySet()) {
                p.put(k, o.get(k).getAsString());
            }
        }
        return changed;
    }

    /**
     * Normalise a whole {@code params} object for a design whose effects (canonical) include
     * {@code turn_into}: a group keyed by an OLD name moves under {@code turn_into}, and its keys —
     * or the flat keys of a one-effect design — are renamed. In place; returns whether anything changed.
     */
    public static boolean normaliseParams(JsonObject params, List<String> effects, String shape) {
        if (params == null || effects == null || !effects.contains(EFFECT)) {
            return false;
        }
        boolean changed = false;
        for (String old : OLD_IDS) {
            if (params.has(old) && params.get(old).isJsonObject()) {
                JsonObject g = params.remove(old).getAsJsonObject();
                if (params.has(EFFECT) && params.get(EFFECT).isJsonObject()) {
                    JsonObject cur = params.getAsJsonObject(EFFECT);
                    for (String k : g.keySet()) {
                        cur.add(k, g.get(k));
                    }
                } else {
                    params.add(EFFECT, g);
                }
                changed = true;
            }
        }
        if (params.has(EFFECT) && params.get(EFFECT).isJsonObject()) {
            changed |= renameParams(params.getAsJsonObject(EFFECT), shape);
        }
        if (effects.size() == 1 && params.has(EFFECT) && params.get(EFFECT).isJsonObject()
                && params.keySet().stream().anyMatch(k -> !k.startsWith("_") && !params.get(k).isJsonObject())) {
            // A ONE-EFFECT design stored FLAT, and an edit that sent its change GROUPED (under an old
            // name, say) over it, leave both — and the tool would read only the group, the design's
            // TO and RADIUS "still needed". Fold the group onto the flat keys; it wins. A design that
            // was only ever grouped stays grouped (that is how it was stored, and the census's rule).
            JsonObject g = params.remove(EFFECT).getAsJsonObject();
            for (String k : g.keySet()) {
                params.add(k, g.get(k));
            }
            changed = true;
        }
        if (effects.size() == 1) {
            JsonObject flat = new JsonObject();
            for (String k : params.keySet()) {
                if (!params.get(k).isJsonObject() && !k.startsWith("_")) {
                    flat.add(k, params.get(k));
                }
            }
            JsonObject before = flat.deepCopy();
            if (renameParams(flat, shape)) {
                for (String k : before.keySet()) {
                    params.remove(k);
                }
                for (String k : flat.keySet()) {
                    params.add(k, flat.get(k));
                }
                changed = true;
            }
        }
        return changed;
    }

    /** The effect list with every old name written as {@code turn_into} (order kept, no repeats). */
    public static List<String> canonicalEffects(List<String> effects) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String e : effects) {
            out.add(OLD_IDS.contains(e) ? EFFECT : e);
        }
        return List.copyOf(out);
    }
}
