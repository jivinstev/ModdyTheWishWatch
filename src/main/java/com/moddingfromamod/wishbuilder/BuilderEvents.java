package com.moddingfromamod.wishbuilder;

import java.util.List;

import com.google.gson.JsonObject;

/**
 * <b>"Make one up": an event from what happens and what it is made of</b>. Every motion here is one
 * {@code stage_event} already stages for a typed wish (EventDesign.Motion); this is the Builder's reviewed slice:
 * the motions a kid names, in his words, and for each the few things it may be made of. Fire burns CREATURES (a
 * fire twister, the meteor crater's rim fires); there is no lava wave or flood, because lava sets fire to wood on its
 * own and a burnt block is the one thing an event's journal cannot give back (the ready-made meteor is a
 * composition now, the same call). A motion that only PASSES (a wave, a flood) puts the world back by itself; one that LEAVES something
 * (cracks, a crater, gold ground, the animals that fell) stays until he undoes it, the same rule a wish follows.
 *
 * <p>Pure: {@code BuilderEventsTest} checks every pair against the tool's own schema rules and
 * {@code BuilderEventGameTest} stages every motion through the Builder and looks at the world.
 */
public final class BuilderEvents {

    private BuilderEvents() {
    }

    /** One thing an event can be made of: the id the tool takes, the tile's words and icon. */
    public record What(String id, String label, String tile, List<String> words, String field) {
        What(String id, String label, String tile, List<String> words) {
            this(id, label, tile, words, null);
        }

        /** The same choice, going in another tool argument (a twister of chickens: they are its `drop`). */
        What as(String otherField) {
            return new What(id, label, tile, words, otherField);
        }
    }

    /**
     * One motion: the Builder's id, the catalogue motion, the words and icon, which tool argument the "what"
     * goes in ({@code material}, {@code drop}, or none), the choices, and how a title reads ("A big wave of %s").
     */
    public record Motion(String id, String motion, String label, String tile, String field, List<What> whats,
                         String title, List<String> words, String extras, java.util.Map<String, String> titles) {
        Motion(String id, String motion, String label, String tile, String field, List<What> whats, String title,
               List<String> words) {
            this(id, motion, label, tile, field, whats, title, words, "", java.util.Map.of());
        }

        /** the same motion carrying the stage_event arguments its ready-made carried, and its classic titles. */
        Motion with(String extrasJson, java.util.Map<String, String> classic) {
            return new Motion(id, motion, label, tile, field, whats, title, words, extrasJson, classic);
        }
    }

    private static What w(String id, String label, String tile, String... words) {
        return new What(id, label, tile, List.of(words));
    }

    private static final List<What> ANIMALS = List.of(
            w("minecraft:chicken", "Chickens", "minecraft:chicken_spawn_egg", "chickens", "chicken", "hens"),
            w("minecraft:pig", "Pigs", "minecraft:pig_spawn_egg", "pigs", "pig", "piggies"),
            w("minecraft:sheep", "Sheep", "minecraft:sheep_spawn_egg", "sheep", "lambs", "sheeps"),
            w("minecraft:frog", "Frogs", "minecraft:frog_spawn_egg", "frogs", "frog", "toads"),
            w("minecraft:rabbit", "Bunnies", "minecraft:rabbit_spawn_egg", "bunnies", "rabbits", "bunny", "rabbit"),
            w("minecraft:cat", "Cats", "minecraft:cat_spawn_egg", "cats", "cat", "kittens", "kitties"));

    public static final List<Motion> ALL = List.of(
            new Motion("wave", "moving_front", "A big wave", "minecraft:water_bucket", "material", List.of(
                    w("minecraft:water", "Water", "minecraft:water_bucket", "water", "sea", "ocean"),
                    w("minecraft:honey_block", "Honey", "minecraft:honey_bottle", "honey", "sticky", "syrup"),
                    w("minecraft:slime_block", "Slime", "minecraft:slime_ball", "slime", "goo", "slimy"),
                    w("minecraft:snow_block", "Snow", "minecraft:snowball", "snow", "snowy", "blizzard")), "A big wave of %s!", List.of("wave", "big wave", "wave of", "giant wave", "tidal wave", "tsunami", "huge wave"))
                    // it picks up what it reaches and carries it (the tidal wave). It WRECKS by
                    // default — smashes through builds and leaves the damage until undo — unless the tune dialog spares them.
                    .with("{\"catch\":[{\"where\":\"inside\",\"do\":[\"carry\",\"lift\"]}]}",
                            java.util.Map.of("minecraft:water", "TIDAL WAVE!")),
            new Motion("flood", "layer", "A flood", "minecraft:blue_ice", "material", List.of(
                    w("minecraft:water", "Water", "minecraft:water_bucket", "water", "sea", "ocean"),
                    w("minecraft:snow_block", "Snow", "minecraft:snowball", "snow", "snowy", "blizzard"),
                    w("minecraft:honey_block", "Honey", "minecraft:honey_bottle", "honey", "sticky", "syrup")), "A flood of %s!", List.of("flood", "floods", "flooding", "fill up with", "fill", "fills", "filled with")),
            new Motion("rain", "from_the_sky", "It rains...", "minecraft:feather", "drop", ANIMALS, "It's raining %s!", List.of("rain", "rains", "raining", "from the sky", "fall from the sky", "falls from the sky"))
                    // what fell stays until he undoes it (it rains chickens).
                    .with("{\"afterwards\":\"until_undo\"}", java.util.Map.of("minecraft:chicken", "IT'S RAINING CHICKENS!")),
            new Motion("crowd", "crowd", "A crowd of...", "minecraft:lead", "drop", ANIMALS, "A crowd of %s!", List.of("crowd", "herd", "stampede", "lots of", "bunch of", "army of", "spawn")),
            new Motion("turn", "transform", "The ground turns into...", "minecraft:gold_block", "material", List.of(
                    w("minecraft:gold_block", "Gold", "minecraft:gold_ingot", "gold", "golden", "gold blocks"),
                    w("minecraft:diamond_block", "Diamond", "minecraft:diamond", "diamond", "diamonds", "diamond blocks"),
                    w("minecraft:snow_block", "Snow", "minecraft:snowball", "snow", "snowy", "blizzard"),
                    w("minecraft:slime_block", "A bounce pit", "minecraft:slime_ball", "bounce pit", "bouncy", "trampoline", "slime"),
                    w("minecraft:pink_wool", "Candy floss", "minecraft:pink_wool", "candy floss", "cotton candy", "candy", "pink")), "The ground turns into %s!", List.of("turns into", "turn into", "ground turns", "becomes", "turn the ground", "cover", "covers", "covered in", "turn everything into")),
            new Motion("crater", "impact_stamp", "A crater", "minecraft:fire_charge", "material", List.of(
                    w("minecraft:stone", "Stone", "minecraft:stone", "stone", "rock", "rocks"),
                    w("minecraft:gold_block", "Gold", "minecraft:gold_ingot", "gold", "golden", "gold blocks"),
                    w("minecraft:amethyst_block", "Crystal", "minecraft:amethyst_shard", "crystal", "amethyst", "crystals")), "A crater of %s!", List.of("crater", "big hole", "impact", "giant hole")),
            new Motion("quake", "spreading_cracks", "An earthquake", "minecraft:cracked_stone_bricks", "material", List.of(
                    w("minecraft:air", "Cracks", "minecraft:cracked_stone_bricks", "cracks", "crack", "split")), "Earthquake!", List.of("earthquake", "quake", "ground shakes", "shakes", "cracks in the ground")),
            // a meteor falls; in water it splashes a wave, on land it leaves a crater (meteor_splash and
            // meteor_crater, the follow-on designs the ready-made always had). A fiery rock is the only one: the
            // follow-ons are drawn for it.
            new Motion("meteor", "falling_body", "A meteor", "minecraft:magma_block", "material", List.of(
                    w("minecraft:magma_block", "A fiery rock", "minecraft:magma_block", "meteor", "fiery rock", "space rock", "asteroid")),
                    "METEOR!", List.of("meteor", "meteorite", "asteroid", "falls from space", "space rock", "comet", "meteor shower", "meteors", "shooting star"))
                    .with("{\"then\":[{\"if\":\"water_near\",\"design\":\"meteor_splash\"},{\"if\":\"on_land\",\"design\":\"meteor_crater\"}]}",
                            java.util.Map.of()),
            // Vortexes (EVENTS_PLAN §16.5): one `vortex` motion, three tiles, because a child names them differently. Each is
            // a shape with its defaults; what it is made of is what its walls are DRAWN in, or the animals spinning in it.
            // A fire twister sets alight what it catches (fire is offered in every world).
            new Motion("twister", "vortex", "A twister", "minecraft:white_stained_glass", "material", List.of(
                    w("minecraft:white_stained_glass", "Wind", "minecraft:white_stained_glass", "wind", "windy", "air", "tornado"),
                    w("minecraft:water", "Water", "minecraft:water_bucket", "water", "waterspout", "water spout", "sea"),
                    w("minecraft:sand", "Sand", "minecraft:sand", "sand", "sandy", "dust", "dust devil", "desert"),
                    w("minecraft:orange_stained_glass", "Fire", "minecraft:blaze_powder", "fire", "fiery", "flames", "firenado", "burning"),
                    w("minecraft:snow_block", "Snow", "minecraft:snowball", "snow", "snowy", "blizzard", "ice"),
                    w("minecraft:oak_leaves", "Leaves", "minecraft:oak_leaves", "leaves", "leaf", "leafy", "autumn"),
                    w("minecraft:pink_wool", "Candy floss", "minecraft:pink_wool", "candy floss", "cotton candy", "candy", "pink"),
                    w("minecraft:purple_stained_glass", "Ender", "minecraft:ender_pearl", "ender", "purple", "end", "enderman"),
                    // A twister of animals: they spin in it, are flung out, land and stay until undo (its `drop`).
                    ANIMALS.get(0).as("drop"), ANIMALS.get(1).as("drop"), ANIMALS.get(2).as("drop"),
                    ANIMALS.get(3).as("drop"), ANIMALS.get(4).as("drop"), ANIMALS.get(5).as("drop")),
                    "A twister of %s!", List.of("tornado", "twister", "whirlwind", "dust devil", "waterspout", "water spout",
                            "funnel", "spinning wind", "tornadoes", "twisters", "spins", "tornado of", "tornado full of",
                            "cyclone of", "twister of", "whirlwind of", "tornado with"))
                    .with("{\"shape\":\"funnel\"}", java.util.Map.of("minecraft:white_stained_glass", "TORNADO!",
                            "minecraft:water", "WATERSPOUT!", "minecraft:sand", "DUST DEVIL!", "minecraft:orange_stained_glass", "FIRE TORNADO!",
                            "minecraft:snow_block", "SNOW TWISTER!", "minecraft:oak_leaves", "LEAF WHIRLWIND!",
                            "minecraft:pink_wool", "CANDY FLOSS TWISTER!", "minecraft:purple_stained_glass", "ENDER VORTEX!")),
            new Motion("whirlpool", "vortex", "A whirlpool", "minecraft:heart_of_the_sea", "material", List.of(
                    w("minecraft:water", "Water", "minecraft:water_bucket", "water", "sea", "ocean", "lake"),
                    w("minecraft:sand", "Quicksand", "minecraft:sand", "quicksand", "sand", "sinking sand", "sand pit"),
                    w("minecraft:honey_block", "Honey", "minecraft:honey_bottle", "honey", "sticky", "syrup")),
                    "A whirlpool of %s!", List.of("whirlpool", "maelstrom", "vortex", "swirl", "swirling", "sucks down",
                            "sucks in", "pulls down", "drain", "whirlpools"))
                    .with("{\"shape\":\"drain\"}", java.util.Map.of("minecraft:water", "WHIRLPOOL!",
                            "minecraft:sand", "QUICKSAND!")),
            new Motion("hurricane", "vortex", "A hurricane", "minecraft:white_wool", "material", List.of(
                    w("minecraft:white_wool", "Storm clouds", "minecraft:white_wool", "clouds", "storm", "rain", "stormy"),
                    w("minecraft:snow_block", "Snow", "minecraft:snowball", "snow", "snowstorm", "blizzard", "snowy"),
                    w("minecraft:pink_wool", "Candy floss", "minecraft:pink_wool", "candy floss", "cotton candy", "candy", "pink")),
                    "A hurricane of %s!", List.of("hurricane", "typhoon", "cyclone", "tropical storm", "eye of the storm",
                            "superstorm", "hurricanes"))
                    .with("{\"shape\":\"spiral\"}", java.util.Map.of("minecraft:white_wool", "HURRICANE!",
                            "minecraft:snow_block", "BLIZZARD!")));

    /**
     * How a spinning thing moves, the tune dialog's "How does it move?" for a vortex. The first is its own way
     * (a whirlpool stays, a small twister wanders, a big one cuts across), so a Crafter who never opens it gets a
     * vortex that moves right; the rest are {@code stage_event}'s {@code path}.
     */
    public record PathChoice(String id, String label, List<String> words) {
    }

    public static final String OWN_WAY = "own";

    public static final List<PathChoice> PATHS = List.of(
            new PathChoice(OWN_WAY, "Its own way", List.of()),
            new PathChoice("stay", "Stays in one spot", List.of("stays in one spot", "in one spot", "stays still",
                    "stays put", "stationary", "does not move", "one place", "stays there")),
            new PathChoice("wander", "Wanders about", List.of("wanders", "wanders around", "moves around", "randomly",
                    "all over the place", "zigzag", "dances around")),
            new PathChoice("line", "Cuts across in a line", List.of("straight line", "in a line", "cuts through",
                    "rips through", "goes across", "straight across", "across the field", "tears through")),
            new PathChoice("circle", "Goes round and round", List.of("round and round", "circles", "goes around",
                    "in a circle", "circling", "orbits", "spins around my")),
            new PathChoice("follow", "Follows me", List.of("follows me", "chases me", "follow me", "chasing me",
                    "comes after me", "following me")));

    /**
     * What a wrecking event spares: the tune dialog's "What does it wreck?" for a wave and every
     * spinning thing. The first is the default — it wrecks everything: smashes through builds (undo rebuilds them) and
     * hurts what it catches, never his pets, his wished creatures or him below one heart. The rest are
     * {@code stage_event}'s {@code spares}.
     */
    public record WreckChoice(String id, String label, List<String> spares, List<String> words) {
    }

    public static final String WRECKS_ALL = "everything";

    public static final List<WreckChoice> WRECKS = List.of(
            new WreckChoice(WRECKS_ALL, "Wrecks everything", List.of(), List.of()),
            new WreckChoice("builds", "Spares my builds", List.of("builds"), List.of("doesnt break my house",
                    "does not break my house", "safe for my house", "safe for my builds", "spares my builds",
                    "wont wreck my house", "doesnt destroy my stuff", "leaves my house alone")),
            new WreckChoice("creatures", "Spares the animals", List.of("creatures"), List.of("doesnt hurt the animals",
                    "does not hurt the animals", "safe for animals", "wont hurt the animals", "spares the animals",
                    "doesnt hurt anyone", "nobody gets hurt")),
            new WreckChoice("nothing", "Totally safe", List.of("builds", "creatures"), List.of("totally safe",
                    "harmless", "safe tornado", "safe twister", "a safe one", "pretend", "gentle", "friendly")));

    /** Whether this Builder motion wrecks what it passes (a wave, anything that spins), so its tune dialog asks what. */
    public static boolean wrecks(String motionId) {
        Motion m = byId(motionId);
        return m != null && (m.motion().equals("vortex") || m.motion().equals("moving_front"));
    }

    /** The choice he picked, or null for the default (it wrecks everything) or anything that is not one. */
    public static WreckChoice wreck(String picked) {
        for (WreckChoice c : WRECKS) {
            if (c.id().equals(picked) && !c.id().equals(WRECKS_ALL)) {
                return c;
            }
        }
        return null;
    }

    /** Whether this Builder motion spins (a vortex), so its tune dialog asks how it moves. */
    public static boolean spins(String motionId) {
        Motion m = byId(motionId);
        return m != null && m.motion().equals("vortex");
    }

    /** A path he picked, or null for its own way (or anything that is not a path). */
    public static String path(String picked) {
        if (picked == null || picked.isEmpty() || picked.equals(OWN_WAY)) {
            return null;
        }
        for (PathChoice p : PATHS) {
            if (p.id().equals(picked)) {
                return p.id();
            }
        }
        return null;
    }

    /**
     * the ready-made events, each the composition it now is. A Gate A test lowers each preset and this
     * composition to the same {@code stage_event} call (BuilderEventComposableTest); a saved make from one migrates
     * to it (SpecMigrations.readyEventComposed).
     */
    public static final java.util.Map<String, String[]> READY_MADES = java.util.Map.of(
            "events/chicken_rain", new String[] {"rain", "minecraft:chicken"},
            "events/tidal_wave", new String[] {"wave", "minecraft:water"},
            "events/meteor", new String[] {"meteor", "minecraft:magma_block"});

    public static Motion byId(String id) {
        for (Motion m : ALL) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static What what(Motion m, String id) {
        for (What w : m.whats()) {
            if (w.id().equals(id)) {
                return w;
            }
        }
        What chicken = creatureWhat(m);
        What f = chicken == null ? null : found(id);
        return f == null ? null : chicken.field() == null ? f : f.as(chicken.field());
    }

    /** the farm-animal choice of a motion that takes creatures (a rain, a crowd, a twister of chickens), or null. */
    static What creatureWhat(Motion m) {
        for (What w : m.whats()) {
            if (w.id().equals("minecraft:chicken")) {
                return w;
            }
        }
        return null;
    }

    /**
     * (R1, a rain of slimes): a motion that drops creatures takes ANY creature installed, found by
     * name (🔍 Find), not just its six farm animals. Never Moddy's own, never a boss (one a wish: no rain of them); how
     * many come is the event's own count, and every spawn still goes through the world's spawn rules.
     */
    /** the motions that drop creatures ("event:rain", "event:crowd"), for a found creature's {@code only}. */
    public static List<String> dropMotions() {
        List<String> out = new java.util.ArrayList<>();
        for (Motion m : ALL) {
            if (creatureWhat(m) != null) {
                out.add("event:" + m.id());
            }
        }
        return out;
    }

    public static What found(String id) {
        if (id == null || !id.contains(":") || id.startsWith(com.moddingfromamod.content.Found.OWN)) {
            return null;
        }
        String name = com.moddingfromamod.content.Found.name(com.moddingfromamod.content.ContentIndex.Kind.MOB, id);
        if (name == null || com.moddingfromamod.mob.SpawnBudget.classify(id, true) == com.moddingfromamod.mob.SpawnBudget.Kind.BOSS) {
            return null;
        }
        String egg = id + "_spawn_egg";
        String tile = com.moddingfromamod.content.Found.name(com.moddingfromamod.content.ContentIndex.Kind.ITEM, egg) != null
                ? egg : "minecraft:paper";
        String many = English.plural(name, true);
        return new What(id, many, tile, List.of(many.toLowerCase(java.util.Locale.ROOT), name.toLowerCase(java.util.Locale.ROOT)));
    }

    /** The words across his screen: "It's raining pigs!", "The ground turns into a bounce pit!". */
    public static String title(String motion, String what) {
        Motion m = byId(motion);
        if (m == null) {
            return "Something happens!";
        }
        What w = what(m, what);
        if (w != null && m.titles().containsKey(w.id())) {
            return m.titles().get(w.id());   //: the ready-made's own words (TIDAL WAVE!)
        }
        String word = w == null ? "" : w.label().toLowerCase(java.util.Locale.ROOT);
        return m.title().contains("%s") ? String.format(m.title(), word) : m.title();
    }

    /**
     * The stage_event arguments (all but name, title, size and count, which the lowering adds for every event).
     * Null when the pair is not one of these.
     */
    public static JsonObject args(String motion, String what) {
        Motion m = byId(motion);
        if (m == null) {
            return null;
        }
        What w = what(m, what);
        if (w == null) {
            return null;
        }
        JsonObject a = new JsonObject();
        a.addProperty("motion", m.motion());
        if (!w.id().equals("minecraft:air")) {
            a.addProperty(w.field() != null ? w.field() : m.field(), w.id());
        }
        if (!m.extras().isEmpty()) {   //: what its ready-made also said (what it catches, what it leaves)
            com.google.gson.JsonParser.parseString(m.extras()).getAsJsonObject().entrySet()
                    .forEach(e -> a.add(e.getKey(), e.getValue().deepCopy()));
        }
        return a;
    }
}
