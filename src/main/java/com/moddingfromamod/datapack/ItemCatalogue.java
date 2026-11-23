package com.moddingfromamod.datapack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>Ready-made ITEMS the agent selects instead of authoring.</b>
 *
 * <p>Design, evidence, and the check that nearly stopped this being built at all:
 * docs/ITEM_CATALOGUE_PLAN.md and docs/ITEM_CATALOGUE_KILLCHECK.md. The short version is that three
 * of the four axes of an item — icon, colour, shape — already shipped, and only the EFFECT was
 * hand-authored on every wish.
 *
 * <p><b>It is a cross product, and that is the whole reason it is cheap.</b> An effect body says
 * what happens; a shape wrapper says when and where. 16 × 3 = 48 combinations out of 19 files,
 * because the two axes are genuinely independent — the same "set it on fire" body works whether it
 * fires from a right-click, from an impact, or from a thing sitting on the ground. If a change ever
 * makes an effect need to know its own shape, that is the design breaking, not a small exception:
 * the templates would start multiplying per combination and "19 files cover 48 items" stops being
 * true. {@code ItemMatrixGameTest} compiles every cell, so it fails loudly rather than quietly.
 *
 * <p>Like {@link EffectCatalogue} this half has <b>no Minecraft imports</b>, so the substitution
 * rules — the part that silently produces an item aimed at the wrong thing when wrong — are
 * unit-testable without booting a game. And like it, <b>every failure is loud</b>: unknown effect,
 * unknown shape, unknown parameter and unfilled placeholder all throw. A template rendered with
 * {@code ${TARGET}} still in it compiles perfectly and does nothing.
 */
public final class ItemCatalogue {

    private static final String DIR = "/items/";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z_]+)}");

    /**
     * <b>A thing he puts down that BECOMES a thing</b> — a body he can see, a handle he can pull.
     *
     * <p>Distinct from {@code placed}, and the distinction is the child's: a trap or a healing
     * totem works by ITSELF on a cadence and wants nothing in the way, while a machine is
     * something you walk up to and operate. Keeping them apart is also what makes the shape safe
     * to add — every placed wish already granted stays exactly as it was.
     *
     * <p>The mechanism is the placeable-things spike, generalised: vanilla drops a tagged marker on
     * the cell he aimed at and turns it to face him, a tick builds the body around it, and a lever
     * under our own sign is what runs the effect. See {@code items/shape_machine.cbs} and
     * docs/SPIKE_PLACEABLE_MECHANISM.md.
     */
    public static final String MACHINE = "machine";

    /**
     * Every shape that FIRES AN EFFECT, and the wrapper each is built from.
     *
     * <p>This is the list the effect matrix is a product over — {@code effects x EFFECT_SHAPES} —
     * so a new trigger grows it and every effect gets compiled in it for free.
     */
    public static final List<String> EFFECT_SHAPES = effectShapes();

    /**
     * The five trigger shapes, plus the four WORN ones where this Minecraft can put a wished thing on
     * the body ({@link com.moddingfromamod.compat.Wearables}). On the 1.21.1 compat build they
     * are absent, so every matrix over this list stays what it was there, and {@code make_item}
     * refuses a worn shape by name ({@link WornShape}).
     */
    private static List<String> effectShapes() {
        List<String> out = new java.util.ArrayList<>(List.of("held", "thrown", "placed", "on_hit", MACHINE,
                MagicBlock.SHAPE,
                "arrow", "on_mine", "on_shot", "on_consume", "on_block", "on_throw_land", "on_use_done"));
        if (com.moddingfromamod.compat.Wearables.SUPPORTED) {
            out.addAll(WornShape.SHAPES);
        }
        return List.copyOf(out);
    }

    /**
     * A shape for an item that DOES NOTHING, and never fires anything at all.
     *
     * <p>Not a trigger, which is the whole point: it is the absence of one. Most of what a child
     * asks for around a machine is an ingredient — a syrup, a topping, a shot of something — and
     * those items have no use behaviour and are not supposed to. Their meaning is their identity:
     * a machine reads them, or he sorts them.
     *
     * <p>It is deliberately NOT an effect called "nothing". An effect is something that happens,
     * and this is the case where nothing does; a no-op effect would still declare
     * {@code function act()} and still be reachable by right-click, so the item would silently
     * respond to a click by doing nothing — which reads as broken rather than as finished.
     */
    public static final String PLAIN = "plain";

    /**
     * Every shape {@code make_item} accepts. Validation uses this; the matrix uses
     * {@link #EFFECT_SHAPES}, and the difference between them is exactly {@link #PLAIN}.
     */
    public static final List<String> SHAPES =
            java.util.stream.Stream.concat(EFFECT_SHAPES.stream(), java.util.stream.Stream.of(PLAIN))
                    .toList();

    /**
     * The 24 icons, by NAME. The agent never picks a number.
     *
     * <p>Numbers are an interface a model gets wrong silently — an unlisted {@code custom_model_data}
     * wears the default artwork and nothing anywhere says so. Names are checkable, so a typo is a
     * refusal listing the real ones instead of a mystery item that looks wrong in the child's hand.
     */
    public static final Map<String, Integer> ICONS = icons();

    private static Map<String, Integer> icons() {
        String[] names = {"machine", "block", "cup", "cone", "bottle", "bucket", "bag", "food_round",
                          "orb", "wand", "pad", "rocket", "coin", "lamp", "vehicle", "small_pile",
                          "sword", "axe", "spear", "bow", "shield", "hammer", "helmet", "key"};
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            out.put(names[i], i + 1);   // custom_model_data is 1-based, matching the primer's list
        }
        // the Muse-drawn library, at the numbers items/icon-library.json fixes for them.
        for (com.moddingfromamod.item.IconSprites.LibraryIcon icon
                : com.moddingfromamod.item.IconSprites.drawn()) {
            out.put(icon.name(), icon.number());
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** The 24 shapes every item could always wear, before the drawn library. */
    public static final String SHAPES_GROUP = "shapes";

    /**
     * <b>Every icon, grouped the way the agent reads them</b>: the 24 shapes first, then the
     * drawn library by category. 163 bare names in one list is a wall nobody scans; grouped, "a
     * panda" is found under animals and "a laser blaster" under vehicles &amp; space.
     */
    public static Map<String, List<String>> iconGroups() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : ICONS.entrySet()) {
            var drawn = com.moddingfromamod.item.IconSprites.drawnByName(e.getKey());
            String group = drawn == null ? SHAPES_GROUP : drawn.category();
            out.computeIfAbsent(group, k -> new ArrayList<>()).add(
                    drawn != null && !drawn.tints() ? e.getKey() + "*" : e.getKey());
        }
        return out;
    }

    /** {@link #iconGroups} as one line per group, for a tool description or a refusal. */
    public static String iconMenu() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> g : iconGroups().entrySet()) {
            sb.append("\n  ").append(g.getKey()).append(": ").append(String.join(", ", g.getValue()));
        }
        return sb.toString();
    }

    /**
     * <b>The icons nearest a name that is not one</b>, for the refusal: whole-word overlap
     * first ("teddy" → teddy_bear), then a small edit distance ("octopuss" → octopus). Never a
     * silent pick — the agent chooses, and the miss is logged for the next wave.
     */
    public static List<String> nearestIcons(String name, int max) {
        String want = normalise(name);
        List<String> out = new ArrayList<>();
        if (want.isEmpty()) {
            return out;
        }
        for (String icon : ICONS.keySet()) {
            for (String part : want.split("_")) {
                if (part.length() >= 3 && java.util.Arrays.asList(icon.split("_")).contains(part)
                        && !out.contains(icon)) {
                    out.add(icon);
                }
            }
        }
        for (String icon : ICONS.keySet()) {
            if (!out.contains(icon) && editDistance(want, icon) <= Math.max(1, want.length() / 4)) {
                out.add(icon);
            }
        }
        return out.size() > max ? out.subList(0, max) : out;
    }

    /** A name as the agent might type it, folded the way the library spells names. */
    public static String normalise(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s-]+", "_").replaceAll("[^a-z0-9_]", "");
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private final List<Entry> entries;

    /**
     * One catalogue entry: an effect, what it takes, and who it lands on.
     *
     * @param appliesTo self · target · world — WHO it lands on. See {@link #targetFor}; the reason
     *                  it is a first-class field rather than prose is that getting it wrong builds a
     *                  healing potion that heals the zombie you are looking at, silently.
     * @param aim       self · ahead — WHERE it happens, which is a different question, and the two
     *                  were one field until that shipped a teleport that went nowhere: it lands on
     *                  SELF, so the held wrapper ran it without stepping forward, and
     *                  {@code tp @s ~ ~ ~} moved the child to exactly where he already stood. An
     *                  effect can land on the player and still need to happen where he is pointing.
     */
    public record Entry(String id, boolean core, String appliesTo, String aim, String summary,
                        String who,
                        String when, String childWhen, String template, List<String> params,
                        Map<String, String> paramDocs, Map<String, String> paramAsks,
                        List<String> derived, List<String> wishes, List<String> aka) {

        /** Everything a template may be filled with: what the agent supplies, plus what the tool computes. */
        public List<String> allParams() {
            List<String> out = new ArrayList<>(params);
            out.addAll(derived);
            return out;
        }
    }

    private ItemCatalogue(List<Entry> entries) {
        this.entries = entries;
    }

    public static ItemCatalogue load() {
        try (InputStream in = ItemCatalogue.class.getResourceAsStream(DIR + "catalogue.json")) {
            if (in == null) {
                throw new IllegalStateException("no " + DIR + "catalogue.json on the classpath");
            }
            JsonObject root = JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            List<Entry> out = new ArrayList<>();
            for (var el : root.getAsJsonArray("effects")) {
                JsonObject o = el.getAsJsonObject();
                String id = require(o, "id", "<an entry with no id>");
                List<String> params = new ArrayList<>();
                Map<String, String> docs = new LinkedHashMap<>();
                Map<String, String> asks = new LinkedHashMap<>();
                JsonObject ps = o.getAsJsonObject("params");
                for (String name : ps.keySet()) {
                    params.add(name);
                    JsonObject spec = ps.getAsJsonObject(name);
                    if (spec.has("default")) {
                        // LOUD, because the whole discipline of this catalogue is that there are
                        // none. A default added later would be invisible: every gate stays green
                        // and one wish in three quietly becomes a different item (playbook 4f).
                        throw new IllegalStateException("item effect '" + id + "' gives parameter '"
                                + name + "' a DEFAULT. No parameter in this catalogue may have one "
                                + "— a default is a silent decision made after the wish text is "
                                + "gone. Require it and teach the choice with 'ask' instead.");
                    }
                    docs.put(name, require(spec, "doc", id + "." + name));
                    // The child's words for this choice. Required, not optional: a category
                    // documented only by its definition is one the agent will not reach for.
                    asks.put(name, require(spec, "ask", id + "." + name));
                }
                String appliesTo = require(o, "applies_to", id);
                if (!List.of("self", "target", "world").contains(appliesTo)) {
                    throw new IllegalStateException("item effect '" + id + "' has applies_to='"
                            + appliesTo + "'; it must be self, target or world");
                }
                String aim = require(o, "aim", id);
                // `block`: at the first block along his line of sight — ChangeBlock.
                if (!List.of("self", "ahead", "block").contains(aim)) {
                    throw new IllegalStateException("item effect '" + id + "' has aim='" + aim
                            + "'; it must be self, ahead or block");
                }
                out.add(new Entry(id,
                        o.has("core") && o.get("core").getAsBoolean(),
                        appliesTo, aim,
                        require(o, "summary", id), require(o, "who", id), require(o, "when", id),
                        require(o, "child_when", id), require(o, "template", id),
                        params, docs, asks,
                        strings(o.getAsJsonArray("derived")),
                        strings(o.getAsJsonArray("wishes")),
                        strings(o.getAsJsonArray("aka"))));
            }
            // AN ALIAS MUST POINT AT EXACTLY ONE EFFECT. Checked at load rather than trusted,
            // because a duplicate would resolve to whichever entry happened to be read first and
            // would do it silently -- a wish quietly becoming a different wish, which is the one
            // failure this catalogue's whole `_no_defaults` discipline exists to prevent.
            Map<String, String> owner = new LinkedHashMap<>();
            for (Entry e : out) {
                owner.put(e.id(), e.id());
            }
            for (Entry e : out) {
                for (String a : e.aka()) {
                    String prior = owner.putIfAbsent(a, e.id());
                    if (prior != null) {
                        throw new IllegalStateException("the alias '" + a + "' is claimed by both '"
                                + prior + "' and '" + e.id() + "'. An alias is a lookup, so it must "
                                + "name one effect and never two.");
                    }
                }
            }
            return new ItemCatalogue(out);
        } catch (Exception e) {
            throw new IllegalStateException("could not read the item catalogue: " + e, e);
        }
    }

    private static List<String> strings(JsonArray arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            arr.forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }

    public List<Entry> all() {
        return List.copyOf(entries);
    }

    public Entry get(String id) {
        if (id == null) {
            return null;
        }
        for (Entry e : entries) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        // ONLY AFTER EVERY REAL ID HAS BEEN TRIED. An alias can never shadow an effect's own name.
        for (Entry e : entries) {
            if (e.aka().contains(id)) {
                return e;
            }
        }
        return null;
    }

    /**
     * <b>The one true id for whatever the agent typed</b> — an effect's own name, or null.
     *
     * <p>Called before anything is stored, so a wish granted as {@code anger} is written down,
     * compiled and read back as {@code aggro}. Resolving at use and forgetting to canonicalise is
     * the version of this that works perfectly until the next wish tries to refine the item and
     * {@code inspect_item} reports an effect the catalogue has never heard of.
     */
    public String canonical(String id) {
        Entry e = get(id);
        return e == null ? null : e.id();
    }

    /**
     * <b>Every effect id, in one line, and NEVER throwing.</b>
     *
     * <p>Exists because {@code effect} was the one closed-set field on {@code make_item} whose
     * members were not named where the field is defined. {@code icon}, {@code shape} and
     * {@code properties} each join their whole set into their own schema description; {@code
     * effect} explained only its {@code '+'} syntax, and its 18 members lived solely in a prose
     * block 4,000 characters further down. Measured at opus/low x8 on "a pearl that makes the
     * zombie attack the cow": 7 of 8 runs needed TWO build calls, and three of them typed a
     * request for the catalogue into the effect field itself. That is an agent asking for a
     * lookup and being refused.
     *
     * <p>Never throws, unlike {@link #load()}: a schema is built once at start-up and an
     * exception there removes the whole tool, which reads as "the agent went back to authoring"
     * rather than as a broken catalogue.
     */
    public static List<String> effectIds() {
        try {
            return load().all().stream().map(Entry::id).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** The raw source of a template file, placeholders intact. */
    public static String source(String file) {
        try (InputStream in = ItemCatalogue.class.getResourceAsStream(DIR + file)) {
            if (in == null) {
                throw new IllegalStateException("template '" + file + "' is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("could not read template '" + file + "': " + e, e);
        }
    }

    /**
     * The entity selector an effect body should act on, for a given shape.
     *
     * <p>This one method is where the cross product actually happens, and every branch of it is a
     * decision that would be invisible if wrong:
     *
     * <ul>
     *   <li><b>held + self</b> — {@code @s}, the child. A "make me GIANT" potion that made the
     *       creature in front of them giant instead would build, run, and be wrong.</li>
     *   <li><b>held + target</b> — what they are pointing at, and explicitly <b>not the player</b>:
     *       the shape wrapper has already stepped the position out along their line of sight, so
     *       without {@code type=!player} a fire wand sets its own owner alight.</li>
     *   <li><b>thrown</b> — whatever is near the impact, for self and target alike, <b>and that
     *       deliberately INCLUDES the thrower</b>. A thrown healing orb should heal whoever it lands
     *       on, including the child if he is standing there.
     *
     *       <p><b>Yes, this means a thrown weapon can hurt him — decided, not overlooked (§5).</b>
     *       The asymmetry with {@code held} below is real and intentional: a held fire wand must not
     *       torch its own owner, because he is holding it and pointing it away. A bomb he lobs at his
     *       own feet is a different thing entirely — standing in your own explosion hurting you is
     *       how the game works everywhere else, and it is a consequence he can see, understand and
     *       avoid. A twenty-heart bomb at point-blank is armour-ignoring and more than his health,
     *       and that is the answer: don't stand there.
     *
     *       <p>Do not "fix" this by adding {@code type=!minecraft:player} here. It looks like the
     *       oversight the held branch avoided; it is the opposite. If it is ever revisited, the way
     *       to do it is a required parameter the child's own words answer — the shape
     *       {@code explode}'s {@code BREAKS_BLOCKS} already uses — never a silent default.</li>
     *   <li><b>placed</b> — whatever comes near the marker, never {@code @s}, because {@code @s} is
     *       the marker: an invisible armour stand. A placed healing totem that healed only itself is
     *       the failure this excludes.</li>
     * </ul>
     *
     * <p>Items and the thrown snowball itself are always excluded — an item entity is not something
     * a child means by "whatever it hits", and a snowball freezing itself mid-flight is a bug.
     *
     * <p><b>{@code radius} is taken as a value, not left as a placeholder.</b> The first cut of this
     * returned {@code distance=..${RADIUS}} for the placed shape and expected a later pass to fill
     * it — but the effect body is substituted INTO the wrapper as a value, and a value is inserted
     * literally, so nothing would ever have filled it. It would have shipped
     * {@code @e[distance=..${RADIUS}]}: an unparseable selector in a pack that compiles fine and
     * then does nothing at all, which is this repo's most-repeated bug.
     */
    public static String targetFor(String shape, String appliesTo, double radius) {
        String notJunk = "type=!minecraft:item,type=!minecraft:snowball,type=!minecraft:armor_stand";
        return switch (shape) {
            case "held" -> "self".equals(appliesTo)
                    ? "@s"
                    : "@e[distance=..3.5," + notJunk + ",type=!minecraft:player]";
            case "thrown" -> "@e[distance=..3," + notJunk + "]";
            // ARROW: run AS the shooter at the impact (WishThrowImpact), so an effect on
            // "you" is him wherever he stands, and anything else is what is near where it landed —
            // never the arrow itself, and never the arrows stuck in the ground around it.
            case "arrow" -> "self".equals(appliesTo)
                    ? "@s"
                    : "@e[distance=..3," + notJunk + ",type=!#minecraft:arrows]";
            // ON_THROW_LAND: as the thrower, at the impact — an arrow's geometry, and like an
            // arrow it never catches the thing that was thrown.: a thrown potion is a SPLASH or LINGERING
            // potion entity since 1.21.5 — `minecraft:potion` names no entity there, and the server refused every
            // thrown wish that landed on something (only the matrix's world effect, glowstone, had ever run).
            case "on_throw_land" -> "self".equals(appliesTo)
                    ? "@s"
                    : "@e[distance=..3," + notJunk + ",type=!#minecraft:arrows,type=!minecraft:ender_pearl"
                            + ",type=!minecraft:egg,type=!minecraft:wind_charge,type=!minecraft:splash_potion"
                            + ",type=!minecraft:lingering_potion]";
            // ON_USE_DONE: as him, when he has finished using it — a right-click's geometry.
            case "on_use_done" -> "self".equals(appliesTo)
                    ? "@s"
                    : "@e[distance=..3.5," + notJunk + ",type=!minecraft:player]";
            // ON_BLOCK: as him, the moment his shield stops a hit — a right-click's geometry,
            // so what he blocked (standing right in front of him) is what the wish reaches.
            case "on_block" -> "self".equals(appliesTo)
                    ? "@s"
                    : "@e[distance=..3.5," + notJunk + ",type=!minecraft:player]";
            // ON_CONSUME: as him, when he has finished eating or drinking — a right-click's
            // geometry, because he is standing there holding it the whole time.
            case "on_consume" -> "self".equals(appliesTo)
                    ? "@s"
                    : "@e[distance=..3.5," + notJunk + ",type=!minecraft:player]";
            // ON_SHOT: as the shooter, at the end of his beam (ShotBeam) — what the laser
            // hit is what is near there. Never him: he is tagged for the beam's duration, so a
            // laser fired into a wall one block away does not hurt the one who fired it.
            case "on_shot" -> "self".equals(appliesTo)
                    ? "@s"
                    : "@e[distance=..3," + notJunk + ",type=!#minecraft:arrows,tag=!"
                            + ShotBeam.SHOOTER + "]";
            // ON_MINE: as him, at the block he broke — a right-click's geometry.
            case "on_mine" -> "self".equals(appliesTo)
                    ? "@s"
                    : "@e[distance=..3.5," + notJunk + ",type=!minecraft:player]";
            // ON_HIT is the one shape that already KNOWS its target: WishMeleeHit runs the function
            // standing on the thing he hit, so the nearest non-junk entity IS it. The radius is
            // tighter than held's 3.5 for exactly that reason — held has to guess a reach because a
            // right-click has no target, and a swing does not. A wider one here would let a wish
            // meant for the mob he hit spill onto the one behind it.
            case "on_hit" -> "self".equals(appliesTo)
                    ? "@s"
                    : "@e[distance=..2," + notJunk + ",type=!minecraft:player,limit=1,sort=nearest]";
            case "placed" -> "@e[distance=.." + trim(radius) + "," + notJunk + "]";
            // THE SAME AS HELD, and for the same reason. A machine's effect runs from the
            // advancement reward that fires when he pulls the handle, and an advancement reward
            // always runs AS the player — so he is standing right there and the geometry is a
            // right-click's, not a placed thing's. Giving it `placed`'s wide radius would let a
            // machine meant to serve HIM serve whatever wandered past while he pulled it.
            case MACHINE -> "self".equals(appliesTo)
                    ? "@s"
                    : "@e[distance=..3.5," + notJunk + ",type=!minecraft:player]";
            // MAGIC BLOCK: its reach is one of its own dials, set per wish by make_item's
            // `block` object and computed there (MagicBlock.Spec.target). This is the default —
            // every beat, everyone within 6 — which is what the effect matrix compiles against.
            case MagicBlock.SHAPE -> MagicBlock.defaults().target();
            // WORN: the same geometry as a right-click. The clock runs the effect AS and AT the
            // wearer, so an effect on "you" is @s and one on something else reaches what is right
            // beside him — never placed's wide radius, or boots would fight for him across a room.
            case "worn_head", "worn_chest", "worn_legs", "worn_feet" -> "self".equals(appliesTo)
                    ? "@s"
                    : "@e[distance=..3.5," + notJunk + ",type=!minecraft:player]";
            default -> throw new IllegalArgumentException("no shape '" + shape + "'; have " + EFFECT_SHAPES);
        };
    }

    /** 6.0 -> "6", 3.5 -> "3.5". A selector reads better without the trailing zero. */
    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v)
                : String.valueOf(v);
    }

    /**
     * Build the whole CBScript for one item: render the effect body, then set it into the shape.
     *
     * <p>Two stages rather than one substitution pass, because the effect body is itself a value
     * substituted into the wrapper — and doing it in one pass would let a child's item NAME
     * containing {@code ${...}} reach the wrapper as a placeholder.
     *
     * @param effectArgs the effect's own parameters plus everything the tool derived
     * @param shapeArgs  the wrapper's: icon, colour, name, count, and the shape-specific bits
     */
    public String render(String effectId, String shape,
                         Map<String, String> effectArgs, Map<String, String> shapeArgs) {
        return render(List.of(effectId), shape, Map.of(effectId, effectArgs), shapeArgs);
    }

    /** The literal every effect template declares, and the hook composition renames. */
    private static final String EFFECT_FN = "function effect()";

    /**
     * Build one item out of SEVERAL effects.
     *
     * <p>Compound wishes are the normal case in a child's register, not the exotic one — "a javelin
     * that lands butt cheek water" is also expected to hurt what it hits, and "a sword that sets
     * things on fire" is also expected to be a sword. One effect per item made the agent author a
     * second datapack by hand to finish the job, which is the round trip this catalogue exists to
     * remove.
     *
     * <p><b>Why this is a composition rule and not more entries.</b> A {@code hurt_and_place} entry
     * would be a precomputed <i>wish</i>. This multiplies the same 20 files to 100+ pairs, and every
     * pair is made of halves the matrix already compiles individually.
     *
     * <p>Each body keeps its own {@code function effect()} — renamed to {@code effect_<id>} — and a
     * synthesised dispatcher calls them in order, so the shape wrappers are untouched. Two bodies
     * may each carry their own {@code reset} and {@code clock} blocks; CBScript accepts repeats of
     * both, which is what makes stateful effects like {@code grow} composable at all.
     */
    public String render(List<String> effectIds, String shape,
                         Map<String, Map<String, String>> argsByEffect,
                         Map<String, String> shapeArgs) {
        boolean plain = PLAIN.equals(shape);
        if (effectIds == null || effectIds.isEmpty()) {
            // PLAIN IS THE ONE SHAPE THAT MAY HAVE NONE, and it must have none: it declares no
            // `act`, so an effect passed with it would be compiled and then never reachable -- an
            // item that carries a behaviour nothing can ever set off, which is worse than refusing.
            // ...and a MAGIC BLOCK may have none too: "a new block that no mod has" is a
            // block he places and breaks, and its shape is complete without anything to set off.
            if (!plain && !MagicBlock.SHAPE.equals(shape)) {
                throw new IllegalArgumentException("an item needs at least one effect");
            }
        } else if (plain) {
            throw new IllegalArgumentException("the '" + PLAIN + "' shape is for an item that does "
                    + "NOTHING, so it takes no effect — and " + effectIds + " would be built and "
                    + "then never run, because a plain item has nothing to set it off. If he wants "
                    + "it to do that, give it a shape that fires: " + EFFECT_SHAPES);
        }
        if (effectIds.size() > MAX_EFFECTS) {
            throw new IllegalArgumentException("an item may combine at most " + MAX_EFFECTS
                    + " effects, not " + effectIds.size() + " — past that nobody can tell what it "
                    + "did, least of all the child");
        }
        if (Set.copyOf(effectIds).size() != effectIds.size()) {
            // Two copies of one effect share its scoreboards and its function name, so the second
            // silently overwrites the first. There is also no wish it would serve.
            throw new IllegalArgumentException("the same effect twice does nothing twice: "
                    + effectIds);
        }
        if (!SHAPES.contains(shape)) {
            throw new IllegalArgumentException("no shape '" + shape + "'; have " + SHAPES);
        }
        StringBuilder bodies = new StringBuilder();
        StringBuilder dispatch = new StringBuilder(EFFECT_FN + "\n");
        for (String effectId : effectIds) {
            Entry entry = get(effectId);
            if (entry == null) {
                throw new IllegalArgumentException("no item effect '" + effectId
                        + "' in the catalogue; have: " + entries.stream().map(Entry::id).toList());
            }
            Map<String, String> args = argsByEffect.getOrDefault(effectId, Map.of());
            for (String given : args.keySet()) {
                if (given.startsWith("_")) {
                    // Scratch a derivation hands back to its caller (a sign line, a recipe
                    // sentence) -- never a template parameter, so never checked or rendered.
                    continue;
                }
                if (!entry.allParams().contains(given)) {
                    throw new IllegalArgumentException("effect '" + effectId + "' has no parameter '"
                            + given + "'; it takes " + entry.params());
                }
            }
            String body = fill(source(entry.template()), args, "the '" + effectId + "' effect");
            if (effectIds.size() > 1) {
                if (!body.contains(EFFECT_FN)) {
                    throw new IllegalStateException("the '" + effectId + "' template does not "
                            + "declare '" + EFFECT_FN + "', so it cannot be composed with anything");
                }
                body = body.replace(EFFECT_FN, "function effect_" + effectId + "()");
                dispatch.append("    /function $namespace:effect_").append(effectId).append('\n');
            }
            bodies.append(body).append('\n');
        }
        if (effectIds.size() > 1) {
            bodies.append(dispatch).append("end\n");
        }
        Map<String, String> withBody = new LinkedHashMap<>(shapeArgs);
        withBody.put("EFFECT_BLOCK", bodies.toString());
        // The four worn shapes share one wrapper; the slot arrives as a placeholder (WornShape).
        String wrapper = WornShape.isWorn(shape) ? "worn" : shape;
        return fill(source("shape_" + wrapper + ".cbs"), withBody, "the '" + shape + "' shape");
    }

    /**
     * {@link #render} with the player's values taken out: the key of the item's precompiled pack. Each
     * effect's own parameters are scoped to it (two effects may each have a SECONDS); the shape's identity values
     * (feature id, name, colour) are taken out of the blocks the tool built too (the give line), so the same pick
     * made under another name is the same skeleton. Its {@link ReadyMadeSkeleton.Skeleton#fill()} is exactly
     * {@link #render} of the same arguments.
     */
    public ReadyMadeSkeleton.Skeleton skeleton(List<String> effectIds, String shape,
                                               Map<String, Map<String, String>> argsByEffect,
                                               Map<String, String> shapeArgs) {
        render(effectIds, shape, argsByEffect, shapeArgs);   // the same refusals
        List<String> ids = effectIds == null ? List.of() : effectIds;
        String wrapper = source("shape_" + (WornShape.isWorn(shape) ? "worn" : shape) + ".cbs");
        Map<String, String> sa = withOptional(shapeArgs);
        ReadyMadeSkeleton.Composer c = new ReadyMadeSkeleton.Composer();
        c.scan(wrapper, sa, "");
        for (String identity : List.of("FEATURE", "NAME", "HEX", "COLOUR", "LOOK")) {
            c.slot("", identity, sa.get(identity));
        }
        for (String effectId : ids) {
            c.scan(source(get(effectId).template()), withOptional(argsByEffect.getOrDefault(effectId, Map.of())),
                    effectId);
        }
        StringBuilder bodies = new StringBuilder();
        StringBuilder dispatch = new StringBuilder(EFFECT_FN + "\n");
        for (String effectId : ids) {
            String body = c.render(source(get(effectId).template()),
                    withOptional(argsByEffect.getOrDefault(effectId, Map.of())), effectId, null);
            if (ids.size() > 1) {
                body = body.replace(EFFECT_FN, "function effect_" + effectId + "()");
                dispatch.append("    /function $namespace:effect_").append(effectId).append('\n');
            }
            bodies.append(body).append('\n');
        }
        if (ids.size() > 1) {
            bodies.append(dispatch).append("end\n");
        }
        Map<String, String> withBody = new LinkedHashMap<>(sa);
        withBody.put("EFFECT_BLOCK", bodies.toString());
        return c.skeleton(c.render(wrapper, withBody, "", java.util.Set.of("EFFECT_BLOCK")));
    }

    /** The args with every OPTIONAL placeholder present, as {@link #fill} fills them. */
    private static Map<String, String> withOptional(Map<String, String> args) {
        Map<String, String> out = new LinkedHashMap<>(args);
        for (String o : OPTIONAL) {
            out.putIfAbsent(o, "");
        }
        return out;
    }

    /**
     * How many effects one item may carry.
     *
     * <p>Three, because a child's compound wish is almost always two ("burns AND hurts") and never
     * four. A cap is not timidity: past three the closing sentence cannot honestly describe what he
     * is holding, and an item nobody can explain is one he stops using.
     */
    public static final int MAX_EFFECTS = 3;

    /** Substitute, and throw naming anything left unfilled. */
    /**
     * Placeholders a wish may leave out, and what "left out" means: an empty line, not a mistake.
     *
     * <p>{@code KIT} is the second: the supplies a machine is handed over WITH, in the
     * shapes that hand a broken one back ({@code refund}) — kept apart from {@code GIVES} so the
     * refund gives the machine alone.
     *
     * <p>{@code YIELD} is the first of them. Every other placeholder is something the shape always
     * needs — an aim, a period, a trail — while a yield is an axis most wishes never touch, and
     * refusing to render a held item because it does not turn into slices would be absurd.
     *
     * <p>Kept as an explicit list rather than a default-to-empty rule, because the guard this sits
     * in front of has real value: a template that names a placeholder nobody fills produces a pack
     * that compiles, activates, and silently does nothing.
     */
    private static final java.util.Set<String> OPTIONAL = java.util.Set.of("YIELD", "KIT");

    private static String fill(String src, Map<String, String> args, String what) {
        Matcher m = PLACEHOLDER.matcher(src);
        StringBuilder sb = new StringBuilder();
        List<String> missing = new ArrayList<>();
        while (m.find()) {
            String name = m.group(1);
            String value = args.get(name);
            if (value == null && OPTIONAL.contains(name)) {
                // An OPT-IN axis: nearly every wish asks for none, and the line it would render is
                // genuinely nothing. Named one by one rather than defaulting every placeholder,
                // because the guard below is the only thing standing between a template and a pack
                // that compiles, activates and does nothing.
                value = "";
            }
            if (value == null) {
                missing.add(name);
                value = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        if (!missing.isEmpty()) {
            // It would compile. It would activate. It would do nothing, and say nothing.
            throw new IllegalArgumentException(what + " still has unfilled placeholder(s) "
                    + missing + " — it would compile cleanly and do nothing");
        }
        return sb.toString();
    }

    /** An icon's model number, or null if that is not one of the 24. */
    public static Integer icon(String name) {
        return name == null ? null : ICONS.get(normalise(name));
    }

    private static String require(JsonObject o, String key, String id) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            throw new IllegalStateException("item effect '" + id + "' in catalogue.json is missing "
                    + "the required key '" + key + "'");
        }
        return o.get(key).getAsString();
    }
}
