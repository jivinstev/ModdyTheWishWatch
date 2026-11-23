package com.moddingfromamod.wishbuilder;

import java.util.List;
import java.util.Map;

/**
 * <b>What a child calls each Builder pick that has no record of its own</b> (the Apprentice wish box).
 *
 * <p>Most Builder entries are records, and their words sit on the record ({@link Schemes.Scheme#words},
 * {@link BuilderParts.Part#words}, {@link BuilderAbilities.Ability#words}, {@link BuilderItemPowers},
 * {@link BuilderEvents}, {@link RuleEffects.Effect#words}, {@link BuilderSnacks.Look#words}). The entries here are
 * the ones the Builder only LABELS: the nine kinds, the creature powers and temperaments, a rule's triggers and
 * mobs, the snack ingredients, a slider's steps, and every ready-made. A ready-made's files are tool arguments
 * (the wish worker's own library, and the Builder library that other tests and the harvest read verbatim), so its
 * words are kept here rather than inside them.
 *
 * <p><b>Adding a pick? Add its words here (or on its record).</b> {@code BuilderTreeTest} walks the menus and fails
 * by name on an offered tile with fewer than {@link BuilderTree#MIN_WORDS} words, on a row here that names nothing
 * the menus offer, and on a word two entries of one slot both claim.
 *
 * <p><b>The grammar of a word</b> (parsed by {@link BuilderTree.Term}):
 * <ul>
 *   <li>{@code "race car"}: a phrase, matched whole, plurals and small misspellings included;</li>
 *   <li>{@code "dino~"}: a FAMILY word several entries of one slot share on purpose; the apprentice offers each;</li>
 *   <li>{@code "unicorn>parts=kit:unicorn_horn"}: the word also picks something in another slot.</li>
 * </ul>
 * A word says what the Builder's entry IS in a child's vocabulary. It is never a wish: no wish text belongs here.
 */
public final class BuilderVocab {

    private BuilderVocab() {
    }

    /** The kinds on the first page (supplies is the tenth). */
    public static final Map<String, List<String>> KINDS = Map.ofEntries(
            Map.entry("creature", List.of("creature", "pet", "animal", "mob", "monster", "friend", "buddy", "critter", "beast")),
            Map.entry("ride", List.of("ride", "rideable", "ridable", "vehicle", "i can ride", "to ride", "drive", "mount", "sit on", "fly on", "ride on")),
            Map.entry("item", List.of("item", "magic item", "tool", "weapon", "gadget", "thing i hold")),
            Map.entry("machine", List.of("machine", "maker", "vending machine", "dispenser", "snack machine", "factory")),
            Map.entry("magic_block", List.of("magic block", "block", "special block", "cube")),
            Map.entry("building", List.of("building", "build", "structure", "hideout", "place to live")),
            Map.entry("picture", List.of("picture", "painting", "drawing", "pixel art", "poster", "art", "draw", "paint", "portrait")),
            Map.entry("event", List.of("event", "happens", "disaster", "something happens", "make it")),
            Map.entry("rule", List.of("rule", "whenever", "every time", "always", "world rule", "new rule")),
            // a bundle of a vanilla thing
            Map.entry("supplies", List.of("supplies", "supply", "resources", "materials", "bundle of", "pile of")));

    /** A creature's powers ({@code power:} ids, {@link BuilderFlow#POWER_WORDS}). */
    public static final Map<String, List<String>> POWERS = Map.ofEntries(
            Map.entry("flies", List.of("flies", "fly", "flying", "can fly", "it flies", "floats", "float", "floating", "hovers", "in the air", "through the air", "in the sky", "soars", "soar", "zooms through the air")),
            Map.entry("glowing", List.of("glows", "glow", "glowing", "shiny", "lights up", "glow in the dark", "shines", "shine", "shines in the dark", "glowy")),
            Map.entry("rideable", List.of("ride", "rideable", "ridable", "i can ride", "ride it", "saddle", "sit on")),
            Map.entry("hops", List.of("hops", "hop", "hopping", "bouncy", "bounces", "boing", "goes boing", "hoppy")),
            Map.entry("leaps", List.of("pounces", "pounce", "leaps", "leap", "jumps high")),
            Map.entry("fireproof", List.of("fireproof", "fire proof", "lava proof", "lives in lava", "swims in lava", "immune to fire", "cannot burn", "cant burn", "does not burn", "doesnt burn", "wont burn", "never burns", "safe from fire", "safe in lava")),
            Map.entry("naps", List.of("naps", "nap", "sleepy", "sleeps", "sleeping", "lazy", "falls asleep", "asleep", "snoozes", "snooze", "takes naps", "dozes")),
            Map.entry("persistent", List.of("stays forever", "never despawns", "never leaves", "forever", "never goes away", "never disappears", "does not disappear", "doesnt disappear", "wont disappear", "always stays")));

    /** A creature's temperaments ({@link BuilderFlow#TEMPER_TILES}). */
    public static final Map<String, List<String>> TEMPERS = Map.ofEntries(
            Map.entry("friendly", List.of("friendly", "nice", "cute", "kind", "tame", "gentle", "loves me")),
            // step 6: "pet" and "follows me" were friendly's words; following is its own pick now.
            Map.entry("pet", List.of("follows me", "follow me", "follows me around", "my pet", "pet", "comes with me", "tags along",
                    "goes everywhere with me", "by my side", "walks with me", "sidekick")),
            Map.entry("stays", List.of("stays put", "stays there", "stays home", "stays in one place", "stays where i put it",
                    "doesnt wander", "does not wander", "stands still", "waits there", "stays at home")),
            Map.entry("shy", List.of("shy", "scared", "timid", "runs away", "nervous", "hides away", "hides from me", "bashful", "afraid")),
            Map.entry("wild", List.of("wild", "fierce", "fights", "mean", "angry", "attacks", "scary", "evil", "fighter", "aggressive", "attacks everything")),
            Map.entry("guard", List.of("guard", "guards", "protects", "protects me", "bodyguard", "fights monsters", "defends", "guards my house")),
            Map.entry("boss", List.of("boss", "boss fight", "final boss", "boss battle")));

    /** A rule's triggers ({@code when:} ids, {@link RuleEffects#TRIGGERS}). */
    public static final Map<String, List<String>> TRIGGERS = Map.ofEntries(
            Map.entry("jumps", List.of("jump", "when i jump", "jumping", "every time i jump")),
            Map.entry("sneaks", List.of("sneak", "crouch", "when i sneak", "sneaking", "crouching")),
            Map.entry("sprints", List.of("sprint", "run", "when i run", "running", "sprinting")),
            Map.entry("lands", List.of("land", "when i land", "landing", "hit the ground")),
            Map.entry("sunrise", List.of("sunrise", "morning", "every morning", "dawn", "when the sun comes up")),
            Map.entry("rain_starts", List.of("when it rains", "starts raining", "rain", "raining")),
            Map.entry("mob_spawns", List.of("appears", "spawns", "spawn", "shows up", "is born")),
            Map.entry("mob_dies", List.of("dies", "die", "defeated", "killed", "when i kill", "kill")),
            Map.entry("sunset", List.of("sunset", "evening", "at night", "dusk", "when it gets dark")),
            Map.entry("rain_stops", List.of("rain stops", "stops raining", "after the rain", "when the rain ends")),
            Map.entry("fish_caught", List.of("catch a fish", "fishing", "when i fish", "catch fish", "caught a fish")),
            Map.entry("every", List.of("every 10 seconds", "every few seconds", "all the time", "every minute", "sometimes")));

    /** The mobs a rule may be about ({@link RuleEffects#RULE_MOBS}). */
    public static final Map<String, List<String>> RULE_MOBS = Map.ofEntries(
            Map.entry("cow", List.of("cows", "cow", "moo cows")),
            Map.entry("pig", List.of("pigs", "pig", "piggies")),
            Map.entry("sheep", List.of("sheep", "lambs", "sheeps")),
            Map.entry("chicken", List.of("chickens", "chicken", "hens")),
            Map.entry("rabbit", List.of("rabbits", "bunnies", "bunny", "rabbit")),
            Map.entry("zombie", List.of("zombies", "zombie", "undead")),
            Map.entry("skeleton", List.of("skeletons", "skeleton", "skellies")),
            Map.entry("spider", List.of("spiders", "spider", "cave spiders")),
            Map.entry("creeper", List.of("creepers", "creeper", "green exploders")));

    /**
     * the REAL farm animals a creature may be ({@link BuilderFlow#FARM_ANIMALS}), the game's own, which breed. A
     * sculpted body of the same name is the creature form's; these are what "real" and "farm" call them.
     */
    public static final Map<String, List<String>> FARM_ANIMALS = Map.ofEntries(
            Map.entry("cow", List.of("cow", "cows", "real cow", "moo cow", "calf", "cattle", "milk cow")),
            Map.entry("pig", List.of("pig", "pigs", "real pig", "piggies", "farm pig", "pork")),
            Map.entry("sheep", List.of("sheep", "sheeps", "real sheep", "lambs", "woolly", "ewe")),
            Map.entry("chicken", List.of("chicken", "chickens", "real chicken", "hens", "chooks", "egg layers")),
            Map.entry("rabbit", List.of("rabbit", "rabbits", "real rabbit", "bunnies", "bunny", "farm rabbit")));

    /** What may go in a snack machine ({@link BuilderSnacks#INGREDIENTS}). */
    public static final Map<String, List<String>> INGREDIENTS = Map.ofEntries(
            Map.entry("minecraft:wheat", List.of("wheat", "grain", "flour")),
            Map.entry("minecraft:wheat_seeds", List.of("seeds", "wheat seeds", "corn")),
            Map.entry("minecraft:sugar", List.of("sugar", "sweet stuff", "sugary")),
            Map.entry("minecraft:sugar_cane", List.of("sugar cane", "sugarcane", "cane")),
            Map.entry("minecraft:egg", List.of("egg", "eggs", "chicken egg")),
            Map.entry("minecraft:milk_bucket", List.of("milk", "bucket of milk", "milk bucket")),
            Map.entry("minecraft:apple", List.of("apple", "apples", "red apple")),
            Map.entry("minecraft:cocoa_beans", List.of("cocoa beans", "cocoa", "chocolate beans")),
            Map.entry("minecraft:sweet_berries", List.of("sweet berries", "berries", "berry")),
            Map.entry("minecraft:glow_berries", List.of("glow berries", "glowing berries", "glow berry")),
            Map.entry("minecraft:honey_bottle", List.of("honey", "honey bottle", "bottle of honey")),
            Map.entry("minecraft:carrot", List.of("carrot", "carrots", "veggie")),
            Map.entry("minecraft:potato", List.of("potato", "potatoes", "spud")),
            Map.entry("minecraft:beetroot", List.of("beetroot", "beet", "beets")),
            Map.entry("minecraft:melon_slice", List.of("melon", "melon slice", "watermelon")),
            Map.entry("minecraft:pumpkin", List.of("pumpkin", "pumpkins", "jack o lantern")),
            Map.entry("minecraft:snowball", List.of("snowball", "snowballs", "snow")),
            Map.entry("minecraft:ice", List.of("ice", "ice cube", "ice block")),
            Map.entry("minecraft:cod", List.of("cod", "fish", "raw fish")),
            Map.entry("minecraft:salmon", List.of("salmon", "pink fish", "salmons")),
            Map.entry("minecraft:beef", List.of("beef", "steak", "meat")),
            Map.entry("minecraft:porkchop", List.of("porkchop", "pork", "bacon")),
            Map.entry("minecraft:chicken", List.of("chicken", "raw chicken", "chicken meat")),
            Map.entry("minecraft:kelp", List.of("kelp", "seaweed", "sea weed")),
            Map.entry("minecraft:sea_pickle", List.of("sea pickle", "sea pickles", "pickle")),
            Map.entry("minecraft:bread", List.of("bread", "loaf", "toast")),
            Map.entry("minecraft:slime_ball", List.of("slime ball", "slimeball", "slime")),
            Map.entry("minecraft:diamond", List.of("diamond", "diamonds", "gem")),
            Map.entry("minecraft:emerald", List.of("emerald", "emeralds", "green gem")),
            Map.entry("minecraft:gold_ingot", List.of("gold", "gold ingot", "gold bar")),
            Map.entry("minecraft:iron_ingot", List.of("iron", "iron ingot", "iron bar")),
            Map.entry("minecraft:redstone", List.of("redstone", "red stone", "redstone dust")),
            Map.entry("minecraft:glowstone_dust", List.of("glowstone", "glowstone dust", "glow dust")),
            Map.entry("minecraft:amethyst_shard", List.of("amethyst", "amethyst shard", "purple crystal")),
            Map.entry("minecraft:feather", List.of("feather", "feathers", "bird feather")),
            Map.entry("minecraft:bone", List.of("bone", "bones", "dog bone")),
            Map.entry("minecraft:poppy", List.of("poppy", "red flower", "flower")),
            Map.entry("minecraft:dandelion", List.of("dandelion", "yellow flower", "dandelions")),
            Map.entry("minecraft:brown_mushroom", List.of("brown mushroom", "mushroom", "mushrooms")),
            Map.entry("minecraft:red_mushroom", List.of("red mushroom", "toadstool", "red mushrooms")),
            Map.entry("minecraft:cactus", List.of("cactus", "cacti", "prickly cactus")),
            Map.entry("minecraft:bamboo", List.of("bamboo", "bamboo stick", "panda food")),
            Map.entry("minecraft:coal", List.of("coal", "charcoal", "lump of coal")),
            Map.entry("minecraft:lapis_lazuli", List.of("lapis", "lapis lazuli", "blue stone")));

    /**
     * A slider's steps, as {@code <key>:<value>}. A step with no row is reached only by its number; the default
     * step (size 2, one of it, strength 2) needs no word.
     */
    public static final Map<String, List<String>> STEPS = Map.ofEntries(
            // a simple building's floors
            Map.entry("floors:1", List.of("one floor", "one storey", "bungalow", "single storey")),
            Map.entry("floors:2", List.of("two floors", "two storey", "two storeys", "2 floors", "upstairs")),
            Map.entry("floors:3", List.of("three floors", "three storey", "three storeys", "3 floors")),
            // how big a picture hangs, in blocks a side
            Map.entry("frame:1", List.of("tiny picture", "one block picture", "a little picture")),
            Map.entry("frame:2", List.of("small picture", "two blocks wide", "two by two")),
            Map.entry("frame:3", List.of("medium picture", "three blocks wide", "three by three")),
            Map.entry("frame:4", List.of("big picture", "huge picture", "giant picture", "four by four")),
            Map.entry("size:1", List.of("tiny", "small", "little", "mini", "baby", "teeny", "smaller", "smallest", "tiniest", "teeny tiny", "super small")),
            Map.entry("size:3", List.of("big", "large", "tall", "bigger", "a bit bigger", "bit bigger", "pretty big", "quite big", "larger")),
            Map.entry("size:4", List.of("giant", "huge", "gigantic", "enormous", "massive", "ginormous", "humongous", "biggest", "as big as it can be", "as big as possible", "super big", "mega",
                    "category 4", "category 5", "cat 5", "ef5", "monster")),
            // step 6: how fast a creature goes
            Map.entry("speed:1", List.of("slow", "slowly", "sluggish", "slowpoke", "takes its time", "walks slowly", "super slow")),
            Map.entry("speed:3", List.of("fast", "quick", "speedy", "zooms", "runs fast", "super fast", "fastest", "zoom", "races", "zippy")),
            Map.entry("count:2", List.of("two", "2", "pair", "couple", "twins")),
            Map.entry("count:3", List.of("three", "3", "family", "trio", "a few")),
            Map.entry("count:4", List.of("four", "4")),
            Map.entry("count:5", List.of("five", "5")),
            // "lots of", "a bunch": ten, said by name; an army is as many as there may be (COUNT_MOST)
            Map.entry("count:10", List.of("lots", "many", "loads", "heaps", "tons", "lots and lots")),
            Map.entry("fill:1", List.of("one at a time", "1 at a time", "one per fill")),
            Map.entry("fill:2", List.of("two at a time", "2 at a time", "two per fill")),
            Map.entry("fill:3", List.of("three at a time", "3 at a time", "three per fill")),
            Map.entry("fill:4", List.of("four at a time", "4 at a time", "four per fill")),
            Map.entry("fill:5", List.of("five at a time", "5 at a time", "five per fill")),
            Map.entry("fill:6", List.of("six at a time", "6 at a time", "six per fill")),
            Map.entry("fill:7", List.of("seven at a time", "7 at a time", "seven per fill")),
            Map.entry("fill:8", List.of("eight at a time", "8 at a time", "eight per fill")),
            // how many supplies (a pile's slider: eight steps to a stack)
            Map.entry("amount:8", List.of("eight", "8")),
            Map.entry("amount:16", List.of("sixteen", "16")),
            Map.entry("amount:24", List.of("twenty four", "24")),
            Map.entry("amount:32", List.of("thirty two", "32", "half a stack")),
            Map.entry("amount:40", List.of("forty", "40")),
            Map.entry("amount:48", List.of("forty eight", "48")),
            Map.entry("amount:56", List.of("fifty six", "56")),
            Map.entry("amount:64", List.of("sixty four", "64", "stack", "full stack", "whole stack", "stack of",
                    // "lots of eggs", "tons of tnt": as many as he may have (the item's own most fits it down)
                    "lots", "lots and lots", "loads", "tons", "heaps", "a lot", "bunch", "pile", "zillion")),
            Map.entry("strength:1", List.of("weak", "gentle", "a little bit", "not very strong", "not too strong", "a bit weak", "weaker", "weakest", "low power")),
            Map.entry("strength:3", List.of("strong", "powerful", "extra strong", "strongest", "mighty", "max", "lots of power", "stronger", "super strong", "very strong", "full power", "max power")));

    /** Every ready-made, by its preset id ({@code species/cat}, {@code bodies/bear}, {@code buildings/farm}). */
    public static final Map<String, List<String>> PRESETS = Map.ofEntries(
            Map.entry("species/cat", List.of("cat", "kitten", "kitty", "kitties")),
            Map.entry("species/chicken", List.of("chicken", "hen", "chick", "rooster", "bird~")),
            Map.entry("species/fire_imp", List.of("fire guardian", "imp", "fire imp", "fire monster", "lava monster>scheme=lava", "fire spirit", "monster~")),
            Map.entry("species/frog", List.of("frog", "toad", "froggy")),
            Map.entry("species/helper_caller", List.of("captain puff", "captain", "puff", "little helpers")),
            Map.entry("species/pet_dragon", List.of("dragon", "pet dragon", "wyvern", "drake")),
            Map.entry("species/sheep", List.of("sheep", "lamb", "woolly sheep")),
            Map.entry("species/snail", List.of("snail", "slug", "snails")),
            Map.entry("species/trex", List.of("t rex~", "trex~", "tyrannosaurus~", "dino~", "dinosaur~", "rex~")),
            Map.entry("species/turtle", List.of("turtle", "tortoise", "sea turtle")),
            Map.entry("species/wolf", List.of("wolf", "dog", "puppy", "wolves", "doggy", "pup")),
            Map.entry("bodies/bear", List.of("bear", "teddy bear", "brown bear", "polar bear>scheme=cloud")),
            Map.entry("bodies/boat", List.of("boat", "speedboat", "sailboat", "raft", "canoe", "ship")),
            Map.entry("bodies/butterfly", List.of("butterfly", "moth", "butterflies")),
            Map.entry("bodies/car", List.of("car", "race car", "racecar", "go kart", "jeep", "police car")),
            Map.entry("bodies/crab", List.of("crab", "lobster", "crabs")),
            Map.entry("bodies/dolphin", List.of("dolphin", "porpoise", "dolphins")),
            Map.entry("bodies/duck", List.of("duck", "duckling", "goose", "ducky", "bird~")),
            Map.entry("bodies/eagle", List.of("eagle", "hawk", "bird~")),
            Map.entry("bodies/elephant", List.of("elephant", "elephants", "jumbo", "big elephant")),
            Map.entry("bodies/flying_saucer", List.of("flying saucer", "ufo", "saucer", "alien ship", "spaceship~")),
            Map.entry("bodies/ghost", List.of("ghost", "spirit", "spooky ghost", "monster~")),   //: no "phantom" (a vanilla mob)
            Map.entry("bodies/gnome", List.of("gnome", "elf", "dwarf", "garden gnome")),
            Map.entry("bodies/griffin", List.of("griffin", "gryphon", "griffon")),
            Map.entry("bodies/hamster", List.of("hamster", "guinea pig", "mouse", "gerbil", "rat")),
            Map.entry("bodies/helicopter", List.of("helicopter", "chopper", "copter")),
            Map.entry("bodies/horse", List.of("horse", "pony", "stallion", "unicorn>parts=kit:unicorn_horn", "pegasus>parts=kit:feather_wings>powers=power:flies")),
            Map.entry("bodies/hot_air_balloon", List.of("hot air balloon", "balloon", "air balloon")),
            Map.entry("bodies/jellyfish", List.of("jellyfish", "jelly fish", "jellyfishes")),
            Map.entry("bodies/lion", List.of("lion", "tiger", "big cat", "panther")),
            Map.entry("bodies/long_neck_dinosaur", List.of("long neck dinosaur", "long neck", "brontosaurus", "diplodocus", "dino~", "dinosaur~")),
            Map.entry("bodies/monkey", List.of("monkey", "ape", "gorilla", "chimp")),
            Map.entry("bodies/motorbike", List.of("motorbike", "motorcycle", "bike", "dirt bike", "scooter")),
            Map.entry("bodies/octopus", List.of("octopus", "squid", "kraken")),
            Map.entry("bodies/ogre", List.of("ogre", "troll", "brute", "cyclops", "monster~")),
            Map.entry("bodies/owl", List.of("owl", "owlet", "owls", "bird~")),
            Map.entry("bodies/penguin", List.of("penguin", "puffin", "penguins", "bird~")),
            Map.entry("bodies/person", List.of("person", "human", "guy", "knight", "pirate", "wizard", "king", "princess", "villager", "teacher", "shopkeeper", "chef", "farmer")),
            Map.entry("bodies/phoenix", List.of("phoenix", "fire bird", "firebird", "bird~")),
            Map.entry("bodies/pig", List.of("pig", "piggy", "piglet", "hog", "boar")),
            Map.entry("bodies/plane", List.of("plane", "airplane", "aeroplane", "jet", "aircraft")),
            Map.entry("bodies/rabbit", List.of("rabbit", "bunny", "hare", "bunnies")),
            Map.entry("bodies/raptor", List.of("raptor", "velociraptor", "raptors", "dino~", "dinosaur~")),
            Map.entry("bodies/robot", List.of("robot", "bot", "android", "mech")),
            Map.entry("bodies/rock_golem", List.of("rock golem", "golem", "rock monster", "stone golem", "stone monster", "monster~")),
            Map.entry("bodies/rocket", List.of("rocket", "rocket ship", "space ship", "spaceship~")),
            Map.entry("bodies/shark", List.of("shark", "great white", "sharks")),
            Map.entry("bodies/snake", List.of("snake", "serpent", "cobra", "python", "sea serpent")),
            Map.entry("bodies/submarine", List.of("submarine", "sub", "submarines")),
            Map.entry("bodies/t_rex", List.of("t rex~", "trex~", "tyrannosaurus~", "dino~", "dinosaur~", "rex~")),
            Map.entry("bodies/tank", List.of("tank", "army tank", "tanks")),
            Map.entry("bodies/triceratops", List.of("triceratops", "horned dinosaur", "three horned dinosaur", "dino~", "dinosaur~")),
            Map.entry("bodies/truck", List.of("truck", "monster truck", "fire truck", "tractor", "bus", "school bus")),
            Map.entry("bodies/whale", List.of("whale", "orca", "killer whale")),
            Map.entry("bodies/yeti", List.of("yeti", "bigfoot", "abominable snowman", "snow monster", "sasquatch", "monster~")),
            Map.entry("buildings/floating_island", List.of("floating island", "sky island", "island in the sky", "flying island")),
            Map.entry("buildings/lava_moat_castle", List.of("castle", "lava castle", "castle with a moat", "fortress", "fort", "palace")),
            Map.entry("buildings/mushroom_village", List.of("mushroom village", "mushroom house", "mushroom town", "giant mushrooms")),
            Map.entry("buildings/secret_treehouse", List.of("treehouse", "inside a tree", "in a tree", "giant tree", "tree house", "secret treehouse", "tree fort")),
            Map.entry("buildings/underwater_dome", List.of("underwater dome", "under the ocean", "under the sea", "under water", "glass house", "glass dome", "dome", "underwater base", "underwater house", "aquarium")),
            // the plain words ("house", "cottage", "barn", "shop", "tower") are the simple types' now (BuilderHouses).
            Map.entry("buildings/cozy_house", List.of("cozy house", "cosy house", "snug house", "house with a garden")),
            Map.entry("buildings/farm", List.of("farm", "farmhouse", "animal farm", "barnyard")),
            Map.entry("buildings/hedge_maze", List.of("maze", "hedge maze", "labyrinth", "garden maze")),
            Map.entry("buildings/jungle_temple", List.of("temple", "jungle temple", "pyramid", "ruins", "ancient temple")),
            Map.entry("buildings/lighthouse", List.of("lighthouse", "light house", "beacon tower")),
            Map.entry("buildings/mansion", List.of("mansion", "fancy mansion", "big house", "villa", "manor")),
            Map.entry("buildings/pirate_ship", List.of("pirate ship", "ship", "galleon", "pirate boat")),
            Map.entry("buildings/playground", List.of("playground", "play ground", "park", "jungle gym", "swings")),
            Map.entry("buildings/red_barn", List.of("red barn", "big red barn", "old barn")),
            Map.entry("buildings/small_village", List.of("village", "small village", "town", "little town")),
            Map.entry("buildings/stone_well", List.of("well", "stone well", "wishing well", "water well")),
            Map.entry("buildings/underground_base", List.of("underground base", "secret base", "bunker", "base", "hideout", "secret lair")),
            Map.entry("buildings/village_shop", List.of("village shop", "shop with a counter", "corner shop", "market stall")),
            Map.entry("buildings/wizard_tower", List.of("wizard tower", "magic tower", "mage tower", "wizards tower")),
            // the working builds
            Map.entry("buildings/farm_plot", List.of("farm plot", "little farm", "vegetable patch", "veggie patch", "wheat farm",
                    "crop farm", "crops", "field", "garden plot", "allotment")),
            Map.entry("buildings/animal_pen", List.of("animal pen", "pen", "pen for my animals", "corral", "fence for cows",
                    "cow pen", "sheep pen", "paddock", "pasture", "enclosure")),
            Map.entry("buildings/tree_nursery", List.of("tree nursery", "tree farm", "trees to grow", "sapling garden",
                    "saplings", "orchard", "nursery", "plant trees")),
            Map.entry("buildings/monster_cave", List.of("monster cave", "spooky cave", "cave", "dungeon",
                    "dungeon where monsters come out", "mob farm", "monster spawner", "spawning room", "dark cave", "monster den")),
            Map.entry("pictures/castle", List.of("castle", "castle picture", "fortress")),
            Map.entry("pictures/frog", List.of("frog", "froggy", "toad")),
            Map.entry("pictures/heart", List.of("heart", "love heart", "hearts", "love")),
            Map.entry("pictures/starter_creeper_face", List.of("creeper", "creeper face", "creeper head")),
            Map.entry("pictures/steve", List.of("steve", "minecraft steve", "player")),
            Map.entry("pictures/wolf", List.of("wolf", "dog", "puppy")),
            Map.entry("pictures/axolotl", List.of("axolotl", "axolotle", "axo")),
            Map.entry("pictures/chicken", List.of("chicken", "hen", "chick")),
            Map.entry("pictures/cupcake", List.of("cupcake", "cupcakes", "muffin")),
            Map.entry("pictures/diamond", List.of("diamond", "diamonds", "gem")),
            Map.entry("pictures/diamond_pickaxe", List.of("pickaxe", "diamond pickaxe", "pick axe")),
            Map.entry("pictures/diamond_sword", List.of("sword", "diamond sword", "blade")),
            Map.entry("pictures/duck", List.of("duck", "ducky", "duckling")),
            Map.entry("pictures/ender_dragon", List.of("ender dragon", "enderdragon", "end dragon")),
            Map.entry("pictures/ghost", List.of("ghost", "spooky ghost", "boo")),
            Map.entry("pictures/golden_apple", List.of("golden apple", "gold apple", "gapple")),
            Map.entry("pictures/moon", List.of("moon", "crescent moon", "full moon")),
            Map.entry("pictures/rainbow", List.of("rainbow", "rainbows", "rain bow")),
            Map.entry("pictures/red_apple", List.of("apple", "red apple", "apples")),
            Map.entry("pictures/red_dragon", List.of("dragon", "red dragon", "fire dragon")),
            Map.entry("pictures/sandwich", List.of("sandwich", "sandwiches", "sammich")),
            Map.entry("pictures/skull", List.of("skull", "skeleton", "skull and crossbones")),
            Map.entry("pictures/slime", List.of("slime", "slime cube", "green slime")),
            Map.entry("pictures/smiley_face", List.of("smiley face", "smiley", "happy face", "emoji", "smile")),
            Map.entry("pictures/smiling_sun", List.of("sun", "smiling sun", "sunshine", "happy sun")),
            Map.entry("pictures/star", List.of("star", "stars", "gold star")),
            Map.entry("pictures/sunset", List.of("sunset", "sun set", "sunrise")),
            Map.entry("pictures/taco", List.of("taco", "tacos", "burrito")));

    /**
     * the words a child calls a stock machine by ("pizza oven", "cookie factory"), on the LOOK it makes — the
     * machine kind has no ready-mades, so "a pizza oven" is the pizza look composed into a machine.
     */
    public static final Map<String, List<String>> MACHINE_LOOKS = Map.ofEntries(
            // The slushie machine is a starter, not one of the 23; a child asking for one composes an icy one.
            Map.entry("ice_pop", List.of("slushie machine", "slushy machine", "slush machine", "slushie maker")),
            Map.entry("bubble_tea", List.of("bubble tea machine", "boba machine", "bubble tea maker", "boba tea machine")),
            Map.entry("cake", List.of("cake maker", "birthday cake maker", "cake machine", "cake oven")),
            Map.entry("candy", List.of("candy machine", "sweets machine", "candy maker", "candy dispenser")),
            Map.entry("chocolate_drink", List.of("chocolate fountain", "chocolate machine", "chocolate fountain machine", "chocolate maker")),
            Map.entry("cookie", List.of("cookie oven", "cookie machine", "cookie maker", "cookie factory")),
            Map.entry("cotton_candy", List.of("cotton candy machine", "candy floss machine", "cotton candy maker")),
            Map.entry("cupcake", List.of("cupcake oven", "cupcake machine", "cupcake maker")),
            Map.entry("donut", List.of("donut machine", "doughnut machine", "donut maker")),
            Map.entry("hot_chocolate", List.of("hot chocolate machine", "cocoa machine", "hot cocoa machine", "hot chocolate maker")),
            Map.entry("hot_dog", List.of("hot dog machine", "hot dog stand", "hotdog machine", "hot dog maker")),
            Map.entry("ice_cream", List.of("ice cream machine", "ice cream maker", "icecream machine", "sundae machine")),
            Map.entry("orange_juice", List.of("orange juice machine", "juice machine", "juice maker", "juicer")),
            Map.entry("lemonade", List.of("lemonade stand", "lemonade machine", "lemonade maker")),
            Map.entry("milkshake", List.of("milkshake machine", "milkshake maker", "milk shake machine")),
            Map.entry("pancake", List.of("pancake machine", "pancake maker", "pancake griddle")),
            Map.entry("pizza", List.of("pizza oven", "pizza machine", "pizza maker")),
            Map.entry("popcorn", List.of("popcorn machine", "popcorn maker", "popcorn popper")),
            Map.entry("pretzel", List.of("pretzel machine", "pretzel maker", "pretzel oven")),
            Map.entry("smoothie", List.of("smoothie maker", "smoothie machine", "smoothie blender", "blender")),
            Map.entry("fizzy_soda", List.of("soda fountain", "soda machine", "fizzy drink machine", "cola machine")),
            Map.entry("sushi", List.of("sushi machine", "sushi maker", "sushi bar")),
            Map.entry("taco", List.of("taco maker", "taco machine", "taco stand")),
            Map.entry("waffle", List.of("waffle maker", "waffle machine", "waffle iron")));

    private static final String[] ONES = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    /** A number as a child says it: its digits and its words ("24", "twenty four"); 1–99. */
    /** a crowd with no number: as many as this make may bring (the count slot's top). */
    public static final List<String> COUNT_MOST = List.of("army", "herd", "swarm", "flock", "horde", "crowd", "zillion",
            "a million", "as many as you can", "as many as possible");

    public static List<String> number(int n) {
        if (n >= 100) {   //: a hundred is the most a make offers
            return n == 100 ? List.of("hundred", "100", "a hundred") : List.of(String.valueOf(n));
        }
        String w = n < 20 ? ONES[n] : TENS[n / 10] + (n % 10 == 0 ? "" : " " + ONES[n % 10]);
        return List.of(w, String.valueOf(n));
    }
}
