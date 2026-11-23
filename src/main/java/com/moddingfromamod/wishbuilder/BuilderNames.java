package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>Moddy names what the Builder makes</b>. A child never types or picks a name: the tune dialog has
 * no name control, and the name is worked out here from the finished picks, so a three-storey sunset tower is
 * a "Tall Sunset Tower" rather than "Tower", and a fire-breathing dragon is "Ember the Dragon".
 *
 * <p>Two shapes, by kind:
 * <ul>
 *   <li><b>a thing</b> (a building, a picture, an item, a magic block, a snack): the picks that set it apart,
 *       as describing words in front of its noun ("Tall Domed Sunset Tower", "Duck at Sunset");</li>
 *   <li><b>a creature or a ride</b>: a pet name whose THEME comes from its strongest picks (fire breath, its
 *       colours, flying, its mood…), then "the" and its body ("Frosty the Penguin").</li>
 * </ul>
 *
 * <p>Pure and deterministic: the same picks always make the same name (the choice inside a pet-name pool is a
 * hash of the picks), and {@code taken} — the names already on his shelf — moves it to the next candidate so his
 * things can be told apart; a number is the last resort. Every word comes from a fixed table here or from a
 * reviewed catalogue label, so nothing a model or a player wrote reaches a name (WISH_BUILDER_PLAN §4 rule 5);
 * {@code BuilderNamesTest} runs every table word past the safety lexicon.
 */
public final class BuilderNames {

    /** The longest name, in characters: a name tag and a watch tile both stay readable. */
    public static final int LONGEST = 28;

    private BuilderNames() {
    }

    // ------------------------------------------------------------------ the tables

    /** A colour scheme as a describing word for a THING: its theme first, its plain colour second. */
    static final Map<String, String[]> SCHEME_WORDS = new LinkedHashMap<>();

    /** A colour scheme's pet-name theme. */
    static final Map<String, String> SCHEME_THEME = new LinkedHashMap<>();

    /** Pet names by theme. The first theme a creature's picks favour supplies its name. */
    static final Map<String, List<String>> PET_NAMES = new LinkedHashMap<>();

    static {
        scheme("ocean", "ocean", "Ocean", "Blue");
        scheme("sunset", "sunset", "Sunset", "Orange");
        scheme("forest", "forest", "Forest", "Green");
        scheme("candy", "sweet", "Candy", "Pink");
        scheme("royal", "royal", "Royal", "Purple");
        scheme("ice", "ice", "Icy", "Frosty");
        scheme("lava", "fire", "Lava", "Red");
        scheme("crystal", "crystal", "Crystal", "Violet");
        scheme("slime", "slime", "Slime", "Lime");
        scheme("cloud", "sky", "White", "White");
        scheme("gold", "gold", "Golden", "Gold");
        // the solids, by their own colour word ("Red Dragon"), each with the nearest pet-name theme
        scheme("white", "sky", "White", "White");
        scheme("silver", "crystal", "Silver", "Silver");
        scheme("grey", "pal", "Grey", "Grey");
        scheme("black", "wild", "Black", "Black");
        scheme("red", "fire", "Red", "Red");
        scheme("dark_red", "fire", "Dark Red", "Dark Red");
        scheme("orange", "sunset", "Orange", "Orange");
        scheme("yellow", "gold", "Yellow", "Yellow");
        scheme("lime", "slime", "Lime", "Lime");
        scheme("green", "forest", "Green", "Green");
        scheme("teal", "ocean", "Teal", "Teal");
        scheme("light_blue", "ice", "Light Blue", "Light Blue");
        scheme("blue", "ocean", "Blue", "Blue");
        scheme("navy", "ocean", "Navy", "Navy");
        scheme("purple", "royal", "Purple", "Purple");
        scheme("pink", "sweet", "Pink", "Pink");
        scheme("brown", "forest", "Brown", "Brown");

        pets("fire", "Ember", "Blaze", "Sparky", "Cinder", "Flicker", "Scorch", "Pepper", "Torch", "Fizzle");
        pets("ice", "Frosty", "Snowball", "Flurry", "Icicle", "Blizzard", "Chilly", "Snowdrop", "Sleet");
        pets("flower", "Petal", "Daisy", "Clover", "Blossom", "Poppy", "Tulip", "Buttercup", "Bluebell");
        pets("honey", "Honey", "Buzz", "Nectar", "Butterscotch", "Hunny", "Bumble", "Caramel");
        pets("sweet", "Cupcake", "Sprinkles", "Gumdrop", "Jellybean", "Toffee", "Marshmallow", "Lolly", "Fudge");
        pets("crystal", "Prism", "Gem", "Sparkle", "Quartz", "Jewel", "Glimmer", "Opal", "Twinkle");
        pets("sky", "Zoom", "Skye", "Breeze", "Comet", "Swoop", "Nimbus", "Feather", "Zephyr");
        pets("glow", "Twinkle", "Starlight", "Lumi", "Beacon", "Shimmer", "Glowbug", "Sunbeam", "Flash");
        pets("ocean", "Splash", "Bubbles", "Coral", "Marina", "Puddle", "Tide", "Wave", "Pearl");
        pets("sunset", "Sunny", "Apricot", "Marmalade", "Dusk", "Amber", "Saffron", "Peaches", "Tangerine");
        pets("forest", "Mossy", "Fern", "Sprout", "Willow", "Acorn", "Pine", "Juniper", "Maple");
        pets("royal", "Duke", "Duchess", "Prince", "Princess", "Majesty", "Crown", "Plum", "Velvet");
        pets("slime", "Gloop", "Wobble", "Squish", "Jelly", "Goober", "Slurp", "Splodge", "Blob");
        pets("gold", "Goldie", "Nugget", "Treasure", "Penny", "Coin", "Goldilocks", "Glint", "Sunny");
        pets("bouncy", "Boing", "Bouncer", "Hopper", "Springy", "Pogo", "Ziggy", "Bounce", "Kanga");
        pets("sleepy", "Snooze", "Dozy", "Pillow", "Dreamy", "Snoozy", "Mellow", "Slumber", "Yawn");
        pets("wild", "Rascal", "Ruckus", "Fang", "Rumble", "Chomper", "Grizzle", "Snarl", "Havoc");
        pets("guard", "Captain", "Champ", "Valor", "Sentry", "Boulder", "Ranger", "Major", "Knight");
        pets("shy", "Whisper", "Peep", "Bashful", "Pip", "Wisp", "Hush", "Cuddles", "Timid");
        pets("boss", "Titan", "Chief", "Rex", "Goliath", "Thunder", "Kingpin", "Brutus", "Tyrant");
        pets("tiny", "Peanut", "Button", "Bean", "Squeak", "Nibbles", "Pipsqueak", "Teeny", "Sprinkle");
        pets("giant", "Biggie", "Tank", "Chonky", "Colossus", "Megaton", "Jumbo", "Bigfoot", "Boulder");
        pets("pal", "Buddy", "Pebble", "Biscuit", "Pickles", "Noodle", "Waffles", "Scout", "Ziggy");
        // step 6: "Follows you" and "Stays put"
        pets("sidekick", "Shadow", "Buddy", "Tag", "Scout", "Echo", "Rover", "Sidekick", "Pal");
        pets("homebody", "Patch", "Anchor", "Stumpy", "Moss", "Rocky", "Cozy", "Dozer", "Tuck");
    }

    private static void scheme(String id, String theme, String word, String colour) {
        SCHEME_WORDS.put(id, new String[] {word, colour});
        SCHEME_THEME.put(id, theme);
    }

    private static void pets(String theme, String... names) {
        PET_NAMES.put(theme, List.of(names));
    }

    /** How strongly each pick pulls a creature's name towards a theme: {theme, weight}. */
    static final Map<String, Object[]> PICK_THEMES = Map.ofEntries(
            Map.entry("ability:fire_breath", new Object[] {"fire", 10}),
            Map.entry("ability:frost_breath", new Object[] {"ice", 10}),
            Map.entry("ability:snow_trail", new Object[] {"ice", 7}),
            Map.entry("ability:flower_trail", new Object[] {"flower", 7}),
            Map.entry("ability:honey", new Object[] {"honey", 7}),
            Map.entry("ability:treats", new Object[] {"sweet", 6}),
            Map.entry("ability:spikes", new Object[] {"crystal", 7}),
            Map.entry("ability:knock_away", new Object[] {"guard", 4}),
            Map.entry("power:flies", new Object[] {"sky", 8}),
            Map.entry("power:glowing", new Object[] {"glow", 6}),
            Map.entry("power:hops", new Object[] {"bouncy", 5}),
            Map.entry("power:leaps", new Object[] {"bouncy", 5}),
            Map.entry("power:naps", new Object[] {"sleepy", 4}),
            Map.entry("power:fireproof", new Object[] {"fire", 3}),
            Map.entry("temper:wild", new Object[] {"wild", 6}),
            Map.entry("temper:guard", new Object[] {"guard", 5}),
            Map.entry("temper:shy", new Object[] {"shy", 5}),
            Map.entry("temper:boss", new Object[] {"boss", 7}),
            Map.entry("temper:pet", new Object[] {"sidekick", 5}),
            Map.entry("temper:stays", new Object[] {"homebody", 5}),
            Map.entry("kit:wing", new Object[] {"sky", 4}),
            Map.entry("kit:feather_wings", new Object[] {"sky", 4}),
            Map.entry("kit:bat_wings", new Object[] {"sky", 4}),
            Map.entry("kit:butterfly_wings", new Object[] {"sky", 4}),
            Map.entry("kit:dragon_wings", new Object[] {"fire", 4}),
            Map.entry("kit:fin", new Object[] {"ocean", 4}),
            Map.entry("kit:fish_tail", new Object[] {"ocean", 4}),
            Map.entry("kit:spike_row", new Object[] {"crystal", 3}));

    /** A body's own pull on its name's theme ("Fire Guardian" is not a "Frosty"), by its label, lower case. */
    static final Map<String, String> BODY_THEMES = Map.ofEntries(
            Map.entry("fire guardian", "fire"), Map.entry("phoenix", "fire"), Map.entry("pet dragon", "fire"),
            Map.entry("fluffy yeti", "ice"), Map.entry("penguin", "ice"),
            Map.entry("dolphin", "ocean"), Map.entry("big blue whale", "ocean"), Map.entry("great white shark", "ocean"),
            Map.entry("octopus", "ocean"), Map.entry("red crab", "ocean"), Map.entry("glowing pink jellyfish", "ocean"),
            Map.entry("yellow submarine", "ocean"), Map.entry("speed boat", "ocean"), Map.entry("turtle", "ocean"),
            Map.entry("captain puff", "ocean"),
            Map.entry("eagle", "sky"), Map.entry("brown owl", "sky"), Map.entry("sky plane", "sky"),
            Map.entry("helicopter", "sky"), Map.entry("hot air balloon", "sky"), Map.entry("griffin", "sky"),
            Map.entry("rainbow butterfly", "sky"), Map.entry("flying saucer", "sky"), Map.entry("rocket ship", "sky"));

    static final int BODY_WEIGHT = 6;

    /** A colour scheme's weight on a creature's theme: what he sees first. */
    static final int SCHEME_WEIGHT = 8;

    /** A part as a word in front of a body ("the Winged Pig"), when it fits. */
    static final Map<String, String> KIT_WORDS = Map.ofEntries(
            Map.entry("horn", "Horned"), Map.entry("antlers", "Antlered"), Map.entry("ram_horns", "Horned"),
            Map.entry("wing", "Winged"), Map.entry("feather_wings", "Winged"), Map.entry("bat_wings", "Winged"),
            Map.entry("butterfly_wings", "Winged"), Map.entry("dragon_wings", "Winged"),
            Map.entry("spike_row", "Spiky"), Map.entry("fluffy_tail", "Fluffy"), Map.entry("mane", "Maned"),
            Map.entry("shell", "Shelled"), Map.entry("tusks", "Tusked"), Map.entry("claw", "Clawed"),
            Map.entry("spider_legs", "Leggy"), Map.entry("insect_legs", "Leggy"), Map.entry("fin", "Finned"),
            Map.entry("peacock_tail", "Fancy"), Map.entry("crest", "Crested"));

    /** A body's noun where its label's last word is not one ("Captain Puff", "Person"), by label, lower case. */
    static final Map<String, String> BODY_NOUNS = Map.ofEntries(
            Map.entry("captain puff", "Pufferfish"), Map.entry("person", "Hero"), Map.entry("flying saucer", "UFO"),
            Map.entry("hot air balloon", "Balloon"), Map.entry("velociraptor", "Raptor"), Map.entry("t rex", "T Rex"),
            Map.entry("giant t rex", "T Rex"), Map.entry("rocket ship", "Rocket"), Map.entry("sky plane", "Plane"),
            Map.entry("fire guardian", "Fire Guardian"), Map.entry("long neck dinosaur", "Dinosaur"));

    /**
     * A pick as a describing word in front of a creature's body ("Blaze the GREEN Submarine"): the word the name
     * adds beside its pet name, so the second thing he chose shows too.
     */
    static final Map<String, String> PICK_WORDS = Map.ofEntries(
            Map.entry("ability:fire_breath", "Fiery"), Map.entry("ability:frost_breath", "Frosty"),
            Map.entry("ability:snow_trail", "Snowy"), Map.entry("ability:flower_trail", "Flowery"),
            Map.entry("ability:honey", "Honey"), Map.entry("ability:treats", "Sweet"),
            Map.entry("ability:spikes", "Spiky"), Map.entry("ability:knock_away", "Mighty"),
            Map.entry("power:flies", "Flying"), Map.entry("power:glowing", "Glowing"), Map.entry("power:hops", "Hoppy"),
            Map.entry("power:leaps", "Pouncy"), Map.entry("power:naps", "Sleepy"),
            Map.entry("temper:wild", "Wild"), Map.entry("temper:guard", "Brave"), Map.entry("temper:shy", "Shy"),
            Map.entry("temper:boss", "Boss"));

    /** A colour scheme as a plain colour word ("the Green Submarine"). */
    static String colourWord(String scheme) {
        String[] w = SCHEME_WORDS.get(scheme);
        return w == null ? "" : scheme.equals("ice") ? "Icy" : scheme.equals("gold") ? "Golden" : w[1];
    }

    /** A part that turns a body into another creature's name: {body word, part, new noun}. */
    static final List<String[]> BECOMES = List.of(
            new String[] {"horse", "unicorn_horn", "Unicorn"},
            new String[] {"pony", "unicorn_horn", "Unicorn"},
            new String[] {"horse", "feather_wings", "Pegasus"},
            new String[] {"horse", "wing", "Pegasus"},
            new String[] {"pony", "feather_wings", "Pegasus"});

    /** A simple building's noun by its floors: {type, one floor, two, three}. */
    static final Map<String, String[]> HOUSE_NOUNS = Map.of(
            "house", new String[] {"Cabin", "House", "Mansion"},
            "cottage", new String[] {"Cottage", "Cottage", "Cottage"},
            "tower", new String[] {"Tower", "Tower", "Tower"},
            "barn", new String[] {"Barn", "Barn", "Barn"},
            "shop", new String[] {"Shop", "Shop", "Shop"});

    /** A size (1–4) in front of a thing: nothing at the usual size. */
    static final String[] SIZE_WORDS = {"Little", "", "Big", "Giant"};

    static final Map<String, String> ROOF_WORDS = Map.of("dome", "Domed", "pointed", "Pointy");

    static final Map<String, String> WINDOW_WORDS = Map.of("lots", "Sunny");

    static final Map<String, String> OUTSIDE_WORDS = Map.of("pool", "Poolside", "garden", "Garden", "lamps", "Lamplit");

    /** A picture's background as words after its subject: "Duck at Sunset". */
    static final Map<String, String> BACKGROUND_WORDS = Map.of(
            "sky", "in the Sky", "night", "at Night", "hill", "on a Hill", "sunset", "at Sunset");

    /** A snack's power as a word in front of the food ("Bouncy Apple"); a tile's label is not an adjective. */
    static final Map<String, String> TREAT_WORDS = Map.ofEntries(
            Map.entry("treat_speed", "Zoomy"), Map.entry("treat_jump", "Bouncy"), Map.entry("treat_heal", "Cosy"),
            Map.entry("treat_strong", "Mighty"), Map.entry("treat_filling", "Hearty"), Map.entry("treat_underwater", "Scuba"),
            Map.entry("speed", "Zoomy"), Map.entry("jump", "Bouncy"), Map.entry("heal", "Healing"),
            Map.entry("giant", "Mega"), Map.entry("tiny", "Shrinky"), Map.entry("shield", "Brave"), Map.entry("fly", "Rocket"),
            Map.entry("glow", "Glowing"), Map.entry("slushie", "Slushie"));


    /** A strength (1–3) in front of an item or a block. */
    static final String[] STRENGTH_WORDS = {"", "", "Super"};

    // ------------------------------------------------------------------ the name

    /**
     * Moddy's name for {@code spec} (its own name is ignored), different from every name in {@code taken}
     * (ignoring case). A rule and an event have none: a rule is "your new rule", and an event is called by its
     * preset's own title ("It's raining chickens!", {@link BuilderEvents#title}), which no name may replace.
     */
    public static String name(BuilderSpec spec, LibraryPresets presets, Collection<String> taken) {
        if (spec.kind() == BuilderSpec.Kind.RULE || spec.kind() == BuilderSpec.Kind.EVENT
                || spec.kind() == BuilderSpec.Kind.SUPPLIES) {   //: supplies are called what they are
            return "";   // a rule is "your new rule"; an event is called by its own title, said across his screen
        }
        Set<String> used = new java.util.HashSet<>();
        if (taken != null) {
            taken.forEach(t -> used.add(t.strip().toLowerCase(Locale.ROOT)));
        }
        List<String> all = candidates(spec, presets);
        for (String c : all) {
            if (!used.contains(c.toLowerCase(Locale.ROOT))) {
                return c;
            }
        }
        String first = all.isEmpty() ? "Thing" : all.get(0);
        for (int n = 2; ; n++) {
            String numbered = fit(first, " " + n);
            if (!used.contains(numbered.toLowerCase(Locale.ROOT))) {
                return numbered;
            }
        }
    }

    /** Every name Moddy would give {@code spec}, best first, each a valid name. Never empty for a non-rule. */
    public static List<String> candidates(BuilderSpec spec, LibraryPresets presets) {
        List<String> raw = switch (spec.kind()) {
            case CREATURE, RIDE -> creature(spec, presets);
            case BUILDING -> building(spec, presets);
            case PICTURE -> picture(spec, presets);
            case ITEM -> item(spec);
            case MAGIC_BLOCK -> block(spec);
            case MACHINE -> machine(spec, presets);
            case EVENT, RULE, SUPPLIES -> List.of();   //: supplies are what they are ("16 oak logs")
        };
        Set<String> out = new LinkedHashSet<>();
        for (String r : raw) {
            String c = clean(r);
            if (valid(c)) {
                out.add(c);
            }
        }
        if (out.isEmpty() && spec.kind() != BuilderSpec.Kind.RULE && spec.kind() != BuilderSpec.Kind.EVENT
                && spec.kind() != BuilderSpec.Kind.SUPPLIES) {
            String fallback = clean(BuilderLowering.label(unnamed(spec)));
            out.add(valid(fallback) ? fallback : cap(spec.kind().id().replace('_', ' ')));
        }
        return new ArrayList<>(out);
    }

    /** Is {@code name} one Moddy gives {@code spec} (any candidate, or one with a number on the end)? */
    public static boolean isModdys(BuilderSpec spec, LibraryPresets presets, String name) {
        String n = name == null ? "" : name.strip().replaceFirst(" [0-9]+$", "");
        for (String c : candidates(spec, presets)) {
            if (c.equalsIgnoreCase(n) || fit(c, "").equalsIgnoreCase(n) || n.length() < c.length() && c.startsWith(n + " ")) {
                return true;
            }
        }
        return false;
    }

    /** Is {@code name} one Moddy could give: letters and single spaces, 1 to {@link #LONGEST} characters? */
    public static boolean valid(String name) {
        return name != null && name.length() <= LONGEST && name.matches("[A-Za-z]+( [A-Za-z0-9]+)*");
    }

    // ------------------------------------------------------------------ things: describing words + a noun

    /** A describing word with its place in English order (size, look, colour) and how much it tells. */
    private record Word(String text, int order) {
    }

    /**
     * Names from a noun and its describing words, which are in PRIORITY order (the most telling first): the
     * best two, then each alone, then the next pairs, then the noun alone. Each name's words are in English
     * order (size before shape before colour), so it reads "Tall Domed Sunset Tower".
     */
    private static List<String> described(String noun, List<Word> words, String after) {
        List<Word> w = words.stream().filter(x -> x != null && !x.text().isEmpty()).toList();
        List<String> out = new ArrayList<>();
        if (w.size() >= 3) {
            out.add(phrase(noun, List.of(w.get(0), w.get(1), w.get(2)), after));
        }
        for (int i = 0; i < w.size(); i++) {
            for (int j = i + 1; j < w.size(); j++) {
                out.add(phrase(noun, List.of(w.get(i), w.get(j)), after));
            }
        }
        // The full three first only when it fits; otherwise the best pair leads.
        if (w.size() >= 3 && !valid(clean(out.get(0)))) {
            out.remove(0);
        }
        for (Word x : w) {
            out.add(phrase(noun, List.of(x), after));
        }
        out.add(phrase(noun, List.of(), after));
        return out;
    }

    private static String phrase(String noun, List<Word> words, String after) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(java.util.Comparator.comparingInt(Word::order));
        StringBuilder b = new StringBuilder();
        for (Word x : sorted) {
            b.append(x.text()).append(' ');
        }
        b.append(noun);
        if (after != null && !after.isEmpty()) {
            b.append(' ').append(after);
        }
        return b.toString();
    }

    private static final int SIZE = 0;
    private static final int POWER = 1;
    private static final int SHAPE = 2;
    private static final int COLOUR = 3;
    private static final int PLACE = 4;

    private static Word scheme(BuilderSpec spec, int which) {
        String[] w = SCHEME_WORDS.get(spec.scheme());
        return w == null ? null : new Word(w[which], COLOUR);
    }

    private static Word size(int size) {
        return size >= 1 && size <= SIZE_WORDS.length ? new Word(SIZE_WORDS[size - 1], SIZE) : null;
    }

    private static List<String> building(BuilderSpec spec, LibraryPresets presets) {
        List<String> out = new ArrayList<>();
        List<String> outside = BuilderBuildings.parse(spec.opt("outside"));
        Word around = null;
        for (String o : List.of("pool", "garden", "lamps")) {
            if (outside.contains(o)) {
                around = new Word(OUTSIDE_WORDS.get(o), PLACE);
                break;
            }
        }
        if (BuilderHouses.composed(spec)) {
            BuilderHouses.Simple s = BuilderHouses.simple(spec.opt("type"));
            int floors = Math.max(1, Math.min(3, num(spec.opt("floors"), 1)));
            String[] nouns = HOUSE_NOUNS.getOrDefault(s.id(), new String[] {s.noun(), s.noun(), s.noun()});
            String noun = nouns[floors - 1];
            // A storey count is told by the noun where the noun changes (Cabin, House, Mansion), else by a word.
            Word tall = noun.equals(nouns[1]) && floors != 2 ? new Word(floors == 3 ? "Tall" : "Little", SIZE) : null;
            Word roof = ROOF_WORDS.containsKey(spec.opt("roof")) ? new Word(ROOF_WORDS.get(spec.opt("roof")), SHAPE) : null;
            Word windows = WINDOW_WORDS.containsKey(spec.opt("windows"))
                    ? new Word(WINDOW_WORDS.get(spec.opt("windows")), SHAPE) : null;
            out.addAll(described(noun, list(tall, scheme(spec, 0), roof, around, windows), ""));
            out.addAll(described(noun, list(tall, scheme(spec, 1), roof, windows), ""));
            return out;
        }
        String noun = "";
        if (BuilderBuildings.composed(spec)) {
            BuilderBuildings.Type t = BuilderBuildings.type(spec.opt("type"));
            noun = title(t.label().replaceFirst("^(A|An) ", ""));
        } else if (!spec.from().isEmpty()) {
            noun = title(presets.label(Catalogue.bare(spec.from())));
        }
        if (noun.isEmpty()) {
            noun = "Building";
        }
        out.addAll(described(noun, list(size(spec.size()), scheme(spec, 0), around), ""));
        out.addAll(described(noun, list(size(spec.size()), scheme(spec, 1)), ""));
        return out;
    }

    private static List<String> picture(BuilderSpec spec, LibraryPresets presets) {
        String subject = BuilderPictures.composed(spec) ? BuilderPictures.subject(spec.opt("subject")) : Catalogue.bare(spec.from());
        String noun = subject == null || subject.isEmpty() ? "Picture" : title(presets.label(subject));
        String behind = BACKGROUND_WORDS.getOrDefault(spec.opt("background"), "");
        int frame = num(spec.opt("frame"), 0);
        Word big = frame >= BuilderPictures.BIGGEST ? new Word("Big", SIZE)
                : frame == BuilderPictures.SMALLEST ? new Word("Little", SIZE) : null;
        List<String> out = new ArrayList<>();
        if (namesAColour(noun)) {
            // "Golden Red Dragon" was judged a contradiction: a subject that says its own colour keeps it.
            if (!behind.isEmpty()) {
                out.addAll(described(noun, list(big), behind));
            }
            out.addAll(described(noun, list(big), "Picture"));
            out.addAll(described(noun, list(scheme(spec, 0), big), "Picture"));
            return out;
        }
        if (!behind.isEmpty()) {
            out.addAll(described(noun, list(scheme(spec, 0), big), behind));
        }
        out.addAll(described(noun, list(scheme(spec, 0), big), "Picture"));
        out.addAll(described(noun, list(scheme(spec, 1), big), "Picture"));
        return out;
    }

    static boolean namesAColour(String s) {
        for (String w : s.toLowerCase(Locale.ROOT).split(" ")) {
            if (Set.of("red", "blue", "green", "yellow", "pink", "purple", "orange", "white", "black", "brown", "golden",
                    "gold", "rainbow", "grey", "gray").contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> item(BuilderSpec spec) {
        BuilderItemPowers.Stock stock = BuilderItemPowers.of(spec);
        BuilderItemPowers.Power power = BuilderItemPowers.power(spec.opt("power"));
        String noun = stock == null ? "Magic Item" : stock.noun();
        String word = power == null ? "" : power.word();
        BuilderLooks.Look look = BuilderLooks.look(spec.opt(BuilderLooks.SLOT));
        if (look != null) {   //: the look is what it IS ("Crown"); with no power, the look alone names it
            noun = look.label();
            word = power != null && power.id().equals(BuilderLooks.SHOW) ? "" : word;
        }
        if (power != null && power.id().equals(BuilderTurnInto.POWER) && BuilderTurnInto.to(spec.opt(BuilderTurnInto.TO_SLOT)) != null) {
            word = BuilderTurnInto.to(spec.opt(BuilderTurnInto.TO_SLOT)).word();   //: "Midas Wand", by what it makes
        }
        Word strong = strength(spec);
        List<String> out = new ArrayList<>();
        // The power names it ("Lightning Crossbow"), "Super" at strength 3. Its colours only tell two apart:
        // judged, a colour in front read as clutter ("Lime Fire Snowball") more often than it helped.
        String core = word.isEmpty() ? noun : word + " " + noun;
        Word colour = colourWord(spec.scheme()).isEmpty() ? null : new Word(colourWord(spec.scheme()), COLOUR);
        out.add(core);
        out.addAll(described(core, list(strong), ""));
        out.addAll(described(core, list(strong, colour), ""));
        out.addAll(described(core, list(scheme(spec, 0)), ""));
        return out;
    }

    private static Word strength(BuilderSpec spec) {
        int s = Math.max(1, Math.min(3, num(spec.opt("strength"), 2)));
        return new Word(STRENGTH_WORDS[s - 1], SIZE);
    }

    private static List<String> block(BuilderSpec spec) {
        if (!BuilderBlocks.composed(spec)) {
            return List.of(BuilderLowering.label(unnamed(spec)));
        }
        BuilderBlocks.Look look = BuilderBlocks.look(spec.opt("block_look"));
        BuilderBlocks.Does does = BuilderBlocks.does(spec.opt("block_does"));
        String core = (does == null ? "Magic" : does.word()) + " " + look.label();
        List<String> out = new ArrayList<>();
        // The look IS its material: a colour word in front ("Sunset Gold Block") only tells two apart.
        // "Super" (strength 3) was judged filler in front: it only tells two apart.
        out.add(core + " Block");
        out.addAll(described(core + " Block", list(strength(spec)), ""));
        out.addAll(described(core + " Block", list(scheme(spec, 0)), ""));
        out.addAll(described(core + " Block", list(scheme(spec, 1)), ""));
        return out;
    }

    /** A snack machine's name is its SNACK's: the treats in front of the food ("Bouncy Apple"). */
    private static List<String> machine(BuilderSpec spec, LibraryPresets presets) {
        if (!BuilderSnacks.composed(spec)) {
            return List.of(BuilderLowering.label(unnamed(spec)));
        }
        BuilderSnacks.Look look = BuilderSnacks.look(spec.opt("look"));
        List<Word> treats = new ArrayList<>();
        for (String p : BuilderSnacks.chosenPowers(spec)) {
            String w = TREAT_WORDS.get(p);
            if (w == null) {
                BuilderItemPowers.Power power = BuilderItemPowers.power(p);
                w = power == null || power.word().isEmpty() || power.id().equals("yummy") ? null : power.word();
            }
            String word = w;
            if (word != null && treats.stream().noneMatch(t -> t.text().equals(word))) {
                treats.add(new Word(word, POWER + treats.size()));
            }
        }
        Word strong = strength(spec);
        List<String> out = new ArrayList<>();
        // ONE word in front of the food ("Bouncy Apple Machine"): judged, two read as a pile, three as word salad and
        // "Magic" as nothing at all. The second word, and "Super" for strength 3, only tell two of his apart.
        if (!treats.isEmpty()) {
            out.add(phrase(look.label(), List.of(treats.get(0)), ""));
            out.addAll(described(look.label(), list(treats.get(0), strong), ""));
        }
        if (treats.size() > 1) {
            out.add(phrase(look.label(), List.of(treats.get(1)), ""));
            out.add(phrase(look.label(), List.of(treats.get(0), treats.get(1)), ""));
        }
        out.add(look.label());
        return out;
    }

    // ------------------------------------------------------------------ creatures: a pet name and its body

    private static List<String> creature(BuilderSpec spec, LibraryPresets presets) {
        List<String> kits = new ArrayList<>();
        for (Map.Entry<String, String> e : spec.opts().entrySet()) {
            if (e.getKey().startsWith("kit")) {
                kits.add(e.getValue());
            }
        }
        String body = body(spec, presets, kits);
        List<String> themes = themes(spec, kits, bodyLabel(spec, presets));
        long seed = seed(spec);
        List<String> out = new ArrayList<>();
        for (String theme : themes) {
            List<String> pool = PET_NAMES.get(theme);
            int start = (int) Math.floorMod(seed, pool.size());
            List<String> words = describing(spec, kits, theme, body);
            for (int i = 0; i < pool.size(); i++) {
                String pet = pool.get((start + i) % pool.size());
                if (pet.equalsIgnoreCase(body)) {
                    continue;
                }
                if (!body.isEmpty()) {
                    for (String w : words) {
                        if (!w.equalsIgnoreCase(pet)) {
                            out.add(pet + " the " + w + " " + body);
                        }
                    }
                    out.add(pet + " the " + body);
                }
                out.add(pet);
            }
        }
        return out;
    }

    /**
     * The words that can stand in front of a creature's body, best first: the strongest pick whose theme is NOT
     * the pet name's (so the name tells two things: "Blaze the Green Submarine" breathes fire and is green), then
     * its colours, then the rest. A word the body already says ("the Flying Plane") is left out.
     */
    static List<String> describing(BuilderSpec spec, List<String> kits, String petTheme, String body) {
        record Pick(String word, String theme, int weight) {
        }
        List<Pick> picks = new ArrayList<>();
        String colour = colourWord(spec.scheme());
        if (!colour.isEmpty()) {
            picks.add(new Pick(colour, SCHEME_THEME.get(spec.scheme()), SCHEME_WEIGHT));
        }
        List<String> ids = new ArrayList<>(spec.powers());
        if (!spec.temper().isEmpty()) {
            ids.add("temper:" + spec.temper());
        }
        for (String id : ids) {
            Object[] t = PICK_THEMES.get(id);
            if (PICK_WORDS.containsKey(id) && t != null) {
                picks.add(new Pick(PICK_WORDS.get(id), (String) t[0], (Integer) t[1]));
            }
        }
        for (String k : kits) {
            String w = KIT_WORDS.get(k.replaceFirst("^kit:", ""));
            if (w != null) {
                Object[] t = PICK_THEMES.get(k);
                picks.add(new Pick(w, t == null ? "" : (String) t[0], 3));
            }
        }
        if (spec.size() <= 1) {
            picks.add(new Pick("Tiny", "tiny", 5));
        } else if (spec.size() >= 4) {
            picks.add(new Pick("Giant", "giant", 5));
        }
        picks.sort((a, b) -> b.weight() - a.weight());
        Set<String> out = new LinkedHashSet<>();
        String bodyLower = body.toLowerCase(Locale.ROOT);
        for (boolean other : new boolean[] {true, false}) {
            for (Pick p : picks) {
                boolean differs = !p.theme().equals(petTheme);
                // The pet name already says its own theme: only a colour may repeat it ("Sparky the Red Dragon").
                boolean says = differs || p.word().equals(colour);
                if (differs == other && says && !bodyLower.contains(p.word().toLowerCase(Locale.ROOT))) {
                    out.add(p.word());
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** The themes a creature's picks favour, strongest first, ending with the everyday one. */
    static List<String> themes(BuilderSpec spec, List<String> kits, String bodyLabel) {
        Map<String, Integer> score = new LinkedHashMap<>();
        String bodyTheme = BODY_THEMES.get(bodyLabel.toLowerCase(Locale.ROOT));
        if (bodyTheme != null) {
            score.merge(bodyTheme, BODY_WEIGHT, Integer::sum);
        }
        if (!spec.scheme().isEmpty() && SCHEME_THEME.containsKey(spec.scheme())) {
            score.merge(SCHEME_THEME.get(spec.scheme()), SCHEME_WEIGHT, Integer::sum);
        }
        List<String> picks = new ArrayList<>(spec.powers());
        if (!spec.temper().isEmpty()) {
            picks.add("temper:" + spec.temper());
        }
        picks.addAll(kits);
        for (String p : picks) {
            Object[] t = PICK_THEMES.get(p);
            if (t != null) {
                score.merge((String) t[0], (Integer) t[1], Integer::sum);
            }
        }
        if (spec.size() <= 1) {
            score.merge("tiny", 5, Integer::sum);
        } else if (spec.size() >= 4) {
            score.merge("giant", 5, Integer::sum);
        }
        List<String> out = new ArrayList<>(score.keySet());
        out.sort((a, b) -> score.get(b) - score.get(a));   // stable: ties keep the order they were met
        out.add("pal");
        return out;
    }

    /** The body's own noun: the last word of its label ("Tiny Hamster" → "Hamster"), or what a part makes it. */
    /** The body's label as the menu shows it ("Fire Guardian"), or empty. */
    static String bodyLabel(BuilderSpec spec, LibraryPresets presets) {
        String from = Catalogue.bare(spec.from());
        if (from == null || from.isEmpty()) {
            return "";
        }
        return Catalogue.prefix(spec.from()).equals("mob")
                ? from.substring(from.indexOf(':') + 1).replace('_', ' ') : presets.label(from);
    }

    static String body(BuilderSpec spec, LibraryPresets presets, List<String> kits) {
        String clean = bodyLabel(spec, presets).replaceAll("[^A-Za-z ]", " ").replaceAll("\\s+", " ").strip();
        if (clean.isEmpty()) {
            return "";
        }
        String noun = BODY_NOUNS.getOrDefault(clean.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "),
                title(clean.substring(clean.lastIndexOf(' ') + 1)));
        for (String[] b : BECOMES) {
            if (noun.equalsIgnoreCase(b[0]) && kits.stream().anyMatch(k -> k.endsWith(":" + b[1]))) {
                return b[2];
            }
        }
        return noun;
    }

    /** A stable number from the picks (never the name), so the same picks make the same name. */
    static long seed(BuilderSpec spec) {
        String s = spec.kind().id() + "|" + spec.from() + "|" + spec.scheme() + "|" + spec.size() + "|"
                + String.join(",", spec.powers()) + "|" + spec.temper() + "|" + new java.util.TreeMap<>(spec.opts());
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h ^ (h >>> 29);
    }

    // ------------------------------------------------------------------ small helpers

    private static BuilderSpec unnamed(BuilderSpec s) {
        return s.withName("");
    }

    @SafeVarargs
    private static <T> List<T> list(T... xs) {
        List<T> out = new ArrayList<>();
        for (T x : xs) {
            if (x != null) {
                out.add(x);
            }
        }
        return out;
    }

    /** Letters, digits and single spaces only; each word capitalised. */
    static String clean(String s) {
        String t = s == null ? "" : s.replace('-', ' ').replaceAll("[^A-Za-z0-9 ]", "").replaceAll("\\s+", " ").strip();
        return title(t);
    }

    /** Each word capitalised, small joining words kept small ("Duck at Sunset", "Ember the Dragon"). */
    static String title(String s) {
        StringBuilder b = new StringBuilder();
        String[] words = s.strip().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) {
                continue;
            }
            boolean small = i > 0 && Set.of("the", "at", "on", "in", "a", "of").contains(w.toLowerCase(Locale.ROOT));
            b.append(i == 0 ? "" : " ").append(small ? w.toLowerCase(Locale.ROOT)
                    : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return b.toString();
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** {@code base} with {@code tail} on the end, the base cut back so the whole stays a valid length. */
    private static String fit(String base, String tail) {
        String b = base;
        while (b.length() + tail.length() > LONGEST && b.contains(" ")) {
            b = b.substring(0, b.lastIndexOf(' '));
        }
        return b + tail;
    }

    private static int num(String s, int dflt) {
        try {
            return s == null || s.isEmpty() ? dflt : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
