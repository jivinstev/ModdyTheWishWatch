package com.moddingfromamod.item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.moddingfromamod.datapack.VanillaCarrier;

/**
 * <b>Every sprite the item pack binds, authored BY NAME — the number is derived.</b>
 *
 * <p>The rule it exists for is {@code docs/VERSION_STRATEGY.md} §6.2 in
 * one line:
 *
 * <blockquote><b>If an icon is authored as "number 14 in the carrier's override table", the 1.21.4 port is a
 * rewrite; if it is authored as "a sprite named {@code lamp}", the 1.21.4 port is a second
 * emitter.</b></blockquote>
 *
 * <p>On 1.21.1 a wished thing is one vanilla carrier ({@link VanillaCarrier#ITEM_ID}) whose artwork
 * is picked by {@code custom_model_data} — <i>an integer</i>, matched against one shared
 * {@code overrides} array. On 1.21.4+ the same binding is a {@code minecraft:item_model} component
 * naming {@code assets/&lt;ns&gt;/items/&lt;path&gt;.json}, with no table and no number anywhere.
 * The two dialects agree on exactly one thing — <b>the sprite's name</b> — so that is what this
 * class makes authoritative, and the integer becomes an artefact of the 1.21.1 emitter rather than
 * an input to authoring.
 *
 * <h2>What is actually different from what was here before</h2>
 *
 * <p>{@link WishIcons} used to be 27 typed {@code (number, name)} pairs. It is now
 * {@link #library()} with the numbers <i>derived from position</i>, so adding an icon is adding a
 * NAME to the end of one list. Nothing downstream changed shape: {@code WishIcons.all()} still
 * answers the same 27 pairs, and {@code FROZEN} is the check that says so out loud rather than
 * leaving it to be noticed by a child whose cup turned into a bucket.
 *
 * <h2>Two rules that do not survive being broken</h2>
 *
 * <ol>
 *   <li><b>A number is a contract.</b> A wish stores its icon id on the stack and in its
 *       provenance, so repointing a number silently changes the shape of a thing a child already
 *       owns. Removing a name from the library would shift every name after it by one — which is
 *       precisely the repoint — so a removal is expressed by retiring the slot, which holds its
 *       place forever and hands out nothing.</li>
 *   <li><b>A sprite is named once.</b> Two entries with one name is two pictures claiming one
 *       word, and {@link #byName} would answer whichever came first while the pack bound the
 *       other. It throws at class-init instead.</li>
 * </ol>
 *
 * <p>Pure and Minecraft-free, like every other decision in this repo that is really a decision.
 */
public final class IconSprites {

    private IconSprites() {
    }

    /** The first number the wish library may use. 0 is what a stack with no component reports. */
    public static final int FIRST = 1;

    /**
     * <b>The wish icon library, in minting order. APPEND ONLY.</b>
     *
     * <p>Position is the number: the first entry is {@link #FIRST}. So a name may be added at the
     * end and may never be inserted, reordered or deleted — see {@link #RETIRED_PREFIX} for the way
     * to stop offering one.
     *
     * <p>Which shapes, and why these: {@code docs/ICON_LIBRARY.md}, mined from the wish corpus
     * rather than guessed. 1–16 are CORE (wished for, or built because he asked), 17–24 are cover
     * (seen in the world, never asked for), and 25–27 were added after {@code orb} was
     * measured serving every round noun.
     */
    private static final List<String> LIBRARY = List.of(
            "machine", "block", "cup", "cone", "bottle", "bucket", "bag", "food_round",
            "orb", "wand", "pad", "rocket", "coin", "lamp", "vehicle", "small_pile",
            "sword", "axe", "spear", "bow", "shield", "hammer", "helmet", "key",
            "egg", "bomb", "snowball");

    /**
     * <b>Numbers that were once issued and may never be handed out again.</b>
     *
     * <p>Empty, and that is a fact worth keeping visible rather than deleting: nothing has been
     * retired yet, and the machinery exists so that the first retirement is a one-line edit
     * instead of a renumber. A retired slot keeps its place in the library under a
     * {@link #RETIRED_PREFIX} name so every later icon's number is unmoved.
     */
    private static final List<Integer> RETIRED = List.of();

    /** A retired slot's name begins with this; it binds no artwork and is served to nobody. */
    public static final String RETIRED_PREFIX = "retired_";

    /**
     * <b>The numbers as they shipped</b>, so a derivation can never quietly repoint one.
     *
     * <p>This is the contract from {@link WishIcons}'s own class note, written down as data. It is
     * deliberately a literal and deliberately redundant with the library: the whole risk of
     * deriving a number from a position is that a careless edit to the list moves everything after
     * it, and a check against a second, independently-typed copy is the only thing that catches
     * that. Two sources of truth is the point here, not an oversight.
     */
    private static final Map<String, Integer> FROZEN = frozen();

    private static Map<String, Integer> frozen() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("machine", 1);
        m.put("block", 2);
        m.put("cup", 3);
        m.put("cone", 4);
        m.put("bottle", 5);
        m.put("bucket", 6);
        m.put("bag", 7);
        m.put("food_round", 8);
        m.put("orb", 9);
        m.put("wand", 10);
        m.put("pad", 11);
        m.put("rocket", 12);
        m.put("coin", 13);
        m.put("lamp", 14);
        m.put("vehicle", 15);
        m.put("small_pile", 16);
        m.put("sword", 17);
        m.put("axe", 18);
        m.put("spear", 19);
        m.put("bow", 20);
        m.put("shield", 21);
        m.put("hammer", 22);
        m.put("helmet", 23);
        m.put("key", 24);
        m.put("egg", 25);
        m.put("bomb", 26);
        m.put("snowball", 27);
        return Map.copyOf(m);
    }

    /**
     * Which population a sprite belongs to — the thing the 1.21.1 emitter has to keep apart and the
     * 1.21.4 one does not have to think about at all.
     */
    public enum Band {
        /** A picture a WISH may pick. */
        WISH,
        /** Moddy himself. Reserved above the library, on purpose — {@link VanillaCarrier}. */
        WATCH,
        /** The wish anvil's animated "Wish!" button. Reserved beside the watch. */
        ANVIL,
        /** The Wish Bag in hotbar slot 2. Reserved beside the watch. */
        BAG,
        /** A menu's own picture, never a wish's: the back arrow. Reserved beside the watch. */
        MENU
    }

    /**
     * One sprite: the name it is authored by, the model that draws it, and the number the 1.21.1
     * emitter binds it at.
     *
     * @param name  the word a wish asks for it by, and the file name of its artwork
     * @param model the path under {@code models/item/} — {@code wish/cone}, or {@code wish_watch}
     *              for Moddy, who does not live in the icon folder
     */
    public record Sprite(String name, String model, int customModelData, Band band) {

        public Sprite {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a sprite with no name is a number again");
            }
            if (customModelData < FIRST) {
                throw new IllegalArgumentException(
                        "custom_model_data must be >= " + FIRST + ", was " + customModelData
                                + " — 0 is what a stack with NO custom_model_data reports, so a "
                                + "binding at 0 claims every plain carrier in the world");
            }
        }
    }

    /**
     * MODDY, and deliberately not in the library — the library is the set of pictures a WISH may
     * pick, and a wished thing that came back looking like the thing you wish WITH is the confusion
     * the item spike paid for five times over. He is still a NAMED sprite, which is the whole of
     * The asset rule: on 1.21.4 he is {@code wish_watch}, with no number at all.
     *
     * <p>Added here rather than inside {@code build} so that method is about the LIBRARY's rules
     * and nothing else — the watch's number is fixed by {@link VanillaCarrier} and has no position
     * to derive from.
     */
    private static final Sprite WATCH = new Sprite(VanillaCarrier.WATCH_ICON,
            VanillaCarrier.WATCH_ICON, VanillaCarrier.WATCH_MODEL_DATA, Band.WATCH);

    private static final Sprite WISH_WAND = new Sprite(VanillaCarrier.WISH_WAND_ICON,
            VanillaCarrier.WISH_WAND_ICON, VanillaCarrier.WISH_WAND_MODEL_DATA, Band.ANVIL);

    private static final Sprite WISH_BAG = new Sprite(VanillaCarrier.WISH_BAG_ICON,
            VanillaCarrier.WISH_BAG_ICON, VanillaCarrier.WISH_BAG_MODEL_DATA, Band.BAG);

    private static final Sprite BACK_ARROW = new Sprite(VanillaCarrier.BACK_ARROW_ICON,
            VanillaCarrier.BACK_ARROW_ICON, VanillaCarrier.BACK_ARROW_MODEL_DATA, Band.MENU);

    private static final Sprite LIGHT_BULB = new Sprite(VanillaCarrier.LIGHT_BULB_ICON,
            VanillaCarrier.LIGHT_BULB_ICON, VanillaCarrier.LIGHT_BULB_MODEL_DATA, Band.MENU);

    private static final List<Sprite> ALL = all();

    private static List<Sprite> all() {
        List<Sprite> out = new ArrayList<>(build(LIBRARY, RETIRED, FROZEN));
        // the Muse-drawn library, numbered 28+ in items/icon-library.json. Its numbers are
        // in the file rather than derived from a position, so nothing here can move one; the file
        // is append-only and tools/gen-library-icons.py refuses to drop a name.
        java.util.Set<String> taken = new java.util.HashSet<>(LIBRARY);
        for (LibraryIcon icon : drawn()) {
            if (!taken.add(icon.name())) {
                throw new IllegalStateException("icon '" + icon.name() + "' is named twice");
            }
            if (icon.number() < FIRST + LIBRARY.size() || icon.number() >= VanillaCarrier.WATCH_MODEL_DATA) {
                throw new IllegalStateException("library icon " + icon.name() + " has number "
                        + icon.number() + ", outside " + (FIRST + LIBRARY.size()) + ".."
                        + (VanillaCarrier.WATCH_MODEL_DATA - 1));
            }
            out.add(new Sprite(icon.name(), "wish/" + icon.name(), icon.number(), Band.WISH));
        }
        out.add(WATCH);
        out.add(WISH_WAND);
        out.add(WISH_BAG);
        out.add(BACK_ARROW);
        out.add(LIGHT_BULB);
        return List.copyOf(out);
    }

    /**
     * The whole table, derived.
     *
     * <p><b>Parameterised, and that is the difference between a guard and a claim about one.</b>
     * The three rules below run in a static initialiser, so driving them from a test against the
     * real constants would break the class for the rest of the JVM — which is exactly how a guard
     * ends up asserted-about rather than exercised. Taking the three tables as arguments lets
     * {@code IconSpritesTest} hand it a duplicate, a reissued number and a deletion and watch each
     * one throw, against the same code path the real table walks.
     */
    static List<Sprite> build(List<String> library, List<Integer> retired,
                              Map<String, Integer> frozen) {
        List<Sprite> out = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < library.size(); i++) {
            String name = library.get(i);
            int number = FIRST + i;
            if (name == null || name.isBlank()) {
                throw new IllegalStateException(
                        "icon slot " + number + " has no name. A blank holds the slot but binds "
                                + "nothing; use \"" + RETIRED_PREFIX + number + "\" so the gap is "
                                + "readable in a diff.");
            }
            Integer first = seen.put(name, number);
            if (first != null) {
                throw new IllegalStateException(
                        "icon sprite '" + name + "' is named twice, at " + first + " and "
                                + number + ". One word cannot mean two pictures: byName() would "
                                + "answer " + first + " while the pack bound " + number + ", so a "
                                + "wish would ask for one and wear the other.");
            }
            if (retired.contains(number) && !name.startsWith(RETIRED_PREFIX)) {
                throw new IllegalStateException(
                        "custom_model_data " + number + " is RETIRED and slot " + number
                                + " now names '" + name + "'. A retired number was issued once: a "
                                + "thing already standing in a child's world carries it, and "
                                + "reissuing it repaints that thing. Append '" + name
                                + "' at the end instead.");
            }
            if (name.startsWith(RETIRED_PREFIX)) {
                continue;   // holds its slot, binds nothing, served to nobody
            }
            Integer was = frozen.get(name);
            if (was != null && was != number) {
                throw new IllegalStateException(
                        "icon '" + name + "' shipped as custom_model_data " + was
                                + " and now derives to " + number + ". Something was inserted or "
                                + "removed in the library rather than appended, so every icon after "
                                + "it has moved — which silently changes the shape of every wished "
                                + "thing already standing in a world.");
            }
            out.add(new Sprite(name, "wish/" + name, number, Band.WISH));
        }
        for (Map.Entry<String, Integer> e : frozen.entrySet()) {
            if (!seen.containsKey(e.getKey())) {
                throw new IllegalStateException(
                        "icon '" + e.getKey() + "' shipped at custom_model_data " + e.getValue()
                                + " and is no longer in the library. Deleting a name shifts every "
                                + "name after it; retire the slot instead (see RETIRED_PREFIX).");
            }
        }
        return List.copyOf(out);
    }



    /**
     * <b>One Muse-drawn icon </b>, as {@code items/icon-library.json} lists it.
     *
     * @param kind     {@code tint} takes the wish's colour; {@code fixed} keeps its own (a strawberry)
     * @param accent   it has a second colour, tinted from {@code custom_model_data.colors[0]}
     * @param category what the agent sees it listed under
     */
    public record LibraryIcon(String name, int number, String kind, boolean accent, String category) {
        public boolean tints() {
            return "tint".equals(kind);
        }
    }

    private static volatile List<LibraryIcon> drawn;

    /** Every Muse-drawn icon, ascending by number. Empty if the file is absent. */
    public static List<LibraryIcon> drawn() {
        List<LibraryIcon> d = drawn;
        if (d == null) {
            d = loadDrawn();
            drawn = d;
        }
        return d;
    }

    private static List<LibraryIcon> loadDrawn() {
        try (java.io.InputStream in = IconSprites.class.getResourceAsStream("/items/icon-library.json")) {
            if (in == null) {
                return List.of();
            }
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            List<LibraryIcon> out = new ArrayList<>();
            for (var e : root.getAsJsonArray("icons")) {
                com.google.gson.JsonObject o = e.getAsJsonObject();
                out.add(new LibraryIcon(o.get("name").getAsString(), o.get("number").getAsInt(),
                        o.get("kind").getAsString(), o.get("accent").getAsBoolean(),
                        o.get("category").getAsString()));
            }
            out.sort(java.util.Comparator.comparingInt(LibraryIcon::number));
            return List.copyOf(out);
        } catch (java.io.IOException | RuntimeException e) {
            throw new IllegalStateException("items/icon-library.json is unreadable", e);
        }
    }

    /** The drawn icon by name, or null. */
    public static LibraryIcon drawnByName(String name) {
        if (name == null) {
            return null;
        }
        for (LibraryIcon i : drawn()) {
            if (i.name().equals(name)) {
                return i;
            }
        }
        return null;
    }

    /** The wish icon library, numbers derived — the set a wish may choose from. */
    public static List<Sprite> library() {
        List<Sprite> out = new ArrayList<>();
        for (Sprite s : ALL) {
            if (s.band() == Band.WISH) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    /**
     * <b>Every sprite the item pack must bind, in ascending number order.</b>
     *
     * <p>This is the seam the 1.21.4 port plugs a second emitter into: a dialect iterates NAMES and asks this
     * class what to call each one in its own spelling. {@code WishPackWriter} is the
     * {@code OVERRIDES_34} caller; an {@code ITEM_DEFS_46+} caller would read the same list and
     * never look at {@link Sprite#customModelData()} at all.
     *
     * <p>It does NOT include the atom rig's cubes. Those are {@code AtomLibrary}'s, they are 3D
     * models rather than flat sprites, and {@code VanillaItemPack.Extra} is the shape that carries
     * them; the one thing this class owes that population is a number space it never walks into,
     * which {@link VanillaCarrier#ATOM_MODEL_DATA_FIRST} reserves and {@code AtomLibraryTest} pins.
     */
    public static List<Sprite> bindings() {
        List<Sprite> out = new ArrayList<>(ALL);
        out.sort(java.util.Comparator.comparingInt(Sprite::customModelData));
        return List.copyOf(out);
    }

    /** The sprite a wish asked for by name, or null — never a silent default (playbook §4f). */
    public static Sprite byName(String name) {
        if (name == null) {
            return null;
        }
        String want = name.trim().toLowerCase(Locale.ROOT);
        for (Sprite s : ALL) {
            if (s.name().equals(want)) {
                return s;
            }
        }
        return null;
    }

    /**
     * The number the 1.21.1 emitter binds this name at.
     *
     * @throws IllegalArgumentException if nothing is named that — never a default, for
     *         {@link #byName}'s reason
     */
    public static int numberFor(String name) {
        Sprite s = byName(name);
        if (s == null) {
            throw new IllegalArgumentException(
                    "no sprite is named '" + name + "'. An unknown icon name must be refused and "
                            + "taught, not quietly turned into icon " + FIRST
                            + " — a default is a decision made after the wish text is gone.");
        }
        return s.customModelData();
    }
}
