package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>PROPERTIES — what an item IS, as opposed to what it DOES.</b>
 *
 * <p>The axis is defined by one line in {@code docs/ITEM_CAPABILITY_AXES.md}: <i>if a capability can
 * be expressed on the {@code /give} line it is an AXIS; if it can only be expressed as something
 * that happens it is an EFFECT.</i> A stack size is on the give line. Setting fire to what you hit
 * is not.
 *
 * <p><b>Why this is a table and not a map the agent fills in.</b> A component map an agent can write
 * into is an injection surface straight into NBT — one free-text value and the give line says
 * whatever the model was persuaded to say. So every property here is a <b>closed key</b> with a
 * <b>bounded integer</b>, the value is parsed as a number and re-rendered from that number, and
 * nothing the agent typed ever reaches the command. It is the shape {@code SUMMON_AS} already uses:
 * a lookup, never interpolation.
 *
 * <p><b>Adding a property is one row of {@link #RULES} and one case in {@link #component}.</b> That
 * is the whole reason this is a class rather than two {@code if}s in the give builder — the axis is
 * meant to grow, and the four that were considered and NOT shipped are recorded here so the next
 * person does not re-derive the argument:
 *
 * <ul>
 *   <li><b>{@code unbreakable} — blocked, and it is a product decision.</b> {@code gives()} hands
 *       out {@code moddingfromamod:wish_item}, which has no durability, so the component is inert
 *       on it. Spike 3 priced the fork: a {@code max_damage} component keeps the icon library and
 *       makes an axe a re-skinned generic item, while a real {@code minecraft:golden_axe} is
 *       genuinely an axe and loses the icon library. Neither is mine to choose, so
 *       <i>"a gold axe that never brakes"</i> stays blocked rather than half-answered.</li>
 *   <li><b>{@code food} — deliberately NOT added, because there is already a {@code food} EFFECT.</b>
 *       The component would be the better mechanism (a real eat animation, real hunger, and it
 *       composes with {@code plain}), but shipping it alongside the effect gives the agent two ways
 *       to say one thing — and measured how sharply selection turns on how choices are framed.
 *       It should REPLACE the effect one day, which is a migration, not an addition.</li>
 *   <li><b>{@code enchantments} — SHIPPED 2026-09-25</b>, the biggest single part E1
 *       names (8 wishes: a pickaxe that gives extra diamonds, a trident that comes back, a rod that
 *       catches treasure…). It was held back because whether an enchantment does anything on an
 *       item it does not normally go on was unmeasured. Since 1.21 an enchantment's effects are
 *       data-driven and keyed on the SLOT it is in, not on the item ({@code supported_items} only
 *       gates the table and the anvil), and {@code ItemPropertiesGameTest} now measures it on the
 *       real game rather than asserting it. Still a closed key and bounded ints: the value is an
 *       object whose keys are {@link #ENCHANTMENTS} and whose values are levels, re-rendered from
 *       the parsed numbers, so nothing the agent typed reaches the command.</li>
 *   <li><b>{@code fire_resistant} — no evidence.</b> It works today; no wish in the corpus asks for
 *       it. An axis grows on demand, not on the component list.</li>
 * </ul>
 */
public final class ItemProperties {

    private ItemProperties() {
    }

    /**
     * One property: which component carries it, and the range the value must sit in.
     *
     * @param what how to describe it to the agent when the value is out of range — in the child's
     *             terms, because the refusal is read while his wish is still on screen
     */
    public record Rule(String key, int min, int max, String what) {
    }

    /** The one property whose value is an object of {@code enchantment: level}, not a number. */
    public static final String ENCHANTMENTS_KEY = "enchantments";

    /**
     * <b>The enchantments a wish may put on a thing, and the highest level of each.</b> Vanilla's
     * own ids (the same on 1.21.1 and 1.21.4), curses left out — nobody wishes for one, and a
     * vanishing or binding curse is a thing done TO him. The caps are vanilla's own maximum, raised
     * only where "super" is the whole wish and a higher level stays a toy (digging, hitting,
     * fortune, looting); protection stays at 4, because past it a child is simply unhurtable.
     */
    public static final Map<String, Integer> ENCHANTMENTS = new LinkedHashMap<>();

    static {
        Object[][] table = {
                {"efficiency", 10}, {"fortune", 5}, {"silk_touch", 1}, {"unbreaking", 10},
                {"mending", 1}, {"sharpness", 10}, {"smite", 5}, {"bane_of_arthropods", 5},
                {"knockback", 5}, {"fire_aspect", 2}, {"looting", 5}, {"sweeping_edge", 3},
                {"power", 10}, {"punch", 3}, {"flame", 1}, {"infinity", 1}, {"multishot", 1},
                {"piercing", 4}, {"quick_charge", 3}, {"loyalty", 3}, {"riptide", 3},
                {"channeling", 1}, {"impaling", 5}, {"luck_of_the_sea", 5}, {"lure", 3},
                {"protection", 4}, {"fire_protection", 4}, {"blast_protection", 4},
                {"projectile_protection", 4}, {"feather_falling", 4}, {"thorns", 3},
                {"respiration", 3}, {"aqua_affinity", 1}, {"depth_strider", 3},
                {"frost_walker", 2}, {"soul_speed", 3}, {"swift_sneak", 3}, {"density", 5},
                {"breach", 4}, {"wind_burst", 3}};
        for (Object[] row : table) {
            ENCHANTMENTS.put((String) row[0], (Integer) row[1]);
        }
    }

    /**
     * <b>What a bow or crossbow shoots INSTEAD of its arrow (T6F follow-up).</b> One word from
     * this closed list, rendered to the entity id from OUR table, never from what the agent typed.
     * The draw, the loose and the power stay vanilla's; only what flies changes. Two are absent on
     * purpose: a ghast's fireball blows holes in the world ("a bow that shoots fireballs" is a
     * blaze's burning one), and a dragon's leaves a cloud of harming that does not spare the child
     * who fired it.
     */
    public static final String PROJECTILE_KEY = "projectile";
    public static final Map<String, String> PROJECTILES = new LinkedHashMap<>();

    static {
        PROJECTILES.put("fireball", "minecraft:small_fireball");
        PROJECTILES.put("egg", "minecraft:egg");
        PROJECTILES.put("snowball", "minecraft:snowball");
        PROJECTILES.put("wind_charge", "minecraft:wind_charge");
    }

    /** The launchers a {@code projectile} means anything on: vanilla's own two that loose an arrow. */
    public static final List<String> PROJECTILE_BASES = List.of("minecraft:bow", "minecraft:crossbow");

    /**
     * <b>A bucket that stays full (T6F follow-up).</b> The pour is vanilla's; afterwards the
     * wished bucket is put back where the empty one would have been. Only the buckets a child pours
     * or drinks from — a fish bucket stays a fish bucket, and "never empty" of a fish is a spawner.
     */
    public static final String NEVER_EMPTY_KEY = "never_empty";
    public static final List<String> NEVER_EMPTY_BASES = List.of("minecraft:water_bucket",
            "minecraft:lava_bucket", "minecraft:powder_snow_bucket", "minecraft:milk_bucket");

    /**
     * <b>A bag for one kind of thing.</b> One word, rendered to an item TAG from this
     * table — never from the agent's text — on a real bundle. Everything else is refused at the
     * click ({@code item/OneKindBag}). A value may name several tags, comma-separated, any of which
     * lets a thing in: {@code #minecraft:flowers} is an EMPTY item tag on 1.21.4 (measured by
     * {@code OneKindBagGameTest}), so "every kind of flower" is vanilla's small ones and the convention tag's tall ones
     * ({@code #minecraft:tall_flowers} is empty there too).
     */
    public static final String HOLDS_ONLY_KEY = "holds_only";

    /**
     * <b>Eaten or drunk faster.</b> How many times faster, 2–8: the use is shortened
     * on the server ({@code item/EatSpeed}), so it works on a real food or drink on both targets.
     */
    public static final String EAT_SPEED_KEY = "eat_speed";

    /**
     * <b>Leaves something behind him.</b> One word from {@link #TRAILS}; placed by
     * {@code item/Trail} where he just stood, while he holds or wears it.
     */
    public static final String TRAIL_KEY = "trail";

    /**
     * <b>Breaks more than one block a swing.</b> One word from {@code item.MineShape#WORDS}:
     * 3x3, 5x5, vein or hole; {@code item/MineArea} breaks the rest as he would by hand.
     */
    public static final String MINE_AREA_KEY = "mine_area";

    /**
     * <b>Shows him where the ore is.</b> An object {@code {"ore": …, "how": …}},
     * both words from {@link com.moddingfromamod.item.RevealRule}'s closed lists; {@code item/Reveal}
     * does it while he holds or wears the thing.
     */
    public static final String REVEALS_KEY = "reveals";

    /** A bag that throws out the junk BY ITSELF when he is full: 0 or 1, rendered to custom_data. */
    public static final String DROPS_JUNK_KEY = "drops_junk";

    /** A bag that keeps his things sorted BY ITSELF (the held-out "automatically"): 0 or 1. */
    public static final String AUTO_SORT_KEY = "auto_sort";
    public static final String AUTO_SORT_DATA = "WishAutoSort";

    /**
     * <b>Catches something special.</b> An object {@code {"what": …, "chance": n}} on a
     * real fishing rod: {@code what} is one item id, {@code "random: a, b, c"} (an earlier issue's list, up to
     * {@link PickList#MAX}), or one of {@link #CATCH_SPECIALS}; {@code chance} 1–100 is how often it
     * comes up instead of the fish. Ids are checked for SHAPE here and against the registry by
     * make_item; nothing the agent typed reaches the game but a checked id.
     */
    public static final String CATCHES_KEY = "catches";

    /** A fork or spoon that eats a CAKE faster (part 2): bites per click, 2–7 (a cake has 7). */
    public static final String CAKE_BITES_KEY = "cake_bites";

    /** A real fishing rod that PULLS him to where its bobber rests on a block: 0 or 1. */
    public static final String GRAPPLE_KEY = "grapple";
    public static final String GRAPPLE_DATA = "WishGrapple";
    public static final String CAKE_BITES_DATA = "WishCakeBites";
    public static final String CATCHES_DATA = "WishCatch";
    public static final List<String> CATCH_SPECIALS = List.of("treasure_chest", "enchanted_book");
    private static final java.util.regex.Pattern ITEM_ID =
            java.util.regex.Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+");

    /** {what, chance} normalised ("random: a,b" or an id or a special), or null when invalid. */
    public static String[] catchOf(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        Integer chance = intOf(o.get("chance"));
        if (chance == null || chance < 1 || chance > 100 || !o.has("what") || !o.get("what").isJsonPrimitive()) {
            return null;
        }
        String raw = o.get("what").getAsString().trim().toLowerCase(java.util.Locale.ROOT);
        String mode = PickList.modeOf(raw);
        if ("cycle".equals(mode)) {
            return null;   // a catch has no order to keep; random is the word
        }
        List<String> items = new ArrayList<>();
        for (String id : PickList.items(mode == null ? raw : PickList.withoutMode(raw))) {
            String full = CATCH_SPECIALS.contains(id) || id.contains(":") ? id : "minecraft:" + id;
            if (!CATCH_SPECIALS.contains(full) && !ITEM_ID.matcher(full).matches()) {
                return null;
            }
            items.add(full);
        }
        if (items.isEmpty() || items.size() > PickList.MAX || (mode == null && items.size() > 1)) {
            return null;
        }
        return new String[]{String.join(",", items), String.valueOf(chance)};
    }
    public static final String DROPS_JUNK_DATA = "WishDropsJunk";
    public static final String REVEALS_DATA = "WishReveal";

    /** {ore, how} from OUR lists, or null. */
    public static String[] revealOf(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String ore = com.moddingfromamod.item.RevealRule.ore(o.has("ore") && o.get("ore").isJsonPrimitive()
                ? o.get("ore").getAsString() : null);
        String how = o.has("how") && o.get("how").isJsonPrimitive()
                ? o.get("how").getAsString().trim().toLowerCase(java.util.Locale.ROOT) : "glow";
        int h = com.moddingfromamod.item.RevealRule.HOW.indexOf(how);
        return ore == null || h < 0 ? null : new String[]{ore, com.moddingfromamod.item.RevealRule.HOW.get(h)};
    }
    public static final String TRAIL_DATA = "WishTrail";
    public static final List<String> TRAILS = List.of("fire", "flowers", "snow", "light");

    /** The trail word, from OUR list, or null. */
    public static String trailOf(JsonObject props) {
        if (props == null || !props.has(TRAIL_KEY) || !props.get(TRAIL_KEY).isJsonPrimitive()) {
            return null;
        }
        String w = props.get(TRAIL_KEY).getAsString().trim().toLowerCase(java.util.Locale.ROOT);
        if (w.equals("flower")) {
            w = "flowers";
        }
        return TRAILS.contains(w) ? TRAILS.get(TRAILS.indexOf(w)) : null;
    }
    public static final String MINE_AREA_DATA = "WishMineArea";

    /** The mine_area word, from OUR list, or null. */
    public static String mineAreaOf(JsonObject props) {
        if (props == null || !props.has(MINE_AREA_KEY) || !props.get(MINE_AREA_KEY).isJsonPrimitive()) {
            return null;
        }
        return com.moddingfromamod.item.MineShape.word(props.get(MINE_AREA_KEY).getAsString());
    }
    public static final String EAT_SPEED_DATA = "WishEatSpeed";
    public static final String HOLDS_ONLY_DATA = "WishHoldsOnly";
    public static final Map<String, String> HOLDS_ONLY = new LinkedHashMap<>();

    static {
        HOLDS_ONLY.put("food", "c:foods");
        HOLDS_ONLY.put("arrows", "minecraft:arrows");
        HOLDS_ONLY.put("flowers", "minecraft:small_flowers,c:flowers/tall");
        HOLDS_ONLY.put("seeds", "c:seeds");
        HOLDS_ONLY.put("ores", "c:ores");
        HOLDS_ONLY.put("gems", "c:gems");
        HOLDS_ONLY.put("ingots", "c:ingots");
        HOLDS_ONLY.put("wool", "minecraft:wool");
        HOLDS_ONLY.put("saplings", "minecraft:saplings");
        HOLDS_ONLY.put("logs", "minecraft:logs");
        HOLDS_ONLY.put("fish", "minecraft:fishes");
        HOLDS_ONLY.put("music_discs", "c:music_discs");
        HOLDS_ONLY.put("dyes", "c:dyes");
        HOLDS_ONLY.put("potions", "c:potions");
        HOLDS_ONLY.put("eggs", "c:eggs");
    }

    /** The bases a {@code holds_only} means anything on: the plain bundle and its sixteen dyes. */
    public static boolean isBundle(String base) {
        return base != null && (base.equals("minecraft:bundle") || base.matches("minecraft:[a-z_]+_bundle"));
    }

    /** The tag a {@code holds_only} word names, from OUR table, or null. */
    public static String holdsOnlyTag(JsonObject props) {
        if (props == null || !props.has(HOLDS_ONLY_KEY) || !props.get(HOLDS_ONLY_KEY).isJsonPrimitive()) {
            return null;
        }
        String w = props.get(HOLDS_ONLY_KEY).getAsString().trim().toLowerCase(java.util.Locale.ROOT)
                .replace(' ', '_');
        String t = HOLDS_ONLY.get(w);
        if (t == null && !w.endsWith("s")) {
            t = HOLDS_ONLY.get(w + "s");
        }
        return t;
    }

    /**
     * <b>Stronger at certain times.</b> An object {@code {"when": …, "stat": …,
     * "amount": n}} (and {@code "y"} for {@code below_y}), every word from
     * {@link com.moddingfromamod.item.StrongerWhenRule}'s closed lists and every number re-rendered.
     */
    public static final String STRONGER_WHEN_KEY = "stronger_when";
    public static final String STRONGER_WHEN_DATA = "WishStrongerWhen";

    /** The {@code custom_data} keys these two live under — read by {@code WishItem}. */
    public static final String PROJECTILE_DATA = "WishProjectile";
    public static final String NEVER_EMPTY_DATA = "WishNeverEmpty";

    /**
     * <b>The closed set.</b> Keys are the component names on purpose rather than invented child
     * words: the reader is a model that knows Minecraft, the pre-registration in
     * {@code corpus/item-expressibility.jsonl} was written with these names before any of this
     * existed, and rewriting the yardstick to match the thing it measures is marking your own
     * homework.
     */
    private static final Map<String, Rule> RULES = new LinkedHashMap<>();

    static {
        put(new Rule("max_stack_size", 1, 64,
                "how many of them fit in one slot. A big one he can only carry ONE of is 1; a "
                        + "little one he keeps a pile of is 64. This is what makes 'small, medium "
                        + "and LARGE' three different things rather than one thing in three "
                        + "colours"));
        // ⚠ 0 or 1, not a boolean, because every value in this table is a bounded int and one
        // exception to that is how a table stops being a table. 1 means "never breaks".
        put(new Rule("unbreakable", 0, 1,
                "whether it can ever wear out. 1 means never. It only means anything on something "
                        + "that HAS durability — a real tool or weapon, named with base_item — "
                        + "because an item made from scratch has none to protect"));
        put(new Rule("attack_damage", 1, 40,
                "how hard it hits when he SWINGS it, in damage points — 2 is one heart. This is "
                        + "what the item IS, so it works however he got it and whatever shape it "
                        + "has. It is NOT the `hurt` effect: `hurt` is a wish that fires when the "
                        + "trigger goes off and reaches whatever the trigger points at, which is "
                        + "what a thrown or held thing needs"));
        // min/max unused: the value is one WORD from PROJECTILES.
        put(new Rule("projectile", 0, 0,   // = PROJECTILE_KEY; a literal, because E1 reads it
                "what a bow or crossbow shoots INSTEAD of an arrow, as one word: "
                        + String.join(", ", PROJECTILES.keySet()) + ". The draw and the shot stay "
                        + "the bow's own, and the thing flies as far as the draw sends it — so a "
                        + "launcher whose shot IS an egg or a fireball is a real bow with this, not "
                        + "an arrow that makes eggs where it lands. It still needs an arrow to draw, so give "
                        + "infinity too (or arrows). Only on base_item minecraft:bow or "
                        + "minecraft:crossbow"));
        put(new Rule("never_empty", 0, 1,  // = NEVER_EMPTY_KEY; a literal, because E1 reads it
                "1 = a real bucket that is STILL FULL after he pours or drinks it: the water, lava "
                        + "or powder snow goes where a bucket puts it, every time, and the bucket "
                        + "stays in his hand. Only on base_item minecraft:water_bucket, "
                        + "lava_bucket, powder_snow_bucket or milk_bucket, with shape plain (the "
                        + "bucket's own pour is the whole wish)"));
        // min/max unused: the value is an object, checked by catchOf.
        put(new Rule("catches", 0, 0,   // = CATCHES_KEY; a literal, because E1 reads it
                "a real FISHING ROD that sometimes catches something else, as {\"what\": "
                        + "\"minecraft:diamond\", \"chance\": 30}: what is one item id, \"random: id, id, …\" "
                        + "(a different one each time, up to " + PickList.MAX + "), treasure_chest (a chest full of "
                        + "buried treasure) or enchanted_book (a real random enchantment); chance 1–100 is how often "
                        + "it comes up INSTEAD of the fish ('sometimes' is about 25, 'always' is 100). Only on "
                        + "base_item minecraft:fishing_rod"));
        put(new Rule("grapple", 0, 1,   // = GRAPPLE_KEY; a literal, because E1 reads it
                "1 = a real FISHING ROD that pulls him: cast it onto a block (not water), reel in, and "
                        + "he swings to where the bobber is, landing without fall damage. Only on base_item "
                        + "minecraft:fishing_rod; for a hook he THROWS, it is the grapple effect on a "
                        + "throwable with shape on_throw_land"));
        put(new Rule("cake_bites", 2, 7,   // = CAKE_BITES_KEY; a literal, because E1 reads it
                "a thing he HOLDS that eats a cake faster: how many bites one click on a real cake "
                        + "takes (a whole cake is 7). He still has to be hungry, and each bite fills him "
                        + "like a bite. For food he holds and eats, that is eat_speed, not this"));
        put(new Rule("auto_sort", 0, 1,   // = AUTO_SORT_KEY; a literal, because E1 reads it
                "1 = it keeps his bag SORTED BY ITSELF while he carries it: every couple of seconds his "
                        + "inventory (never his hotbar) is stacked and grouped — blocks, food, tools, the "
                        + "rest — the way the sort_inventory effect does on a click. It waits while a "
                        + "screen is open, so it never moves a thing under his cursor"));
        put(new Rule("drops_junk", 0, 1,   // = DROPS_JUNK_KEY; a literal, because E1 reads it
                "1 = it throws out the junk BY ITSELF whenever his bag is full: dirt, cobblestone, "
                        + "gravel, netherrack, rotten flesh, seeds and the like are dropped at his feet "
                        + "(never destroyed). It works anywhere he carries it. To do it on a click "
                        + "instead, that is the drop_junk effect"));
        // min/max unused: the value is an object, checked by revealOf.
        put(new Rule("reveals", 0, 0,   // = REVEALS_KEY; a literal, because E1 reads it
                "it SHOWS him where an ore is, while he holds it or wears it, as {\"ore\": \"diamond\", "
                        + "\"how\": \"glow\"}: ore is one of " + String.join(", ", com.moddingfromamod.item.RevealRule.ORES.keySet())
                        + "; how is glow (the nearest ores within " + com.moddingfromamod.item.RevealRule.RADIUS
                        + " blocks shine through walls) or beep (a beep that comes faster and higher the "
                        + "closer he gets). A map, goggles, a detector: build it on the real thing"));
        // min/max unused: the value is one WORD from TRAILS.
        put(new Rule("trail", 0, 0,   // = TRAIL_KEY; a literal, because E1 reads it
                "it LEAVES something behind him where he walks, while he holds it or wears it, as "
                        + "one word: fire (and he does not burn), flowers (only where a flower can "
                        + "grow), snow, light (an invisible light that lights his path). Freezing "
                        + "WATER into a path is not this: that is real boots with the frost_walker "
                        + "enchantment"));
        // min/max unused: the value is one WORD from MineShape.WORDS.
        put(new Rule("mine_area", 0, 0,   // = MINE_AREA_KEY; a literal, because E1 reads it
                "a real TOOL that breaks MORE than the block he hits, as one word: 3x3 (a hammer: "
                        + "the face he hit, three by three), 5x5, vein (the whole ore vein, or a whole "
                        + "tree trunk, at once) or hole (a shovel that digs a round hole). Every block "
                        + "drops and wears the tool as if he had mined it by hand. Build it on the real "
                        + "pickaxe, shovel or axe with base_item. Three ARROWS at once is not this: "
                        + "that is a crossbow with multishot"));
        put(new Rule("eat_speed", 2, 8,   // = EAT_SPEED_KEY; a literal, because E1 reads it
                "how many times FASTER he eats or drinks it: 2 is twice as fast, 8 is gone in a "
                        + "gulp. Only on a real food or drink named with base_item (a cake is a block "
                        + "and is not this). Build it on shape on_consume if the wish also does "
                        + "something when he finishes, or plain if being quick is the whole wish"));
        // min/max unused: the value is one WORD from HOLDS_ONLY.
        put(new Rule("holds_only", 0, 0,   // = HOLDS_ONLY_KEY; a literal, because E1 reads it
                "a real BUNDLE that only takes one kind of thing, as one word: "
                        + String.join(", ", HOLDS_ONLY.keySet()) + ". Anything else stays on his cursor "
                        + "when he tries to put it in. Only on base_item minecraft:bundle (or a dyed "
                        + "bundle). A bundle holds one stack's worth whatever it is called — a 'HUGE' "
                        + "bag is not possible, so say so plainly rather than promising it"
                        + (com.moddingfromamod.compat.Bundles.SUPPORTED ? ""
                                : ". NOT ON THIS MINECRAFT: 1.21.1's bundle is experimental, so this is refused")));
        // min/max unused: the value is an object, checked by StrongerWhenRule.
        put(new Rule("stronger_when", 0, 0,   // = STRONGER_WHEN_KEY; a literal, because E1 reads it
                "it gets STRONGER at a certain time, as {\"when\": \"night\", \"stat\": \"attack_damage\", "
                        + "\"amount\": 6}: while he holds it and the time is right, it hits harder or digs "
                        + "faster, and when the time passes it is back to normal. when is one of "
                        + String.join(", ", com.moddingfromamod.item.StrongerWhenRule.CONDITIONS)
                        + " (below_y takes \"y\", the height he has to be under — 'really deep "
                        + "underground' is 0); stat is attack_damage (1–20 damage points, 2 is one heart) "
                        + "or mining_speed (1–10: 1 is twice as fast). Build it on the real sword or "
                        + "pickaxe with base_item. A sword that is ALWAYS strong is attack_damage, not this"));
        // min/max are the LEVEL range; the value is {"<enchantment>": level, ...}.
        put(new Rule("enchantments", 1, 10,   // = ENCHANTMENTS_KEY; a literal, because E1 reads it
                "vanilla enchantments it carries, as {\"fortune\": 3, \"unbreaking\": 3}. Use it when "
                        + "what he asked for is exactly what an enchantment already does — more "
                        + "drops from ore (fortune), a throw that returns to him (loyalty), better "
                        + "fishing (luck_of_the_sea), faster digging (efficiency), water that "
                        + "freezes under his feet (frost_walker), arrows that never run out "
                        + "(infinity). It does what the enchantment does in the SLOT it is in "
                        + "(fortune and efficiency in his hand, frost_walker on his feet), so build "
                        + "it on the real item with base_item, and remember vanilla's own rules: "
                        + "three arrows at once is multishot, which only a CROSSBOW fires. NOT for "
                        + "a power he keeps while wearing it: breathing underwater for good is the "
                        + "`status` effect water_breathing (respiration only makes one breath last "
                        + "longer), and seeing in the dark is status night_vision. Levels "
                        + "go up to vanilla's maximum (a few 'super' ones higher). Allowed: "
                        + String.join(", ", ENCHANTMENTS.keySet())));
    }

    private static void put(Rule r) {
        RULES.put(r.key(), r);
    }

    /** The property names, in the order the refusal lists them. */
    public static List<String> keys() {
        return List.copyOf(RULES.keySet());
    }

    /**
     * The table itself, so a gate can be a MATRIX OVER IT rather than a list of cells somebody
     * typed.
     *
     * <p>That distinction is the whole reason this is exposed. The first version of
     * {@code ItemPropertiesGameTest} checked the two properties that happened to ship, by hand —
     * and it caught a real defect only because one of those two was the broken one. A third
     * property added tomorrow would have had no cover at all, which is exactly the shape of green
     * that means nothing.
     */
    public static List<Rule> rules() {
        return List.copyOf(RULES.values());
    }

    /** One line per property for the tool description and for {@code inspect_item}. */
    public static List<String> catalogue() {
        List<String> out = new ArrayList<>();
        for (Rule r : RULES.values()) {
            out.add(r.key() + (PROJECTILE_KEY.equals(r.key()) || HOLDS_ONLY_KEY.equals(r.key())
                    || TRAIL_KEY.equals(r.key()) || MINE_AREA_KEY.equals(r.key()) ? " (one word)"
                    : STRONGER_WHEN_KEY.equals(r.key()) || REVEALS_KEY.equals(r.key())
                    || CATCHES_KEY.equals(r.key()) ? " (an object)"
                    : " (" + r.min() + "–" + r.max() + ")") + " — " + r.what());
        }
        return out;
    }

    /**
     * Everything wrong with this {@code properties} object, in the agent's terms, or empty.
     *
     * <p>Refuses rather than clamps, on the same argument every other parameter here follows: a
     * wish for a stack of 200 quietly answered with 64 is a wrong answer the child discovers by
     * counting, and nothing in the transcript would say so.
     */
    public static List<String> refusals(JsonObject props) {
        List<String> bad = new ArrayList<>();
        if (props == null) {
            return bad;
        }
        for (String key : props.keySet()) {
            Rule r = RULES.get(key);
            if (r == null) {
                bad.add("there is no item property called '" + key + "'. The ones that exist are: "
                        + String.join(", ", RULES.keySet()) + ". If what he asked for is something "
                        + "that HAPPENS rather than something the thing IS, it is an effect, not a "
                        + "property.");
                continue;
            }
            if (ENCHANTMENTS_KEY.equals(key)) {
                bad.addAll(enchantmentRefusals(props.get(key)));
                continue;
            }
            if (CATCHES_KEY.equals(key)) {
                if (catchOf(props.get(key)) == null) {
                    bad.add("catches must be {\"what\": an item id, \"random: id, id\" (up to " + PickList.MAX
                            + "), treasure_chest or enchanted_book, \"chance\": 1 to 100}, not " + props.get(key) + ".");
                }
                continue;
            }
            if (REVEALS_KEY.equals(key)) {
                if (revealOf(props.get(key)) == null) {
                    bad.add("reveals must be {\"ore\": one of " + com.moddingfromamod.item.RevealRule.ORES.keySet()
                            + ", \"how\": glow or beep}, not " + props.get(key) + ". If the thing he wants "
                            + "found is not an ore on that list, say so rather than picking one for him.");
                }
                continue;
            }
            if (MINE_AREA_KEY.equals(key)) {
                if (mineAreaOf(props) == null) {
                    bad.add("mine_area is " + props.get(key) + ", and it has to be one of: "
                            + String.join(", ", com.moddingfromamod.item.MineShape.WORDS) + ".");
                }
                continue;
            }
            if (TRAIL_KEY.equals(key)) {
                if (trailOf(props) == null) {
                    bad.add("trail is " + props.get(key) + ", and it has to be one of: "
                            + String.join(", ", TRAILS) + ". Ice over water is frost_walker boots, "
                            + "not a trail; anything else, say so rather than picking one for him.");
                }
                continue;
            }
            if (HOLDS_ONLY_KEY.equals(key)) {
                if (holdsOnlyTag(props) == null) {
                    bad.add("holds_only is " + props.get(key) + ", and it has to be one of: "
                            + String.join(", ", HOLDS_ONLY.keySet()) + ". If what he wants the bag to "
                            + "hold is not one of those, say so rather than picking one for him.");
                }
                continue;
            }
            if (STRONGER_WHEN_KEY.equals(key)) {
                String why = strongerWhenRefusal(props.get(key));
                if (why != null) {
                    bad.add(why);
                }
                continue;
            }
            if (PROJECTILE_KEY.equals(key)) {
                if (projectileOf(props) == null) {
                    bad.add("projectile is " + props.get(key) + ", and it has to be one of: "
                            + String.join(", ", PROJECTILES.keySet()) + ". If what he wants the bow "
                            + "to shoot is not one of those, say so rather than picking one for him.");
                }
                continue;
            }
            Integer v = intOf(props.get(key));
            if (v == null) {
                bad.add(key + " must be a whole number, " + r.min() + " to " + r.max() + " — "
                        + r.what() + ".");
            } else if (v < r.min() || v > r.max()) {
                bad.add(key + " is " + v + ", and it has to be " + r.min() + " to " + r.max()
                        + " — " + r.what() + ". Ask him to pick inside that rather than choosing "
                        + "for him.");
            }
        }
        return bad;
    }

    /**
     * What is wrong with these properties ON THIS BASE, or empty. A projectile on a sword and a
     * never-empty diamond are not refused by the table — they parse — and would silently do
     * nothing, which is the one answer a child cannot see through.
     */
    public static List<String> baseRefusals(JsonObject props, String baseItem) {
        List<String> bad = new ArrayList<>();
        if (props == null) {
            return bad;
        }
        String base = baseItem == null ? "" : baseItem.contains(":") ? baseItem : "minecraft:" + baseItem;
        if (props.has(PROJECTILE_KEY) && !PROJECTILE_BASES.contains(base)) {
            bad.add("projectile only means something on a real bow or crossbow — set base_item to "
                    + "minecraft:bow or minecraft:crossbow (it is " + (base.isEmpty() ? "nothing" : base)
                    + "). A thing that is not a launcher does not shoot.");
        }
        if (props.has(HOLDS_ONLY_KEY) && !com.moddingfromamod.compat.Bundles.SUPPORTED) {
            bad.add("holds_only needs a real bundle, and on this Minecraft (1.21.1) the bundle is an "
                    + "experimental item a world does not have. Tell him plainly it is not possible here.");
        } else if (props.has(HOLDS_ONLY_KEY) && !isBundle(base)) {
            bad.add("holds_only only means something on a real bundle — set base_item to "
                    + "minecraft:bundle (it is " + (base.isEmpty() ? "nothing" : base) + ").");
        }
        Integer pulls = intOf(props.get(GRAPPLE_KEY));
        if (pulls != null && pulls == 1 && !"minecraft:fishing_rod".equals(base)) {
            bad.add("grapple only means something on a real fishing rod — set base_item to "
                    + "minecraft:fishing_rod (it is " + (base.isEmpty() ? "nothing" : base) + "). A hook he "
                    + "THROWS is the grapple effect instead, on a throwable with shape on_throw_land.");
        }
        if (props.has(CATCHES_KEY) && !"minecraft:fishing_rod".equals(base)) {
            bad.add("catches only means something on a real fishing rod — set base_item to "
                    + "minecraft:fishing_rod (it is " + (base.isEmpty() ? "nothing" : base) + ").");
        }
        //, measured: "three arrows at once" came as multishot on a BOW in 2 of 8 runs. Vanilla
        // fires these three from a crossbow only; on anything else they do nothing and say nothing.
        JsonElement ench = props.get(ENCHANTMENTS_KEY);
        if (ench != null && ench.isJsonObject() && !"minecraft:crossbow".equals(base)) {
            for (String crossbowOnly : List.of("multishot", "quick_charge", "piercing")) {
                if (ench.getAsJsonObject().has(crossbowOnly)) {
                    bad.add(crossbowOnly + " only works on a CROSSBOW — set base_item to minecraft:crossbow "
                            + "(it is " + (base.isEmpty() ? "nothing" : base) + "). A bow cannot fire several "
                            + "arrows at once; tell him it is a crossbow.");
                }
            }
        }
        Integer keep = intOf(props.get(NEVER_EMPTY_KEY));
        if (keep != null && keep == 1 && !NEVER_EMPTY_BASES.contains(base)) {
            bad.add("never_empty only means something on a real bucket — set base_item to one of "
                    + String.join(", ", NEVER_EMPTY_BASES) + " (it is " + (base.isEmpty() ? "nothing" : base)
                    + ").");
        }
        return bad;
    }

    /** The entity id this item shoots instead of an arrow, from OUR table, or null. */
    public static String projectileOf(JsonObject props) {
        if (props == null || !props.has(PROJECTILE_KEY) || !props.get(PROJECTILE_KEY).isJsonPrimitive()) {
            return null;
        }
        String w = props.get(PROJECTILE_KEY).getAsString().trim().toLowerCase(java.util.Locale.ROOT);
        return PROJECTILES.get(w.startsWith("minecraft:") ? w.substring(10) : w);
    }

    /**
     * The properties that live in {@code custom_data} rather than in a component of their own —
     * <b>starting with a comma</b>, or empty. Rendered from our tables, never from the agent's text.
     */
    public static String dataEntries(JsonObject props) {
        if (props == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        String shoots = projectileOf(props);
        if (shoots != null) {
            b.append(',').append(PROJECTILE_DATA).append(":\"").append(shoots).append('"');
        }
        Integer keep = intOf(props.get(NEVER_EMPTY_KEY));
        if (keep != null && keep == 1) {
            b.append(',').append(NEVER_EMPTY_DATA).append(":1b");
        }
        String[] caught = catchOf(props.get(CATCHES_KEY));
        if (caught != null) {
            b.append(',').append(CATCHES_DATA).append(":{what:\"").append(caught[0])
                    .append("\",chance:").append(caught[1]).append('}');
        }
        Integer grapple = intOf(props.get(GRAPPLE_KEY));
        if (grapple != null && grapple == 1) {
            b.append(',').append(GRAPPLE_DATA).append(":1b");
        }
        Integer bites = intOf(props.get(CAKE_BITES_KEY));
        if (bites != null && bites >= 2 && bites <= 7) {
            b.append(',').append(CAKE_BITES_DATA).append(':').append(bites);
        }
        Integer tidy = intOf(props.get(AUTO_SORT_KEY));
        if (tidy != null && tidy == 1) {
            b.append(',').append(AUTO_SORT_DATA).append(":1b");
        }
        Integer junk = intOf(props.get(DROPS_JUNK_KEY));
        if (junk != null && junk == 1) {
            b.append(',').append(DROPS_JUNK_DATA).append(":1b");
        }
        String[] reveal = revealOf(props.get(REVEALS_KEY));
        if (reveal != null) {
            b.append(',').append(REVEALS_DATA).append(":{ore:\"").append(reveal[0])
                    .append("\",how:\"").append(reveal[1]).append("\"}");
        }
        String mine = mineAreaOf(props);
        if (mine != null) {
            b.append(',').append(MINE_AREA_DATA).append(":\"").append(mine).append('"');
        }
        String trail = trailOf(props);
        if (trail != null) {
            b.append(',').append(TRAIL_DATA).append(":\"").append(trail).append('"');
        }
        Integer faster = intOf(props.get(EAT_SPEED_KEY));
        if (faster != null && faster >= 2 && faster <= 8) {
            b.append(',').append(EAT_SPEED_DATA).append(':').append(faster);
        }
        String only = holdsOnlyTag(props);
        if (only != null) {
            b.append(',').append(HOLDS_ONLY_DATA).append(":\"").append(only).append('"');
        }
        JsonElement sw = props.get(STRONGER_WHEN_KEY);
        if (sw != null && strongerWhenRefusal(sw) == null) {
            JsonObject o = sw.getAsJsonObject();
            String when = com.moddingfromamod.item.StrongerWhenRule.norm(o.get("when").getAsString());
            String stat = com.moddingfromamod.item.StrongerWhenRule.norm(o.get("stat").getAsString());
            Integer y = intOf(o.get("y"));
            b.append(',').append(STRONGER_WHEN_DATA).append(":{when:\"")
                    .append(com.moddingfromamod.item.StrongerWhenRule.CONDITIONS.get(
                            com.moddingfromamod.item.StrongerWhenRule.CONDITIONS.indexOf(when)))
                    .append("\",stat:\"").append(com.moddingfromamod.item.StrongerWhenRule.STATS.get(
                            com.moddingfromamod.item.StrongerWhenRule.STATS.indexOf(stat)))
                    .append("\",amount:").append(intOf(o.get("amount")))
                    .append(",y:").append(y == null ? com.moddingfromamod.item.StrongerWhenRule.DEFAULT_Y : y)
                    .append('}');
        }
        return b.toString();
    }

    /**
     * The component text to splice into a {@code /give} line — <b>starting with a comma</b>, or the
     * empty string when there is nothing to add.
     *
     * <p>Every number is re-rendered from the parsed {@code int}, so no string the agent sent
     * appears in the command. Call {@link #refusals} first; this assumes a valid object.
     */
    public static String render(JsonObject props) {
        return render(props, "");
    }

    /**
     * The same, keeping whatever the BASE ITEM already did.
     *
     * @param keepModifiers the base item's own attribute modifiers, already rendered as SNBT
     *                      entries without their brackets, or empty for an item made from scratch.
     *                      Supplied by the caller because resolving them needs the registry and
     *                      this class is deliberately Minecraft-free.
     *
     *                      <p>It exists because NeoForge's {@code getAttributeModifiers()} uses the
     *                      component when it is non-empty and <b>skips the item's own defaults
     *                      entirely</b> — so a one-entry {@code attack_damage} component does not
     *                      make a diamond sword stronger, it makes it a sword with only that one
     *                      modifier, swinging at the base 4.0/sec instead of 1.6. On an item whose
     *                      modifiers are not just damage and speed it deletes them outright.
     */
    public static String render(JsonObject props, String keepModifiers) {
        if (props == null || props.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        // Iterated over RULES rather than over the agent's keys, so the rendered order is OURS and
        // two identical wishes cannot produce two different give lines (which would make every
        // rendered-bytes assertion in the suite depend on JSON key order).
        for (Rule r : RULES.values()) {
            if (!props.has(r.key())) {
                continue;
            }
            if (PROJECTILE_KEY.equals(r.key()) || NEVER_EMPTY_KEY.equals(r.key())
                    || HOLDS_ONLY_KEY.equals(r.key()) || EAT_SPEED_KEY.equals(r.key())
                    || TRAIL_KEY.equals(r.key()) || REVEALS_KEY.equals(r.key())
                    || MINE_AREA_KEY.equals(r.key())
                    || DROPS_JUNK_KEY.equals(r.key()) || CATCHES_KEY.equals(r.key())
                    || AUTO_SORT_KEY.equals(r.key())
                    || CAKE_BITES_KEY.equals(r.key()) || GRAPPLE_KEY.equals(r.key())
                    || STRONGER_WHEN_KEY.equals(r.key())) {
                continue;   // custom_data, not a component: dataEntries
            }
            if (ENCHANTMENTS_KEY.equals(r.key())) {
                String c = enchantmentsComponent(levels(props.get(r.key())));
                if (!c.isEmpty()) {
                    b.append(',').append(c);
                }
                continue;
            }
            Integer v = intOf(props.get(r.key()));
            if (v == null) {
                continue;
            }
            String c = component(r.key(), v, keepModifiers);
            if (!c.isEmpty()) {
                b.append(',').append(c);
            }
        }
        return b.toString();
    }

    /** One property as its 1.21.1 component. The only place a component name is spelled. */
    private static String component(String key, int value, String keepModifiers) {
        return switch (key) {
            case "max_stack_size" -> "minecraft:max_stack_size=" + value;
            // The empty object is the whole component: 1.21.1's `unbreakable` carries only
            // show_in_tooltip, which defaults to true, and that is the tooltip line telling him it
            // never breaks. `unbreakable=0` writes nothing rather than writing a falsey component,
            // since removing durability protection is just not asking for it.
            case "unbreakable" -> value == 0 ? "" : "minecraft:unbreakable={}";
            // 1.21 replaced the UUID+name pair with a single Identifier id (see the migrator's
            // catalog), and the id must be OURS so a second modifier from anywhere else cannot
            // collide with it or silently replace it.
            // ⚠ `minecraft:generic.attack_damage`, NOT `minecraft:attack_damage`. 1.21.1 still
            // carries the `generic.` prefix on the vanilla attribute ids; the flattened spelling
            // arrives in 1.21.2. Written the flattened way first, and it rendered, compiled and
            // passed every unit test -- the game refused it with `Unknown registry key in
            // ResourceKey[minecraft:root / minecraft:attribute]`, which only ItemPropertiesGameTest
            // could ever have said. A version-specific spelling is measured or it is not known.
            // ⚠ WHATEVER THE ITEM ALREADY DID COMES FIRST. Ours is APPENDED to the base
            // item's own list, not written instead of it. See render(props, keepModifiers) for
            // the bytecode; the short version is that a non-empty component makes the game stop
            // asking the item what its modifiers are.
            case "attack_damage" -> com.moddingfromamod.compat.ItemSyntax.attributeModifiers(
                    (keepModifiers == null || keepModifiers.isBlank() ? "" : keepModifiers + ",")
                    + "{type:\"" + com.moddingfromamod.compat.Attrs.generic("attack_damage") + "\","
                    + "id:\"moddingfromamod:wish_attack_damage\","
                    + "amount:" + value + ",operation:\"add_value\",slot:\"mainhand\"}");
            default -> throw new IllegalStateException("no component for property '" + key + "'");
        };
    }

    /** What this item IS, in one line per property, for {@code inspect_item}. */
    public static List<String> describe(JsonObject props) {
        List<String> out = new ArrayList<>();
        if (props == null) {
            return out;
        }
        for (Rule r : RULES.values()) {
            if (!props.has(r.key())) {
                continue;
            }
            if (CATCHES_KEY.equals(r.key())) {
                String[] c = catchOf(props.get(r.key()));
                if (c != null) {
                    out.add(r.key() + " = " + c[0] + " at " + c[1] + "%");
                }
                continue;
            }
            if (REVEALS_KEY.equals(r.key())) {
                String[] rv = revealOf(props.get(r.key()));
                if (rv != null) {
                    out.add(r.key() + " = " + rv[0] + " by " + rv[1]);
                }
                continue;
            }
            if (MINE_AREA_KEY.equals(r.key())) {
                String m = mineAreaOf(props);
                if (m != null) {
                    out.add(r.key() + " = " + m);
                }
                continue;
            }
            if (TRAIL_KEY.equals(r.key())) {
                String t = trailOf(props);
                if (t != null) {
                    out.add(r.key() + " = " + t);
                }
                continue;
            }
            if (HOLDS_ONLY_KEY.equals(r.key())) {
                String only = holdsOnlyTag(props);
                if (only != null) {
                    out.add(r.key() + " = #" + only);
                }
                continue;
            }
            if (PROJECTILE_KEY.equals(r.key())) {
                String shoots = projectileOf(props);
                if (shoots != null) {
                    out.add(r.key() + " = " + shoots);
                }
                continue;
            }
            if (STRONGER_WHEN_KEY.equals(r.key())) {
                JsonElement sw = props.get(r.key());
                if (strongerWhenRefusal(sw) == null) {
                    JsonObject o = sw.getAsJsonObject();
                    out.add(r.key() + " = " + o.get("stat").getAsString() + " +" + intOf(o.get("amount"))
                            + " at " + o.get("when").getAsString());
                }
                continue;
            }
            if (ENCHANTMENTS_KEY.equals(r.key())) {
                Map<String, Integer> lv = levels(props.get(r.key()));
                if (!lv.isEmpty()) {
                    List<String> each = new ArrayList<>();
                    lv.forEach((k, n) -> each.add(k + " " + n));
                    out.add(r.key() + " = " + String.join(", ", each));
                }
                continue;
            }
            Integer v = intOf(props.get(r.key()));
            if (v != null) {
                out.add(r.key() + " = " + v);
            }
        }
        return out;
    }

    /** What is wrong with a {@code stronger_when} value, in the agent's terms, or null. */
    private static String strongerWhenRefusal(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return "stronger_when must be an object like {\"when\": \"night\", \"stat\": "
                    + "\"attack_damage\", \"amount\": 6}.";
        }
        JsonObject o = el.getAsJsonObject();
        String when = o.has("when") && o.get("when").isJsonPrimitive() ? o.get("when").getAsString() : null;
        String stat = o.has("stat") && o.get("stat").isJsonPrimitive() ? o.get("stat").getAsString() : null;
        String why = com.moddingfromamod.item.StrongerWhenRule.refusal(when, stat, intOf(o.get("amount")));
        if (why == null && o.has("y")) {
            Integer y = intOf(o.get("y"));
            if (y == null || y < -64 || y > 320) {
                return "stronger_when.y is " + o.get("y") + ", and it has to be a height from -64 to 320.";
            }
        }
        return why;
    }

    /** What is wrong with an {@code enchantments} value, in the agent's terms, or empty. */
    private static List<String> enchantmentRefusals(JsonElement el) {
        List<String> bad = new ArrayList<>();
        if (el == null || !el.isJsonObject() || el.getAsJsonObject().isEmpty()) {
            bad.add("enchantments must be an object of enchantment to level, like "
                    + "{\"fortune\": 3} — the ones that exist are: "
                    + String.join(", ", ENCHANTMENTS.keySet()) + ".");
            return bad;
        }
        for (var e : el.getAsJsonObject().entrySet()) {
            String name = e.getKey().startsWith("minecraft:") ? e.getKey().substring(10) : e.getKey();
            Integer max = ENCHANTMENTS.get(name);
            if (max == null) {
                bad.add("there is no enchantment here called '" + e.getKey() + "'. The ones that "
                        + "exist are: " + String.join(", ", ENCHANTMENTS.keySet())
                        + ". If what he asked for is something that HAPPENS, it is an effect.");
                continue;
            }
            Integer v = intOf(e.getValue());
            if (v == null || v < 1 || v > max) {
                bad.add("enchantments." + name + " is " + e.getValue() + ", and it has to be a "
                        + "whole number 1 to " + max + ". Ask him to pick inside that rather than "
                        + "choosing for him.");
            }
        }
        return bad;
    }

    /**
     * The valid {@code enchantment → level} pairs, in OUR table's order (so two identical wishes
     * render one give line whatever order the JSON arrived in). Assumes {@link #refusals} passed;
     * anything invalid is dropped rather than rendered.
     */
    public static Map<String, Integer> levels(JsonElement el) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (el == null || !el.isJsonObject()) {
            return out;
        }
        JsonObject o = el.getAsJsonObject();
        for (var known : ENCHANTMENTS.entrySet()) {
            JsonElement v = o.has(known.getKey()) ? o.get(known.getKey())
                    : o.get("minecraft:" + known.getKey());
            Integer n = intOf(v);
            if (n != null && n >= 1 && n <= known.getValue()) {
                out.put(known.getKey(), n);
            }
        }
        return out;
    }

    /**
     * The {@code enchantments} component: the flattened map, 1.21.5's shape (— the old
     * {@code {levels:{…}}} is refused, "Failed to get element minecraft:levels", measured on 1.21.8 by
     * {@code ItemPropertiesGameTest}). Names come from our table, never from the agent.
     */
    private static String enchantmentsComponent(Map<String, Integer> levels) {
        if (levels.isEmpty()) {
            return "";
        }
        List<String> each = new ArrayList<>();
        levels.forEach((k, n) -> each.add("\"minecraft:" + k + "\":" + n));
        return "minecraft:enchantments={" + String.join(",", each) + "}";
    }

    /** A whole number however it arrived — agents send {@code 64} and {@code "64"} interchangeably. */
    private static Integer intOf(JsonElement el) {
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        try {
            String s = el.getAsString().trim();
            return s.isEmpty() ? null : Integer.valueOf(Integer.parseInt(s));
        } catch (NumberFormatException bad) {
            return null;
        }
    }
}
