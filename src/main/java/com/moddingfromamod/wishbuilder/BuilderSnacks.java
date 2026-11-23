package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>Compose your own snack machine</b>. The owner, grading B5's 23 fixed snack machines: <i>"Since this is a
 * wish engine, can't we just let people have ultimate creativity?"</i> So a machine is now COMPOSED from four picks,
 * each a reviewed slice of what the engine already does (no AI, no new capability):
 *
 * <ol>
 *   <li><b>What it makes</b> — a {@link Look}: the 23 stock foods (B5's own sprites and tints) and 22 more drawn food
 *       icons from the icon library, one chest of 45. He names it in the tune dialog.</li>
 *   <li><b>What eating it does</b> — one or two of {@link BuilderItemPowers}' snack powers, at a strength.
 *       Two only because {@code make_item} already takes {@code effect: "speed+jump"} with grouped parameters, and
 *       {@code BuilderSnackGameTest} eats such a snack and finds BOTH on him (measured, not assumed).</li>
 *   <li><b>What goes in</b> — the look's own ingredient first, then {@link #INGREDIENTS}, plain everyday items: no
 *       command block, spawn egg or operator item can ever be picked, because only this list can.</li>
 *   <li><b>How many per fill</b> — 1 to 8.</li>
 * </ol>
 *
 * <p><b>Why the machine is {@code load_and_pour} and not {@code convert}</b> (the stock machines' effect):
 * {@code convert} is one in, one out, one pull — it has no count, so "how many per fill" would have nowhere to go.
 * {@code load_and_pour} is the engine's own FILL machine (the slushie): its {@code MAX} is "how many of the first
 * ingredient it holds", it says how full it is on every pull, and its sign shows the recipe. So "how many per fill"
 * IS {@code MAX}, and nothing in the engine changes.
 *
 * <p><b>The snack is made first, in the same wish</b>, exactly as B5's foods are (§14): the tool refuses an
 * {@code OUTPUT} it does not know, and {@code make_item} names a new item {@code featureId(name, effects)}, which is
 * the id the machine's {@code OUTPUT} holds ({@link #snackId}). Pure; {@code BuilderSnacksTest}.
 */
public final class BuilderSnacks {

    private BuilderSnacks() {
    }

    /**
     * A food's LOOK: what the chest tile shows ({@code tile}, a vanilla item every edition draws), what the made
     * snack wears ({@code sprite} from the icon library, {@code tint}), what it is made on ({@code base}: a cookie
     * is eaten in a moment, a potion is drunk), and the ingredient that goes in it first ({@code ingredient}).
     */
    public record Look(String id, String label, String tile, String sprite, String tint, String base, String ingredient,
                       List<String> words) {
    }

    private static final String COOKIE = "minecraft:cookie";
    private static final String DRINK = "minecraft:potion";

    /**
     * The 23 stock foods first — B5's own (their sprite, tint and base are {@code builder-library/outputs/<id>_food},
     * their ingredient is the stock machine's {@code INPUT}; {@code BuilderSnacksTest} holds them to those files), then
     * the cookie and the birthday cake, which the stock machines pour as vanilla items — then 22 more drawn foods.
     */
    public static final List<Look> LOOKS = List.of(
            new Look("popcorn", "Popcorn", "minecraft:bone_meal", "popcorn", "#FFF4C2", COOKIE, "minecraft:wheat_seeds", List.of("popcorn", "pop corn", "kettle corn")),
            new Look("ice_cream", "Ice Cream", "minecraft:snowball", "sundae", "#FFD1DC", COOKIE, "minecraft:snowball", List.of("ice cream", "icecream", "sundae", "gelato")),
            new Look("hot_chocolate", "Hot Chocolate", "minecraft:cocoa_beans", "cup", "#6B3E26", DRINK, "minecraft:cocoa_beans", List.of("hot chocolate", "hot cocoa", "cocoa")),
            new Look("lemonade", "Lemonade", "minecraft:honey_bottle", "cup", "#FFF44F", DRINK, "minecraft:sugar", List.of("lemonade", "lemon drink", "lemon juice")),
            new Look("pizza", "Pizza", "minecraft:pumpkin_pie", "pizza_slice", "#F4C542", COOKIE, "minecraft:wheat", List.of("pizza", "pizzas", "pepperoni")),
            new Look("cotton_candy", "Cotton Candy", "minecraft:pink_wool", "cotton_candy", "#FFB6E1", COOKIE, "minecraft:sugar", List.of("cotton candy", "candy floss", "fairy floss")),
            new Look("smoothie", "Smoothie", "minecraft:sweet_berries", "smoothie", "#FF6FA8", COOKIE, "minecraft:sweet_berries", List.of("smoothie", "smoothies", "fruit shake")),
            new Look("milkshake", "Milkshake", "minecraft:milk_bucket", "milkshake", "#F7C8D8", COOKIE, "minecraft:milk_bucket", List.of("milkshake", "milk shake", "shake")),
            new Look("cookie", "Cookie", "minecraft:cookie", "food_round", "#C68A4A", COOKIE, "minecraft:wheat", List.of("cookie", "cookies", "biscuit")),
            new Look("donut", "Donut", "minecraft:pink_dye", "donut", "#F59AC4", COOKIE, "minecraft:sugar", List.of("donut", "doughnut", "donuts")),
            new Look("orange_juice", "Orange Juice", "minecraft:orange_dye", "juice_box", "#FF9A1F", COOKIE, "minecraft:sugar", List.of("orange juice", "juice", "juice box")),
            new Look("fizzy_soda", "Fizzy Soda", "minecraft:experience_bottle", "cup", "#B5462E", DRINK, "minecraft:sugar", List.of("soda", "fizzy soda", "cola", "fizzy drink", "pop")),
            new Look("cupcake", "Cupcake", "minecraft:pink_candle", "cupcake", "#F59AC4", COOKIE, "minecraft:wheat", List.of("cupcake", "cupcakes", "muffin")),
            new Look("pancake", "Pancake", "minecraft:honeycomb", "pancake", "#E8B060", COOKIE, "minecraft:wheat", List.of("pancake", "pancakes", "flapjack")),
            new Look("hot_dog", "Hot Dog", "minecraft:cooked_porkchop", "hot_dog", "#C0602A", COOKIE, "minecraft:porkchop", List.of("hot dog", "hotdog", "sausage")),
            new Look("taco", "Taco", "minecraft:cooked_beef", "taco", "#E8B040", COOKIE, "minecraft:beef", List.of("taco", "tacos", "burrito")),
            new Look("cake", "Birthday Cake", "minecraft:cake", "food_round", "#FFF4E8", COOKIE, "minecraft:wheat", List.of("birthday cake", "cake", "cakes")),
            new Look("chocolate_drink", "Chocolate Drink", "minecraft:brown_dye", "milkshake", "#5A3015", COOKIE, "minecraft:cocoa_beans", List.of("chocolate drink", "chocolate milk", "chocolate fountain")),
            new Look("candy", "Candy", "minecraft:sugar", "wrapped_candy", "#E040A0", COOKIE, "minecraft:sugar", List.of("candy", "sweets", "candies")),
            new Look("bubble_tea", "Bubble Tea", "minecraft:glass_bottle", "boba_tea", "#C8A07A", COOKIE, "minecraft:milk_bucket", List.of("bubble tea", "boba", "boba tea")),
            new Look("sushi", "Sushi", "minecraft:salmon", "sushi", "#F06A4A", COOKIE, "minecraft:cod", List.of("sushi", "sushi roll", "fish roll")),
            new Look("pretzel", "Pretzel", "minecraft:bread", "bread_loaf", "#A0622D", COOKIE, "minecraft:wheat", List.of("pretzel", "pretzels", "soft pretzel")),
            new Look("waffle", "Waffle", "minecraft:honeycomb_block", "waffle", "#D9A040", COOKIE, "minecraft:wheat", List.of("waffle", "waffles", "wafle")),
            // ---- more foods: the icon library's drawn food and sweets, each a sprite make_item knows
            new Look("burger", "Burger", "minecraft:cooked_mutton", "burger", "#B5651D", COOKIE, "minecraft:beef", List.of("burger", "hamburger", "cheeseburger")),
            new Look("fries", "Fries", "minecraft:baked_potato", "fries", "#F2C14E", COOKIE, "minecraft:potato", List.of("fries", "french fries", "chips")),
            new Look("drumstick", "Drumstick", "minecraft:cooked_chicken", "drumstick", "#C8783C", COOKIE, "minecraft:chicken", List.of("drumstick", "chicken leg", "fried chicken", "chicken nugget")),
            new Look("sandwich", "Sandwich", "minecraft:bread", "sandwich", "#E8C07A", COOKIE, "minecraft:bread", List.of("sandwich", "sandwiches", "toastie")),
            new Look("ramen", "Ramen", "minecraft:mushroom_stew", "ramen", "#F2D16B", COOKIE, "minecraft:wheat", List.of("ramen", "noodles", "noodle soup")),
            new Look("soup", "Soup", "minecraft:beetroot_soup", "soup_bowl", "#C0392B", COOKIE, "minecraft:beetroot", List.of("soup", "stew", "broth")),
            new Look("cheese", "Cheese", "minecraft:yellow_wool", "cheese", "#F7D046", COOKIE, "minecraft:milk_bucket", List.of("cheese", "cheesy", "cheddar")),
            new Look("pickle", "Pickle", "minecraft:sea_pickle", "pickle", "#6B8E23", COOKIE, "minecraft:sea_pickle", List.of("pickle", "pickles", "gherkin")),
            new Look("apple", "Apple", "minecraft:apple", "apple", "#D93030", COOKIE, "minecraft:apple", List.of("apple", "apples", "red apple")),
            new Look("banana", "Banana", "minecraft:yellow_dye", "banana", "#F5D547", COOKIE, "minecraft:sugar_cane", List.of("banana", "bananas", "nana")),
            new Look("cherry", "Cherries", "minecraft:red_dye", "cherry", "#B0102A", COOKIE, "minecraft:sweet_berries", List.of("cherry", "cherries", "cherrys")),
            new Look("strawberry", "Strawberry", "minecraft:glow_berries", "strawberry", "#E8384F", COOKIE, "minecraft:sweet_berries", List.of("strawberry", "strawberries", "berry")),
            new Look("lemon", "Lemon", "minecraft:lime_dye", "lemon", "#F7E04A", COOKIE, "minecraft:glow_berries", List.of("lemon", "lemons", "sour lemon")),
            new Look("pineapple", "Pineapple", "minecraft:hay_block", "pineapple", "#E8B83A", COOKIE, "minecraft:sugar", List.of("pineapple", "pineapples", "pine apple")),
            new Look("watermelon", "Watermelon", "minecraft:melon_slice", "watermelon", "#E8484F", COOKIE, "minecraft:melon_slice", List.of("watermelon", "melon", "water melon")),
            new Look("carrot", "Carrot", "minecraft:carrot", "carrot", "#F08A24", COOKIE, "minecraft:carrot", List.of("carrot", "carrots", "veggie")),
            new Look("chocolate_bar", "Chocolate Bar", "minecraft:brown_terracotta", "chocolate_bar", "#5A3015", COOKIE, "minecraft:cocoa_beans", List.of("chocolate bar", "chocolate", "candy bar")),
            new Look("lollipop", "Lollipop", "minecraft:magenta_dye", "lollipop", "#E85AAE", COOKIE, "minecraft:sugar", List.of("lollipop", "lolly", "sucker")),
            new Look("candy_cane", "Candy Cane", "minecraft:red_candle", "candy_cane", "#E8384F", COOKIE, "minecraft:sugar", List.of("candy cane", "candy canes", "peppermint")),
            new Look("gummy_bear", "Gummy Bear", "minecraft:slime_ball", "gummy_bear", "#FF5A5A", COOKIE, "minecraft:slime_ball", List.of("gummy bear", "gummy bears", "gummy", "gummies")),
            new Look("ice_pop", "Ice Pop", "minecraft:blue_ice", "ice_pop", "#5AC8FA", COOKIE, "minecraft:ice", List.of("ice pop", "popsicle", "ice lolly")),
            new Look("marshmallow", "Marshmallow", "minecraft:white_wool", "marshmallow", "#FFF8F0", COOKIE, "minecraft:egg", List.of("marshmallow", "marshmallows", "smore")));

    /** How many of {@link #LOOKS} are B5's stock foods (the rest are the drawn library's). */
    public static final int STOCK = 23;

    /**
     * What may go IN (after the look's own): everyday things a child carries — crops, food, fish, a few shiny things.
     * The whole list is reviewed: nothing an operator holds back (command blocks, spawn eggs, barriers, structure
     * blocks, debug sticks) and nothing that hurts (TNT, lava, fire charges). Every look's own ingredient is on it.
     */
    public static final List<String> INGREDIENTS = List.of(
            "minecraft:wheat", "minecraft:wheat_seeds", "minecraft:sugar", "minecraft:sugar_cane", "minecraft:egg",
            "minecraft:milk_bucket", "minecraft:apple", "minecraft:cocoa_beans", "minecraft:sweet_berries",
            "minecraft:glow_berries", "minecraft:honey_bottle", "minecraft:carrot", "minecraft:potato",
            "minecraft:beetroot", "minecraft:melon_slice", "minecraft:pumpkin", "minecraft:snowball", "minecraft:ice",
            "minecraft:cod", "minecraft:salmon", "minecraft:beef", "minecraft:porkchop", "minecraft:chicken",
            "minecraft:kelp", "minecraft:sea_pickle", "minecraft:bread", "minecraft:slime_ball", "minecraft:diamond",
            "minecraft:emerald", "minecraft:gold_ingot", "minecraft:iron_ingot", "minecraft:redstone",
            "minecraft:glowstone_dust", "minecraft:amethyst_shard", "minecraft:feather", "minecraft:bone",
            "minecraft:poppy", "minecraft:dandelion", "minecraft:brown_mushroom", "minecraft:red_mushroom",
            "minecraft:cactus", "minecraft:bamboo", "minecraft:coal", "minecraft:lapis_lazuli");

    /** The snack powers, in the menu's order: every {@link BuilderItemPowers} power that fits a snack, then the treats. */
    public static List<BuilderItemPowers.Power> powers() {
        List<BuilderItemPowers.Power> out = new ArrayList<>(BuilderItemPowers.forTrigger("on_consume"));
        out.removeIf(w -> w.effect().isEmpty());   //: "Just for fun" is no snack power
        out.addAll(TREATS);
        out.add(YUMMY);
        return out;
    }

    private static Map<String, String> p(String bonus, int seconds) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("BONUS", bonus);
        m.put("SECONDS", String.valueOf(seconds));
        return m;
    }

    private static BuilderItemPowers.Power treat(String id, String label, String tile, String bonus, int s1, int s2, int s3,
                                                 List<String> words) {
        return new BuilderItemPowers.Power(id, "food", label, tile, label, List.of("on_consume"),
                List.of(p(bonus, s1), p(bonus, s2), p(bonus, s3)), words, "", List.of(MACHINE_ONLY), "");
    }

    /** A power only a snack MACHINE's snack has (never offered on an item's stock list). */
    static final String MACHINE_ONLY = "snack-machine";

    /**
     * the stock machines' TREATS — {@code make_item}'s {@code food} effect, a snack that is eaten and gives a
     * boost (B5's 21 foods are each one of these). The strengths are chosen so every B5 food is one of them: speed
     * is 5, 10 or 30 seconds (Cotton Candy, Ice Cream, Candy…), jump 10, 15 or 30 (Popcorn, Waffle) and so on.
     * {@code food} is one effect, so two treats on one snack are refused like any two powers of one effect.
     */
    public static final List<BuilderItemPowers.Power> TREATS = List.of(
            treat("treat_speed", "Sugar rush", "minecraft:sugar", "minecraft:speed", 5, 10, 30,
                    List.of("sugar rush", "quick treat", "zoomy snack", "energy")),
            treat("treat_jump", "Bouncy", "minecraft:slime_ball", "minecraft:jump_boost", 10, 15, 30,
                    List.of("bouncy", "boingy", "springy", "bouncy snack", "hoppy")),
            treat("treat_heal", "Cosy (heals)", "minecraft:glistering_melon_slice", "minecraft:regeneration", 5, 10, 20,
                    List.of("cosy", "cozy", "warm", "comfort food", "soothing")),
            treat("treat_strong", "Strong", "minecraft:iron_ingot", "minecraft:strength", 10, 20, 30,
                    List.of("strong", "strength", "muscles", "power up", "makes me strong")),
            treat("treat_filling", "Filling", "minecraft:bread", "minecraft:saturation", 5, 10, 15,
                    List.of("filling", "full", "not hungry", "fills me up", "hearty")),
            treat("treat_underwater", "Breathe underwater", "minecraft:pufferfish", "minecraft:water_breathing", 15, 30, 60,
                    List.of("breathe underwater", "underwater", "water breathing", "swim", "under the sea")));

    /**
     * no power at all — the machine pours the plain vanilla food (the Cookie Oven pours cookies, the Birthday Cake
     * Maker cakes). Only for a look that IS a vanilla food ({@link Machine#plain}); a snack with it has nothing else.
     */
    public static final BuilderItemPowers.Power YUMMY = new BuilderItemPowers.Power("yummy", "", "Just yummy",
            "minecraft:cookie", "Yummy", List.of("on_consume"), List.of(Map.of(), Map.of(), Map.of()),
            List.of("just yummy", "plain", "normal", "nothing special", "just food", "regular"), "", List.of(MACHINE_ONLY), "");

    /** A snack power by id: an item power that fits a snack, a treat, or {@link #YUMMY}. */
    public static BuilderItemPowers.Power power(String id) {
        for (BuilderItemPowers.Power w : powers()) {
            if (w.id().equals(id)) {
                return w;
            }
        }
        return null;
    }

    /**
     * a stock look's own MACHINE, as B5 made it — its name ("Pizza Oven"), its colour, how many of the
     * ingredient come in its kit, and (for a vanilla food) what it pours with no power. Looks with no row are named
     * "{look} Machine", wear the look's colour, and come with 32 (a bucket ingredient: 8).
     */
    public record Machine(String name, String tint, int kit, String plain) {
    }

    public static final Map<String, Machine> MACHINES = Map.ofEntries(
            Map.entry("popcorn", new Machine("Popcorn Machine", "red", 32, "")),
            Map.entry("ice_cream", new Machine("Ice Cream Machine", "#FFB6E1", 32, "")),
            Map.entry("hot_chocolate", new Machine("Hot Chocolate Machine", "#6B3E26", 32, "")),
            Map.entry("lemonade", new Machine("Lemonade Stand Machine", "#FFE135", 32, "")),
            Map.entry("pizza", new Machine("Pizza Oven", "#B5482E", 32, "")),
            Map.entry("cotton_candy", new Machine("Cotton Candy Machine", "#FF8FD0", 32, "")),
            Map.entry("smoothie", new Machine("Smoothie Maker", "#FF6FA8", 32, "")),
            Map.entry("milkshake", new Machine("Milkshake Machine", "#F7A8C8", 8, "")),
            Map.entry("cookie", new Machine("Cookie Oven", "#8B5A2B", 32, "minecraft:cookie")),
            Map.entry("donut", new Machine("Donut Machine", "#F59AC4", 32, "")),
            Map.entry("orange_juice", new Machine("Orange Juice Machine", "#FF9A1F", 32, "")),
            Map.entry("fizzy_soda", new Machine("Soda Fountain", "#D93A3A", 32, "")),
            Map.entry("cupcake", new Machine("Cupcake Oven", "#F59AC4", 32, "")),
            Map.entry("pancake", new Machine("Pancake Machine", "#E8B060", 32, "")),
            Map.entry("hot_dog", new Machine("Hot Dog Machine", "#D93A3A", 32, "")),
            Map.entry("taco", new Machine("Taco Maker", "#E8B040", 32, "")),
            Map.entry("cake", new Machine("Birthday Cake Maker", "#F59AC4", 32, "minecraft:cake")),
            Map.entry("chocolate_drink", new Machine("Chocolate Fountain Machine", "#5A3015", 32, "")),
            Map.entry("candy", new Machine("Candy Machine", "#E040A0", 32, "")),
            Map.entry("bubble_tea", new Machine("Bubble Tea Machine", "#C8A07A", 8, "")),
            Map.entry("sushi", new Machine("Sushi Machine", "#F06A4A", 16, "")),
            Map.entry("pretzel", new Machine("Pretzel Machine", "#A0622D", 16, "")),
            Map.entry("waffle", new Machine("Waffle Maker", "#D9A040", 32, "")));

    /** The machine a look makes: its stock row, or the default. */
    public static Machine machine(Look l) {
        Machine m = l == null ? null : MACHINES.get(l.id());
        if (m != null) {
            return m;
        }
        String in = l == null ? "" : l.ingredient();
        return new Machine((l == null ? "Snack" : l.label()) + " Machine", l == null ? "#FFD54A" : l.tint(),
                in.endsWith("_bucket") ? 8 : 32, "");
    }

    /** how the machine works — {@link #POUR} (an earlier issue's fill machine) or {@link #CONVERT} (B5's: one in, one out). */
    public static final String POUR = "pour";
    public static final String CONVERT = "convert";

    /** every B5 stock machine, as what it is composed of: its look, its treat (or yummy) and strength. */
    public record Ready(String look, String power, int strength) {
    }

    public static final Map<String, Ready> READY_MADES = Map.ofEntries(
            Map.entry("items/bubble_tea_machine", new Ready("bubble_tea", "treat_speed", 3)),
            Map.entry("items/cake_maker", new Ready("cake", "yummy", 2)),
            Map.entry("items/candy_machine", new Ready("candy", "treat_speed", 3)),
            Map.entry("items/chocolate_fountain", new Ready("chocolate_drink", "treat_speed", 2)),
            Map.entry("items/cookie_oven", new Ready("cookie", "yummy", 2)),
            Map.entry("items/cotton_candy_machine", new Ready("cotton_candy", "treat_speed", 1)),
            Map.entry("items/cupcake_oven", new Ready("cupcake", "treat_speed", 3)),
            Map.entry("items/donut_machine", new Ready("donut", "treat_speed", 3)),
            Map.entry("items/hot_chocolate_machine", new Ready("hot_chocolate", "treat_heal", 2)),
            Map.entry("items/hot_dog_stand", new Ready("hot_dog", "treat_strong", 3)),
            Map.entry("items/ice_cream_machine", new Ready("ice_cream", "treat_speed", 2)),
            Map.entry("items/juice_machine", new Ready("orange_juice", "treat_speed", 3)),
            Map.entry("items/lemonade_stand", new Ready("lemonade", "treat_speed", 2)),
            Map.entry("items/milkshake_machine", new Ready("milkshake", "treat_jump", 3)),
            Map.entry("items/pancake_machine", new Ready("pancake", "treat_speed", 3)),
            Map.entry("items/pizza_oven", new Ready("pizza", "treat_filling", 1)),
            Map.entry("items/popcorn_machine", new Ready("popcorn", "treat_jump", 2)),
            Map.entry("items/pretzel_machine", new Ready("pretzel", "treat_filling", 1)),
            Map.entry("items/smoothie_maker", new Ready("smoothie", "treat_speed", 3)),
            Map.entry("items/soda_fountain", new Ready("fizzy_soda", "treat_speed", 3)),
            Map.entry("items/sushi_machine", new Ready("sushi", "treat_underwater", 2)),
            Map.entry("items/taco_maker", new Ready("taco", "treat_speed", 3)),
            Map.entry("items/waffle_maker", new Ready("waffle", "treat_jump", 3)));

    /** How many a fill holds: {@code load_and_pour}'s {@code MAX} range. */
    public static final int FILL_MIN = 1;
    public static final int FILL_MAX = 8;
    public static final int FILL_DEFAULT = 3;

    public static Look look(String id) {
        String bare = id == null ? null : id.startsWith("look:") ? id.substring("look:".length()) : id;
        for (Look l : LOOKS) {
            if (l.id().equals(bare)) {
                return l;
            }
        }
        return null;
    }

    /** The ingredients a look offers, its own first, as item ids. */
    public static List<String> ingredientsFor(Look look) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (look != null) {
            out.add(look.ingredient());
        }
        out.addAll(INGREDIENTS);
        return new ArrayList<>(out);
    }

    /** "Wheat Seeds" for {@code minecraft:wheat_seeds}. */
    public static String words(String itemId) {
        String bare = itemId.substring(itemId.indexOf(':') + 1).replace('_', ' ');
        StringBuilder b = new StringBuilder();
        for (String w : bare.split(" ")) {
            if (!w.isEmpty()) {
                b.append(b.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return b.toString();
    }

    /** Is {@code spec} a composed snack machine (a machine with a look, no ready-made)? */
    public static boolean composed(BuilderSpec spec) {
        return spec.kind() == BuilderSpec.Kind.MACHINE && spec.from().isEmpty() && look(spec.opt("look")) != null;
    }

    /** The snack's power ids on a composed spec, in order (one or two). */
    public static List<String> chosenPowers(BuilderSpec spec) {
        List<String> out = new ArrayList<>();
        // As many as were stored, and one past make_item's limit is read so that it is REFUSED, not dropped.
        for (int i = 1; i <= BuilderLimits.SNACK_EFFECTS + 1; i++) {
            String v = spec.opt(i == 1 ? "snack_power" : "snack_power" + i);
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    /** The snack's name: what he called it, else its look's. */
    public static String snackName(BuilderSpec spec) {
        Look l = look(spec.opt("look"));
        return !spec.name().isEmpty() ? spec.name() : l == null ? "Snack" : l.label();
    }

    /**
     * The machine's name: the one he gave it ({@code machine}, kept from a ready-made he named), else his snack's name +
     * " Machine" ("Zoom Pizza Machine"), else the look's own machine ("Pizza Oven").
     */
    public static String machineName(BuilderSpec spec) {
        if (!spec.opt("machine").isEmpty()) {
            return spec.opt("machine");
        }
        Look l = look(spec.opt("look"));
        if (!spec.name().isEmpty() && (l == null || !spec.name().equalsIgnoreCase(l.label()))) {
            return spec.name() + " Machine";   // he named his snack: "Zoom Pizza Machine"
        }
        return machine(l).name();              // its own: "Pizza Oven", "Lemonade Stand Machine"
    }

    /** How it works: {@link #CONVERT} or {@link #POUR} (a spec from is a fill machine). */
    public static String how(BuilderSpec spec) {
        return CONVERT.equals(spec.opt("how")) ? CONVERT : POUR;
    }

    /**
     * The id {@code make_item} mints for the snack, so the machine's {@code OUTPUT} can name it in the same wish:
     * {@code featureId(name, effects joined by _)}, the same rule as {@code MakeItemTool.featureId} (B5's foods ride
     * on it: "Pizza" + {@code food} is {@code pizza_food}). {@code BuilderSnackGameTest} proves the poured snack is it.
     */
    public static String snackId(String name, List<String> effects) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) {
            base = "wish";
        }
        String id = base + "_" + String.join("_", effects);
        if (!Character.isLetter(id.charAt(0))) {
            id = "i" + id;
        }
        return id.length() > 47 ? id.substring(0, 47).replaceAll("_+$", "") : id;
    }

    /** Why two powers cannot ride together, or null when they can: {@link BuilderLimits#CONFLICTS}. */
    public static String clash(String a, String b) {
        if (power(a) == null || power(b) == null) {
            return "That isn't on this world's Builder.";
        }
        return BuilderLimits.whyNot("snack_powers", List.of(a), b);
    }

    /**
     * The two {@code make_item} calls a composed spec lowers to: the snack, then the machine that pours it. Throws,
     * naming the problem, for a spec that does not compose (a missing look, power, ingredient or a bad fill).
     */
    public static List<JsonObject> calls(BuilderSpec spec) {
        Look look = look(spec.opt("look"));
        if (look == null) {
            throw new IllegalArgumentException("no food look '" + spec.opt("look") + "'");
        }
        List<String> powers = chosenPowers(spec);
        if (powers.isEmpty()) {
            throw new IllegalArgumentException("a snack needs a power");
        }
        String limit = BuilderLimits.problem("snack_powers", powers);   //: make_item's own limits, no other
        if (limit != null) {
            throw new IllegalArgumentException(limit);
        }
        int strength = num(spec.opt("strength"), 2, "strength");
        if (strength < 1 || strength > 3) {
            throw new IllegalArgumentException("strength " + strength + " is not 1–3");
        }
        String ingredient = Catalogue.bare(spec.opt("ingredient"));
        if (ingredient == null || !ingredientsFor(look).contains(ingredient)) {
            throw new IllegalArgumentException("'" + spec.opt("ingredient") + "' cannot go in a snack machine");
        }
        int fill = num(spec.opt("fill"), FILL_DEFAULT, "fill");
        if (fill < FILL_MIN || fill > FILL_MAX) {
            throw new IllegalArgumentException("fill " + fill + " is not " + FILL_MIN + "–" + FILL_MAX);
        }

        Machine mach = machine(look);
        String how = how(spec);
        boolean yummy = powers.size() == 1 && YUMMY.id().equals(powers.get(0));
        if (powers.contains(YUMMY.id()) && !yummy) {
            throw new IllegalArgumentException("'yummy' rides alone");
        }
        if (yummy && mach.plain().isEmpty()) {
            throw new IllegalArgumentException("a " + look.label() + " is not a plain food, so it needs a power");
        }
        String name = snackName(spec);
        List<String> effects = new ArrayList<>();
        JsonObject snack = null;
        if (!yummy) {
            // ---- the snack: on_consume, his power(s) at his strength, wearing the look
            JsonObject params = new JsonObject();
            for (String id : powers) {
                BuilderItemPowers.Power w = power(id);
                if (w == null || w == YUMMY || !w.shapes().contains("on_consume")) {
                    throw new IllegalArgumentException("'" + id + "' is not a snack power");
                }
                effects.add(w.effect());
                JsonObject one = new JsonObject();
                w.params(strength).forEach(one::addProperty);
                if (powers.size() == 1) {
                    params = one;
                } else {
                    params.add(w.effect(), one);   // grouped by effect: two of these can both want SECONDS
                }
            }
            snack = new JsonObject();
            snack.addProperty("effect", String.join("+", effects));
            snack.addProperty("shape", "on_consume");
            snack.addProperty("base_item", look.base());
            snack.addProperty("icon", look.sprite());
            snack.addProperty("colour", look.tint());
            snack.add("params", params);
            snack.add("parts", new JsonArray());
            snack.add("properties", new JsonObject());
            snack.add("variants", new JsonArray());
            snack.addProperty("name", name);
            // A fill machine hands one over to taste; a one-in-one-out machine hands over four, as B5's did.
            snack.addProperty("count", CONVERT.equals(how) ? 4 : 1);
            snack.addProperty("as_new", true);
        }
        String output = yummy ? mach.plain() : snackId(name, effects);

        JsonObject mp = new JsonObject();
        JsonObject kit = new JsonObject();
        JsonObject machine = new JsonObject();
        if (CONVERT.equals(how)) {
            // ----: a one-in-one-out machine, as B5's stock machines are — turn_into, which on
            // the machine shape always takes from his inventory (the "how" CONVERT is a menu mode, not an effect id)
            mp.addProperty("FROM", ingredient);
            mp.addProperty("TO", output);
            kit.addProperty("items", ingredient + " " + mach.kit());
            mp.add("_kit", kit);
            machine.addProperty("effect", com.moddingfromamod.datapack.TurnInto.EFFECT);
        } else {
            // ---- the machine: a fill machine (load_and_pour) that pours the snack
            mp.addProperty("INGREDIENTS", ingredient);
            mp.addProperty("EXTRAS", "none");
            mp.addProperty("MAX", String.valueOf(fill));
            mp.addProperty("WHERE", "hand");
            mp.addProperty("OUTPUT", output);
            kit.addProperty("items", ingredient + " " + Math.min(64, fill * 4));   // enough for four fills
            mp.add("_kit", kit);
            machine.addProperty("effect", "load_and_pour");
        }
        machine.addProperty("shape", "machine");
        machine.addProperty("icon", "machine");
        machine.addProperty("colour", mach.tint());
        machine.add("params", mp);
        machine.add("parts", new JsonArray());
        machine.add("properties", new JsonObject());
        machine.add("variants", new JsonArray());
        machine.addProperty("name", machineName(spec));
        List<JsonObject> out = new ArrayList<>();
        if (snack != null) {
            out.add(snack);
        }
        out.add(machine);
        return out;
    }

    /** The opts a composed spec carries (what {@code BuilderFlow} writes and {@link #calls} reads). */
    public static Map<String, String> opts(String look, List<String> powers, int strength, String ingredient, int fill) {
        return opts(look, powers, strength, ingredient, fill, POUR);
    }

    /** The same, with how it works ({@link #POUR} is left out, as a spec from has it). */
    public static Map<String, String> opts(String look, List<String> powers, int strength, String ingredient, int fill,
                                           String how) {
        Map<String, String> o = new java.util.LinkedHashMap<>();
        o.put("look", "look:" + look);
        for (int i = 0; i < powers.size(); i++) {
            BuilderItemPowers.Power w = power(powers.get(i));
            String n = i == 0 ? "" : String.valueOf(i + 1);
            // ⚠ Not "power"/"effect": a build from reads those as a made-up ITEM and would make a
            // machine that does the snack's power. Under these names it finds neither and refuses the spec.
            o.put("snack_power" + n, powers.get(i));
            if (w == null || !w.effect().isEmpty()) {   // "just yummy" is no effect at all
                o.put("snack_effect" + n, "effect:" + (w == null ? powers.get(i) : w.effect()));
            }
        }
        o.put("strength", String.valueOf(strength));
        o.put("ingredient", "ingredient:" + ingredient);
        o.put("fill", String.valueOf(fill));
        if (CONVERT.equals(how)) {
            o.put("how", CONVERT);
        }
        return o;
    }

    private static int num(String v, int dflt, String what) {
        if (v == null || v.isBlank()) {
            return dflt;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + what + "' is not a number: " + v);
        }
    }
}
