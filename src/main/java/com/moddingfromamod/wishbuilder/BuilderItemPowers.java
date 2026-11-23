package com.moddingfromamod.wishbuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>A magic item, made from scratch</b>: a STOCK item a kid knows (a sword, a bow, a pickaxe, a
 * potion…), one POWER whose trigger that item really has, and a strength. Everything here already exists in the
 * item catalogue the wish worker uses ({@code items/catalogue.json}'s effects, {@code items/shape_*.cbs}); this is
 * only the Builder's reviewed slice of it, with every power's exact parameters at three strengths, because the
 * catalogue has NO defaults ({@code _no_defaults}) and a menu never asks a kid for a number.
 *
 * <p><b>A stock item has ONE trigger</b>: its catalogue shape ({@code on_hit} for a sword, {@code arrow} for a bow,
 * {@code on_mine} for a pickaxe, {@code on_consume} for a potion…). A power lists the triggers it fits, so the
 * powers page for a stock item shows only what can happen when he USES it that way. Every (stock, trigger) is
 * proven by using the made item in {@code BuilderAbilityGameTest} (docs/EFFECT_VALIDATION.md).
 *
 * <p><b>Built on the real item where one exists</b>: a bow is a real bow, a pickaxe a real pickaxe, so every
 * client — Java or Bedrock — draws vanilla's own picture and the item keeps its own use. Only the wand and the
 * armour pieces ride Moddy's carrier (they have no vanilla item to be, or would take its click away).
 *
 * <p><b>No power lands on another player</b> (WISH_BUILDER_PLAN §6.2): a melee weapon fires on a hit that lands
 * (PvP is often off on a shared server); a ranged or thrown one lands near wherever it falls, so it carries {@code spare_players}
 * and its power never touches a player there. Tools, wands, armour and food carry only powers that land on him or on
 * the block he points at or mines.
 *
 * <p><b>The ready-mades are compositions of these</b> ({@link #READY_MADES}): the Lightning Sword is the sword with
 * lightning at strength 1, and a spec saved from the ready-made is migrated to that (SpecMigrations v1→v2).
 * {@code BuilderItemComposableTest} proves both lower to the same tool calls.
 *
 * <p>Pure: {@code BuilderItemPowersTest} checks every pair against the catalogue and the trigger table.
 */
public final class BuilderItemPowers {

    private BuilderItemPowers() {
    }

    /**
     * What the item IS: its id, the kid's label, the chest tile (a vanilla item every edition draws), its trigger
     * (a catalogue shape), the real item it is made on (or none: Moddy's carrier), its icon, its noun, its own colour
     * when it has one (a cookie is brown whatever it does; empty takes the power's), and the words a kid calls it.
     */
    public record Stock(String id, String label, String tile, String shape, String base, String icon, String noun,
                        String tint, List<String> words) {
    }

    /**
     * A power: the catalogue effect it is, the kid's words, the triggers it fits, its parameters at strength 1, 2
     * and 3 (index 0..2), its colour, and — rarely — the only stock items it fits ({@code only}, empty for any with
     * the trigger) and extra make_item arguments ({@code extra}, a JSON object: the slushie's three sizes).
     */
    public record Power(String id, String effect, String label, String tile, String word, List<String> shapes,
                        List<Map<String, String>> byStrength, List<String> words, String tint, List<String> only,
                        String extra) {

        public Power(String id, String effect, String label, String tile, String word, List<String> shapes,
                     List<Map<String, String>> byStrength, List<String> words, String tint) {
            this(id, effect, label, tile, word, shapes, byStrength, words, tint, List.of(), "");
        }

        public Map<String, String> params(int strength) {
            return byStrength.get(Math.max(1, Math.min(3, strength)) - 1);
        }

        /** Does it fit this stock item (its trigger, and its {@code only} list)? */
        public boolean fits(Stock s) {
            return s != null && shapes.contains(s.shape()) && (only.isEmpty() || only.contains(s.id()));
        }
    }

    private static final String HIT = "on_hit";
    private static final String SHOOT = "arrow";
    private static final String THROW = "on_throw_land";
    private static final String MINE = "on_mine";
    private static final String WAND = "held";
    private static final String HEAD = "worn_head";
    private static final String CHEST = "worn_chest";
    private static final String LEGS = "worn_legs";
    private static final String FEET = "worn_feet";
    private static final String EAT = "on_consume";

    /** The triggers whose power lands near where the item FALLS, not on a mob he hit: they spare players. */
    public static final List<String> RANGED = List.of(SHOOT, THROW);

    /**
     * The stock items, in the menu's order: weapons, tools, held magic, wearables, things to eat and drink. The first
     * four ids are the four shapes (a spec saved then names the trigger, {@link #legacy}).
     */
    public static final List<Stock> STOCK = List.of(
            new Stock("sword", "A sword (hit things)", "minecraft:diamond_sword", HIT, "minecraft:diamond_sword", "sword", "Sword", "",
                    List.of("sword", "blade", "dagger", "katana", "weapon", "swords")),
            new Stock("axe", "An axe (hit things)", "minecraft:diamond_axe", HIT, "minecraft:diamond_axe", "axe", "Axe", "",
                    List.of("axe", "battle axe", "hatchet", "axes")),
            new Stock("mace", "A mace (smash things)", "minecraft:mace", HIT, "minecraft:mace", "hammer", "Mace", "",
                    List.of("mace", "hammer", "club", "war hammer", "smasher")),
            new Stock("bow", "A bow (shoot arrows)", "minecraft:bow", SHOOT, "minecraft:bow", "bow", "Bow", "",
                    List.of("bow", "bow and arrow", "arrows", "archer", "longbow")),
            new Stock("crossbow", "A crossbow (shoot bolts)", "minecraft:crossbow", SHOOT, "minecraft:crossbow", "bow", "Crossbow", "",
                    List.of("crossbow", "cross bow", "bolt shooter", "crossbows")),
            new Stock("trident", "A trident (throw it)", "minecraft:trident", SHOOT, "minecraft:trident", "spear", "Trident", "",
                    List.of("trident", "spear", "javelin", "fork", "tridents")),
            new Stock("snowball", "A snowball (throw it)", "minecraft:snowball", THROW, "minecraft:snowball", "orb", "Snowball", "",
                    List.of("snowball", "snow ball", "throwable", "snowballs", "something to throw")),
            new Stock("pickaxe", "A pickaxe (mine with it)", "minecraft:diamond_pickaxe", MINE, "minecraft:diamond_pickaxe", "pickaxe", "Pickaxe", "",
                    List.of("pickaxe", "pick axe", "pick", "mining", "mine", "pickaxes")),
            new Stock("shovel", "A shovel (dig with it)", "minecraft:diamond_shovel", MINE, "minecraft:diamond_shovel", "shovel", "Shovel", "",
                    List.of("shovel", "spade", "dig", "digging", "shovels")),
            new Stock("wand", "A wand (point and tap)", "minecraft:blaze_rod", WAND, "", "wand", "Wand", "",
                    List.of("wand", "stick", "rod", "magic wand", "scepter", "wands")),
            new Stock("staff", "A staff (point and tap)", "minecraft:breeze_rod", WAND, "minecraft:breeze_rod", "wand", "Staff", "",
                    List.of("staff", "wizard staff", "magic staff", "walking stick", "staffs")),
            new Stock("helmet", "A helmet (wear it)", "minecraft:leather_helmet", HEAD, "", "helmet", "Helmet", "",
                    List.of("helmet", "hat", "cap", "helmets")),   //: "crown" is the crown look's now
            new Stock("chestplate", "Armour (wear it)", "minecraft:leather_chestplate", CHEST, "", "helmet", "Armour", "",
                    List.of("chestplate", "armour", "armor", "chest plate", "vest", "shirt")),
            new Stock("leggings", "Leggings (wear them)", "minecraft:leather_leggings", LEGS, "", "helmet", "Leggings", "",
                    List.of("leggings", "pants", "trousers", "legs")),
            new Stock("boots", "Boots (wear them)", "minecraft:leather_boots", FEET, "", "helmet", "Boots", "",
                    List.of("boots", "shoes", "sneakers", "slippers")),
            new Stock("snack", "A snack (eat it)", "minecraft:cookie", EAT, "minecraft:cookie", "food_round", "Snack", "#B5793A",
                    List.of("snack", "food", "cookie", "treat", "biscuit", "candy")),
            new Stock("drink", "A drink (drink it)", "minecraft:potion", EAT, "minecraft:potion", "cup", "Potion", "",
                    List.of("potion", "drink", "juice", "bottle", "soda", "elixir")));

    /** a world's {@code crafters.builder.itemTier}. {@link #TIER_ANY} is the stock items as listed. */
    public static final String TIER_ANY = "any";
    /**
     * the basic tier ("no piles of rare things"): tools and weapons of STONE, a staff of a stick, no mace and no
     * trident. The power is the magic, not the diamond. Bows, a snowball, food, drink and the carrier pieces are as listed.
     */
    public static final String TIER_BASIC = "basic";
    public static final List<String> TIERS = List.of(TIER_ANY, TIER_BASIC);

    /** The basic tier's own version of a stock item: its base (also its tile) and its label. */
    static final Map<String, String[]> BASIC = Map.of(
            "sword", new String[] {"minecraft:stone_sword", "A stone sword (hit things)"},
            "axe", new String[] {"minecraft:stone_axe", "A stone axe (hit things)"},
            "pickaxe", new String[] {"minecraft:stone_pickaxe", "A stone pickaxe (mine with it)"},
            "shovel", new String[] {"minecraft:stone_shovel", "A stone shovel (dig with it)"},
            "staff", new String[] {"minecraft:stick", "A staff (point and tap)"});

    /** Not offered at the basic tier at all. */
    public static final List<String> NOT_BASIC = List.of("mace", "trident");

    /** {@code s} as a world of {@code tier} makes it, or null when that world does not offer it. */
    public static Stock tiered(Stock s, String tier) {
        if (s == null || !TIER_BASIC.equals(tier)) {
            return s;
        }
        if (NOT_BASIC.contains(s.id())) {
            return null;
        }
        String[] b = BASIC.get(s.id());
        return b == null ? s : new Stock(s.id(), b[1], b[0], s.shape(), b[0], s.icon(), s.noun(), s.tint(), s.words());
    }

    private static Map<String, String> p(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** The wearables' powers: on every armour piece, refreshed by the worn clock while he has it on. */
    private static final List<String> WORN = List.of(HEAD, CHEST, LEGS, FEET);

    private static List<String> and(List<String> a, String... more) {
        List<String> out = new java.util.ArrayList<>(a);
        out.addAll(List.of(more));
        return List.copyOf(out);
    }

    /** the Place power that puts LAVA down: offered only where the world's {@code lava} knob allows it. */
    public static final String LAVA_POWER = "place_lava";

    public static final List<Power> POWERS = List.of(
            new Power("lightning", "lightning", "Lightning", "minecraft:lightning_rod", "Lightning", List.of(HIT, SHOOT, THROW),
                    List.of(p("STRIKES", "1"), p("STRIKES", "2"), p("STRIKES", "3")), List.of("lightning", "thunder", "zap", "zaps", "electric", "shock", "lightning bolt"), "yellow"),
            new Power("freeze", "freeze", "Freeze them", "minecraft:blue_ice", "Frost", List.of(HIT, SHOOT, THROW),
                    List.of(p("SECONDS", "3"), p("SECONDS", "6"), p("SECONDS", "10")), List.of("freeze", "freezes", "frozen", "ice", "frost", "icy", "freezing"), "#7FD7FF"),
            new Power("fling", "launch", "Fling them up", "minecraft:slime_ball", "Fling", List.of(HIT, SHOOT, THROW),
                    List.of(p("POWER", "shove"), p("POWER", "launch"), p("POWER", "huge")), List.of("fling", "flings", "launch", "launches", "sends flying", "knock up", "yeet", "knockback"), "#7BD148"),
            new Power("fire", "ignite", "Set them on fire", "minecraft:flint_and_steel", "Fire", List.of(HIT, SHOOT, THROW),
                    List.of(p("SECONDS", "4"), p("SECONDS", "8"), p("SECONDS", "12")), List.of("fire", "burn", "burns", "flame", "flaming", "sets on fire", "fiery"), "#FF7A2F"),
            new Power("teleport", "teleport", "Teleport", "minecraft:ender_pearl", "Teleport", List.of(WAND),
                    List.of(p("SAFE", "yes"), p("SAFE", "yes"), p("SAFE", "yes")), List.of("teleport", "teleports", "warp", "blink", "teleporting"), "purple"),
            new Power("speed", "speed", "Super speed", "minecraft:sugar", "Speedy", and(WORN, WAND, EAT, MINE),
                    List.of(p("SECONDS", "30", "LEVEL", "0"), p("SECONDS", "30", "LEVEL", "1"), p("SECONDS", "30", "LEVEL", "3")), List.of("speed", "fast", "faster", "run fast", "super speed", "speedy", "quick", "zoom"), "#4FC3F7"),
            new Power("jump", "jump", "Super jump", "minecraft:rabbit_foot", "Jumpy", and(WORN, WAND, EAT),
                    List.of(p("SECONDS", "30", "LEVEL", "1"), p("SECONDS", "30", "LEVEL", "2"), p("SECONDS", "30", "LEVEL", "3")), List.of("jump", "jumps", "jump high", "super jump", "double jump", "high jump", "moon jump", "big jumper", "jumper", "jump higher"), "#2FC7C7"),
            new Power("heal", "heal", "Heal me", "minecraft:golden_apple", "Healing", and(WORN, WAND, EAT),
                    List.of(p("SECONDS", "4"), p("SECONDS", "6"), p("SECONDS", "10")), List.of("heal", "heals", "healing", "health", "regen", "regeneration", "hearts", "get better", "feel better", "heals me", "healthy"), "#FF6F91"),
            new Power("giant", "grow", "Grow giant", "minecraft:red_mushroom", "Giant", List.of(WAND, EAT),
                    List.of(p("SCALE", "1.5", "SECONDS", "60"), p("SCALE", "2.5", "SECONDS", "60"), p("SCALE", "4", "SECONDS", "60")), List.of("giant", "grow", "grows me", "huge", "makes me big", "makes me giant", "big"), "#E53935"),
            new Power("tiny", "grow", "Shrink tiny", "minecraft:brown_mushroom", "Tiny", List.of(WAND, EAT),
                    List.of(p("SCALE", "0.6", "SECONDS", "60"), p("SCALE", "0.4", "SECONDS", "60"), p("SCALE", "0.3", "SECONDS", "60")), List.of("tiny", "shrink", "shrinks", "small", "makes me small", "makes me tiny", "mini", "teeny", "shrink me"), "#B5793A"),
            new Power("shield", "protect", "Nothing hurts me", "minecraft:shield", "Tough", and(WORN, EAT),
                    List.of(p("SECONDS", "30", "LEVEL", "1"), p("SECONDS", "30", "LEVEL", "2"), p("SECONDS", "30", "LEVEL", "3")), List.of("shield", "invincible", "nothing hurts me", "protect", "protects me", "no damage", "unbreakable", "safe from damage", "keeps me safe", "cant get hurt", "cannot get hurt"), "#9E9E9E"),
            new Power("fly", "fly", "Fly up high", "minecraft:feather", "Rocket", List.of(WAND, EAT),
                    List.of(p("SECONDS", "3", "RATE", "3"), p("SECONDS", "5", "RATE", "4"), p("SECONDS", "8", "RATE", "6")), List.of("fly", "flying", "float", "hover", "lets me fly", "fly up", "jetpack"), "#FFFFFF"),
            new Power("glow", "light", "Light up the dark", "minecraft:glowstone", "Glow", List.of(WAND, MINE),
                    List.of(p("GLOW_SECONDS", "0"), p("GLOW_SECONDS", "30"), p("GLOW_SECONDS", "60")), List.of("light", "glow", "torch", "lights up", "flashlight", "see in the dark", "lantern"), "#FFE082"),
            new Power("haste", "status", "Dig super fast", "minecraft:golden_pickaxe", "Turbo", List.of(MINE),
                    List.of(p("EFFECT", "minecraft:haste", "LEVEL", "0", "SECONDS", "30"), p("EFFECT", "minecraft:haste", "LEVEL", "1", "SECONDS", "30"),
                            p("EFFECT", "minecraft:haste", "LEVEL", "2", "SECONDS", "30")), List.of("haste", "dig fast", "mines fast", "fast mining", "super mining", "digs faster", "mine faster"), "#FFD54A"),
            new Power("home", "teleport_home", "Take me home", "minecraft:red_bed", "Homing", List.of(WAND),
                    List.of(p(), p(), p()), List.of("home", "take me home", "teleport home", "go home", "back home"), "#EF5350"),
            new Power("pets", "call_pets", "Call my pets", "minecraft:bone", "Pet", List.of(WAND),
                    List.of(p("KIND", "any"), p("KIND", "any"), p("KIND", "any")), List.of("call my pets", "calls my pets", "summon pets", "pets come", "whistle"), "#A1887F"),
            // the Slushie, made up — a drink only (a cold drink that makes you quick), in its three sizes.
            new Power("slushie", "food", "Icy slushie (quick!)", "minecraft:blue_ice", "Slushie", List.of(EAT),
                    List.of(p("BONUS", "minecraft:speed", "SECONDS", "15"), p("BONUS", "minecraft:speed", "SECONDS", "30"),
                            p("BONUS", "minecraft:speed", "SECONDS", "60")), List.of("slushie", "slushy", "slush", "icy drink", "frozen drink"), "#4FC3F7",
                    List.of("drink"), "{\"variants\":[{\"name\":\"Small Slushie\",\"colour\":\"#81D4FA\"},"
                            + "{\"name\":\"Medium Slushie\",\"colour\":\"#29B6F6\"},{\"name\":\"GIANT Slushie\",\"colour\":\"#0277BD\"}]}"),
            // PLACE — a wand that puts a block down where he points (the catalogue's `place`: only into empty
            // space, never over a block). Water and lava are what any survival world's wand-wisher asks for first (a
            // pool, a farm's water, a cobblestone generator he builds himself); lava only where the world's `lava` knob
            // allows it (LAVA_POWER, BuilderAllowlist), the same knob as the Supplies lava bucket.
            place("place_water", "Put water down", "minecraft:water_bucket", "Water", "minecraft:water", "#3F76E4",
                    "water", "places water", "puts water", "pours water", "makes water", "spills water", "squirts water"),
            place(LAVA_POWER, "Put lava down", "minecraft:lava_bucket", "Lava", "minecraft:lava", "#FF6A00",
                    "lava", "places lava", "puts lava", "pours lava", "makes lava", "spills lava", "magma"),
            place("place_dirt", "Put dirt down", "minecraft:dirt", "Dirt", "minecraft:dirt", "#8B5A2B",
                    "dirt", "places dirt", "puts dirt", "makes dirt", "soil", "mud"),
            place("place_grass", "Put grass down", "minecraft:grass_block", "Grass", "minecraft:grass_block", "#5DA130",
                    "grass", "grass block", "places grass", "puts grass", "makes grass", "lawn", "grassy"),
            place("place_sand", "Put sand down", "minecraft:sand", "Sand", "minecraft:sand", "#E3D59B",
                    "sand", "places sand", "puts sand", "makes sand", "beach", "sandy"),
            // JUST FOR FUN — no power: a plain keepsake that only looks (a crown, a teddy), the power a look alone
            // gets. Just before Turn things into…, which stays last.
            new Power(BuilderLooks.SHOW, "", "Just for fun (no power)", "minecraft:painting", "Fancy",
                    List.of(HIT, SHOOT, THROW, MINE, WAND, HEAD, CHEST, LEGS, FEET, EAT),
                    List.of(p(), p(), p()), List.of("just for fun", "just for show", "for decoration", "decoration",
                            "no power", "no powers", "does nothing", "just looks", "just to look at"), "#FFFFFF"),
            // TURN THINGS INTO… — one power 's six fixed pairs (raw iron into iron, logs into charcoal,
            // sand into glass, dirt into sand, cobblestone into gravel, stone into cobblestone): what changes and what it
            // becomes are its own two pages (BuilderTurnInto), and the item decides how (a wand aims, a sword changes
            // what it hits, a snowball where it lands). Its strength is the block side's patch. Its params are the
            // spec's picks (args), so the list here holds only the radius steps. Last, so it is never the power an item
            // with no power named gets (BuilderTree lists it last too).
            new Power(BuilderTurnInto.POWER, com.moddingfromamod.datapack.TurnInto.EFFECT, "Turn things into…",
                    "minecraft:gold_ingot", "Changing", List.of(HIT, THROW, WAND),
                    List.of(p("RADIUS", "0"), p("RADIUS", "1"), p("RADIUS", "2")),
                    List.of("turn", "turns", "turning", "turned", "turn into", "turns into", "change", "changes",
                            "changing", "change into", "become", "becomes", "becoming", "transform", "transforms",
                            "transforming", "transformer", "transmute", "transmutes", "transmuting", "morph", "morphs",
                            "morphing", "swap", "swaps", "swapping", "convert", "converts", "converter", "melt", "melts",
                            "melting", "alchemy", "alchemist",
                            "turn everything into>" + BuilderTurnInto.FROM_SLOT + "=" + BuilderTurnInto.FROM_PREFIX + "any_block"),
                    "#FFD54A"));

    private static Power place(String id, String label, String tile, String word, String block, String tint, String... words) {
        Map<String, String> each = p("BLOCK", block);
        return new Power(id, "place", label, tile, word, List.of(WAND), List.of(each, each, each), List.of(words), tint);
    }

    /**
     * <b>Every ready-made item, as what it is made of</b>: preset → stock, power, strength. The rule
     * is that the made-up flow IS the item flow, so a ready-made is only a saved composition; a spec that names one
     * is migrated to it ({@code SpecMigrations} v1→v2) and {@code BuilderItemComposableTest} proves the two lower to
     * the same calls. A new item preset needs its row here, or that test fails naming it.
     */
    public record Composed(String stock, String power, int strength, String name) {
    }

    public static final Map<String, Composed> READY_MADES = Map.of(
            "items/lightning_sword_lightning", new Composed("sword", "lightning", 1, "Lightning Sword"),
            "items/teleport_wand_teleport", new Composed("wand", "teleport", 2, "Teleport Wand"),
            "items/double_jump_boots_jump", new Composed("boots", "jump", 3, "Double Jump Boots"),
            "items/tiny_cookie_grow", new Composed("snack", "tiny", 3, "Tiny Cookie"),
            "items/slushie_plain", new Composed("drink", "slushie", 2, "Slushie"));

    /** an earlier issue's four shape ids, as the stock item each one was: a spec saved then names the trigger. */
    public static Stock legacy(String shape) {
        return switch (shape == null ? "" : shape) {
            case HIT -> stock("sword");
            case WAND -> stock("wand");
            case FEET -> stock("boots");
            case EAT -> stock("snack");
            default -> null;
        };
    }

    public static Stock stock(String id) {
        for (Stock s : STOCK) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** The stock item a made-up spec is: its {@code stock} opt, else (saved) its shape's. */
    public static Stock of(BuilderSpec spec) {
        Stock s = stock(spec.opt("stock"));
        return s != null ? s : legacy(Catalogue.bare(spec.opt("shape")));
    }

    public static Power power(String id) {
        for (Power w : POWERS) {
            if (w.id().equals(id)) {
                return w;
            }
        }
        return null;
    }

    /** The powers that fit a stock item, in the menu's order. */
    public static List<Power> fitting(Stock s) {
        return POWERS.stream().filter(w -> w.fits(s)).toList();
    }

    /** The powers that fit a TRIGGER on any item with it (no stock-only powers): a snack machine's are on_consume's. */
    public static List<Power> forTrigger(String shape) {
        return POWERS.stream().filter(w -> w.shapes().contains(shape) && w.only().isEmpty()).toList();
    }

    /**
     * the vanilla MATERIAL a colour scheme is. An item built on a real tool, weapon or armour piece is drawn by
     * every client as that piece, Java and Bedrock alike, and a colour on it is silently meaningless
     * ({@code MakeItemTool.itemArg}). So "Gold" on a pickaxe built on the diamond one (at the basic tier, the stone one)
     * arrived as that pickaxe, while a turtle took its gold because a sculpt's parts ARE paint. A scheme that is a
     * material vanilla makes these pieces of is built ON that material instead: gold is the golden one. Only gold: no
     * other scheme names a material vanilla has tools in, and mapping a colour to a stronger tier (ocean to diamond)
     * would turn a colour pick into an upgrade. A golden tool is weaker than stone (32 uses), so it is no rare thing
     * at the basic tier either.
     */
    static final Map<String, String> SCHEME_MATERIAL = Map.of("gold", "golden");

    private static final java.util.regex.Pattern TIERED = java.util.regex.Pattern.compile(
            "minecraft:(?:wooden|stone|iron|golden|diamond|netherite)_(sword|axe|pickaxe|shovel|hoe)"
                    + "|minecraft:(?:leather|chainmail|iron|golden|diamond|netherite)_(helmet|chestplate|leggings|boots)");

    /**
     * The real item {@code base} becomes in colour scheme {@code scheme}, or null when the scheme names no
     * material or {@code base} is not a piece vanilla makes in tiers (a bow, a cookie, Moddy's carrier).
     */
    public static String inScheme(String base, String scheme) {
        String material = scheme == null ? null : SCHEME_MATERIAL.get(Catalogue.bare(scheme));
        if (material == null || base == null) {
            return null;
        }
        java.util.regex.Matcher m = TIERED.matcher(base);
        if (!m.matches()) {
            return null;
        }
        return "minecraft:" + material + "_" + (m.group(1) != null ? m.group(1) : m.group(2));
    }

    /**
     * does a colour scheme SHOW on this stock item? Moddy's carrier pieces (the wand, the armour) are dyed; a
     * real item is drawn in the scheme by its tinted model ({@code pack.TintedItems}); gold is the golden piece. The
     * trident has neither (in the hand it is a special renderer that takes no tint, and vanilla makes no golden one),
     * so the tune dialog offers it no Colours: a pick that changes nothing is not offered.
     */
    public static boolean takesColour(Stock s) {
        return s != null && (s.base().isEmpty() || com.moddingfromamod.pack.TintedItems.modelFor(s.base()) != null
                || inScheme(s.base(), "gold") != null);
    }

    /** "Lightning Sword", "Speedy Boots", "Frost Bow". */
    public static String name(String stock, String power) {
        return name(stock, power, "");
    }

    /** …and a turn-into item by what it turns things into: "Midas Wand", "Smelting Staff". */
    public static String name(String stock, String power, String turnTo) {
        Stock s = stock(stock);
        Power w = power(power);
        if (s != null && w != null && w.id().equals(BuilderTurnInto.POWER)) {
            return BuilderTurnInto.name(s, BuilderTurnInto.to(turnTo));
        }
        return s == null || w == null ? "Magic Item" : w.word() + " " + s.noun();
    }

    /** Its colour: the stock item's own (a cookie is brown), else the power's. */
    public static String colour(Stock s, Power w) {
        return !s.tint().isEmpty() ? s.tint() : w.tint();
    }

    /**
     * The make_item arguments for a made-up item (everything but name, scheme and count, which the lowering adds as
     * it does for any item). Null when the pair is not one of these.
     */
    public static JsonObject args(String stock, String power, int strength) {
        return args(stock, power, strength, TIER_ANY);
    }

    /** …as a world of {@code tier} makes it; null too when that world does not offer the stock item. */
    public static JsonObject args(String stock, String power, int strength, String tier) {
        return args(stock, power, strength, tier, "", "");
    }

    /**
     * …and, for {@link BuilderTurnInto#POWER}, what it turns ({@code turnFrom}, the From groups as the spec keeps them:
     * "ores,logs") and into what ({@code turnTo}); null when those are missing or make_item could not make them.
     */
    public static JsonObject args(String stock, String power, int strength, String tier, String turnFrom, String turnTo) {
        Stock s = tiered(stock(stock), tier);
        Power w = power(power);
        if (s == null || w == null || !w.fits(s)) {
            return null;
        }
        Map<String, String> params = w.params(strength);
        String colour = colour(s, w);
        if (w.id().equals(BuilderTurnInto.POWER)) {
            List<BuilderTurnInto.From> from = BuilderTurnInto.parse(turnFrom);
            BuilderTurnInto.To to = BuilderTurnInto.to(turnTo);
            if (to == null || BuilderTurnInto.whyNot(s.shape(), from, to) != null) {
                return null;
            }
            params = BuilderTurnInto.params(s.shape(), from, to, strength);
        }
        JsonObject a = new JsonObject();
        if (w.effect().isEmpty()) {
            // no power is make_item's PLAIN item.: on a thing he WEARS or EATS it is the real one (a leather
            // helmet, a cookie, a potion), so a crown goes on his head and a cupcake is eaten; the lowering draws it as
            // its look (make_item's `look`). Anything else is a keepsake he holds.
            a.addProperty("shape", com.moddingfromamod.datapack.ItemCatalogue.PLAIN);
            String real = !s.base().isEmpty() ? s.base()
                    : WORN.contains(s.shape()) || EAT.equals(s.shape()) ? s.tile() : "";
            if (!real.isEmpty()) {
                a.addProperty("base_item", real);
            }
        } else {
            a.addProperty("effect", w.effect());
            a.addProperty("shape", s.shape());
            if (!s.base().isEmpty()) {
                a.addProperty("base_item", s.base());
            }
        }
        a.addProperty("icon", s.icon());
        a.addProperty("colour", colour);
        JsonObject ps = new JsonObject();
        params.forEach(ps::addProperty);
        a.add("params", ps);
        a.add("parts", new JsonArray());
        a.add("properties", new JsonObject());
        a.add("variants", new JsonArray());
        if (!w.extra().isEmpty()) {
            JsonObject extra = JsonParser.parseString(w.extra()).getAsJsonObject();
            extra.keySet().forEach(k -> a.add(k, extra.get(k)));
        }
        if (RANGED.contains(s.shape())) {
            a.addProperty("spare_players", true);   // §6.2: what lands near where it falls never touches a player
        }
        return a;
    }
}
