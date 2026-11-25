package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.datapack.EffectCatalogue;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.event.design.EventDesign;
import com.moddingfromamod.species.Species;

/**
 * <b>What a make IS, as facts a world can decide by</b>: its kind, a few measured {@link #numbers} in real game units,
 * and a set of {@link #tags} from a small closed vocabulary. Facts only: the core never rates, scores or ranks a make,
 * and never combines these into one figure. A world that wants a rating makes its own from them.
 *
 * <p>One pure function answers it for every kind of make and every way a make reaches the core: a Builder spec (also an
 * Apprentice tile's, and a kept {@code builder:<id>} make's), a stored spec, a recipe's replay commands (a cached or
 * taught recipe, a hotbar fire), and a thing on a child's shelf (what the Magician made). A plug-in reads it; nothing
 * here is a hook, and nothing is stored: facts are computed when asked.
 *
 * <p><b>Nothing is hand-rated.</b> Every fact is DERIVED from fields the entries already carry: a creature's powers and
 * temperament, an item effect's class in the item catalogue ({@code applies_to}) and the parameters it takes, a creature
 * ability's parameters in the effects catalogue, an event's motion and what it leaves, a kind, and the numbers a spec or
 * a stored design holds. A Builder entry or catalogue effect added later gets its facts with no decision from anyone
 * ({@code MakeFactsTest} reads every reviewed entry and every ready-made, and fails one by name that the rules cannot
 * read).
 *
 * <h2>Numbers</h2>
 * A STABLE vocabulary ({@link #NUMBERS}), the unit in the name, additive only: a name is never renamed or given another
 * unit once shipped. A number is present only where the make really carries it; ABSENT means "not known", never 0
 * ({@link #number} is empty).
 * <ul>
 *   <li>{@link #HEALTH_HALF_HEARTS}: a stored creature's maximum health, in health points (one is half a heart);</li>
 *   <li>{@link #ATTACK_DAMAGE_HALF_HEARTS}: a stored creature's attack damage, in health points;</li>
 *   <li>{@link #MOVEMENT_SPEED_ATTRIBUTE}: a stored creature's {@code movement_speed} attribute value (a zombie is 0.23);</li>
 *   <li>{@link #SCALE_TIMES}: how many times its normal size a creature is (a sculpt's {@code scale});</li>
 *   <li>{@link #BUILDER_SIZE_STEP}: the Builder's size pick, 1 to 4 (a creature, ride, building or event);</li>
 *   <li>{@link #BUILDER_STRENGTH_STEP}: the Builder's strength pick, 1 to 3 (an item power, a snack machine, a magic block);</li>
 *   <li>{@link #COUNT_MADE}: how many are made at once;</li>
 *   <li>{@link #AMOUNT_ITEMS}: how many items supplies hand over;</li>
 *   <li>{@link #RADIUS_BLOCKS}: the largest radius, in blocks, an effect of it takes;</li>
 *   <li>{@link #DURATION_TICKS}: the longest time, in ticks (20 a second), an effect of it lasts;</li>
 *   <li>{@link #EXTENT_BLOCKS}: a stored building's longest side, in blocks.</li>
 * </ul>
 *
 * <h2>Tags</h2>
 * <ul>
 *   <li>{@link #FIGHTS}: it can hurt a creature (an item effect that lands on a target, lightning or a blast at the
 *       spot, a creature ability that catches or commands others, a guard, anything hostile);</li>
 *   <li>{@link #HOSTILE}: a creature that may attack players (a hostile or boss power, or a creature given no friendly
 *       or shy power: the species' own rule, {@code Species.isHostile});</li>
 *   <li>{@link #BOSS}: a creature with a boss bar;</li>
 *   <li>{@link #CHANGES_BLOCKS}: it puts, takes or turns blocks where it acts (an effect taking a block, a turn-into, an
 *       explosion that may break blocks, an event that leaves something or wrecks what it passes);</li>
 *   <li>{@link #BUILDS}: it puts a built thing into the world (a building, a picture's board);</li>
 *   <li>{@link #SUMMONS}: it makes other creatures appear (a summoning effect, an event that drops creatures, a
 *       creature's call-for-help move);</li>
 *   <li>{@link #GIVES_ITEMS}: it hands items over (supplies, a prize table, a creature that drops treats);</li>
 *   <li>{@link #WORLD_RULE}: it changes a rule for the whole world;</li>
 *   <li>{@link #DECORATIVE}: it does nothing but be there (a picture, a "just for fun" item);</li>
 *   <li>{@link #HARMLESS}: none of fights, hostile, boss, changes blocks, summons, world rule.</li>
 * </ul>
 *
 * <p><b>{@link #known} false</b> when the facts were not read from the thing itself (an artifact that is gone or does
 * not read, a command the core does not know): then they are the CAUTIOUS facts of its kind ({@link #cautious}): every
 * tag such a thing could carry, and no numbers.
 *
 * <p>Pure and Minecraft-free.
 */
public record MakeFacts(String kind, Map<String, Double> numbers, Set<String> tags, boolean known) {

    public static final String HEALTH_HALF_HEARTS = "healthHalfHearts";
    public static final String ATTACK_DAMAGE_HALF_HEARTS = "attackDamageHalfHearts";
    public static final String MOVEMENT_SPEED_ATTRIBUTE = "movementSpeedAttribute";
    public static final String SCALE_TIMES = "scaleTimes";
    public static final String BUILDER_SIZE_STEP = "builderSizeStep";
    public static final String BUILDER_STRENGTH_STEP = "builderStrengthStep";
    public static final String COUNT_MADE = "countMade";
    public static final String AMOUNT_ITEMS = "amountItems";
    public static final String RADIUS_BLOCKS = "radiusBlocks";
    public static final String DURATION_TICKS = "durationTicks";
    public static final String EXTENT_BLOCKS = "extentBlocks";

    /**
     * Every number's name, as shipped. Additive only: a name here is never renamed, removed or given another unit
     * ({@code MakeFactsTest} pins it); a new one is added at the end.
     */
    public static final List<String> NUMBERS = List.of(HEALTH_HALF_HEARTS, ATTACK_DAMAGE_HALF_HEARTS,
            MOVEMENT_SPEED_ATTRIBUTE, SCALE_TIMES, BUILDER_SIZE_STEP, BUILDER_STRENGTH_STEP, COUNT_MADE, AMOUNT_ITEMS,
            RADIUS_BLOCKS, DURATION_TICKS, EXTENT_BLOCKS);

    public static final String FIGHTS = "fights";
    public static final String HOSTILE = "hostile";
    public static final String BOSS = "boss";
    public static final String CHANGES_BLOCKS = "changes_blocks";
    public static final String BUILDS = "builds";
    public static final String SUMMONS = "summons";
    public static final String GIVES_ITEMS = "gives_items";
    public static final String WORLD_RULE = "world_rule";
    public static final String DECORATIVE = "decorative";
    public static final String HARMLESS = "harmless";

    /** The whole vocabulary, in the order the docs list it. A tag a world names that is not here matches nothing. */
    public static final List<String> TAGS = List.of(FIGHTS, HOSTILE, BOSS, CHANGES_BLOCKS, BUILDS, SUMMONS, GIVES_ITEMS,
            WORLD_RULE, DECORATIVE, HARMLESS);

    /** The tags that make a thing NOT harmless. */
    static final List<String> NOT_HARMLESS = List.of(FIGHTS, HOSTILE, BOSS, CHANGES_BLOCKS, SUMMONS, WORLD_RULE);

    /** The kind of a thing the core cannot place (a command it does not know, a datapack that is not an item). */
    public static final String OTHER = "other";

    /** Every kind a make can be: the Builder's kinds ({@link BuilderSpec.Kind#id}) and {@link #OTHER}. */
    public static final List<String> KINDS = kinds();

    private static List<String> kinds() {
        List<String> out = new ArrayList<>();
        for (BuilderSpec.Kind k : BuilderSpec.Kind.values()) {
            out.add(k.id());
        }
        out.add(OTHER);
        return List.copyOf(out);
    }

    public MakeFacts {
        kind = kind == null || kind.isBlank() ? OTHER : kind;
        Map<String, Double> n = new java.util.TreeMap<>();
        if (numbers != null) {
            numbers.forEach((k, v) -> {
                if (k != null && v != null && !v.isNaN() && !v.isInfinite()) {
                    n.put(k, v);
                }
            });
        }
        numbers = Collections.unmodifiableMap(n);
        tags = Collections.unmodifiableSet(new TreeSet<>(tags == null ? Set.of() : tags));
    }

    /** This number, or empty when the make does not carry it (never 0 for "not known"). */
    public java.util.OptionalDouble number(String name) {
        Double v = numbers.get(name);
        return v == null ? java.util.OptionalDouble.empty() : java.util.OptionalDouble.of(v);
    }

    /** Does it carry this tag? */
    public boolean has(String tag) {
        return tags.contains(tag);
    }

    /** "creature fights hostile {countMade=2.0}" — for a log line. */
    @Override
    public String toString() {
        return kind + (tags.isEmpty() ? "" : " " + String.join(" ", tags)) + (numbers.isEmpty() ? "" : " " + numbers)
                + (known ? "" : " (cautious)");
    }

    // ── what a thing reads to make its facts ──────────────────────────────────────────────────

    /** The facts as they are gathered: what has been seen so far, then {@link #done}. */
    private static final class Acc {
        final String kind;
        final Set<String> tags = new LinkedHashSet<>();
        final Map<String, Double> numbers = new LinkedHashMap<>();
        boolean inert;
        boolean known = true;

        Acc(String kind) {
            this.kind = kind;
        }

        MakeFacts done() {
            Set<String> t = new LinkedHashSet<>(tags);
            boolean acts = NOT_HARMLESS.stream().anyMatch(t::contains) || t.contains(GIVES_ITEMS);
            if (inert && !acts) {
                t.add(DECORATIVE);
            }
            if (NOT_HARMLESS.stream().noneMatch(t::contains)) {
                t.add(HARMLESS);
            }
            return new MakeFacts(kind, numbers, t, known);
        }

        /** Keep the largest value seen for {@code name}. */
        void most(String name, double v) {
            if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                numbers.merge(name, v, Math::max);
            }
        }
    }

    // ── the catalogues, read once ─────────────────────────────────────────────────────────────

    /** Each catalogue effect's tags, derived from its class and its parameters. */
    private static final class Effects {
        static final Map<String, Set<String>> ITEM = itemTags();
        static final Map<String, Set<String>> CREATURE = creatureTags();

        private static Map<String, Set<String>> itemTags() {
            Map<String, Set<String>> out = new LinkedHashMap<>();
            for (ItemCatalogue.Entry e : ItemCatalogue.load().all()) {
                out.put(e.id(), itemEffect(e.appliesTo(), e.params(), e.derived()));
            }
            return Map.copyOf(out);
        }

        private static Map<String, Set<String>> creatureTags() {
            Map<String, Set<String>> out = new LinkedHashMap<>();
            for (EffectCatalogue.Effect e : EffectCatalogue.load().all()) {
                out.put(e.id(), creatureEffect(e.params(), e.derived()));
            }
            return Map.copyOf(out);
        }
    }

    /**
     * An item effect's tags, from what the item catalogue says of it: {@code applies_to} {@code target} lands on what he
     * hits ({@link #FIGHTS}); a strike or blast at the spot ({@code BOLTS}, {@code BLAST}) hurts what is there
     * ({@link #FIGHTS}); one that takes a block, turns blocks or may break them ({@code BLOCK}, {@code FROM}/{@code TO},
     * {@code BREAKS_BLOCKS}) changes blocks; one that takes a mob or summons ({@code MOB}, {@code SUMMONS}) summons; one
     * that hands over an item or prizes ({@code ITEM}, {@code PRESENTS}) gives items. Package-private for the test.
     */
    static Set<String> itemEffect(String appliesTo, List<String> params, List<String> derived) {
        Set<String> all = new LinkedHashSet<>(params);
        all.addAll(derived);
        Set<String> out = new LinkedHashSet<>();
        if ("target".equals(appliesTo) || all.contains("BOLTS") || all.contains("BLAST")) {
            out.add(FIGHTS);
        }
        if (all.contains("BLOCK") || all.contains("FROM") || all.contains("TO") || all.contains("BREAKS_BLOCKS")) {
            out.add(CHANGES_BLOCKS);
        }
        if (all.contains("MOB") || all.contains("SUMMONS")) {
            out.add(SUMMONS);
        }
        if (all.contains("ITEM") || all.contains("PRESENTS")) {
            out.add(GIVES_ITEMS);
        }
        return Set.copyOf(out);
    }

    /**
     * A creature ability's tags, from what the effects catalogue says it takes: a {@code TARGET} it catches or a
     * {@code COMMAND} it runs on others can hurt ({@link #FIGHTS}); a {@code BLOCK} it puts down changes blocks; an
     * {@code ITEM} it drops gives items. A recolour or an ally buff takes none of them.
     */
    static Set<String> creatureEffect(List<String> params, List<String> derived) {
        Set<String> all = new LinkedHashSet<>(params);
        all.addAll(derived);
        Set<String> out = new LinkedHashSet<>();
        if (all.contains("TARGET") || all.contains("COMMAND")) {
            out.add(FIGHTS);
        }
        if (all.contains("BLOCK")) {
            out.add(CHANGES_BLOCKS);
        }
        if (all.contains("ITEM")) {
            out.add(GIVES_ITEMS);
        }
        return Set.copyOf(out);
    }

    /** An item effect by its catalogue id; null when neither catalogue has it. */
    static Set<String> itemEffectTags(String effect) {
        Set<String> t = Effects.ITEM.get(effect);
        return t != null ? t : Effects.CREATURE.get(effect);
    }

    /** A creature ability by its Builder id ({@link BuilderAbilities}) or catalogue effect; null when neither has it. */
    static Set<String> abilityTags(String ability) {
        BuilderAbilities.Ability a = BuilderAbilities.byId(ability);
        Set<String> t = Effects.CREATURE.get(a != null ? a.effect() : ability);
        return t != null ? t : Effects.ITEM.get(a != null ? a.effect() : ability);
    }

    // ── a Builder spec ────────────────────────────────────────────────────────────────────────

    /**
     * A Builder make's facts: a spec from the Builder's menus, an Apprentice tile's ({@code ApprenticeMake}), a kept
     * {@code builder:<id>} make's stored spec. The ready-mades are read from the shipped library.
     */
    public static MakeFacts of(BuilderSpec spec) {
        if (spec == null) {
            return cautious(OTHER);
        }
        // the shipped library's facts of a spec never change at run time: a bounded memo, so a menu drawn under a
        // world's limits (every tile's make, read and fitted) does not lower the same spec again and again
        String key = spec.toJson().toString();
        MakeFacts hit = MEMO.get(key);
        if (hit != null) {
            return hit;
        }
        MakeFacts f = of(spec, LibraryPresets.SHIPPED);
        MEMO.put(key, f);
        return f;
    }

    private static final java.util.concurrent.atomic.AtomicLong DERIVED = new java.util.concurrent.atomic.AtomicLong();

    /** How many specs have been read into facts so far (a memo hit is not one): what a test counts to prove a menu
     *  draw derives nothing. */
    public static long derivations() {
        return DERIVED.get();
    }

    /** At most this many specs' facts are remembered; the oldest goes first. */
    static final int MEMO_SIZE = 4096;

    private static final Map<String, MakeFacts> MEMO = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MakeFacts> eldest) {
            return size() > MEMO_SIZE;
        }
    });

    /**
     * …with the ready-mades read through {@code presets}. A spec still being picked (a menu page's tile: a kind with
     * no ready-made yet, an item with no power) is rated by what it has so far, never cautiously: picking more only
     * adds facts. Never throws.
     */
    public static MakeFacts of(BuilderSpec spec, BuilderLowering.Presets presets) {
        if (spec == null) {
            return cautious(OTHER);
        }
        DERIVED.incrementAndGet();
        BuilderSpec.Kind k = spec.kind();
        Acc a = new Acc(k.id());
        boolean creature = k == BuilderSpec.Kind.CREATURE || k == BuilderSpec.Kind.RIDE;
        switch (k) {
            case BUILDING -> a.tags.add(BUILDS);
            case PICTURE -> {
                a.tags.add(BUILDS);
                a.inert = true;
            }
            case RULE -> a.tags.add(WORLD_RULE);
            case SUPPLIES -> a.tags.add(GIVES_ITEMS);
            default -> {
            }
        }
        boolean realMob = creature && Catalogue.prefix(spec.from()).equals("mob");
        for (String id : BuilderLowering.ids(spec)) {
            idTags(a, k, id, realMob && id.equals(spec.from()));
        }
        if (creature && !realMob) {
            try {
                creaturePowers(a, BuilderLowering.temperPowers(spec.temper()));
            } catch (IllegalArgumentException unknownTemper) {
                a.tags.add(HOSTILE);   // a temperament the lowering does not know: the creature lives as a hostile one
            }
            if ("guard".equals(spec.temper())) {
                a.tags.add(FIGHTS);
            }
        }
        if (realMob) {
            String mob = Catalogue.bare(Catalogue.bare(spec.from()));
            String acts = spec.opt(BuilderActs.SLOT);
            // a farm animal is friendly; any other real creature is rated as the monster it may be, unless he made it
            // act for him (BuilderActs: never against him).
            if (!BuilderFlow.FARM_ANIMALS.contains(mob) && (acts.isEmpty() || acts.equals(BuilderActs.GAME))) {
                a.tags.add(HOSTILE);
                a.tags.add(FIGHTS);
            }
        }
        if (k == BuilderSpec.Kind.ITEM && BuilderLooks.SHOW.equals(spec.opt("power"))) {
            a.inert = true;   // "Just for fun": a keepsake with no power
        }
        // the calls the make will run, when it is complete enough to lower: what a ready-made is made of (a starter's
        // own nature, an item preset's effect, an event's motion and what it catches). A spec still being picked does
        // not lower, and keeps what its picks said.
        try {
            for (BuilderLowering.Call c : BuilderLowering.lower(spec, "make_facts", presets)) {
                callTags(a, c, creature && !realMob);
            }
        } catch (RuntimeException notComplete) {
            // rated by its picks alone
        }
        // the numbers the spec itself carries, where its kind uses them
        if (creature || k == BuilderSpec.Kind.BUILDING || k == BuilderSpec.Kind.EVENT) {
            a.most(BUILDER_SIZE_STEP, spec.size());
        }
        if (!spec.opt("strength").isEmpty()) {
            a.most(BUILDER_STRENGTH_STEP, num(spec.opt("strength"), 0));
        }
        if (creature || k == BuilderSpec.Kind.ITEM || k == BuilderSpec.Kind.MACHINE || k == BuilderSpec.Kind.MAGIC_BLOCK) {
            a.most(COUNT_MADE, spec.count());
        } else if (k == BuilderSpec.Kind.SUPPLIES) {
            a.most(AMOUNT_ITEMS, BuilderSupplies.amount(spec));
        }
        if (creature && a.tags.contains(HOSTILE)) {
            a.tags.add(FIGHTS);
        }
        return a.done();
    }

    /** The catalogue prefixes whose ids carry facts ({@link #idTags}). */
    static final List<String> READS = List.of("power", "ability", "effect", "motion", "rule", "supply", "item");

    /**
     * The prefixes whose ids say nothing on their own: looks, shapes, colours, kits, triggers, ingredients; a ready-made
     * (read through its lowering) and a real mob (read by {@link #of}). With {@link #READS}, every
     * {@code Catalogue.PREFIXES} entry: a new catalogue fails {@code MakeFactsTest} by name until it is in one.
     */
    static final List<String> SAYS_NOTHING = List.of("shape", "icon", "when", "preset", "mob", "scheme", "kit", "look",
            "ingredient");

    /** Null when the rules here can rate {@code id}; otherwise why not (for the test that rates every entry). */
    static String problem(String id) {
        String prefix = Catalogue.prefix(id);
        String bare = Catalogue.bare(id);
        if (!READS.contains(prefix) && !SAYS_NOTHING.contains(prefix)) {
            return "no rule in MakeFacts reads '" + prefix + ":' ids (" + id + "): add the prefix to READS or SAYS_NOTHING";
        }
        return switch (prefix) {
            case "ability" -> abilityTags(bare) == null ? "ability '" + bare + "' is in no effect catalogue" : null;
            case "effect" -> itemEffectTags(bare) == null ? "item effect '" + bare + "' is in no effect catalogue" : null;
            case "motion" -> java.util.Arrays.stream(EventDesign.Motion.values()).anyMatch(m -> m.id().equals(bare))
                    ? null : "no event motion '" + bare + "'";
            default -> null;
        };
    }

    /** One catalogue id a spec names. */
    private static void idTags(Acc a, BuilderSpec.Kind kind, String id, boolean theRealMob) {
        String bare = Catalogue.bare(id);
        switch (Catalogue.prefix(id)) {
            case "power" -> creaturePowers(a, List.of(bare));
            case "ability" -> {
                Set<String> t = abilityTags(bare);
                if (t == null) {
                    unknownEffect(a);
                } else {
                    a.tags.addAll(t);
                }
            }
            case "effect" -> {
                Set<String> t = itemEffectTags(bare);
                if (t == null) {
                    unknownEffect(a);
                } else {
                    a.tags.addAll(t);
                }
            }
            case "motion" -> eventMotion(a, bare, List.of());
            case "rule" -> a.tags.add(WORLD_RULE);
            case "supply", "item" -> a.tags.add(GIVES_ITEMS);
            default -> {
                // looks, shapes, colours, kits, triggers, ingredients and ready-mades say nothing on their own; a
                // ready-made is read through its lowering, a real mob by the caller
            }
        }
    }

    private static void unknownEffect(Acc a) {
        a.tags.add(FIGHTS);
        a.tags.add(CHANGES_BLOCKS);
        a.known = false;
    }

    /** Creature powers (lower-case {@code Species.Power} names, as a sculpt takes them). */
    private static void creaturePowers(Acc a, List<String> powers) {
        Set<String> p = new LinkedHashSet<>();
        for (String s : powers) {
            p.add(s == null ? "" : s.strip().toLowerCase(Locale.ROOT));
        }
        if (p.contains("hostile") || p.contains("boss")) {
            a.tags.add(HOSTILE);
        }
        if (p.contains("boss")) {
            a.tags.add(BOSS);
        }
    }

    /** A sculpt's whole power list: hostile unless it is friendly or shy (the species' own rule). */
    private static void creatureLives(Acc a, List<String> powers) {
        creaturePowers(a, powers);
        Set<String> p = new LinkedHashSet<>();
        powers.forEach(s -> p.add(s.toLowerCase(Locale.ROOT)));
        if (!p.contains("friendly") && !p.contains("skittish")) {
            a.tags.add(HOSTILE);
        }
    }

    /** One tool call a make runs. {@code sculpted}: a sculpt's power list is the whole of how it lives. */
    private static void callTags(Acc a, BuilderLowering.Call c, boolean sculpted) {
        JsonObject args = c.args();
        switch (c.tool()) {
            case "sculpt_creature" -> {
                if (args.has("scale") && args.get("scale").isJsonPrimitive()) {
                    a.most(SCALE_TIMES, args.get("scale").getAsDouble());
                }
                if (sculpted) {
                    creatureLives(a, strings(args.get("powers")));
                }
                if ("guardian".equals(str(args, "base"))) {
                    a.tags.add(FIGHTS);
                }
            }
            case "apply_effect" -> {
                params(a, args.get("params"));
                Set<String> t = abilityTags(str(args, "effect"));
                if (t == null) {
                    unknownEffect(a);
                } else {
                    a.tags.addAll(t);
                }
            }
            case "make_item" -> itemJson(a, args);
            case "build_structure" -> a.tags.add(BUILDS);
            case "paint_surface" -> {
                a.tags.add(BUILDS);
                a.inert = true;
            }
            case "stage_event" -> eventJson(a, args);
            case "set_rule" -> a.tags.add(WORLD_RULE);
            case "give_items" -> a.tags.add(GIVES_ITEMS);
            default -> {
                // plan_wish, finish_building, run_command (a real mob, more of the same), modify_mob: nothing new
            }
        }
    }

    /** An item's arguments or stored design: every effect it carries; none at all is a keepsake. */
    private static void itemJson(Acc a, JsonObject item) {
        List<String> effects = new ArrayList<>();
        collectEffects(item, effects);
        for (String e : effects) {
            Set<String> t = itemEffectTags(e);
            if (t == null) {
                unknownEffect(a);
            } else {
                a.tags.addAll(t);
            }
        }
        if (effects.isEmpty()) {
            a.inert = true;
        }
        params(a, item.get("params"));
    }

    /**
     * The measured numbers an effect's parameters carry: {@code RADIUS} in blocks, {@code SECONDS} as ticks. Only these
     * two, read where the catalogue names them so; a parameter the catalogue spells otherwise is not guessed at.
     */
    private static void params(Acc a, JsonElement params) {
        if (!(params instanceof JsonObject p)) {
            return;
        }
        double radius = numeric(p.get("RADIUS"));
        if (!Double.isNaN(radius)) {
            a.most(RADIUS_BLOCKS, radius);
        }
        double seconds = numeric(p.get("SECONDS"));
        if (!Double.isNaN(seconds)) {
            a.most(DURATION_TICKS, seconds * 20);
        }
    }

    private static double numeric(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(e.getAsString().strip());
        } catch (NumberFormatException notANumber) {
            return Double.NaN;
        }
    }

    /** Every {@code effect} / {@code effects} value anywhere in an item (a magic block's surprises, a machine's snacks). */
    private static void collectEffects(JsonElement e, List<String> out) {
        if (e == null || e.isJsonNull()) {
            return;
        }
        if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> collectEffects(x, out));
            return;
        }
        if (!e.isJsonObject()) {
            return;
        }
        for (var en : e.getAsJsonObject().entrySet()) {
            String key = en.getKey();
            JsonElement v = en.getValue();
            if ((key.equals("effect") || key.equals("effects")) && v.isJsonPrimitive()) {
                String s = v.getAsString().strip();
                if (!s.isEmpty()) {
                    out.add(Catalogue.prefix(s).equals("effect") ? Catalogue.bare(s) : s);
                }
            } else if (key.equals("effects") && v.isJsonArray()) {
                for (JsonElement x : v.getAsJsonArray()) {
                    if (x.isJsonPrimitive() && !x.getAsString().isBlank()) {
                        out.add(x.getAsString().strip());
                    } else {
                        collectEffects(x, out);
                    }
                }
            } else if (!key.equals("params") && (v.isJsonObject() || v.isJsonArray())) {
                collectEffects(v, out);
            }
        }
    }

    /**
     * An event's arguments or stored design: what it LEAVES (its motion's rule, {@code afterwards}, follow-on designs,
     * blocks it hits or breaks) changes blocks; a motion that wrecks what it passes changes blocks and hurts what it
     * catches unless it spares them; what it catches and burns or hurts fights; what it drops summons.
     */
    private static void eventJson(Acc a, JsonObject ev) {
        List<String> spares = strings(ev.get("spares"));
        eventMotion(a, str(ev, "motion"), spares);
        if ("until_undo".equals(str(ev, "afterwards")) || has(ev, "then") || has(ev, "blocks") || has(ev, "breaks")) {
            a.tags.add(CHANGES_BLOCKS);
        }
        if (!str(ev, "drop").isEmpty()) {
            a.tags.add(SUMMONS);
        }
        String catchText = ev.has("catch") ? ev.get("catch").toString().toLowerCase(Locale.ROOT) : "";
        String blocksText = ev.has("blocks") ? ev.get("blocks").toString().toLowerCase(Locale.ROOT) : "";
        if (catchText.contains("ignite") || catchText.contains("hurt") || catchText.contains("status")
                || blocksText.contains("fire")) {
            a.tags.add(FIGHTS);
        }
    }

    /** A motion's rule: one that leaves something changes blocks; one that wrecks hurts too, unless spared. */
    private static void eventMotion(Acc a, String motion, List<String> spares) {
        if (motion == null || motion.isBlank()) {
            return;
        }
        EventDesign.Motion m = null;
        for (EventDesign.Motion x : EventDesign.Motion.values()) {
            if (x.id().equals(motion.strip().toLowerCase(Locale.ROOT))) {
                m = x;
            }
        }
        if (m == null) {
            a.tags.add(CHANGES_BLOCKS);
            a.tags.add(FIGHTS);
            a.known = false;
            return;
        }
        if (!m.restoresByDefault()) {
            a.tags.add(CHANGES_BLOCKS);
        }
        boolean wrecks = m == EventDesign.Motion.VORTEX || m == EventDesign.Motion.MOVING_FRONT;
        if (wrecks && !spares.contains("builds")) {
            a.tags.add(CHANGES_BLOCKS);
        }
        if (wrecks && !spares.contains("creatures")) {
            a.tags.add(FIGHTS);
        }
    }

    // ── a stored thing ────────────────────────────────────────────────────────────────────────

    /**
     * A stored Builder spec (a kept make's, as {@code BuilderSpecStore} keeps it). One that does not read (a newer
     * Moddy's) is the cautious facts of the kind it names.
     */
    public static MakeFacts ofStoredSpec(JsonObject stored) {
        try {
            return of(BuilderSpec.read(stored));
        } catch (RuntimeException e) {
            BuilderSpec.Kind k = stored == null ? null : BuilderSpec.Kind.of(str(stored, "kind"));
            return cautious(k == null ? OTHER : k.id());
        }
    }

    /**
     * A thing a child keeps, read from its own stored design (what the Magician made, or what a recipe replays):
     * {@code creature} (a species), {@code item} (an item design; a machine and a magic block are items), {@code building}
     * (a build plan), {@code picture} (a painting), {@code event} (an event design). Null or a kind this does not read is
     * the cautious facts of that kind.
     */
    public static MakeFacts ofArtifact(String kind, JsonObject stored) {
        String k = kind == null ? OTHER : kind.strip().toLowerCase(Locale.ROOT);
        if (stored == null) {
            return cautious(k);
        }
        try {
            return switch (k) {
                case "creature", "ride" -> species(stored);
                case "item", "machine", "magic_block" -> item(stored);
                case "building" -> building(stored);
                case "picture" -> {
                    Acc a = new Acc(BuilderSpec.Kind.PICTURE.id());
                    a.tags.add(BUILDS);
                    a.inert = true;
                    yield a.done();
                }
                case "event" -> {
                    Acc a = new Acc(BuilderSpec.Kind.EVENT.id());
                    eventJson(a, stored);
                    yield a.done();
                }
                default -> cautious(k);
            };
        } catch (RuntimeException e) {
            return cautious(k);
        }
    }

    private static MakeFacts species(JsonObject stored) {
        Species s = Species.fromJson(stored);
        Acc a = new Acc(s.has(Species.Power.RIDEABLE) ? BuilderSpec.Kind.RIDE.id() : BuilderSpec.Kind.CREATURE.id());
        List<String> powers = new ArrayList<>();
        s.powers().forEach(p -> powers.add(p.name().toLowerCase(Locale.ROOT)));
        creatureLives(a, powers);
        if (s.base() == Species.Base.GUARDIAN) {
            a.tags.add(FIGHTS);
        }
        for (var ab : s.abilities()) {
            switch (ab) {
                case RANGED, SLAM, CHARGE -> a.tags.add(FIGHTS);
                case SUMMON -> a.tags.add(SUMMONS);
                default -> {
                }
            }
        }
        if (!s.abilityPacks().isEmpty()) {
            a.tags.add(FIGHTS);   // an ability pack is a compiled behaviour: rated as one that may catch others
        }
        if (a.tags.contains(HOSTILE)) {
            a.tags.add(FIGHTS);
        }
        a.most(SCALE_TIMES, s.scale());
        a.most(HEALTH_HALF_HEARTS, s.health());
        a.most(ATTACK_DAMAGE_HALF_HEARTS, s.damage());
        a.most(MOVEMENT_SPEED_ATTRIBUTE, s.speed());
        return a.done();
    }

    private static MakeFacts item(JsonObject stored) {
        String shape = str(stored, "shape");
        String kind = ItemCatalogue.MACHINE.equals(shape) ? BuilderSpec.Kind.MACHINE.id()
                : "magic_block".equals(shape) ? BuilderSpec.Kind.MAGIC_BLOCK.id() : BuilderSpec.Kind.ITEM.id();
        Acc a = new Acc(kind);
        itemJson(a, stored);
        return a.done();
    }

    private static MakeFacts building(JsonObject stored) {
        Acc a = new Acc(BuilderSpec.Kind.BUILDING.id());
        a.tags.add(BUILDS);
        int extent = 0;
        if (stored.get("shapes") instanceof JsonArray shapes) {
            for (JsonElement e : shapes) {
                if (e instanceof JsonObject s) {
                    extent = Math.max(extent, Math.abs(num(str(s, "dx"), 0)) + num(str(s, "width"), 1));
                    extent = Math.max(extent, Math.abs(num(str(s, "dz"), 0)) + num(str(s, "depth"), 1));
                    extent = Math.max(extent, Math.abs(num(str(s, "dy"), 0)) + num(str(s, "height"), 1));
                }
            }
        }
        if (extent > 0) {
            a.most(EXTENT_BLOCKS, extent);
        }
        return a.done();
    }

    /**
     * The facts of a thing that could not be read: the most a thing of {@code kind} could be. A creature may fight and
     * be hostile; an item, a machine or a magic block may fight and change blocks; an event may change blocks and
     * hurt; a rule changes the world; supplies give items; a building builds; a picture is a board on a wall; anything
     * else may be all of them. No numbers: nothing was measured.
     */
    public static MakeFacts cautious(String kind) {
        String k = kind == null ? OTHER : kind.strip().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "creature", "ride" -> new MakeFacts(k, Map.of(), Set.of(FIGHTS, HOSTILE), false);
            case "item", "machine", "magic_block" -> new MakeFacts(k, Map.of(), Set.of(FIGHTS, CHANGES_BLOCKS), false);
            case "event" -> new MakeFacts(k, Map.of(), Set.of(FIGHTS, CHANGES_BLOCKS), false);
            case "rule" -> new MakeFacts(k, Map.of(), Set.of(WORLD_RULE), false);
            case "supplies" -> new MakeFacts(k, Map.of(), Set.of(GIVES_ITEMS, HARMLESS), false);
            case "building" -> new MakeFacts(k, Map.of(), Set.of(BUILDS, HARMLESS), false);
            case "picture" -> new MakeFacts(k, Map.of(), Set.of(BUILDS, DECORATIVE, HARMLESS), false);
            default -> new MakeFacts(OTHER, Map.of(), Set.of(FIGHTS, HOSTILE, CHANGES_BLOCKS, SUMMONS, GIVES_ITEMS), false);
        };
    }

    // ── a recipe ──────────────────────────────────────────────────────────────────────────────

    /** Reads a stored design by the kind and id a replay command names; null when there is none. */
    @FunctionalInterface
    public interface Artifacts {
        JsonObject read(String kind, String id);
    }

    /** One thing a replay command makes: its kind ({@link #KINDS}), the id it is stored under (or a mob), how many. */
    public record Ref(String kind, String id, int count) {
    }

    /**
     * What one recipe command makes, or null when it makes no thing (a {@code say}, {@code time set}…). The replay
     * commands ({@code ReplayContract.ALL}): {@code wishmob spawn <id> [count] [at …]} (count: how many it spawns), {@code wishdatapack <id> <entry>},
     * {@code wishbuild <id>}, {@code wishpaint <id>}, {@code moddyevent stage <id>}; and the two plain ones a recipe may
     * carry, {@code summon <mob>} and {@code give <who> <item>}.
     */
    public static Ref ref(String command) {
        if (command == null) {
            return null;
        }
        String[] w = command.strip().replaceFirst("^/", "").split("\\s+");
        if (w.length == 0 || w[0].isEmpty()) {
            return null;
        }
        return switch (w[0]) {
            case "wishmob" -> w.length >= 3 && w[1].equals("spawn")
                    ? new Ref(BuilderSpec.Kind.CREATURE.id(), w[2], w.length >= 4 ? Math.max(1, num(w[3], 1)) : 1) : null;
            case "wishdatapack" -> w.length >= 2 ? new Ref(BuilderSpec.Kind.ITEM.id(), w[1], 1) : null;
            case "wishbuild" -> w.length >= 2 ? new Ref(BuilderSpec.Kind.BUILDING.id(), w[1], 1) : null;
            case "wishpaint" -> w.length >= 2 ? new Ref(BuilderSpec.Kind.PICTURE.id(), w[1], 1) : null;
            case "moddyevent" -> w.length >= 3 && w[1].equals("stage") ? new Ref(BuilderSpec.Kind.EVENT.id(), w[2], 1) : null;
            case "summon" -> w.length >= 2 ? new Ref(BuilderSpec.Kind.CREATURE.id(), "mob:" + w[1], 1) : null;
            case "give" -> w.length >= 3 ? new Ref(BuilderSpec.Kind.SUPPLIES.id(), w[2], 1) : null;
            default -> null;
        };
    }

    /**
     * A recipe's facts, from its commands: each thing it makes read from its stored design through {@code artifacts}
     * ({@link #ofArtifact}), a real mob or a plain give as the Builder rates one, all of them {@link #combine combined}.
     * A recipe whose commands make no thing the core knows is {@link #cautious} {@link #OTHER}.
     */
    public static MakeFacts ofCommands(List<String> commands, Artifacts artifacts) {
        List<MakeFacts> each = new ArrayList<>();
        boolean unknown = false;
        for (String c : commands == null ? List.<String>of() : commands) {
            Ref r = ref(c);
            if (r == null) {
                unknown = true;
                continue;
            }
            each.add(ofRef(r, artifacts));
        }
        if (each.isEmpty()) {
            return cautious(OTHER);
        }
        MakeFacts all = combine(each);
        return unknown ? new MakeFacts(all.kind(), all.numbers(), all.tags(), false) : all;
    }

    private static MakeFacts ofRef(Ref r, Artifacts artifacts) {
        if (r.id().startsWith("mob:")) {
            BuilderSpec mob = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE,
                    "mob:" + r.id().substring(4), "", 2, List.of(), "", r.count(), "", "", Map.of());
            return of(mob);
        }
        if (r.kind().equals(BuilderSpec.Kind.SUPPLIES.id())) {
            Acc a = new Acc(r.kind());
            a.tags.add(GIVES_ITEMS);
            return a.done();
        }
        JsonObject stored;
        try {
            stored = artifacts == null ? null : artifacts.read(r.kind(), r.id());
        } catch (RuntimeException e) {
            stored = null;
        }
        MakeFacts f = ofArtifact(r.kind(), stored);
        if (!f.known() || !r.kind().equals(BuilderSpec.Kind.CREATURE.id())) {
            return f;
        }
        Map<String, Double> n = new LinkedHashMap<>(f.numbers());
        n.put(COUNT_MADE, (double) r.count());   // the command says how many it spawns
        return new MakeFacts(f.kind(), n, f.tags(), true);
    }

    /**
     * Several things made at once (a recipe of a machine and its snack): the first one's kind, every tag (harmless and
     * decorative only when every one is), each number as the largest any of them carries (a number none carries stays
     * absent), known only when every one is.
     */
    public static MakeFacts combine(List<MakeFacts> facts) {
        if (facts == null || facts.isEmpty()) {
            return cautious(OTHER);
        }
        if (facts.size() == 1) {
            return facts.get(0);
        }
        Set<String> tags = new LinkedHashSet<>();
        Map<String, Double> numbers = new LinkedHashMap<>();
        boolean known = true;
        boolean harmless = true;
        boolean decorative = true;
        for (MakeFacts f : facts) {
            tags.addAll(f.tags());
            f.numbers().forEach((k, v) -> numbers.merge(k, v, Math::max));
            known &= f.known();
            harmless &= f.has(HARMLESS);
            decorative &= f.has(DECORATIVE);
        }
        if (!harmless) {
            tags.remove(HARMLESS);
        }
        if (!decorative) {
            tags.remove(DECORATIVE);
        }
        return new MakeFacts(facts.get(0).kind(), numbers, tags, known);
    }

    // ── small readers ─────────────────────────────────────────────────────────────────────────

    private static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static boolean has(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && !e.isJsonNull() && !(e.isJsonArray() && e.getAsJsonArray().isEmpty())
                && !(e.isJsonPrimitive() && e.getAsString().isBlank());
    }

    private static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e != null && e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (x.isJsonPrimitive()) {
                    out.add(x.getAsString());
                }
            }
        } else if (e != null && e.isJsonPrimitive()) {
            out.add(e.getAsString());
        }
        return out;
    }

    private static int num(String v, int dflt) {
        if (v == null || v.isBlank()) {
            return dflt;
        }
        try {
            return Math.round(Float.parseFloat(v.strip()));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
