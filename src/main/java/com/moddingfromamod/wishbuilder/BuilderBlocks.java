package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>A magic block, composed</b>: what it is made of (a {@link Look}), what sets it off (a trigger the engine
 * really has: {@code make_item}'s magic-block {@code when} — on a beat, when someone comes near, when stepped on, when
 * broken), and what it does (a {@link Does}, offered only where its trigger fits). Each is a reviewed slice of what
 * {@code make_item} already builds (shape {@code magic_block}, {@code params._block}); nothing new in the engine.
 *
 * <p><b>No power lands on another player</b> (WISH_BUILDER_PLAN §6.2): the two that hurt (freeze, lightning) reach only
 * monsters ({@code who: hostile}) and carry {@code spare_players}; everything else is kind (heal, speed, jump, light).
 *
 * <p><b>The Lucky Block is a composition</b> ({@link #READY_MADES}): gold, when broken, "a lucky surprise" at strength
 * 2. {@code BuilderBlockComposableTest} lowers both to the same calls; a saved one is migrated (SpecMigrations).
 * Pure: {@code BuilderBlocksTest}.
 */
public final class BuilderBlocks {

    private BuilderBlocks() {
    }

    /** What it is made of: a real block every edition draws, its colour (the item's icon tint) and the kid's words. */
    public record Look(String id, String label, String block, String tint, List<String> words) {
    }

    public static final List<Look> LOOKS = List.of(
            new Look("gold", "Gold", "minecraft:gold_block", "#FFD54F", List.of("gold", "golden", "shiny gold", "lucky gold")),
            new Look("stone", "Stone", "minecraft:stone", "#9E9E9E", List.of("stone", "rock", "grey stone", "rocky")),
            new Look("glass", "Glass", "minecraft:glass", "#B3E5FC", List.of("glass", "see through", "window", "clear")),
            new Look("glowing", "Glowing", "minecraft:glowstone", "#FFE082", List.of("glowing", "glowstone", "bright", "shining")),
            new Look("wood", "Wood", "minecraft:oak_planks", "#C19A6B", List.of("wood", "wooden", "planks", "oak")),
            new Look("metal", "Metal", "minecraft:iron_block", "#CFD8DC", List.of("metal", "iron", "steel", "metallic")),
            new Look("crystal", "Crystal", "minecraft:amethyst_block", "#B388FF", List.of("crystal", "amethyst", "gem", "crystals")),
            new Look("diamond", "Diamond", "minecraft:diamond_block", "#4DD0E1", List.of("diamond", "diamonds", "blue gem", "sparkly")),
            new Look("emerald", "Emerald", "minecraft:emerald_block", "#2ECC71", List.of("emerald", "green gem", "emeralds", "green shiny")),
            new Look("lapis", "Lapis", "minecraft:lapis_block", "#3F51B5", List.of("lapis", "blue stone", "lapis lazuli", "deep blue")),
            new Look("redstone", "Redstone", "minecraft:redstone_block", "#E53935", List.of("redstone", "red block", "red stone", "red")),
            new Look("wool", "Wool", "minecraft:white_wool", "#FAFAFA", List.of("wool", "fluffy", "soft", "white wool")),
            new Look("ice", "Ice", "minecraft:packed_ice", "#90CAF9", List.of("ice", "icy", "frozen block", "packed ice")),
            new Look("slime", "Slime", "minecraft:slime_block", "#8BC34A", List.of("slime", "slimy", "gooey", "jelly")),
            new Look("lantern", "Sea Lantern", "minecraft:sea_lantern", "#E0F7FA", List.of("sea lantern", "lantern", "lamp", "light block")),
            new Look("quartz", "Quartz", "minecraft:quartz_block", "#F5F5F5", List.of("quartz", "white stone", "marble", "smooth white")),
            new Look("honey", "Honey", "minecraft:honey_block", "#FFB300", List.of("honey", "sticky", "honeycomb", "sweet block")),
            new Look("mossy", "Mossy", "minecraft:mossy_cobblestone", "#7CB342", List.of("mossy", "moss", "old stone", "jungle")));

    /** What sets it off: {@code make_item}'s magic-block {@code when}, the four the engine runs. */
    public record When(String id, String label, String tile, List<String> words) {
    }

    public static final List<When> WHENS = List.of(
            new When("broken", "When I break it", "minecraft:iron_pickaxe", List.of("when i break it", "break it", "when broken", "smash it", "mine it")),
            new When("stepped_on", "When I step on it", "minecraft:leather_boots", List.of("step on it", "when i step on it", "stand on it", "walk on it", "jump on it")),
            new When("near", "When someone comes near", "minecraft:spyglass", List.of("when someone comes near", "comes close", "nearby", "when near", "close to it")),
            new When("every", "Every few seconds", "minecraft:clock", List.of("every few seconds", "all the time", "on its own", "keeps going", "again and again")));

    /**
     * What it does: the catalogue effect, the triggers it fits, its parameters at strength 1-3, the block dials it
     * needs beyond the trigger and the look ({@code who}, {@code where}), and whether it spares players.
     */
    public record Does(String id, String effect, String label, String word, String tile, List<String> whens,
                       List<Map<String, String>> byStrength, Map<String, String> dials, boolean sparePlayers,
                       List<String> words) {
        public Map<String, String> params(int strength) {
            return byStrength.get(Math.max(1, Math.min(3, strength)) - 1);
        }
    }

    private static Map<String, String> p(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static final String LUCKY_PRESENTS = "minecraft:diamond*3, minecraft:cake, minecraft:golden_apple, minecraft:emerald*5, "
            + "minecraft:enchanted_golden_apple";

    public static final List<Does> DOES = List.of(
            new Does("lucky", "outcome_table", "A lucky surprise", "Lucky", "minecraft:gold_nugget", List.of("broken"),
                    List.of(p("PRESENTS", "minecraft:cake, minecraft:cookie*4, minecraft:apple*2", "SURPRISES", "minecraft:chicken", "BOOM", "no"),
                            p("PRESENTS", LUCKY_PRESENTS, "SURPRISES", "minecraft:chicken, minecraft:pig, minecraft:zombie", "BOOM", "yes"),
                            p("PRESENTS", LUCKY_PRESENTS, "SURPRISES", "minecraft:chicken, minecraft:pig, minecraft:zombie", "BOOM", "yes")),
                    Map.of(), false, List.of("lucky", "surprise", "random", "mystery", "loot", "lucky surprise", "prize")),
            new Does("chickens", "summon", "Chickens pop out", "Chicken", "minecraft:egg", List.of("broken", "stepped_on"),
                    List.of(p("MOB", "minecraft:chicken", "COUNT", "1", "AS", "plain"), p("MOB", "minecraft:chicken", "COUNT", "3", "AS", "plain"),
                            p("MOB", "minecraft:chicken", "COUNT", "5", "AS", "plain")),
                    Map.of(), false, List.of("chickens", "chicken", "pop out", "chicks", "hatch")),
            new Does("heal", "heal", "Heal me", "Healing", "minecraft:golden_apple", List.of("broken", "stepped_on", "near", "every"),
                    List.of(p("SECONDS", "4"), p("SECONDS", "6"), p("SECONDS", "10")),
                    Map.of(), false, List.of("heal", "healing", "health", "heals me", "hearts")),
            new Does("speed", "speed", "Super speed", "Speedy", "minecraft:sugar", List.of("stepped_on", "near"),
                    List.of(p("SECONDS", "30", "LEVEL", "0"), p("SECONDS", "30", "LEVEL", "1"), p("SECONDS", "30", "LEVEL", "3")),
                    Map.of(), false, List.of("speed", "fast", "speed pad", "zoom", "run fast")),
            new Does("jump", "jump", "Super jump", "Bouncy", "minecraft:rabbit_foot", List.of("stepped_on"),
                    List.of(p("SECONDS", "10", "LEVEL", "1"), p("SECONDS", "10", "LEVEL", "2"), p("SECONDS", "10", "LEVEL", "3")),
                    Map.of(), false, List.of("jump", "jump pad", "bounce", "bouncy", "trampoline")),
            new Does("shield", "protect", "Nothing hurts me", "Safe", "minecraft:shield", List.of("near", "every"),
                    List.of(p("SECONDS", "10", "LEVEL", "1"), p("SECONDS", "10", "LEVEL", "2"), p("SECONDS", "10", "LEVEL", "3")),
                    Map.of(), false, List.of("protect", "safe zone", "shield", "nothing hurts me", "safe")),
            new Does("glow", "light", "Light up the dark", "Glowing", "minecraft:glowstone_dust", List.of("near", "every"),
                    List.of(p("GLOW_SECONDS", "0"), p("GLOW_SECONDS", "30"), p("GLOW_SECONDS", "60")),
                    // light.cbs lights the spot it runs AT; at the block that is the block itself (not replaceable, so
                    // nothing was lit: the Java film, 2026-11-10). So it runs at each player near it: light where he is.
                    Map.of("who", "players", "where", "on_each"), false, List.of("light", "glow", "lights up", "lamp", "bright")),
            new Does("freeze", "freeze", "Freeze monsters", "Frosty", "minecraft:blue_ice", List.of("near", "every"),
                    List.of(p("SECONDS", "3"), p("SECONDS", "6"), p("SECONDS", "10")),
                    Map.of("who", "hostile"), true, List.of("freeze", "freeze monsters", "ice trap", "frozen", "stops monsters")),
            new Does("zap", "lightning", "Zap monsters", "Zappy", "minecraft:lightning_rod", List.of("near", "every"),
                    List.of(p("STRIKES", "1"), p("STRIKES", "2"), p("STRIKES", "3")),
                    Map.of("who", "hostile", "where", "on_each"), true, List.of("lightning", "zap", "zap monsters", "thunder", "tesla")));

    /**
     * a lucky surprise at the basic tier ("no piles of rare things") has everyday presents, never
     * diamonds, emeralds or an enchanted apple. The creatures and the bang are the strength's own.
     */
    static final String BASIC_PRESENTS = "minecraft:cake, minecraft:cookie*4, minecraft:apple*2, minecraft:bread*2, minecraft:torch*8";

    /** {@code d}'s parameters at {@code strength} on a world of {@code tier}. */
    public static Map<String, String> params(Does d, int strength, String tier) {
        Map<String, String> p = new LinkedHashMap<>(d.params(strength));
        if (BuilderItemPowers.TIER_BASIC.equals(tier) && p.containsKey("PRESENTS")) {
            p.put("PRESENTS", BASIC_PRESENTS);
        }
        return p;
    }

    /** Every magic-block ready-made, as what it is composed of (preset → look, trigger, effect, strength). */
    public record Ready(String look, String when, String does, int strength, String name) {
    }

    public static final Map<String, Ready> READY_MADES = Map.of(
            "items/lucky_block_outcome_table", new Ready("gold", "broken", "lucky", 2, "Lucky Block"));

    public static Look look(String id) {
        return LOOKS.stream().filter(l -> l.id().equals(bare(id))).findFirst().orElse(null);
    }

    public static When when(String id) {
        return WHENS.stream().filter(w -> w.id().equals(bare(id))).findFirst().orElse(null);
    }

    public static Does does(String id) {
        return DOES.stream().filter(d -> d.id().equals(bare(id))).findFirst().orElse(null);
    }

    private static String bare(String id) {
        return id == null ? "" : id.substring(id.indexOf(':') + 1);
    }

    /** The effects that fit a trigger, in the menu's order. */
    public static List<Does> fitting(String when) {
        return DOES.stream().filter(d -> d.whens().contains(bare(when))).toList();
    }

    /** Is {@code spec} a composed magic block (a look, a trigger and an effect; no ready-made)? */
    public static boolean composed(BuilderSpec spec) {
        return spec.kind() == BuilderSpec.Kind.MAGIC_BLOCK && spec.from().isEmpty() && look(spec.opt("block_look")) != null;
    }

    /**
     * "Lucky Gold Block", "Healing Stone Block"; "Glowing Diamond" when " Block" would take it past 20 letters, the most
     * the tune dialog's name picker offers (past it, the first name offered was "Sparky": the phone film, 2026-11-10).
     */
    public static String name(Look l, Does d) {
        String n = (d == null ? "Magic" : d.word()) + (l == null ? "" : " " + l.label());
        return n.length() + " Block".length() <= 20 ? n + " Block" : n;
    }

    /** The opts a composed spec carries. */
    public static Map<String, String> opts(String look, String when, String does, int strength) {
        Map<String, String> o = new LinkedHashMap<>();
        Does d = does(does);
        o.put("block_look", look);
        o.put("block_when", when);
        o.put("block_does", does);
        o.put("shape", "shape:magic_block");
        o.put("effect", "effect:" + (d == null ? does : d.effect()));
        o.put("strength", String.valueOf(strength));
        return o;
    }

    /**
     * The {@code make_item} arguments for a composed block (the lowering adds name, scheme, count, as_new and
     * only_looks_like, as for any magic block). Throws, naming it, for a pick that does not compose.
     */
    public static JsonObject args(BuilderSpec spec) {
        Look l = look(spec.opt("block_look"));
        When w = when(spec.opt("block_when"));
        Does d = does(spec.opt("block_does"));
        if (l == null || w == null || d == null) {
            throw new IllegalArgumentException("a magic block needs a look, a trigger and what it does");
        }
        if (!d.whens().contains(w.id())) {
            throw new IllegalArgumentException("'" + d.id() + "' does not happen '" + w.id() + "'");
        }
        int strength = spec.opt("strength").isEmpty() ? 2 : Integer.parseInt(spec.opt("strength").strip());
        JsonObject params = new JsonObject();
        params(d, strength, spec.opt("tier")).forEach(params::addProperty);   //: at the world's tier
        JsonObject block = new JsonObject();
        block.addProperty("when", w.id());
        block.addProperty("look", l.block());
        d.dials().forEach(block::addProperty);
        params.add("_block", block);
        JsonObject a = new JsonObject();
        a.add("params", params);
        a.add("properties", new JsonObject());
        a.addProperty("shape", "magic_block");
        a.add("parts", new JsonArray());
        a.add("variants", new JsonArray());
        a.addProperty("effect", d.effect());
        a.addProperty("icon", "block");
        a.addProperty("colour", l.tint());
        if (d.sparePlayers()) {
            a.addProperty("spare_players", true);   // §6.2: what hurts reaches monsters only, never a player
        }
        return a;
    }
}
