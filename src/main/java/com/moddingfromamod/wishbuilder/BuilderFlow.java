package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.wish.ChildCommands;
import com.moddingfromamod.ui.TuneDialog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * <b>One child's walk through the Builder's menus</b> (B0b): which page comes next, what each
 * press means, and the spec it all becomes. Pure — every path is walked in Gate A
 * ({@code BuilderFlowTest}); {@link BuilderMenus} only opens what this says.
 *
 * <pre>
 *   KIND ─▶ FROM (a ready-made) ─▶ POWERS (creature, ride: ≤2 power tiles + a temperament) ─▶ TUNE
 *      └──▶ WHEN (rule) ─▶ [WHO: which mob, for "when a cow appears/dies"] ─▶ DOES ─▶ TUNE
 *   machine: SNACK_LOOK ─▶ SNACK_POWER (≤2) ─▶ SNACK_INGREDIENT ─▶ TUNE (: no ready-mades, it is composed)
 *   magic block: BLOCK_LOOK ─▶ BLOCK_WHEN ─▶ BLOCK_DOES (only what fits the trigger) ─▶ TUNE (composed)
 *   building: BUILD_TYPE ─▶ BUILD_OUTSIDE (what goes round it; none for one not on the ground) ─▶ TUNE
 *             a simple one, from parts: BUILD_TYPE ─▶ BUILD_ROOF ─▶ BUILD_WINDOWS ─▶ BUILD_OUTSIDE ─▶ TUNE (floors, door)
 *   picture:  PICT_SUBJECT ─▶ PICT_BACKGROUND (nothing to ask for one that fills its canvas) ─▶ TUNE (frame, colours)
 *   supplies: SUPPLY_CATEGORY ─▶ SUPPLY_WHAT (none to ask for a category of one) ─▶ TUNE (how many)
 *   item:     SHAPE ─▶ ITEM_POWER ─▶ TUNE; "Turn things into…" ─▶ TURN_FROM (several) ─▶ TURN_TO ─▶ TUNE
 * </pre>
 * Everything is PICKED from the world's allowlist ({@link BuilderSettings}); the tune dialog adds only
 * size, count, colours and the name. The dialog's Make carries a {@link #token} so a press from an old
 * dialog cannot make what the child is no longer looking at.
 */
public final class BuilderFlow {

    public enum Step { KIND, FROM, SHAPE, ITEM_POWER, EVENT_MOTION, EVENT_WHAT, SNACK_LOOK, SNACK_POWER, SNACK_INGREDIENT,
        BLOCK_LOOK, BLOCK_WHEN, BLOCK_DOES, BUILD_TYPE, BUILD_ROOF, BUILD_WINDOWS, BUILD_OUTSIDE, PICT_SUBJECT, PICT_BACKGROUND, PARTS, POWERS, ABILITIES, WHEN, WHO, DOES, SUPPLY_CATEGORY, SUPPLY_WHAT,
        TURN_FROM, TURN_TO, ITEM_LOOK_GROUP, ITEM_LOOK, TUNE }

    /** What a press did. {@code say} is a line for the child when the press was refused. */
    public record Outcome(Step step, boolean close, String say) {
    }

    private final BuilderAllowlist allow;
    private final LibraryPresets presets;
    public final String token = UUID.randomUUID().toString().substring(0, 8);

    private Step step = Step.KIND;
    private BuilderSpec.Kind kind;
    private String from = "";
    private final Set<String> powers = new LinkedHashSet<>();
    /** special moves ({@code ability:fire_breath}), up to two, from {@link BuilderAbilities}. */
    private final Set<String> abilities = new LinkedHashSet<>();
    /** parts added to a creature ({@code kit:horn}), as many as its body has room for. */
    private final Set<String> kits = new LinkedHashSet<>();
    private String temper = "friendly";
    /** the Magician-made creature this flow changes (null for every Builder make). */
    private MagicianCreature.Thing magician;
    /** slice 2: the Magician-made building this flow changes (null otherwise). */
    private MagicianBuilding.Thing magicianBuilding;
    private List<String> shelf = List.of();
    private String when = "";
    private String does = "";
    /** the mob a mob trigger is about ({@code mob:minecraft:cow}); empty for his own triggers. */
    private String who = "";
    /** Which page of the ready-mades is showing: more than a chest holds once the bodies arrived. */
    private int fromPage = 0;
    /** a made-up item's stock item and power ({@link BuilderItemPowers} ids). */
    private String itemShape = "";
    private String itemPower = "";
    /** the library icon he picked behind "🎨 More looks…" (it chose {@link #itemShape} too), and its page. */
    private String itemLook = "";
    private String lookGroup = "";
    /** "Turn things into…": what it turns (From group ids, in the order picked) and into what (a To id). */
    private final List<String> turnFrom = new ArrayList<>();
    private String turnTo = "";
    /**
     * the 🔍 Find over this page: {@code asking} while its box is open, then {@code found} (null when no results
     * page is showing). His words are kept only to fill the box again on "try again"; never saved, never logged.
     */
    private boolean asking;
    private List<BuilderFind.Result> found;
    private String findWords = "";
    /** §1: what his view and inventory held when the From page first opened (null: not read yet). */
    private BuilderSuggest.Seen seen;
    /** §1: his last To targets, newest first (null: not read yet). */
    private List<String> recentTo;
    /** a made-up event's motion and what it is made of ({@link BuilderEvents} ids); empty for a ready-made. */
    private String eventMotion = "";
    private String eventWhat = "";
    /** a composed snack machine's look, its powers ({@link BuilderItemPowers} ids) and what goes in it. */
    private String snackLook = "";
    private final Set<String> snackPowers = new LinkedHashSet<>();
    private String snackIngredient = "";
    /** a composed magic block's look, trigger and what it does ({@link BuilderBlocks} ids). */
    private String blockLook = "";
    private String blockWhen = "";
    private String blockDoes = "";
    /** a composed building's type and what goes outside it ({@link BuilderBuildings} ids). */
    private String buildType = "";
    private String buildOutside = "";
    /** a simple building's roof and windows ({@link BuilderHouses}); its floors and door are tuned. */
    private String buildRoof = "";
    /** a composed picture's subject (its {@code pictures/*} drawing) and what is behind it ({@link BuilderPictures}). */
    private String pictSubject = "";
    private String pictBackground = "";
    private String buildWindows = "";
    /** the supplies category he picked ({@link BuilderSupplies} id); the supply itself is {@link #from}. */
    private String supplyCategory = "";
    /**
     * The tile that once started a made-up thing from a ready-made page. No page offers it (every kind is a
     * pure builder); kept as the id older logs and Bedrock cases name.
     */
    public static final String MAKE_UP_ID = "parts:new";
    /** a stock item's tile id prefix on the item kind's "What is it?" page. */
    public static final String STOCK_PREFIX = "stock:";
    /** the id of the make this flow CHANGES, or null for a new make; {@link #prior} is its spec. */
    private String editOf;
    private BuilderSpec prior;

    /**
     * what the tune dialog starts at when the Apprentice opened this flow from typed words (a size, a count,
     * a strength, a fill, a colour scheme). Only where the dialog shows a control: he can still change every one.
     */
    private final Map<String, String> suggested = new LinkedHashMap<>();

    /** Start the tune dialog's controls at {@code values} (keys as the dialog's: size, count, strength, fill, scheme). */
    public BuilderFlow suggest(Map<String, String> values) {
        suggested.clear();
        suggested.putAll(values);
        return this;
    }

    private int start(String key, int dflt, int min, int max) {
        return Math.max(min, Math.min(max, num(suggested.get(key), dflt)));
    }

    public BuilderFlow(BuilderAllowlist allow, LibraryPresets presets) {
        this.allow = allow;
        this.presets = presets;
    }

    /**
     * This flow as it would be after pressing {@code id}, or null when the press goes nowhere (a control, a press it
     * refuses, one that closes). THIS flow is never changed: the press runs on a copy. What a page's tile leads to, so a
     * world's view of a tile is the view of the make that pressing it heads for (#14).
     */
    public BuilderFlow after(String id) {
        if (id == null || id.isBlank() || id.startsWith("@") && !id.equals(PickPage.NEXT_ID)) {
            return null;
        }
        BuilderFlow c = copy();
        try {
            Outcome o = c.press(id);
            return o.close() || !o.say().isEmpty() || c.kind == null ? null : c;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A copy of this flow, for work off the server thread: the open flow itself is never handed away. */
    public BuilderFlow snapshot() {
        return copy();
    }

    /** The make this flow holds so far, with the tune dialog's defaults, or null when it holds none yet. Never throws. */
    public BuilderSpec draft() {
        if (kind == null) {
            return null;
        }
        try {
            return spec(Map.of());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A copy holding every pick this one holds. Every instance field is copied (a collection into a fresh one of the
     * same kind), so a field added later is copied with no change here; {@code BuilderFlowCopyTest} proves a copy
     * pages and specs as its original does.
     */
    private BuilderFlow copy() {
        BuilderFlow c = new BuilderFlow(allow, presets);
        for (java.lang.reflect.Field f : BuilderFlow.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getName().equals("token")) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object v = f.get(this);
                if (java.lang.reflect.Modifier.isFinal(f.getModifiers())) {
                    Object mine = f.get(c);
                    if (mine instanceof java.util.Collection<?> col && v instanceof java.util.Collection<?> src) {
                        @SuppressWarnings("unchecked") java.util.Collection<Object> to = (java.util.Collection<Object>) col;
                        to.clear();
                        to.addAll(src);
                    } else if (mine instanceof Map<?, ?> m && v instanceof Map<?, ?> src) {
                        @SuppressWarnings("unchecked") Map<Object, Object> to = (Map<Object, Object>) m;
                        to.clear();
                        to.putAll(src);
                    }
                    continue;   // allow and presets: the constructor's
                }
                if (v instanceof Set<?> set) {
                    v = new LinkedHashSet<>(set);
                } else if (v instanceof List<?> list) {
                    v = new ArrayList<>(list);
                } else if (v instanceof Map<?, ?> map) {
                    v = new LinkedHashMap<>(map);
                }
                f.set(c, v);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot copy BuilderFlow." + f.getName(), e);
            }
        }
        return c;
    }

    /**
     * "Change one thing": a flow reopened from a kept make, every pick he made already made — at the
     * powers page for a creature or a ride (where most changes are), a rule at its "when" page, else straight at
     * the tune dialog with his
     * size, colours and name in it. It cannot become a different KIND of thing (an edit changes the one he has).
     */
    public static BuilderFlow editing(BuilderAllowlist allow, LibraryPresets presets, String id, BuilderSpec spec) {
        BuilderFlow f = new BuilderFlow(allow, presets);
        f.editOf = id;
        f.prior = spec;
        f.kind = spec.kind();
        f.from = spec.from();
        for (String k : BuilderParts.chosen(spec)) {
            f.kits.add("kit:" + k);
        }
        for (String pw : spec.powers()) {
            (Catalogue.prefix(pw).equals("ability") ? f.abilities : f.powers).add(pw);
        }
        f.temper = spec.temper().isEmpty() ? "friendly" : spec.temper();
        f.when = spec.opt("when");
        f.does = spec.opt("does");
        f.who = spec.opt("who");
        BuilderItemPowers.Stock stock = spec.kind() == BuilderSpec.Kind.ITEM ? BuilderItemPowers.of(spec) : null;
        f.itemShape = stock == null ? "" : stock.id();
        f.itemPower = spec.opt("power");
        f.itemLook = spec.opt(BuilderLooks.SLOT);   //
        BuilderLooks.Look had = BuilderLooks.look(f.itemLook);
        f.lookGroup = had == null ? "" : had.group();
        f.turnFrom.addAll(BuilderTurnInto.parse(spec.opt(BuilderTurnInto.FROM_SLOT)).stream().map(BuilderTurnInto.From::id).toList());
        f.turnTo = spec.opt(BuilderTurnInto.TO_SLOT);   //
        f.eventMotion = spec.opt("event");
        f.eventWhat = spec.opt("what");
        if (BuilderSnacks.composed(spec)) {   //
            f.snackLook = Catalogue.bare(spec.opt("look"));
            f.snackPowers.addAll(BuilderSnacks.chosenPowers(spec));
            f.snackIngredient = Catalogue.bare(spec.opt("ingredient"));
        }
        if (BuilderHouses.composed(spec)) {   //: built from parts
            f.buildType = spec.opt("type");
            f.buildRoof = spec.opt("roof");
            f.buildWindows = spec.opt("windows");
            f.buildOutside = spec.opt("outside").isEmpty() ? BuilderBuildings.NOTHING : spec.opt("outside");
        }
        if (BuilderBuildings.composed(spec)) {   //
            f.buildType = BuilderBuildings.type(spec.opt("type")).id();
            f.buildOutside = spec.opt("outside").isEmpty() ? BuilderBuildings.NOTHING : spec.opt("outside");
        }
        if (BuilderPictures.composed(spec)) {   //
            f.pictSubject = BuilderPictures.subject(spec.opt("subject"));
            f.pictBackground = spec.opt("background").isEmpty() ? BuilderPictures.DRAWN : spec.opt("background");
        }
        if (BuilderBlocks.composed(spec)) {   //
            f.blockLook = spec.opt("block_look");
            f.blockWhen = spec.opt("block_when");
            f.blockDoes = spec.opt("block_does");
        }
        // A rule's dialog asks nothing, so a rule reopens at "when" (both its picks are on the way to the end).
        f.step = switch (f.kind) {
            case CREATURE, RIDE -> Step.POWERS;
            case RULE -> Step.WHEN;
            default -> f.firstComposedPage();
        };
        return f;
    }

    /**
     * slice 1: the Builder opened on a creature the MAGICIAN made. Its Builder powers are ticked and its temper (if
     * its powers say one) is on, read from its own record; it opens at the powers page, whose Back stays put (its body is
     * the Magician's: a greyed tile says so), and its dialog asks only its size, as a step either way from what it is.
     * What it sends is {@link MagicianCreature#calls}: only what changed, everything else kept.
     */
    public static BuilderFlow editingMagician(BuilderAllowlist allow, LibraryPresets presets, MagicianCreature.Thing thing) {
        BuilderFlow f = new BuilderFlow(allow, presets);
        f.magician = thing;
        f.editOf = thing.id();
        f.kind = BuilderSpec.Kind.CREATURE;
        f.prior = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "", "", MagicianCreature.NOW, List.of(), "", 1,
                "", thing.name(), Map.of());
        for (String p : MagicianCreature.builderPowers(thing)) {
            if (allow.ids().contains(p)) {
                f.powers.add(p);
            }
        }
        f.temper = MagicianCreature.temper(thing);
        f.step = Step.POWERS;
        return f;
    }

    /** the Magician creature this flow changes, or null. */
    public MagicianCreature.Thing magician() {
        return magician;
    }

    /**
     * slice 2: the Builder opened on a building the MAGICIAN made. It opens on what goes outside it (nothing
     * picked: what is added is new), with its size shown blocked and why, and its dialog asks its colours. What it sends
     * is {@link MagicianBuilding#calls}.
     */
    public static BuilderFlow editingMagicianBuilding(BuilderAllowlist allow, LibraryPresets presets, MagicianBuilding.Thing thing) {
        BuilderFlow f = new BuilderFlow(allow, presets);
        f.magicianBuilding = thing;
        f.editOf = thing.id();
        f.kind = BuilderSpec.Kind.BUILDING;
        f.prior = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.BUILDING, "", "", 2, List.of(), "", 1, "", thing.name(),
                Map.of());
        f.buildOutside = BuilderBuildings.NOTHING;
        f.step = Step.BUILD_OUTSIDE;
        return f;
    }

    /** slice 2: the Magician building this flow changes, or null. */
    public MagicianBuilding.Thing magicianBuilding() {
        return magicianBuilding;
    }

    /** slice 2: what a Magician building's change sends, from the dialog's values. Empty: nothing changed. */
    public List<BuilderLowering.Call> magicianBuildingCalls(Map<String, String> values) {
        if (magicianBuilding == null) {
            throw new IllegalStateException("not a Magician building");
        }
        return MagicianBuilding.calls(magicianBuilding, values.getOrDefault("scheme", "as_is"),
                BuilderBuildings.parse(buildOutside));
    }

    /** what a Magician creature's change sends, from the dialog's values. Empty: nothing changed. */
    public List<BuilderLowering.Call> magicianCalls(Map<String, String> values) {
        if (magician == null) {
            throw new IllegalStateException("not a Magician creature");
        }
        int size = num(values.get("size"), MagicianCreature.NOW);
        List<String> mine = new ArrayList<>(powers);
        // A Builder power the world's Builder does not offer cannot be ticked or unticked here: it stays as it was.
        for (String p : MagicianCreature.builderPowers(magician)) {
            if (!allow.ids().contains(p) && !mine.contains(p)) {
                mine.add(p);
            }
        }
        return MagicianCreature.calls(magician, Math.max(1, Math.min(4, size)), mine, temper,
                values.getOrDefault("scheme", "as_is"));
    }

    /**
     * where an edit of a COMPOSED make reopens: the first page its kind's make went through (an item's shape, a
     * snack's look, a magic block's look, a building's type, a picture's subject, an event's motion), so every choice
     * after it can be changed as well as the tune. A ready-made (a library preset) went through no such page: it
     * reopens at the tune dialog, as it always has. The page's own Back stays put in an edit (an edit keeps its kind).
     */
    private Step firstComposedPage() {
        return switch (kind) {
            case ITEM -> itemShape.isEmpty() ? Step.TUNE : Step.SHAPE;
            case MACHINE -> snackLook.isEmpty() ? Step.TUNE : Step.SNACK_LOOK;
            case MAGIC_BLOCK -> blockLook.isEmpty() ? Step.TUNE : Step.BLOCK_LOOK;
            case BUILDING -> buildType.isEmpty() ? Step.TUNE : Step.BUILD_TYPE;
            case PICTURE -> pictSubject.isEmpty() ? Step.TUNE : Step.PICT_SUBJECT;
            case EVENT -> eventMotion.isEmpty() ? Step.TUNE : Step.EVENT_MOTION;
            default -> Step.TUNE;
        };
    }

    /** The make this flow changes, or null when it makes a new thing. */
    public String editOf() {
        return editOf;
    }

    /**
     * the press that keeps THIS page's current choice, for the editor ({@link BuilderEdit}) walking past a page
     * it does not change, exactly as a child pressing the same tile again would. Null on a multi-pick page (Next walks
     * past it), at the tune step, or where nothing is chosen. A page added to the Builder must answer here, or an edit
     * that walks past it stops there: {@code BuilderEditTest} walks every example make through and fails by name.
     */
    String keepPress() {
        return switch (step) {
            case FROM -> from.startsWith("preset:") ? from.substring("preset:".length()) : from.isEmpty() ? null : from;
            case SHAPE -> !itemLook.isEmpty() ? BuilderLooks.MORE_ID : itemShape.isEmpty() ? null : STOCK_PREFIX + itemShape;
            case ITEM_LOOK_GROUP -> lookGroup.isEmpty() ? null : BuilderLooks.GROUP_PREFIX + lookGroup;   //
            case ITEM_LOOK -> itemLook.isEmpty() ? null : BuilderLooks.PREFIX + itemLook;
            case ITEM_POWER -> itemPower.isEmpty() ? null : "itempower:" + itemPower;
            case TURN_TO -> turnTo.isEmpty() ? null : BuilderTurnInto.TO_PREFIX + turnTo;
            case EVENT_MOTION -> eventMotion.isEmpty() ? null : "event:" + eventMotion;
            case EVENT_WHAT -> eventWhat.isEmpty() ? null : "what:" + eventWhat;
            case SNACK_LOOK -> snackLook.isEmpty() ? null : "look:" + snackLook;
            case SNACK_INGREDIENT -> snackIngredient.isEmpty() ? null : "ingredient:" + snackIngredient;
            case BLOCK_LOOK -> blockLook.isEmpty() ? null : "blocklook:" + blockLook;
            case BLOCK_WHEN -> blockWhen.isEmpty() ? null : "blockwhen:" + blockWhen;
            case BLOCK_DOES -> blockDoes.isEmpty() ? null : "blockdoes:" + blockDoes;
            case BUILD_TYPE -> buildType.isEmpty() ? null
                    : buildType.startsWith("simple:") ? buildType
                    : BuilderBuildings.type(buildType) == null ? null : BuilderBuildings.type(buildType).preset();
            case BUILD_ROOF -> buildRoof.isEmpty() ? null : "roof:" + buildRoof;
            case BUILD_WINDOWS -> buildWindows.isEmpty() ? null : "windows:" + buildWindows;
            case PICT_SUBJECT -> pictSubject.isEmpty() ? null : pictSubject;
            case PICT_BACKGROUND -> pictBackground.isEmpty() ? null : "background:" + pictBackground;
            case WHEN -> when.isEmpty() ? null : when;
            case WHO -> who.isEmpty() ? null : who;
            case DOES -> does.isEmpty() ? null : does;
            default -> null;
        };
    }

    /** step 6: a creature or a ride the Builder sculpts (not a real farm animal) has a "How fast". */
    private boolean paced() {
        return (kind == BuilderSpec.Kind.CREATURE || kind == BuilderSpec.Kind.RIDE) && !realAnimal();
    }

    /** a page where taps toggle picks and Next moves on (the editor presses Next to walk past it). */
    static boolean multiPick(Step s) {
        return switch (s) {
            case POWERS, PARTS, ABILITIES, SNACK_POWER, BUILD_OUTSIDE, TURN_FROM -> true;
            default -> false;
        };
    }

    public Step step() {
        return step;
    }

    public BuilderSpec.Kind kind() {
        return kind;
    }

    // ------------------------------------------------------------------ the pages

    static final Map<BuilderSpec.Kind, String[]> KIND_TILES = new LinkedHashMap<>();

    static {
        KIND_TILES.put(BuilderSpec.Kind.CREATURE, new String[] {"A creature", "minecraft:egg"});
        KIND_TILES.put(BuilderSpec.Kind.RIDE, new String[] {"Something to ride", "minecraft:saddle"});
        KIND_TILES.put(BuilderSpec.Kind.ITEM, new String[] {"A magic item", "minecraft:blaze_rod"});
        KIND_TILES.put(BuilderSpec.Kind.MACHINE, new String[] {"A machine", "minecraft:blast_furnace"});
        KIND_TILES.put(BuilderSpec.Kind.MAGIC_BLOCK, new String[] {"A magic block", "minecraft:gold_block"});
        KIND_TILES.put(BuilderSpec.Kind.BUILDING, new String[] {"A building", "minecraft:bricks"});
        KIND_TILES.put(BuilderSpec.Kind.PICTURE, new String[] {"A picture", "minecraft:painting"});
        KIND_TILES.put(BuilderSpec.Kind.EVENT, new String[] {"Something happens", "minecraft:fire_charge"});
        KIND_TILES.put(BuilderSpec.Kind.RULE, new String[] {"A new world rule", "minecraft:writable_book"});
        // last, so no older kind's tile moves
        KIND_TILES.put(BuilderSpec.Kind.SUPPLIES, new String[] {"Supplies", "minecraft:chest"});
    }

    static final Map<String, String> POWER_WORDS = Map.of(
            "flies", "Flies", "glowing", "Glows", "rideable", "I can ride it", "hops", "Hops",
            "leaps", "Pounces", "fireproof", "Fireproof", "naps", "Naps", "persistent", "Stays forever");

    /** Each power's own picture, so the tiles can be told apart at a glance. */
    static final Map<String, String> POWER_ICONS = Map.of(
            "flies", "minecraft:feather", "glowing", "minecraft:glowstone_dust", "rideable", "minecraft:saddle",
            "hops", "minecraft:rabbit_foot", "leaps", "minecraft:slime_ball", "fireproof", "minecraft:magma_cream",
            "naps", "minecraft:red_bed", "persistent", "minecraft:name_tag");

    static final Map<String, String[]> TEMPER_TILES = new LinkedHashMap<>();

    static {
        TEMPER_TILES.put("friendly", new String[] {"Friendly", "minecraft:poppy"});
        TEMPER_TILES.put("shy", new String[] {"Shy", "minecraft:fern"});
        TEMPER_TILES.put("wild", new String[] {"Wild (it fights)", "minecraft:bone"});
        // Not a shield: its empty banner-pattern list makes the scripted Bedrock client (bedrock-protocol) read a
        // phantom slot, shifting every later slot by one (measured 2026-11-08). A real client is unaffected.
        TEMPER_TILES.put("guard", new String[] {"Guard (fights monsters)", "minecraft:iron_sword"});
        TEMPER_TILES.put("boss", new String[] {"Boss", "minecraft:wither_skeleton_skull"});
        // step 6: after every older tile, so none of theirs moves on any edition.
        TEMPER_TILES.put("pet", new String[] {"Follows you", "minecraft:lead"});
        TEMPER_TILES.put("stays", new String[] {"Stays put", "minecraft:oak_fence"});
    }

    static final Map<String, String> RULE_TRIGGER_WORDS = Map.ofEntries(
            Map.entry("jumps", "When I jump"), Map.entry("sneaks", "When I sneak"), Map.entry("sprints", "When I sprint"),
            Map.entry("lands", "When I land"), Map.entry("sunrise", "At sunrise"), Map.entry("rain_starts", "When it rains"),
            Map.entry("mob_spawns", "When one appears"), Map.entry("mob_dies", "When one is defeated"),
            // more of what WorldRule already watches for
            Map.entry("sunset", "At sunset"), Map.entry("rain_stops", "When the rain stops"),
            Map.entry("fish_caught", "When I catch a fish"), Map.entry("every", "Every 10 seconds"));

    /** Each trigger's own picture: a page of clocks could not be told apart. */
    static final Map<String, String> RULE_TRIGGER_ICONS = Map.ofEntries(
            Map.entry("jumps", "minecraft:rabbit_foot"), Map.entry("sneaks", "minecraft:leather_boots"),
            Map.entry("sprints", "minecraft:sugar"), Map.entry("lands", "minecraft:feather"),
            Map.entry("sunrise", "minecraft:sunflower"), Map.entry("rain_starts", "minecraft:water_bucket"),
            Map.entry("mob_spawns", "minecraft:egg"), Map.entry("mob_dies", "minecraft:bone"),
            Map.entry("sunset", "minecraft:red_bed"), Map.entry("rain_stops", "minecraft:bucket"),
            Map.entry("fish_caught", "minecraft:fishing_rod"), Map.entry("every", "minecraft:clock"));

    /** The page for the current step. */
    public PickPage page() {
        if (found != null) {
            return foundPage();
        }
        return switch (step) {
            case KIND -> PickPage.of("Wish Builder: what shall we make?", kindTiles(), false);
            case FROM -> fromPage();
            case SHAPE -> PickPage.of("What is it?", withLooks(shapeTiles()), true);
            case ITEM_LOOK_GROUP -> PickPage.of("What does it look like?", lookGroupTiles(), true);   //
            case ITEM_LOOK -> PickPage.of("Which one?", lookTiles(), true);
            case ITEM_POWER -> PickPage.of("What does it do?", itemPowerTiles(), true);
            case EVENT_MOTION -> PickPage.of("What happens?", eventMotionTiles(), true);
            case EVENT_WHAT -> PickPage.of("What is it made of?", withFind(eventWhatTiles()), true);
            case SNACK_LOOK -> PickPage.of("What does your machine make?", snackLookTiles(), true);
            case SNACK_POWER -> multi("What eating it does", snackPowerTiles(), null);
            case SNACK_INGREDIENT -> PickPage.of("What goes in it?", snackIngredientTiles(), true);
            case BLOCK_LOOK -> PickPage.of("What is your block made of?", blockLookTiles(), true);
            case BLOCK_WHEN -> PickPage.of("When does it work?", blockWhenTiles(), true);
            case BLOCK_DOES -> PickPage.of("What does it do?", blockDoesTiles(), true);
            case BUILD_TYPE -> PickPage.of("What will you build?", buildTypeTiles(), true);
            case BUILD_OUTSIDE -> magicianBuilding == null ? multi("What goes outside?", buildOutsideTiles(), null)
                    : multi("Change " + (magicianBuilding.name().isEmpty() ? "it" : magicianBuilding.name()) + " "
                            + MagicianCreature.MARK, withMagicianSize(buildOutsideTiles()), null);   // every grounded type takes all of them (the 32-subset walk/stand sweeps)
            case PICT_SUBJECT -> subjectPage();
            case PICT_BACKGROUND -> PickPage.of("What is behind it?", backgroundTiles(), true);
            case BUILD_ROOF -> PickPage.of("What kind of roof?", tiles("roof:", BuilderHouses.ROOFS), true);
            case BUILD_WINDOWS -> PickPage.of("How many windows?", tiles("windows:", BuilderHouses.WINDOWS), true);
            case POWERS -> magician == null ? multi("Powers", powerTiles(), null)
                    : multi("Change " + (magician.name().isEmpty() ? "it" : magician.name()) + " " + MagicianCreature.MARK,
                            withMagicianBody(powerTiles()), null);
            case PARTS -> multi("Parts", partTiles(), partsRoom());
            case ABILITIES -> multi("Special moves", abilityTiles(), null);
            case WHEN -> PickPage.of("When does it happen?", whenTiles(), true);
            case WHO -> PickPage.of("Which animal or monster?", whoTiles(), true);
            case DOES -> PickPage.of("What happens?", doesTiles(), true);
            case SUPPLY_CATEGORY -> PickPage.of("What do you need?", withFind(supplyCategoryTiles()), true);
            case SUPPLY_WHAT -> PickPage.of("Which one?", supplyTiles(), true);
            case TURN_FROM -> multi(BuilderTurnInto.FROM_TITLE, withFind(turnFromTiles()), null);   //
            case TURN_TO -> PickPage.of(BuilderTurnInto.TO_TITLE, withFind(turnToTiles()), true);
            case TUNE -> throw new IllegalStateException("the tune step is a dialog, not a page");
        };
    }

    /**
     * a MULTI-PICK page — its picks shown in a row inside the chest after the thing being made (ui/PickPage.multi).
     * Only the toggling tiles count: a mood beside the powers is a one-of choice on the same page.: no count is
     * typed here — a tile is grey, with the reason, only when {@link BuilderLimits} says the make could not take it
     * now ({@link #whyNot}), and {@code room} is the body's real room for parts (null for none).
     */
    PickPage multi(String title, List<PickPage.Tile> tiles, String room) {
        Set<String> counted = new LinkedHashSet<>();
        Map<String, String> blocked = new LinkedHashMap<>();
        for (PickPage.Tile t : tiles) {
            if (!t.id().startsWith("temper:") && !t.id().equals(BuilderFind.TILE_ID)) {
                counted.add(t.id());
                String why = t.selected() ? null : whyNot(t.id());
                if (why != null) {
                    blocked.put(t.id(), why);
                }
            }
        }
        return PickPage.multi(title, tiles, PickPage.Picks.open(subject(), counted, blocked, room));
    }

    /**
     * why the tile {@code id} cannot be added to what is chosen on this page, in his words, or null. The ONE
     * check the page greys a tile by and a press is refused by: {@link BuilderLimits}, the lowering's own.
     */
    String whyNot(String id) {
        return switch (step) {
            case ABILITIES -> BuilderLimits.whyNot("abilities", bare(abilities), Catalogue.bare(id));
            case SNACK_POWER -> BuilderLimits.whyNot("snack_powers", new ArrayList<>(snackPowers), id.substring("snackpower:".length()));
            case TURN_FROM -> BuilderTurnInto.from(id) == null ? null
                    : BuilderTurnInto.whyNotFrom(BuilderTurnInto.parse(String.join(",", turnFrom)), BuilderTurnInto.from(id));   //
            case PARTS -> {
                List<String> would = bare(kits);
                would.add(Catalogue.bare(id));
                JsonArray body = bodyParts();
                yield body == null ? null
                        : BuilderLimits.parts(BuilderParts.boxes(would, body, ""), BuilderParts.budget(body.size()), presets.label(Catalogue.bare(from)));
            }
            // the Magician's body is shown, never changed: a blocked tile, its reason under its name on a phone.
            case POWERS -> magician != null && MagicianCreature.BODY_ID.equals(id) ? MagicianCreature.BODY_LINE : null;
            case BUILD_OUTSIDE -> magicianBuilding != null && MagicianBuilding.SIZE_ID.equals(id) ? MagicianBuilding.SIZE_LINE : null;
            default -> null;   // powers are flags on the creature: it takes them all
        };
    }

    /**
     * he went Back and picked another ready-made — keep each pick its pages would still take, in the order he
     * picked them, and drop the rest: a horn that fitted a dragon may not fit a hamster, and a truck takes no ears.
     * Found by BuilderLoweringTest's walks: a flying saucer kept a cat's four parts and was refused at Make.
     */
    private void keepWhatStillFits() {
        Set<String> offeredParts = new LinkedHashSet<>();
        partTiles().forEach(t -> offeredParts.add(t.id()));
        List<String> was = new ArrayList<>(kits);
        kits.clear();
        JsonArray body = bodyParts();
        for (String k : was) {
            List<String> would = bare(kits);
            would.add(Catalogue.bare(k));
            if (offeredParts.contains(k) && (body == null
                    || BuilderParts.boxes(would, body, "") <= BuilderParts.budget(body.size()))) {
                kits.add(k);
            }
        }
        Set<String> offered = new LinkedHashSet<>();
        powerTiles().forEach(t -> offered.add(t.id()));
        powers.retainAll(offered);
        if (!offered.contains("temper:" + temper)) {
            temper = "friendly";   // a vehicle has no mood
        }
        Set<String> moves = new LinkedHashSet<>();
        abilityTiles().forEach(t -> moves.add(t.id()));
        abilities.retainAll(moves);
    }

    /** The ready-made's own parts (its sculpt), or null when it has none to measure room on. */
    private JsonArray bodyParts() {
        // a parts page asks once per tile (whyNot), so the body is read once per ready-made, not once per tile.
        if (!from.equals(bodyFrom)) {
            JsonObject pre = from.isEmpty() ? null : presets.read(Catalogue.bare(from));
            bodyFromParts = pre != null && pre.has("parts") ? pre.getAsJsonArray("parts") : null;
            bodyFrom = from;
        }
        return bodyFromParts;
    }

    /** {@link #bodyParts}' memo: the ready-made it was read for, and its parts (only ever read). */
    private String bodyFrom;
    private JsonArray bodyFromParts;

    /** The parts page's room line: "Body room 7/12 boxes", or null with no body to measure. */
    String partsRoom() {
        JsonArray body = bodyParts();
        return body == null ? null : BuilderLimits.room(BuilderParts.boxes(bare(kits), body, ""), BuilderParts.budget(body.size()));
    }

    private static List<String> bare(java.util.Collection<String> ids) {
        List<String> out = new ArrayList<>();
        ids.forEach(x -> out.add(Catalogue.bare(x)));
        return out;
    }

    /** The thing being made, first in the picks row: its ready-made, or a composed snack's look. */
    PickPage.Tile subject() {
        if (!snackLook.isEmpty()) {
            for (BuilderSnacks.Look l : BuilderSnacks.LOOKS) {
                if (l.id().equals(snackLook)) {
                    return PickPage.Tile.of("subject", l.tile(), l.label());
                }
            }
        }
        if (!from.isEmpty()) {
            String p = Catalogue.bare(from);
            return PickPage.Tile.of("subject", iconFor(p), presets.label(p));
        }
        if (kind == BuilderSpec.Kind.ITEM && BuilderItemPowers.stock(itemShape) != null) {   //: the item first
            BuilderItemPowers.Stock st = BuilderItemPowers.tiered(BuilderItemPowers.stock(itemShape), allow.itemTier());
            if (st != null) {
                return PickPage.Tile.of("subject", st.tile(), st.label());
            }
        }
        if (kind == BuilderSpec.Kind.BUILDING && !buildType.isEmpty()) {   // the building first in its picks row
            for (PickPage.Tile t : buildTypeTiles()) {
                if (t.id().equals(buildType) || t.id().equals(BuilderBuildings.type(buildType) == null ? "" : BuilderBuildings.type(buildType).preset())) {
                    return PickPage.Tile.of("subject", t.item(), t.label());
                }
            }
        }
        return null;
    }

    List<PickPage.Tile> kindTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        KIND_TILES.forEach((k, v) -> {
            boolean any = switch (k) {
                case RULE -> !whenIds().isEmpty();
                case ITEM -> !stockTiles().isEmpty();   //: an item is made from scratch, never picked ready
                case MACHINE -> composerOpen();          //: so is a snack machine
                case MAGIC_BLOCK -> !blockLookTiles().isEmpty();   //: and a magic block
                case BUILDING -> !buildTypeTiles().isEmpty();       //: and a building
                case PICTURE -> !subjectIds().isEmpty();             //: and a picture
                case EVENT -> !eventMotionTiles().isEmpty();         //: and an event
                case SUPPLIES -> !supplyCategoryTiles().isEmpty();   //
                default -> !fromIds(k).isEmpty();
            };
            if (allow.kinds().contains(k) && any) {
                out.add(PickPage.Tile.of(k.id(), v[1], v[0]));
            }
        });
        return out;
    }

    /** The ready-mades a kind starts from, as {@code species/cat} — reviewed ones only. */
    List<String> fromIds(BuilderSpec.Kind k) {
        String dir = switch (k) {
            case CREATURE, RIDE -> "species/";
            case ITEM, MACHINE, MAGIC_BLOCK -> "items/";
            case BUILDING -> "buildings/";
            case PICTURE -> "pictures/";
            case EVENT -> "events/";
            case RULE, SUPPLIES -> "";
        };
        List<String> out = new ArrayList<>();
        if (dir.isEmpty()) {
            return out;
        }
        for (String p : Catalogue.presets()) {
            boolean body = dir.equals("species/") && p.startsWith("bodies/");   // the Builder's own bodies
            if (!(p.startsWith(dir) || body) || !allow.ids().contains("preset:" + p)) {
                continue;
            }
            JsonObject j = presets.read(p);
            if (j == null) {
                continue;
            }
            if (dir.equals("items/")) {
                String shape = j.has("shape") ? j.get("shape").getAsString() : "";
                // no ITEM ready-mades — each is a composition of a stock item and a power
                // (BuilderItemPowers.READY_MADES), made on the item page like any other.
                // nor MACHINE ready-mades — each of B5's is a composition (BuilderSnacks.READY_MADES).
                // nor MAGIC_BLOCK ones — the Lucky Block is a composition too (BuilderBlocks.READY_MADES).
                continue;
            }
            if (dir.equals("buildings/")) {
                continue;   //: nor BUILDING ones — each is a type (BuilderBuildings), picked on "What will you build?"
            }
            if (dir.equals("pictures/")) {
                continue;   //: nor PICTURE ones — each is a subject (BuilderPictures), picked on "What will you paint?"
            }
            if (dir.equals("events/")) {
                continue;   //: nor EVENT ones — each is a motion and what it is made of (BuilderEvents.READY_MADES)
            }
            out.add(p);
        }
        if (k == BuilderSpec.Kind.RIDE) {
            // Something to ride: the ones made to be ridden (the vehicles, a horse) come first. A stable sort.
            List<String> rideable = new ArrayList<>(out.stream().filter(this::rideable).toList());
            out.removeAll(rideable);
            rideable.addAll(out);
            return rideable;
        }
        return out;
    }

    /** The ready-mades, a chest at a time: "More" turns the page (PickPage's Next control, the same on every edition). */
    PickPage fromPage() {
        List<List<PickPage.Tile>> pages = PickPage.pages(fromTiles());
        int p = Math.floorMod(fromPage, pages.size());
        // a creature or a ride starts from a BODY, which brings nothing but itself (its look and how it moves).
        String words = kind == BuilderSpec.Kind.CREATURE || kind == BuilderSpec.Kind.RIDE ? "Pick a body" : "Pick one to start from";
        String title = pages.size() > 1 ? words + " (" + (p + 1) + "/" + pages.size() + ")" : words;
        return new PickPage(title, pages.get(p), true, pages.size() > 1, "More");
    }

    int fromPages() {
        return PickPage.pages(fromTiles()).size();
    }

    private boolean rideable(String preset) {
        JsonObject j = presets.read(preset);
        return j != null && j.has("powers") && j.get("powers").toString().contains("\"rideable\"");
    }

    /** can a snack machine be composed here (a look, a snack power and something to put in, all allowed)? */
    boolean composerOpen() {
        return !snackLookTiles().isEmpty() && !snackPowerTiles().isEmpty()
                && BuilderSnacks.INGREDIENTS.stream().anyMatch(i -> allow.ids().contains("ingredient:" + i));
    }

    /** the food looks this world allows: one chest, the 23 stock foods first. */
    List<PickPage.Tile> snackLookTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (BuilderSnacks.Look l : BuilderSnacks.LOOKS) {
            if (allow.ids().contains("look:" + l.id())) {
                out.add(PickPage.Tile.of("look:" + l.id(), l.tile(), l.label()));
            }
        }
        return out;
    }

    /** the snack powers this world allows, each a tile that toggles (two at most). */
    List<PickPage.Tile> snackPowerTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        BuilderSnacks.Look look = BuilderSnacks.look(snackLook);
        for (BuilderItemPowers.Power w : BuilderSnacks.powers()) {
            boolean offered = w == BuilderSnacks.YUMMY
                    ? look != null && !BuilderSnacks.machine(look).plain().isEmpty()   //: only a plain food
                    : allow.ids().contains("effect:" + w.effect());
            if (offered) {
                out.add(new PickPage.Tile("snackpower:" + w.id(), w.tile(), w.label(), List.of("Tap to add or take off"),
                        snackPowers.contains(w.id())));
            }
        }
        return out;
    }

    /** what may go in: the look's own ingredient first (said so), then the everyday list. */
    List<PickPage.Tile> snackIngredientTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        BuilderSnacks.Look look = BuilderSnacks.look(snackLook);
        for (String i : BuilderSnacks.ingredientsFor(look)) {
            if (allow.ids().contains("ingredient:" + i)) {
                boolean best = look != null && i.equals(look.ingredient());
                out.add(new PickPage.Tile("ingredient:" + i, i, BuilderSnacks.words(i),
                        best ? List.of("Goes best in " + look.label()) : List.of(), false));
            }
        }
        return out;
    }

    private static List<PickPage.Tile> tiles(String prefix, Map<String, String[]> table) {
        List<PickPage.Tile> out = new ArrayList<>();
        table.forEach((id, v) -> out.add(PickPage.Tile.of(prefix + id, v[1], v[0])));
        return out;
    }

    /** the drawings this world allows as a picture's subject (its {@code pictures/*} presets). */
    List<String> subjectIds() {
        List<String> out = new ArrayList<>();
        for (String p : Catalogue.presets()) {
            if (p.startsWith("pictures/") && allow.ids().contains("preset:" + p) && presets.read(p) != null) {
                out.add(p);
            }
        }
        return out;
    }

    List<PickPage.Tile> subjectTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (String p : subjectIds()) {
            out.add(PickPage.Tile.of(p, iconFor(p), presets.label(p)));   // the preset id: the subject IS that drawing
        }
        return out;
    }

    /** The subjects, a chest at a time: "More" turns the page, as the ready-mades did. */
    PickPage subjectPage() {
        List<List<PickPage.Tile>> pages = PickPage.pages(subjectTiles());
        int p = Math.floorMod(fromPage, pages.size());
        String title = pages.size() > 1 ? "What will you paint? (" + (p + 1) + "/" + pages.size() + ")" : "What will you paint?";
        return new PickPage(title, pages.get(p), true, pages.size() > 1, "More");
    }

    /** what may go behind the subject he picked (just the drawing for one that fills its canvas). */
    List<PickPage.Tile> backgroundTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (BuilderPictures.Background b : BuilderPictures.fitting(presets.read(pictSubject))) {
            out.add(PickPage.Tile.of("background:" + b.id(), b.tile(), b.label()));
        }
        return out;
    }

    /**
     * the building types this world allows: the simple ones built from parts first (every world that makes
     * buildings), then each whole body whose preset is on the list.
     */
    List<PickPage.Tile> buildTypeTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        if (allow.kinds().contains(BuilderSpec.Kind.BUILDING)) {
            for (BuilderHouses.Simple s : BuilderHouses.SIMPLE) {
                out.add(PickPage.Tile.of("simple:" + s.id(), s.tile(), s.label()));
            }
        }
        for (BuilderBuildings.Type t : BuilderBuildings.TYPES) {
            if (allow.ids().contains("preset:" + t.preset()) && presets.read(t.preset()) != null) {
                out.add(PickPage.Tile.of(t.preset(), t.tile(), t.label()));   // the preset id: the type IS that body
            }
        }
        return out;
    }

    /**
     * what may go outside the type he picked (a simple building stands on the ground: all of it), as a
     * MULTI-PICK (an earlier issue's picks row): a garden AND lamp posts AND a flag. Every one is placed in its own spot round
     * the footprint, so all of them fit at once and the page's limit is simply how many there are; Done with none
     * picked is nothing outside. Empty for a type that does not stand on the ground.
     */
    List<PickPage.Tile> buildOutsideTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        List<BuilderBuildings.Outside> fits = magicianBuilding != null
                || BuilderHouses.simple(buildType) != null && buildType.startsWith("simple:")
                ? BuilderBuildings.OUTSIDE : BuilderBuildings.fitting(BuilderBuildings.type(buildType));
        List<String> on = BuilderBuildings.parse(buildOutside);
        for (BuilderBuildings.Outside o : fits) {
            if (!o.id().equals(BuilderBuildings.NOTHING)) {
                out.add(new PickPage.Tile("outside:" + o.id(), o.tile(), o.label(), List.of("Tap to add or take off"),
                        on.contains(o.id())));
            }
        }
        return out;
    }

    /** the effects a magic block may do on this world (its effect allowed, and the magic-block shape). */
    private List<BuilderBlocks.Does> blockDoesAllowed(String when) {
        if (!allow.ids().contains("shape:magic_block")) {
            return List.of();
        }
        return BuilderBlocks.fitting(when).stream().filter(d -> allow.ids().contains("effect:" + d.effect())).toList();
    }

    /** what a magic block may be made of: every look, when anything at all can be done with one here. */
    List<PickPage.Tile> blockLookTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        if (BuilderBlocks.WHENS.stream().anyMatch(w -> !blockDoesAllowed(w.id()).isEmpty())) {
            for (BuilderBlocks.Look l : BuilderBlocks.LOOKS) {
                out.add(PickPage.Tile.of("blocklook:" + l.id(), l.block(), l.label()));
            }
        }
        return out;
    }

    /** what sets it off — only a trigger something allowed here can follow. */
    List<PickPage.Tile> blockWhenTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (BuilderBlocks.When w : BuilderBlocks.WHENS) {
            if (!blockDoesAllowed(w.id()).isEmpty()) {
                out.add(PickPage.Tile.of("blockwhen:" + w.id(), w.tile(), w.label()));
            }
        }
        return out;
    }

    /** what it does — only what fits the trigger he picked. */
    List<PickPage.Tile> blockDoesTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (BuilderBlocks.Does d : blockDoesAllowed(blockWhen)) {
            out.add(PickPage.Tile.of("blockdoes:" + d.id(), d.tile(), d.label()));
        }
        return out;
    }

    /** the supplies categories with anything offered on this world. */
    List<PickPage.Tile> supplyCategoryTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (BuilderSupplies.Category c : BuilderSupplies.CATEGORIES) {
            if (BuilderSupplies.in(c.id()).stream().anyMatch(allow::offers)) {
                out.add(PickPage.Tile.of("supplies:" + c.id(), c.tile(), c.label()));
            }
        }
        return out;
    }

    /** the supplies of the category he picked that this world offers. Tile ids {@code supply:oak_log}. */
    List<PickPage.Tile> supplyTiles() {
        return supplyTiles(supplyCategory);
    }

    List<PickPage.Tile> supplyTiles(String category) {
        List<PickPage.Tile> out = new ArrayList<>();
        for (BuilderSupplies.Supply s : BuilderSupplies.in(category)) {
            if (allow.offers(s)) {
                out.add(PickPage.Tile.of(s.catalogueId(), s.item(), s.label()));
            }
        }
        return out;
    }

    /** the supply picked, or null. */
    BuilderSupplies.Supply supply() {
        return kind == BuilderSpec.Kind.SUPPLIES && (Catalogue.prefix(from).equals("supply") || BuilderSupplies.found(from)) ? BuilderSupplies.supply(from) : null;
    }

    /** the motions this world allows ({@code motion:} ids on its list). */
    List<PickPage.Tile> eventMotionTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (BuilderEvents.Motion m : BuilderEvents.ALL) {
            if (allow.ids().contains("motion:" + m.motion())) {
                out.add(PickPage.Tile.of("event:" + m.id(), m.tile(), m.label()));
            }
        }
        return out;
    }

    List<PickPage.Tile> eventWhatTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        BuilderEvents.Motion m = BuilderEvents.byId(eventMotion);
        if (m != null) {
            for (BuilderEvents.What w : m.whats()) {
                out.add(PickPage.Tile.of("what:" + w.id(), w.tile(), w.label()));
            }
            BuilderEvents.What had = eventWhat.isEmpty() || m.whats().stream().anyMatch(w -> w.id().equals(eventWhat))
                    ? null : BuilderEvents.what(m, eventWhat);
            if (had != null) {   //: the creature he found stays on the page, chosen
                out.add(PickPage.Tile.of("what:" + had.id(), had.tile(), had.label()));
            }
        }
        return out;
    }

    /** the stock items this world allows (its trigger, and a power that fits it). Tile ids {@code stock:sword}. */
    List<PickPage.Tile> stockTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (BuilderItemPowers.Stock listed : BuilderItemPowers.STOCK) {
            // as this world makes it (a basic-tier world's sword is stone, and it has no mace or trident), so the tile
            // shows what he will get.
            BuilderItemPowers.Stock st = BuilderItemPowers.tiered(listed, allow.itemTier());
            if (st != null && allow.ids().contains("shape:" + st.shape()) && BuilderItemPowers.fitting(st).stream()
                    .anyMatch(allow::offers)) {
                out.add(PickPage.Tile.of(STOCK_PREFIX + st.id(), st.tile(), st.label()));
            }
        }
        return out;
    }

    /** The "What is it?" page's tiles (the item kind's first page). */
    List<PickPage.Tile> shapeTiles() {
        return stockTiles();
    }

    List<PickPage.Tile> itemPowerTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        BuilderItemPowers.Stock stock = BuilderItemPowers.stock(itemShape);
        for (BuilderItemPowers.Power w : BuilderItemPowers.fitting(stock)) {
            if (!allow.offers(w)) {   //: the lava wand only where the world allows lava
                continue;
            }
            if (w.id().equals(BuilderTurnInto.POWER)) {
                // offered with its lore, and only where its pages have something to offer
                if (!turnFromTiles().isEmpty()) {
                    String lore = BuilderTurnInto.lore(stock.shape());
                    out.add(new PickPage.Tile("itempower:" + w.id(), w.tile(), w.label(), List.of(lore), false));
                }
                continue;
            }
            out.add(PickPage.Tile.of("itempower:" + w.id(), w.tile(), w.label()));
        }
        return out;
    }

    /** "What is it?" with "🎨 More looks…" last, when this world offers an item a look goes on. */
    private List<PickPage.Tile> withLooks(List<PickPage.Tile> tiles) {
        if (lookGroupTiles().isEmpty()) {
            return tiles;
        }
        List<PickPage.Tile> out = new ArrayList<>(tiles);
        out.add(new PickPage.Tile(BuilderLooks.MORE_ID, BuilderLooks.MORE_ICON, BuilderLooks.MORE_LABEL,
                List.of("A crown, a cupcake, a teddy…"), !itemLook.isEmpty()));
        return out;
    }

    /** the look groups with a look whose item this world offers. */
    List<PickPage.Tile> lookGroupTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (BuilderLooks.Group g : BuilderLooks.groups()) {
            if (g.looks().stream().anyMatch(this::lookOffered)) {
                out.add(new PickPage.Tile(BuilderLooks.GROUP_PREFIX + g.id(), g.tile(), g.label(), List.of(),
                        g.id().equals(lookGroup)));
            }
        }
        return out;
    }

    /** the looks of the group he picked; each tile its own name and its own drawn picture. */
    List<PickPage.Tile> lookTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        BuilderLooks.Group g = BuilderLooks.group(lookGroup);
        if (g == null) {
            return out;
        }
        for (BuilderLooks.Look l : g.looks()) {
            if (lookOffered(l)) {
                out.add(new PickPage.Tile(BuilderLooks.PREFIX + l.id(), l.tile(), l.label(), List.of(),
                        l.id().equals(itemLook)));
            }
        }
        return out;
    }

    private boolean lookOffered(BuilderLooks.Look l) {
        return shapeTiles().stream().anyMatch(t -> t.id().equals(STOCK_PREFIX + l.stock()));
    }

    /** the From groups this item can change, each a tile that toggles (as many as make_item takes at once). */
    List<PickPage.Tile> turnFromTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        BuilderItemPowers.Stock stock = BuilderItemPowers.stock(itemShape);
        if (stock == null) {
            return out;
        }
        if (seen != null) {   // §1: what he is looking at, then what he carries, above the page's own picks
            boolean bag = com.moddingfromamod.datapack.TurnInto.forShape(stock.shape())
                    != com.moddingfromamod.datapack.TurnInto.Mode.BLOCKS;
            for (String id : BuilderSuggest.lookingAt(seen, id -> {
                BuilderTurnInto.From f = BuilderTurnInto.from(BuilderTurnInto.FOUND + id);
                return f != null && f.block() && BuilderTurnInto.offered(f, stock.shape());
            })) {
                suggested(out, BuilderTurnInto.from(BuilderTurnInto.FOUND + id), BuilderSuggest.LOOKING_LORE);
            }
            for (String id : BuilderSuggest.carrying(seen, id -> {
                BuilderTurnInto.From f = BuilderTurnInto.from(BuilderTurnInto.FOUND + id);
                return bag && f != null && f.item() && BuilderTurnInto.offered(f, stock.shape());
            })) {
                suggested(out, BuilderTurnInto.from(BuilderTurnInto.FOUND + id), BuilderSuggest.CARRYING_LORE);
            }
        }
        for (BuilderTurnInto.From g : BuilderTurnInto.FROM) {
            if (BuilderTurnInto.offered(g, stock.shape())) {
                out.add(new PickPage.Tile(BuilderTurnInto.FROM_PREFIX + g.id(), g.tile(), g.label(),
                        List.of("Tap to add or take off"), turnFrom.contains(g.id())));
            }
        }
        for (String id : turnFrom) {   //: what he found by name, after the page's own, so none of them moves
            BuilderTurnInto.From g = id.startsWith(BuilderTurnInto.FOUND) ? BuilderTurnInto.from(id) : null;
            String tile = g == null ? "" : BuilderTurnInto.FROM_PREFIX + g.id();
            if (g != null && BuilderTurnInto.offered(g, stock.shape()) && out.stream().noneMatch(t -> t.id().equals(tile))) {
                out.add(new PickPage.Tile(BuilderTurnInto.FROM_PREFIX + g.id(), g.tile(), g.label(),
                        List.of("Tap to add or take off"), true));
            }
        }
        return out;
    }

    /**
     * what the picked From can become on this item: a To make_item could not make from them here is not offered. "What a furnace makes" first, and it is what the Apprentice picks when every pick has a result.
     */
    List<PickPage.Tile> turnToTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        BuilderItemPowers.Stock stock = BuilderItemPowers.stock(itemShape);
        List<BuilderTurnInto.From> from = BuilderTurnInto.parse(String.join(",", turnFrom));
        if (stock == null || from.isEmpty()) {
            return out;
        }
        // The tile marked: what he picked, else the furnace when every From has something a furnace makes of it (the
        // default the Apprentice fills too, BuilderTurnInto.furnaceIsDefault); else none, and he picks.
        String marked = !turnTo.isEmpty() ? turnTo : BuilderTurnInto.furnaceIsDefault(from) ? "furnace" : "";
        List<String> recent = recentTo == null ? List.of() : BuilderSuggest.recent(recentTo, id -> {
            BuilderTurnInto.To t = BuilderTurnInto.to(id);
            return t != null && BuilderTurnInto.whyNot(stock.shape(), from, t) == null;
        });
        for (String id : recent) {   // §1: what he turned things into last, above the page's own picks
            BuilderTurnInto.To t = BuilderTurnInto.to(id);
            out.add(new PickPage.Tile(BuilderTurnInto.TO_PREFIX + t.id(), t.tile(), t.label(),
                    List.of(BuilderSuggest.RECENT_LORE), t.id().equals(marked)));
        }
        for (BuilderTurnInto.To t : BuilderTurnInto.TO) {
            if (recent.contains(t.id())) {
                continue;
            }
            if (BuilderTurnInto.whyNot(stock.shape(), from, t) == null) {
                out.add(new PickPage.Tile(BuilderTurnInto.TO_PREFIX + t.id(), t.tile(), t.label(), List.of(),
                        t.id().equals(marked)));
            }
        }
        BuilderTurnInto.To had = foundTo(turnTo, stock, from);   //: what he found by name, last
        if (had != null && !recent.contains(had.id())) {
            out.add(new PickPage.Tile(BuilderTurnInto.TO_PREFIX + had.id(), had.tile(), had.label(), List.of(), true));
        }
        return out;
    }

    /** §1: one suggestion tile, once (a block he looks at AND carries is shown where it came first). */
    private void suggested(List<PickPage.Tile> out, BuilderTurnInto.From f, String lore) {
        String id = BuilderTurnInto.FROM_PREFIX + f.id();
        if (out.stream().noneMatch(t -> t.id().equals(id))) {
            out.add(new PickPage.Tile(id, f.tile(), f.label(), List.of(lore), turnFrom.contains(f.id())));
        }
    }

    /** §1: is the From page about to open without his view read? The menu reads it ({@code BuilderView}). */
    public boolean wantsView() {
        return seen == null && step == Step.TURN_FROM && found == null && !asking;
    }

    public void seeing(BuilderSuggest.Seen view) {
        seen = view == null ? BuilderSuggest.Seen.NOTHING : view;
    }

    /** §1: is the To page about to open without his recent To targets? The menu reads them off his shelf. */
    public boolean wantsRecent() {
        return recentTo == null && step == Step.TURN_TO && found == null && !asking;
    }

    /** His last To targets, newest first: page ids without the prefix ("gold", "found:minecraft:honey_block"). */
    public void recentTo(List<String> newestFirst) {
        recentTo = newestFirst == null ? List.of() : List.copyOf(newestFirst);
    }

    /** a To found by name that this item can make from {@code from}, or null. */
    private static BuilderTurnInto.To foundTo(String id, BuilderItemPowers.Stock stock, List<BuilderTurnInto.From> from) {
        BuilderTurnInto.To t = id.startsWith(BuilderTurnInto.FOUND) ? BuilderTurnInto.to(id) : null;
        return t != null && stock != null && BuilderTurnInto.whyNot(stock.shape(), from, t) == null ? t : null;
    }

    /** a From found by name that this item can work on, or null. */
    private BuilderTurnInto.From foundFrom(String tileId) {
        BuilderTurnInto.From g = BuilderTurnInto.from(tileId);
        BuilderItemPowers.Stock stock = BuilderItemPowers.stock(itemShape);
        return g != null && g.id().startsWith(BuilderTurnInto.FOUND) && stock != null
                && BuilderTurnInto.offered(g, stock.shape()) ? g : null;
    }

    List<PickPage.Tile> fromTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (String p : fromIds(kind)) {
            out.add(PickPage.Tile.of(p, iconFor(p), presets.label(p)));
        }
        for (String m : animalIds()) {   //: after the bodies, so no older tile moves
            out.add(PickPage.Tile.of(m, iconFor(m), animalLabel(m)));
        }
        return out;
    }

    /**
     * the REAL vanilla farm animals a creature may be: passive ones only, never a monster. Each is summoned as the
     * game makes it ({@code BuilderLowering.creature}'s mob branch), so it breeds, can be milked or sheared, and a farm
     * of them is a real farm. Any survival world's player wants "two cows" as much as any sculpted creature.
     */
    public static final List<String> FARM_ANIMALS = List.of("cow", "pig", "sheep", "chicken", "rabbit");

    /** the farm animals this world allows ({@code mob:minecraft:cow}), on the creature page only. */
    List<String> animalIds() {
        List<String> out = new ArrayList<>();
        if (kind != BuilderSpec.Kind.CREATURE) {
            return out;
        }
        for (String m : FARM_ANIMALS) {
            if (allow.ids().contains("mob:minecraft:" + m)) {
                out.add("mob:minecraft:" + m);
            }
        }
        return out;
    }

    /**
     * (any creature in the game, vanilla and mods): any
     * installed creature found by name ({@code content/Found}, the Magician's own search), on a world that offers real
     * animals at all.
     */
    boolean foundMob(String id) {
        return kind == BuilderSpec.Kind.CREATURE && !animalIds().isEmpty() && Catalogue.prefix(id).equals("mob")
                && !id.substring(4).startsWith(com.moddingfromamod.content.Found.OWN)
                && com.moddingfromamod.content.Found.name(com.moddingfromamod.content.ContentIndex.Kind.MOB, id.substring(4)) != null;
    }

    /** "Real cows", the tile's words. */
    static String animalLabel(String mobId) {
        String m = mobId.substring(mobId.lastIndexOf(':') + 1);
        return "Real " + animalWord(m, 2);
    }

    /** "cow" for one, "cows" for more (a sheep is a sheep either way). */
    public static String animalWord(String mob, int n) {
        return n == 1 ? mob : English.plural(mob, true);   //: "cows", "sheep", "foxes"
    }

    /** is this a real farm animal (picked from {@link #animalIds}), which asks only how many? */
    boolean realAnimal() {
        return kind == BuilderSpec.Kind.CREATURE && Catalogue.prefix(from).equals("mob");
    }

    String iconFor(String preset) {
        if (Catalogue.prefix(preset).equals("mob")) {   //: a real animal is its own spawn egg (paper if none)
            String mob = Catalogue.bare(preset);
            return (mob.contains(":") ? mob : "minecraft:" + mob) + "_spawn_egg";
        }
        String id = preset.substring(preset.indexOf('/') + 1);
        if (preset.startsWith("species/")) {
            return switch (id) {
                case "cat", "chicken", "frog", "sheep", "wolf", "turtle" -> "minecraft:" + id + "_spawn_egg";
                case "pet_dragon" -> "minecraft:dragon_head";
                case "fire_imp" -> "minecraft:blaze_powder";
                case "trex" -> "minecraft:bone_block";
                case "snail" -> "minecraft:nautilus_shell";
                default -> "minecraft:egg";
            };
        }
        if (preset.startsWith("bodies/")) {
            return switch (id) {
                case "horse", "pig", "rabbit", "dolphin", "panda", "bee" -> "minecraft:" + id + "_spawn_egg";
                case "bear" -> "minecraft:polar_bear_spawn_egg";
                case "car" -> "minecraft:minecart";
                case "eagle" -> "minecraft:feather";
                case "elephant" -> "minecraft:ravager_spawn_egg";
                case "lion" -> "minecraft:ocelot_spawn_egg";
                case "long_neck_dinosaur", "t_rex" -> "minecraft:bone_block";
                case "monkey" -> "minecraft:cocoa_beans";
                case "octopus" -> "minecraft:squid_spawn_egg";
                case "penguin" -> "minecraft:parrot_spawn_egg";
                case "person" -> "minecraft:player_head";
                case "robot" -> "minecraft:iron_block";
                case "shark" -> "minecraft:cod";
                case "snake" -> "minecraft:string";
                // the B4 bodies and vehicles
                case "phoenix" -> "minecraft:blaze_powder";
                case "griffin" -> "minecraft:feather";
                case "triceratops", "raptor" -> "minecraft:bone";
                case "whale" -> "minecraft:cod";
                case "crab" -> "minecraft:red_dye";
                case "owl" -> "minecraft:parrot_spawn_egg";
                case "duck" -> "minecraft:chicken_spawn_egg";
                case "butterfly" -> "minecraft:allium";
                case "jellyfish" -> "minecraft:glow_ink_sac";
                case "yeti" -> "minecraft:snowball";
                case "rock_golem" -> "minecraft:cobblestone";
                case "ghost" -> "minecraft:white_wool";
                case "gnome" -> "minecraft:red_mushroom";
                case "hamster" -> "minecraft:wheat_seeds";
                case "ogre" -> "minecraft:slime_ball";
                case "truck" -> "minecraft:minecart";
                case "plane", "helicopter" -> "minecraft:elytra";
                case "hot_air_balloon" -> "minecraft:lantern";
                case "rocket" -> "minecraft:firework_rocket";
                case "flying_saucer" -> "minecraft:ender_eye";
                case "boat" -> "minecraft:oak_boat";
                case "submarine" -> "minecraft:heart_of_the_sea";
                case "tank" -> "minecraft:iron_block";
                case "motorbike" -> "minecraft:saddle";
                default -> "minecraft:egg";
            };
        }
        if (preset.startsWith("items/")) {
            JsonObject icon = presets.icon(preset);
            String sprite = icon == null || !icon.has("sprite") ? "" : icon.get("sprite").getAsString();
            return switch (sprite) {
                case "sword" -> "minecraft:diamond_sword";
                case "helmet" -> "minecraft:leather_boots";
                case "block" -> "minecraft:gold_block";
                case "machine" -> "minecraft:blast_furnace";
                case "cup" -> "minecraft:glass_bottle";
                case "wand" -> "minecraft:blaze_rod";
                case "food_round" -> "minecraft:cookie";
                default -> "minecraft:nether_star";
            };
        }
        if (preset.startsWith("buildings/")) {
            return switch (id) {
                case "floating_island" -> "minecraft:grass_block";
                case "lava_moat_castle" -> "minecraft:lava_bucket";
                case "mushroom_village" -> "minecraft:red_mushroom_block";
                case "secret_treehouse" -> "minecraft:oak_leaves";
                case "underwater_dome" -> "minecraft:glass";
                default -> "minecraft:bricks";
            };
        }
        if (preset.startsWith("events/")) {
            return switch (id) {
                case "chicken_rain" -> "minecraft:chicken_spawn_egg";
                case "tidal_wave" -> "minecraft:water_bucket";
                default -> "minecraft:fire_charge";
            };
        }
        return "minecraft:painting";
    }

    /** is the ready-made he picked a vehicle? It then gets no creature parts, moves of a creature or moods. */
    boolean vehicle() {
        String p = from.isEmpty() ? "" : Catalogue.bare(from);
        return !p.isEmpty() && BuilderParts.vehicle(p, presets.species(p));
    }

    /** the parts this world lets him add, each a tile that toggles; none on a vehicle. */
    List<PickPage.Tile> partTiles() {
        return vehicle() ? List.of() : allPartTiles();
    }

    /** Every part this world allows, whatever the body: the Apprentice's tree (which says per body which fit). */
    List<PickPage.Tile> allPartTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (BuilderParts.Part pt : BuilderParts.ALL) {
            String id = "kit:" + pt.id();
            if (allow.ids().contains(id)) {
                out.add(new PickPage.Tile(id, pt.tile(), pt.label(), List.of("Tap to add or take off"), kits.contains(id)));
            }
        }
        return out;
    }

    /** the special moves this world allows, each a tile that toggles. */
    List<PickPage.Tile> abilityTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (BuilderAbilities.Ability a : BuilderAbilities.ALL) {
            String id = "ability:" + a.id();
            if (allow.ids().contains(id)) {
                out.add(new PickPage.Tile(id, a.tile(), a.label(), List.of("Tap to add or take off"), abilities.contains(id)));
            }
        }
        return out;
    }

    /** the Magician's body, shown greyed: what the Builder cannot change here, and why (the tile's line). */
    /** slice 2: the Magician building's size, shown blocked with why (the body tile's way). */
    private static List<PickPage.Tile> withMagicianSize(List<PickPage.Tile> tiles) {
        List<PickPage.Tile> out = new ArrayList<>(tiles);
        out.add(new PickPage.Tile(MagicianBuilding.SIZE_ID, PickPage.BLOCKED_ITEM, MagicianBuilding.SIZE_LABEL,
                List.of(MagicianBuilding.SIZE_LINE), false));
        return out;
    }

    private static List<PickPage.Tile> withMagicianBody(List<PickPage.Tile> tiles) {
        List<PickPage.Tile> out = new ArrayList<>(tiles);
        out.add(new PickPage.Tile(MagicianCreature.BODY_ID, PickPage.BLOCKED_ITEM, MagicianCreature.BODY_LABEL,
                List.of(MagicianCreature.BODY_LINE), false));   // greyed by multi(): whyNot says why
        return out;
    }

    List<PickPage.Tile> powerTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (String id : allow.ids()) {
            String bare = Catalogue.bare(id);
            if (!Catalogue.prefix(id).equals("power") || !POWER_WORDS.containsKey(bare)) {
                continue;
            }
            if (kind == BuilderSpec.Kind.RIDE && bare.equals("rideable")) {
                continue;   // a ride is rideable already
            }
            if (vehicle() && BuilderParts.CREATURE_ONLY_POWERS.contains(bare)) {
                continue;   //: a truck does not hop, pounce or nap
            }
            // what the body does by itself (a dragon flies) is shown ticked; it needs no pick.
            boolean natural = naturalPowers().contains(bare);
            out.add(new PickPage.Tile(id, POWER_ICONS.getOrDefault(bare, "minecraft:glowstone_dust"), POWER_WORDS.get(bare),
                    List.of(natural ? NATURAL_LINE : "Tap to add or take off"), natural || powers.contains(id)));
        }
        // The allowlist is a set: sorted, so a page reads the same every time it opens.
        out.sort(java.util.Comparator.comparing(PickPage.Tile::label));
        TEMPER_TILES.forEach((t, v) -> {
            if (allow.tempers().contains(t) && !vehicle()) {   //: a vehicle has no mood; it stays friendly
                out.add(new PickPage.Tile("temper:" + t, v[1], v[0], List.of("How it behaves"), temper.equals(t)));
            }
        });
        return out;
    }

    static final String NATURAL_LINE = "It does this already";

    /** what the ready-made he picked does by being itself (BuilderNatural); empty before a body is picked. */
    List<String> naturalPowers() {
        if (from.isEmpty() || !Catalogue.prefix(from).equals("preset")) {
            return List.of();
        }
        String p = Catalogue.bare(from);
        return BuilderNatural.of(p, presets.read(p));
    }

    List<String> whenIds() {
        List<String> out = new ArrayList<>();
        for (String w : RuleEffects.TRIGGERS) {
            if (allow.ids().contains("when:" + w)) {
                out.add("when:" + w);
            }
        }
        return out;
    }

    List<PickPage.Tile> whenTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (String id : whenIds()) {
            out.add(PickPage.Tile.of(id, RULE_TRIGGER_ICONS.getOrDefault(Catalogue.bare(id), "minecraft:clock"),
                    RULE_TRIGGER_WORDS.get(Catalogue.bare(id))));
        }
        return out;
    }

    /** The mobs this world lets a rule be about, as {@code mob:minecraft:cow}. */
    List<String> whoIds() {
        List<String> out = new ArrayList<>();
        for (String m : RuleEffects.RULE_MOBS) {
            if (allow.ids().contains("mob:minecraft:" + m)) {
                out.add("mob:minecraft:" + m);
            }
        }
        return out;
    }

    List<PickPage.Tile> whoTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (String id : whoIds()) {
            String m = id.substring("mob:minecraft:".length());
            out.add(PickPage.Tile.of(id, "minecraft:" + m + "_spawn_egg",
                    Character.toUpperCase(m.charAt(0)) + m.substring(1) + "s"));
        }
        return out;
    }

    boolean mobTrigger() {
        return RuleEffects.MOB_TRIGGERS.contains(Catalogue.bare(when));
    }

    List<PickPage.Tile> doesTiles() {
        List<PickPage.Tile> out = new ArrayList<>();
        for (RuleEffects.Effect e : RuleEffects.ALL) {
            if (allow.ids().contains("rule:" + e.id()) && (when.isEmpty() || e.fits(when)) && e.fitsMob(who)) {
                out.add(PickPage.Tile.of("rule:" + e.id(), e.item(), e.label()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ a press

    /** What a press on {@code id} (a tile id or a PickPage control) does. */
    public Outcome press(String id) {
        if (PickPage.CLOSE_ID.equals(id)) {
            return new Outcome(step, true, "");
        }
        if (found != null) {
            return pressFound(id);   //: a press on the Find's results page
        }
        if (BuilderFind.TILE_ID.equals(id)) {
            if (!findable()) {
                return refused("That isn't on this world's Builder.");
            }
            asking = true;
            return new Outcome(step, false, "");
        }
        if (PickPage.BACK_ID.equals(id)) {
            if (magicianBuilding != null && step == Step.BUILD_OUTSIDE) {
                return new Outcome(step, false, "");   //: no page before this one: its type is the Magician's
            }
            if (magician != null && step == Step.POWERS) {
                return new Outcome(step, false, "");   //: its body is the Magician's; there is no page before this one
            }
            if (editOf != null && (step == Step.FROM || step == Step.WHEN || step == Step.SHAPE || step == Step.SNACK_LOOK
                    || step == Step.BLOCK_LOOK || step == Step.BUILD_TYPE || step == Step.PICT_SUBJECT
                    || step == Step.EVENT_MOTION)) {
                return new Outcome(step, false, "");   // an edit keeps its kind
            }
            if ((step == Step.FROM || step == Step.PICT_SUBJECT) && fromPage > 0) {
                fromPage--;
                return new Outcome(step, false, "");
            }
            step = switch (step) {
                case FROM, WHEN -> Step.KIND;
                case SHAPE -> Step.KIND;   //: the item kind starts here
                case PARTS -> Step.FROM;
                case POWERS -> partTiles().isEmpty() ? Step.FROM : Step.PARTS;
                case ABILITIES -> Step.POWERS;
                case ITEM_POWER -> itemLook.isEmpty() ? Step.SHAPE : Step.ITEM_LOOK;   //: back to the looks
                case ITEM_LOOK -> Step.ITEM_LOOK_GROUP;
                case ITEM_LOOK_GROUP -> Step.SHAPE;
                case TURN_FROM -> Step.ITEM_POWER;   //
                case TURN_TO -> Step.TURN_FROM;
                case EVENT_MOTION -> Step.KIND;   //: the event kind starts here
                case EVENT_WHAT -> Step.EVENT_MOTION;
                case SNACK_LOOK -> Step.KIND;   //: the machine kind starts here
                case SNACK_POWER -> Step.SNACK_LOOK;
                case SNACK_INGREDIENT -> Step.SNACK_POWER;
                case BLOCK_LOOK -> Step.KIND;   //: the magic-block kind starts here
                case BLOCK_WHEN -> Step.BLOCK_LOOK;
                case BLOCK_DOES -> Step.BLOCK_WHEN;
                case BUILD_TYPE -> Step.KIND;   //: the building kind starts here
                case BUILD_OUTSIDE -> buildType.startsWith("simple:") ? Step.BUILD_WINDOWS : Step.BUILD_TYPE;
                case BUILD_ROOF -> Step.BUILD_TYPE;
                case BUILD_WINDOWS -> Step.BUILD_ROOF;
                case PICT_SUBJECT -> Step.KIND;   //: the picture kind starts here
                case PICT_BACKGROUND -> Step.PICT_SUBJECT;
                case SUPPLY_CATEGORY -> Step.KIND;   //
                case SUPPLY_WHAT -> Step.SUPPLY_CATEGORY;
                case WHO -> Step.WHEN;
                case DOES -> mobTrigger() ? Step.WHO : Step.WHEN;
                default -> step;
            };
            return new Outcome(step, false, "");
        }
        switch (step) {
            case KIND -> {
                BuilderSpec.Kind k = BuilderSpec.Kind.of(id);
                if (k == null || kindTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                kind = k;
                from = "";   // an earlier issue's walks: a creature's body stayed under a machine composed after Back, and was refused
                fromPage = 0;
                itemShape = "";
                itemPower = "";
                turnFrom.clear();
                turnTo = "";
                eventMotion = "";
                eventWhat = "";
                clearSnack();
                clearBlock();
                buildType = "";
                buildOutside = "";
                pictSubject = "";
                pictBackground = "";
                supplyCategory = "";
                powers.clear();
                abilities.clear();
                kits.clear();
                temper = "friendly";
                step = switch (k) {
                    case RULE -> Step.WHEN;
                    case ITEM -> Step.SHAPE;
                    case MACHINE -> Step.SNACK_LOOK;   //
                    case MAGIC_BLOCK -> Step.BLOCK_LOOK;   //
                    case BUILDING -> Step.BUILD_TYPE;       //
                    case PICTURE -> Step.PICT_SUBJECT;      //
                    case EVENT -> Step.EVENT_MOTION;         //
                    case SUPPLIES -> Step.SUPPLY_CATEGORY;   //
                    default -> Step.FROM;
                };
            }
            case FROM -> {
                if (PickPage.NEXT_ID.equals(id)) {
                    fromPage = (fromPage + 1) % fromPages();
                    return new Outcome(step, false, "");
                }
                if (animalIds().contains(id) || foundMob(id)) {
                    // a real farm animal, as the game makes it: no parts, powers, mood, size or colours to pick.
                    from = id;
                    kits.clear();
                    powers.clear();
                    abilities.clear();
                    temper = "friendly";
                    step = Step.TUNE;
                    return new Outcome(step, false, "");
                }
                if (!fromIds(kind).contains(id)) {
                    return refused("That isn't on this world's Builder.");
                }
                String was = from;
                from = "preset:" + id;
                if (!from.equals(was)) {
                    keepWhatStillFits();
                }
                clearSnack();
                itemShape = "";
                itemPower = "";
                eventMotion = "";
                eventWhat = "";
                step = kind == BuilderSpec.Kind.CREATURE || kind == BuilderSpec.Kind.RIDE
                        ? (partTiles().isEmpty() ? Step.POWERS : Step.PARTS) : Step.TUNE;
            }
            case SHAPE -> {
                if (BuilderLooks.MORE_ID.equals(id) && !lookGroupTiles().isEmpty()) {
                    step = Step.ITEM_LOOK_GROUP;   //: the looks; nothing he picked changes until he picks one
                    return new Outcome(step, false, "");
                }
                if (shapeTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                itemLook = "";   // a stock item of its own: its own look
                String shape = id.substring(STOCK_PREFIX.length());
                if (!shape.equals(itemShape)) {   //: the same shape again keeps what was picked after it
                    itemPower = "";
                    turnFrom.clear();   //: a sword's From may not be a wand's (no raw food on a sword)
                    turnTo = "";
                }
                itemShape = shape;
                step = Step.ITEM_POWER;
            }
            case ITEM_LOOK_GROUP -> {
                if (lookGroupTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                lookGroup = id.substring(BuilderLooks.GROUP_PREFIX.length());
                step = Step.ITEM_LOOK;
            }
            case ITEM_LOOK -> {
                if (lookTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                BuilderLooks.Look l = BuilderLooks.look(id);
                if (!l.stock().equals(itemShape)) {   // the look chose the item: what fitted the old one may not fit this
                    itemPower = "";
                    turnFrom.clear();
                    turnTo = "";
                }
                itemLook = l.id();
                itemShape = l.stock();
                step = Step.ITEM_POWER;
            }
            case EVENT_MOTION -> {
                if (eventMotionTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                String motion = id.substring("event:".length());
                if (!motion.equals(eventMotion)) {   //: the same motion again keeps its what
                    eventWhat = "";
                }
                eventMotion = motion;
                BuilderEvents.Motion m = BuilderEvents.byId(eventMotion);
                if (m.whats().size() == 1) {
                    eventWhat = m.whats().get(0).id();   // nothing to choose: an earthquake is cracks
                    step = Step.TUNE;
                } else {
                    step = Step.EVENT_WHAT;
                }
            }
            case EVENT_WHAT -> {
                if (eventWhatTiles().stream().noneMatch(t -> t.id().equals(id))
                        && !(dropsCreatures() && id.startsWith("what:") && BuilderEvents.found(id.substring(5)) != null)) {
                    return refused("That isn't on this world's Builder.");
                }
                eventWhat = id.substring("what:".length());
                step = Step.TUNE;
            }
            case SNACK_LOOK -> {
                if (snackLookTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                String look = Catalogue.bare(id);
                if (!look.equals(snackLook)) {   //: the same look again keeps its ingredient
                    snackIngredient = "";   // the ingredients page leads with this look's own
                }
                snackLook = look;
                // Keep only what this look's page offers: "just yummy" picked for a cake, Back, then a lemon (not a
                // plain food) was refused at Make. Found by BuilderLoweringTest's seeded walks (moved the seed).
                Set<String> offered = new LinkedHashSet<>();
                snackPowerTiles().forEach(t -> offered.add(t.id().substring("snackpower:".length())));
                snackPowers.retainAll(offered);
                step = Step.SNACK_POWER;
            }
            case SNACK_POWER -> {
                if (PickPage.NEXT_ID.equals(id)) {
                    if (snackPowers.isEmpty()) {
                        return refused("Pick what eating it does first.");
                    }
                    step = Step.SNACK_INGREDIENT;
                } else if (snackPowerTiles().stream().anyMatch(t -> t.id().equals(id))) {
                    String w = id.substring("snackpower:".length());
                    if (!snackPowers.remove(w)) {
                        String why = whyNot(id);
                        if (why != null) {
                            return refused(why + ". Tap one to take it off first.");
                        }
                        snackPowers.add(w);
                    }
                } else {
                    return refused("That isn't on this world's Builder.");
                }
            }
            case SNACK_INGREDIENT -> {
                if (snackIngredientTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                snackIngredient = Catalogue.bare(id);
                step = Step.TUNE;
            }
            case BUILD_TYPE -> {
                if (buildTypeTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                String type = id.startsWith("simple:") ? id : Catalogue.bare(id);
                if (!type.equals(buildType)) {   //: the same type again keeps its roof, windows and outside
                    buildOutside = "";
                    buildRoof = "";
                    buildWindows = "";
                }
                buildType = type;
                if (id.startsWith("simple:")) {
                    step = Step.BUILD_ROOF;
                    return new Outcome(step, false, "");
                }
                if (buildOutside.isEmpty()) {
                    buildOutside = BuilderBuildings.NOTHING;
                }
                // One that does not stand on the ground has nothing outside it: no page to ask.
                step = buildOutsideTiles().isEmpty() ? Step.TUNE : Step.BUILD_OUTSIDE;
            }
            case PICT_SUBJECT -> {
                if (PickPage.NEXT_ID.equals(id)) {
                    fromPage = (fromPage + 1) % PickPage.pages(subjectTiles()).size();
                    return new Outcome(step, false, "");
                }
                if (!subjectIds().contains(id)) {
                    return refused("That isn't on this world's Builder.");
                }
                if (!id.equals(pictSubject)) {   //: the same subject again keeps its background
                    pictBackground = "";
                }
                pictSubject = id;
                // One that fills its own canvas has nothing behind it to show: no page to ask.
                if (backgroundTiles().size() == 1) {
                    pictBackground = BuilderPictures.DRAWN;
                    step = Step.TUNE;
                } else {
                    step = Step.PICT_BACKGROUND;
                }
            }
            case PICT_BACKGROUND -> {
                if (backgroundTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                pictBackground = id.substring("background:".length());
                step = Step.TUNE;
            }
            case BUILD_ROOF -> {
                if (!id.startsWith("roof:") || !BuilderHouses.ROOFS.containsKey(id.substring(5))) {
                    return refused("That isn't on this world's Builder.");
                }
                buildRoof = id.substring(5);
                step = Step.BUILD_WINDOWS;
            }
            case BUILD_WINDOWS -> {
                if (!id.startsWith("windows:") || !BuilderHouses.WINDOWS.containsKey(id.substring(8))) {
                    return refused("That isn't on this world's Builder.");
                }
                buildWindows = id.substring(8);
                if (buildOutside.isEmpty()) {   //: windows again keep what is outside
                    buildOutside = BuilderBuildings.NOTHING;
                }
                step = Step.BUILD_OUTSIDE;
            }
            case BUILD_OUTSIDE -> {
                if (MagicianBuilding.SIZE_ID.equals(id)) {
                    return refused(MagicianBuilding.SIZE_SAY);   // shown, never changed
                }
                if (PickPage.NEXT_ID.equals(id)) {
                    step = Step.TUNE;   // Done: what is picked (none is nothing outside)
                } else if (("outside:" + BuilderBuildings.NOTHING).equals(id)) {
                    buildOutside = BuilderBuildings.NOTHING;   // "just the building": clears the picks and moves on
                    step = Step.TUNE;
                } else if (buildOutsideTiles().stream().anyMatch(t -> t.id().equals(id))) {
                    List<String> on = new ArrayList<>(BuilderBuildings.parse(buildOutside));
                    String one = Catalogue.bare(id);
                    if (!on.remove(one)) {
                        on.add(one);
                    }
                    buildOutside = BuilderBuildings.join(on);
                    if (on.size() >= buildOutsideTiles().size()) {
                        step = Step.TUNE;   //: at the limit the page moves on by itself (Back to change it)
                    }
                } else {
                    return refused("That isn't on this world's Builder.");
                }
            }
            case SUPPLY_CATEGORY -> {
                if (BuilderSupplies.found(id) && allow.offers(BuilderSupplies.supply(id))) {
                    from = id;   //: any installed item, found by name, is its own supply
                    step = Step.TUNE;
                    return new Outcome(step, false, "");
                }
                if (supplyCategoryTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                supplyCategory = Catalogue.bare(id);
                from = "";
                List<PickPage.Tile> here = supplyTiles();
                if (here.size() == 1) {
                    from = here.get(0).id();   // nothing to choose: light is torches
                    step = Step.TUNE;
                } else {
                    step = Step.SUPPLY_WHAT;
                }
            }
            case SUPPLY_WHAT -> {
                if (supplyTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                from = id;
                step = Step.TUNE;
            }
            case BLOCK_LOOK -> {
                if (blockLookTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                blockLook = Catalogue.bare(id);
                step = Step.BLOCK_WHEN;
            }
            case BLOCK_WHEN -> {
                if (blockWhenTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                blockWhen = Catalogue.bare(id);
                if (BuilderBlocks.does(blockDoes) != null && !BuilderBlocks.does(blockDoes).whens().contains(blockWhen)) {
                    blockDoes = "";   // a lucky surprise cannot come from stepping on it
                }
                step = Step.BLOCK_DOES;
            }
            case BLOCK_DOES -> {
                if (blockDoesTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                blockDoes = Catalogue.bare(id);
                step = Step.TUNE;
            }
            case ITEM_POWER -> {
                if (itemPowerTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                String was = itemPower;
                itemPower = id.substring("itempower:".length());
                if (itemPower.equals(BuilderTurnInto.POWER)) {
                    // what it turns and into what are its own two pages; Back to here and the same tile again
                    // keeps what he picked on them.
                    if (!was.equals(itemPower)) {
                        turnFrom.clear();
                        turnTo = "";
                    }
                    step = Step.TURN_FROM;
                } else {
                    turnFrom.clear();
                    turnTo = "";
                    step = Step.TUNE;
                }
            }
            case TURN_FROM -> {
                if (PickPage.NEXT_ID.equals(id)) {
                    if (turnFrom.isEmpty()) {
                        return refused("Pick what it turns first.");
                    }
                    if (turnToTiles().stream().noneMatch(t -> t.id().equals(BuilderTurnInto.TO_PREFIX + turnTo))) {
                        turnTo = "";   // what he picked before he changed the From may not fit it now
                    }
                    if (turnToTiles().isEmpty()) {
                        return refused("Nothing here can turn that into anything. Pick something else.");
                    }
                    step = Step.TURN_TO;
                } else if (turnFromTiles().stream().anyMatch(t -> t.id().equals(id)) || foundFrom(id) != null) {
                    String g = BuilderTurnInto.from(id).id();
                    if (!turnFrom.remove(g)) {
                        String why = whyNot(id);
                        if (why != null) {
                            return refused(why + ". Tap one to take it off first.");
                        }
                        turnFrom.add(g);
                    }
                } else {
                    return refused("That isn't on this world's Builder.");
                }
            }
            case TURN_TO -> {
                if (turnToTiles().stream().noneMatch(t -> t.id().equals(id)) && foundTo(
                        id.startsWith(BuilderTurnInto.TO_PREFIX) ? id.substring(BuilderTurnInto.TO_PREFIX.length()) : id,
                        BuilderItemPowers.stock(itemShape), BuilderTurnInto.parse(String.join(",", turnFrom))) == null) {
                    return refused("That isn't on this world's Builder.");
                }
                turnTo = id.substring(BuilderTurnInto.TO_PREFIX.length());
                step = Step.TUNE;
            }
            case PARTS -> {
                if (PickPage.NEXT_ID.equals(id)) {
                    step = Step.POWERS;
                } else if (partTiles().stream().anyMatch(t -> t.id().equals(id))) {
                    if (!kits.remove(id)) {
                        // The sculpt tool keeps kits to a share of the creature and DROPS the rest without a word;
                        // here he is told before he presses Make (F1: an antlered, maned peacock is too much on a cat).
                        // that room is the only limit — no count of parts.
                        String why = whyNot(id);
                        if (why != null) {
                            return refused(why + ". Take a part off, or pick a smaller one.");
                        }
                        kits.add(id);
                    }
                } else {
                    return refused("That isn't on this world's Builder.");
                }
            }
            case ABILITIES -> {
                if (PickPage.NEXT_ID.equals(id)) {
                    step = Step.TUNE;
                } else if (abilityTiles().stream().anyMatch(t -> t.id().equals(id))) {
                    if (!abilities.remove(id)) {
                        String why = whyNot(id);
                        if (why != null) {
                            return refused(why + ". Tap it to take it off first.");
                        }
                        abilities.add(id);
                    }
                } else {
                    return refused("That isn't on this world's Builder.");
                }
            }
            case POWERS -> {
                if (MagicianCreature.BODY_ID.equals(id)) {
                    return refused(MagicianCreature.BODY_SAY);   // shown, never changed: said, for a phone has no line
                }
                if (PickPage.NEXT_ID.equals(id)) {
                    step = magician != null || abilityTiles().isEmpty() ? Step.TUNE : Step.ABILITIES;
                } else if (id.startsWith("temper:")) {
                    String t = id.substring("temper:".length());
                    if (!allow.tempers().contains(t) || vehicle() && !t.equals("friendly")) {
                        return refused("That isn't on this world's Builder.");
                    }
                    temper = t;
                } else if (naturalPowers().contains(Catalogue.bare(id)) && Catalogue.prefix(id).equals("power")) {
                    // what the body does by itself stays on and is no pick; its tile says so. Not a refusal: an
                    // Apprentice make or an editor change that asks for it ("a ghost that glows") is already met.
                    powers.remove(id);
                } else if (powerTiles().stream().anyMatch(t -> t.id().equals(id))) {
                    if (!powers.remove(id)) {
                        powers.add(id);   //: a power is a flag on the creature; it takes every one
                    }
                } else {
                    return refused("That isn't on this world's Builder.");
                }
            }
            case WHEN -> {
                if (!whenIds().contains(id)) {
                    return refused("That isn't on this world's Builder.");
                }
                when = id;
                if (mobTrigger()) {
                    step = Step.WHO;
                } else {
                    who = "";
                    step = Step.DOES;
                }
                if (!does.isEmpty() && RuleEffects.byId(does) != null && !RuleEffects.byId(does).fits(when)) {
                    does = "";   // a drop cannot follow "when I jump"
                }
            }
            case WHO -> {
                if (!whoIds().contains(id)) {
                    return refused("That isn't on this world's Builder.");
                }
                who = id;
                step = Step.DOES;
            }
            case DOES -> {
                if (doesTiles().stream().noneMatch(t -> t.id().equals(id))) {
                    return refused("That isn't on this world's Builder.");
                }
                does = id;
                step = Step.TUNE;
            }
            case TUNE -> {
                return refused("");
            }
        }
        return new Outcome(step, false, "");
    }

    private void clearSnack() {
        snackLook = "";
        snackPowers.clear();
        snackIngredient = "";
    }

    private void clearBlock() {
        blockLook = "";
        blockWhen = "";
        blockDoes = "";
    }

    private Outcome refused(String say) {
        return new Outcome(step, false, say);
    }

    // ------------------------------------------------------------------: the 🔍 Find

    /** Does this page have a 🔍 Find? The Turn-into pages and the Supplies page. */
    public boolean findable() {
        return switch (step) {
            case TURN_FROM, TURN_TO -> BuilderItemPowers.stock(itemShape) != null;
            case SUPPLY_CATEGORY -> true;
            case EVENT_WHAT -> dropsCreatures();   //: "it rains slimes"
            default -> false;
        };
    }

    /** is the event being made one that drops creatures (rain, a crowd), so any creature may be found for it? */
    boolean dropsCreatures() {
        BuilderEvents.Motion m = BuilderEvents.byId(eventMotion);
        return m != null && BuilderEvents.creatureWhat(m) != null;
    }

    /** Is the Find's box open (the menu shows {@link #findBox}, not a page)? */
    public boolean asking() {
        return asking;
    }

    private List<PickPage.Tile> withFind(List<PickPage.Tile> tiles) {
        if (!findable()) {
            return tiles;
        }
        List<PickPage.Tile> out = new ArrayList<>(tiles);
        out.add(new PickPage.Tile(BuilderFind.TILE_ID, BuilderFind.TILE_ICON, BuilderFind.TILE_LABEL,
                List.of("Type what it is"), false));
        return out;
    }

    /** The Find's one box: the wish box's widget (TuneDialog.Words), filled with what he typed last. */
    public TuneDialog findBox() {
        return new TuneDialog(BuilderFind.BOX_TITLE,
                List.of(new TuneDialog.Words("words", BuilderFind.BOX_LABEL, findWords, BuilderFind.MAX_TYPED, 1)),
                BuilderFind.BOX_ACTION, ChildCommands.MODDY + " " + ChildCommands.BUILDER + " find " + token);
    }

    /** He sent the box: search, and show the results page. Blank is a cancel: back to the page. */
    public Outcome find(String typed) {
        asking = false;
        String words = typed == null ? "" : typed.strip();
        if (words.isEmpty()) {
            return new Outcome(step, false, "");
        }
        findWords = words.length() > BuilderFind.MAX_TYPED ? words.substring(0, BuilderFind.MAX_TYPED) : words;
        found = step == Step.EVENT_WHAT ? BuilderFind.searchCreatures(findWords, this::findFits)
                : BuilderFind.search(findWords, this::findFits);
        return new Outcome(step, false, "");
    }

    /** The box closed with nothing sent: the page again. */
    public void stopAsking() {
        asking = false;
    }

    /** What a result must be to be offered on this page: exactly what a pick of the page's own may be. */
    boolean findFits(BuilderFind.Result r) {
        BuilderItemPowers.Stock stock = BuilderItemPowers.stock(itemShape);
        return switch (step) {
            case TURN_FROM -> {
                BuilderTurnInto.From f = BuilderTurnInto.from(BuilderTurnInto.FOUND + r.id());
                yield stock != null && f != null && BuilderTurnInto.offered(f, stock.shape())
                        && !turnFrom.contains(f.id());
            }
            case TURN_TO -> {
                BuilderTurnInto.To t = BuilderTurnInto.to(BuilderTurnInto.FOUND + r.id());
                yield stock != null && t != null
                        && BuilderTurnInto.whyNot(stock.shape(), BuilderTurnInto.parse(String.join(",", turnFrom)), t) == null;
            }
            case SUPPLY_CATEGORY -> r.item() && allow.offers(BuilderSupplies.supply(BuilderSupplies.FOUND_PREFIX + r.id()));
            case EVENT_WHAT -> dropsCreatures() && BuilderEvents.found(r.id()) != null;   //
            default -> false;
        };
    }

    /** The results: at most nine, then "None of these — try again"; with none, a page that says so. */
    PickPage foundPage() {
        List<PickPage.Tile> tiles = new ArrayList<>();
        for (BuilderFind.Result r : found) {
            tiles.add(new PickPage.Tile(BuilderFind.tileId(r), BuilderTurnInto.tile(r), r.name(), List.of(), false));
        }
        tiles.add(new PickPage.Tile(BuilderFind.AGAIN_ID, BuilderFind.AGAIN_ICON,
                found.isEmpty() ? "Try other words" : BuilderFind.AGAIN_LABEL, List.of(), false));
        return PickPage.of(found.isEmpty() ? BuilderFind.NOTHING_TITLE : BuilderFind.RESULTS_TITLE, tiles, true);
    }

    /** The results being shown, or null (for the tests). */
    public List<BuilderFind.Result> found() {
        return found;
    }

    private Outcome pressFound(String id) {
        if (PickPage.BACK_ID.equals(id)) {
            found = null;
            return new Outcome(step, false, "");
        }
        if (BuilderFind.AGAIN_ID.equals(id)) {
            found = null;
            asking = true;
            return new Outcome(step, false, "");
        }
        String what = BuilderFind.resultId(id);
        BuilderFind.Result r = what == null ? null
                : found.stream().filter(x -> x.id().equals(what)).findFirst().orElse(null);
        if (r == null) {
            return refused("That isn't on this world's Builder.");
        }
        found = null;
        // The same press a pick of the page's own is: one place decides what each page does with it.
        return switch (step) {
            case TURN_FROM -> press(BuilderTurnInto.FROM_PREFIX + BuilderTurnInto.FOUND + r.id());
            case TURN_TO -> press(BuilderTurnInto.TO_PREFIX + BuilderTurnInto.FOUND + r.id());
            case SUPPLY_CATEGORY -> press(BuilderSupplies.FOUND_PREFIX + r.id());
            case EVENT_WHAT -> press("what:" + r.id());   //
            default -> refused("That isn't on this world's Builder.");
        };
    }

    // ------------------------------------------------------------------ the tune dialog and the spec

    /** The words the make is called by, before a name: what he picked. */
    public String pickedLabel() {
        if (kind == BuilderSpec.Kind.RULE) {
            return "rule";
        }
        if (madeUp()) {
            return BuilderLooks.name(itemShape, itemPower, turnTo, itemLook);   //
        }
        if (simpleBuilding()) {
            return BuilderHouses.name(BuilderHouses.simple(buildType));   //: "House"
        }
        if (buildingComposed()) {
            return presets.label(BuilderBuildings.type(buildType).preset());   //: "Cozy House", as before
        }
        if (pictureComposed()) {
            return presets.label(pictSubject);   //: "Duck", as before
        }
        if (blockComposed()) {
            return BuilderBlocks.name(BuilderBlocks.look(blockLook), BuilderBlocks.does(blockDoes));   //
        }
        if (snackComposed()) {
            return BuilderSnacks.look(snackLook).label();   //: the snack is named; "Pizza" first
        }
        if (eventMadeUp()) {
            return BuilderEvents.title(eventMotion, eventWhat);
        }
        if (supply() != null) {
            return supply().label();   //: "Oak logs"
        }
        if (realAnimal()) {
            return animalLabel(from);   //: "Real cows"
        }
        return presets.label(Catalogue.bare(from));
    }

    /**
     * The names already on his shelf, so Moddy's name for this make tells it apart from them. Set by
     * whoever opens the Make (the edited thing's own name is left out by the caller).
     */
    public BuilderFlow shelf(java.util.Collection<String> names) {
        shelf = names == null ? List.of() : List.copyOf(names);
        return this;
    }

    /**
     * The name Moddy gives what these picks make: he never types or picks one. An edit keeps the name its
     * thing has (a pet keeps its name) unless that name was Moddy's own for the old picks of a building, picture,
     * block or item ("Healing Cupcake" made a donut is a "Healing Donut"), which then follows the new picks.
     */
    String nameFor(BuilderSpec unnamed) {
        String kept = prior == null ? "" : prior.name().strip();
        if (!kept.isEmpty()) {
            boolean follows = switch (kind) {
                // an item's name is its look and power ("Healing Cupcake"), so Moddy's follows them too
                case BUILDING, PICTURE, MAGIC_BLOCK, ITEM -> BuilderNames.isModdys(prior, presets, kept);
                default -> false;
            };
            if (!follows) {
                return kept;
            }
        }
        return BuilderNames.name(unnamed, presets, shelf);
    }

    /** What the tune dialog asks for this kind: at most four controls (MenuKit's rule). */
    public TuneDialog tune() {
        if (step != Step.TUNE) {
            throw new IllegalStateException("not at the tune step");
        }
        if (magicianBuilding != null) {
            List<TuneDialog.Option> opts = new ArrayList<>();
            opts.add(new TuneDialog.Option("as_is", "As it is"));
            for (Schemes.Scheme sc : Schemes.ALL) {
                if (allow.ids().contains("scheme:" + sc.id())) {
                    opts.add(new TuneDialog.Option(sc.id(), sc.label()));
                }
            }
            return new TuneDialog("Change " + (magicianBuilding.name().isEmpty() ? "it" : magicianBuilding.name()) + " "
                    + MagicianCreature.MARK, MagicianBuilding.DIALOG_LINES.stream().map(TuneDialog.Line::new).toList(),
                    List.of(new TuneDialog.Choice("scheme", "Colours", opts, "as_is")), "Change it!",
                    ChildCommands.MODDY + " " + ChildCommands.BUILDER + " make " + token);
        }
        if (magician != null) {
            // slice 1: only its size, a step either way from what it is; the Magician's colours are not the Builder's.
            List<TuneDialog.Input> mIn = new ArrayList<>();
            mIn.add(new TuneDialog.Slider("size", "Size", 1, 4, 1, MagicianCreature.NOW));
            if (magician.parts().size() > 0) {   // slice 2: its own parts, in a Builder colour scheme
                List<TuneDialog.Option> opts = new ArrayList<>();
                opts.add(new TuneDialog.Option("as_is", "As it is"));
                for (Schemes.Scheme sc : Schemes.ALL) {
                    if (allow.ids().contains("scheme:" + sc.id())) {
                        opts.add(new TuneDialog.Option(sc.id(), sc.label()));
                    }
                }
                mIn.add(new TuneDialog.Choice("scheme", "Colours", opts, "as_is"));
            }
            return new TuneDialog("Change " + (magician.name().isEmpty() ? "it" : magician.name()) + " " + MagicianCreature.MARK,
                    MagicianCreature.DIALOG_LINES.stream().map(TuneDialog.Line::new).toList(), mIn, "Change it!",
                    ChildCommands.MODDY + " " + ChildCommands.BUILDER + " make " + token);
        }
        List<TuneDialog.Input> in = new ArrayList<>();
        boolean sized = kind != BuilderSpec.Kind.RULE && kind != BuilderSpec.Kind.PICTURE && !simpleBuilding()
                && !realAnimal()   //: a real cow is the game's own size
                && kind != BuilderSpec.Kind.ITEM
                && kind != BuilderSpec.Kind.MACHINE && kind != BuilderSpec.Kind.MAGIC_BLOCK
                && kind != BuilderSpec.Kind.SUPPLIES;
        // a ready-made building is offered only the sizes measured to work (BuilderSizes); one size, no slider.
        List<Integer> sizes = sizes();
        int lo = sizes.get(0);
        int hi = sizes.get(sizes.size() - 1);
        // a creature or ride is sized by name, Teeny 0.25 … Colossal 8; everything else keeps
        // its slider.
        boolean byName = sized && (kind == BuilderSpec.Kind.CREATURE || kind == BuilderSpec.Kind.RIDE);
        in.add(sized && !byName && lo < hi ? new TuneDialog.Slider("size", "Size", lo, hi, 1,
                Math.max(lo, Math.min(hi, prior == null ? start("size", 2, 1, 4) : prior.size()))) : null);
        if (byName) {
            List<TuneDialog.Option> levels = new ArrayList<>();
            BuilderMobSizes.ALL.forEach(l -> levels.add(new TuneDialog.Option(l.id(), l.name())));
            String was = prior == null
                    ? suggested.getOrDefault("mobsize", BuilderMobSizes.fromOldSize(start("size", 2, 1, 4)).id())
                    : prior.opt("mobsize").isEmpty() ? BuilderMobSizes.fromOldSize(prior.size()).id() : prior.opt("mobsize");
            in.add(new TuneDialog.Choice("mobsize", "Size", levels, BuilderMobSizes.of(was).id()));
        }
        // An edit changes the one he has and adds none, so it asks no "how many".
        boolean counted = editOf == null && (kind == BuilderSpec.Kind.CREATURE || kind == BuilderSpec.Kind.ITEM
                || kind == BuilderSpec.Kind.EVENT);
        // as many as this world's spawn rules let the Magician bring (BuilderCounts), never a fixed five
        int most = BuilderCounts.most(allow, kind, from);
        in.add(counted && most > 1
                ? new TuneDialog.Slider("count", "How many", 1, most, 1, start("count", 1, 1, most)) : null);
        // a real creature's named size (Teeny 0.25 … Colossal 8), its own scale each.
        if (realAnimal()) {
            List<TuneDialog.Option> named = new ArrayList<>();
            BuilderMobSizes.ALL.forEach(l -> named.add(new TuneDialog.Option(l.id(), l.name())));
            in.add(new TuneDialog.Choice("mobsize", "Size", named,
                    BuilderMobSizes.of(prior == null ? suggested.getOrDefault("mobsize", BuilderMobSizes.NORMAL)
                            : prior.opt("mobsize")).id()));
            // how it acts: as the game makes it, friendly, following him, or staying put (modify_mob's own)
            List<TuneDialog.Option> acts = new ArrayList<>();
            BuilderActs.ALL.forEach(a -> acts.add(new TuneDialog.Option(a.id(), a.label())));
            in.add(new TuneDialog.Choice(BuilderActs.SLOT, "How it acts", acts,
                    BuilderActs.of(prior == null ? suggested.getOrDefault(BuilderActs.SLOT, BuilderActs.GAME)
                            : prior.opt(BuilderActs.SLOT)).id()));
        }
        // how many supplies: the item's own most and the world's, never past a stack; none to ask for one.
        BuilderSupplies.Supply supply = supply();
        int supplyTop = supply == null ? 1 : BuilderSupplies.top(supply, allow.supplies());
        in.add(supply != null && supplyTop > 1 ? new TuneDialog.Slider("amount", "How many", BuilderSupplies.step(supplyTop),
                BuilderSupplies.sliderTop(supplyTop), BuilderSupplies.step(supplyTop),
                BuilderSupplies.fit(start("amount", supply.dflt(), 1, supplyTop), supplyTop)) : null);
        // a made-up item's strength — the power's parameters at 1, 2 or 3, never a number he types.
        in.add(madeUp() || snackComposed() || blockComposed() ? new TuneDialog.Slider("strength", "How strong", 1, 3, 1,
                prior == null ? start("strength", 2, 1, 3) : num(prior.opt("strength"), 2)) : null);
        // how many of the ingredient fill the machine (load_and_pour's MAX).
        in.add(snackComposed() ? new TuneDialog.Slider("fill", "How many per fill", BuilderSnacks.FILL_MIN,
                BuilderSnacks.FILL_MAX, 1, prior == null ? start("fill", BuilderSnacks.FILL_DEFAULT, BuilderSnacks.FILL_MIN, BuilderSnacks.FILL_MAX)
                        : num(prior.opt("fill"), BuilderSnacks.FILL_DEFAULT))
                : null);
        // how big a picture hangs, in blocks a side (its drawing's own frame unless he changes it).
        in.add(pictureComposed() ? new TuneDialog.Slider("frame", "How big", BuilderPictures.SMALLEST, BuilderPictures.BIGGEST, 1,
                prior == null ? start("frame", ownFrame(), BuilderPictures.SMALLEST, BuilderPictures.BIGGEST)
                        : num(prior.opt("frame"), ownFrame())) : null);
        // a simple building's floors (its size) and the side its door is on.
        in.add(simpleBuilding() ? new TuneDialog.Slider("floors", "How many floors", 1, BuilderHouses.TALLEST, 1,
                prior == null ? start("floors", 1, 1, BuilderHouses.TALLEST) : num(prior.opt("floors"), 1)) : null);
        String doorWas = prior == null || prior.opt("door").isEmpty() ? "front" : prior.opt("door");
        in.add(simpleBuilding() ? new TuneDialog.Choice("door", "Where is the door?", BuilderHouses.DOORS.entrySet().stream()
                .map(e -> new TuneDialog.Option(e.getKey(), e.getValue())).toList(), doorWas) : null);
        // How a spinning thing moves (its own way first: a whirlpool stays, a big twister cuts across).
        if (eventMadeUp() && BuilderEvents.spins(eventMotion)) {
            String pathWas = prior != null && BuilderEvents.path(prior.opt("path")) != null ? prior.opt("path")
                    : BuilderEvents.path(suggested.get("path")) != null ? suggested.get("path") : BuilderEvents.OWN_WAY;
            in.add(new TuneDialog.Choice("path", "How does it move?", BuilderEvents.PATHS.stream()
                    .map(p -> new TuneDialog.Option(p.id(), p.label())).toList(), pathWas));
        }
        // What a wave or a spinning thing wrecks (it wrecks everything unless he spares something).
        if (eventMadeUp() && BuilderEvents.wrecks(eventMotion)) {
            String wreckWas = prior != null && BuilderEvents.wreck(prior.opt("wreck")) != null ? prior.opt("wreck")
                    : BuilderEvents.wreck(suggested.get("wreck")) != null ? suggested.get("wreck") : BuilderEvents.WRECKS_ALL;
            in.add(new TuneDialog.Choice("wreck", "What does it wreck?", BuilderEvents.WRECKS.stream()
                    .map(c -> new TuneDialog.Option(c.id(), c.label())).toList(), wreckWas));
        }
        // how the machine works — a fill machine or one in, one out (B5's stock machines).
        String howWas = prior == null ? suggested.getOrDefault("how", BuilderSnacks.POUR) : BuilderSnacks.how(prior);
        in.add(snackComposed() ? new TuneDialog.Choice("how", "How it works", List.of(
                new TuneDialog.Option(BuilderSnacks.POUR, "Fill it up, then pour"),
                new TuneDialog.Option(BuilderSnacks.CONVERT, "One in, one out")), howWas) : null);
        boolean coloured = kind == BuilderSpec.Kind.CREATURE || kind == BuilderSpec.Kind.RIDE
                || kind == BuilderSpec.Kind.ITEM || kind == BuilderSpec.Kind.MAGIC_BLOCK
                || kind == BuilderSpec.Kind.BUILDING || kind == BuilderSpec.Kind.PICTURE;   //
        // a made-up item whose stock item no scheme can show on (the trident) is offered no Colours.
        if (madeUp() && !BuilderItemPowers.takesColour(BuilderItemPowers.stock(itemShape))) {
            coloured = false;
        }
        if (workingBuild()) {
            coloured = false;   //: ice farmland is no farm: a working build is its own blocks
        }
        if (realAnimal()) {
            coloured = false;   //: a real cow looks as the game draws it
        }
        if (coloured) {
            List<TuneDialog.Option> opts = new ArrayList<>();
            opts.add(new TuneDialog.Option("as_is", "As it is"));
            for (Schemes.Scheme s : Schemes.ALL) {
                if (allow.ids().contains("scheme:" + s.id())) {
                    opts.add(new TuneDialog.Option(s.id(), s.label()));
                }
            }
            String was = prior == null ? suggested.getOrDefault("scheme", "as_is") : prior.scheme().isEmpty() ? "as_is" : prior.scheme();
            in.add(new TuneDialog.Choice("scheme", "Colours", opts,
                    opts.stream().anyMatch(o -> o.id().equals(was)) ? was : "as_is"));
        }
        // step 6: how fast a creature or a ride goes (slow, its own pace, fast). LAST, so no older control moves.
        in.add(paced() ? new TuneDialog.Slider("speed", "How fast", 1, 3, 1,
                prior == null ? start("speed", 2, 1, 3) : Math.max(1, Math.min(3, num(prior.opt("speed"), 2)))) : null);
        in.removeIf(java.util.Objects::isNull);
        String title = editOf != null ? "Change your " + (prior.name().isEmpty() ? pickedLabel() : prior.name())
                : kind == BuilderSpec.Kind.RULE ? "Your new rule"
                : supply != null || realAnimal() ? "Your " + pickedLabel().toLowerCase(Locale.ROOT) : "Tune your " + pickedLabel();
        List<TuneDialog.Line> lines = readyType() == null ? List.of()
                : BuilderSizes.lines(readyType()).stream().map(TuneDialog.Line::new).toList();
        return new TuneDialog(title, lines, in, editOf != null ? "Change it!" : "Make it!", ChildCommands.MODDY + " " + ChildCommands.BUILDER + " make " + token);
    }

    /**
     * The Make command's tail ({@code /moddy builder make <token> <tail>}) as the tune dialog's values, in its
     * input order. Words past the last control are ignored.
     */
    public Map<String, String> values(String tail) {
        List<String> keys = tune().keys();
        // One word a control; anything past the dialog's own (a name an old dialog still sent) is not read.
        String[] parts = (tail == null || tail.isBlank()) ? new String[0] : tail.strip().split("\\s+");
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < keys.size() && i < parts.length; i++) {
            out.put(keys.get(i), parts[i]);
        }
        return out;
    }

    /**
     * The spec, from the tune dialog's values (each may be missing when the dialog did not ask:
     * the default is the dialog's own). The name is Moddy's, never the dialog's ({@link #nameFor}).
     */
    public BuilderSpec spec(Map<String, String> values) {
        int size = num(values.get("size"), prior == null ? start("size", 2, 1, 4) : prior.size());
        if (readyType() != null) {
            size = BuilderSizes.clamp(readyType(), size);   //: never a size that does not work, however it came
        }
        int count = Math.max(1, Math.min(num(values.get("count"), 1), BuilderCounts.most(allow, kind, from)));   //
        String scheme = workingBuild() ? "as_is" : values.getOrDefault("scheme", "as_is");   //
        Map<String, String> opts = new LinkedHashMap<>();
        if (realAnimal()) {   //: unsaid is the game's own size
            BuilderMobSizes.Level named = BuilderMobSizes.of(values.getOrDefault("mobsize", BuilderMobSizes.NORMAL));
            if (!named.id().equals(BuilderMobSizes.NORMAL)) {
                opts.put("mobsize", named.id());
            }
            BuilderActs.Act acts = BuilderActs.of(values.getOrDefault(BuilderActs.SLOT, BuilderActs.GAME));   //
            if (!acts.id().equals(BuilderActs.GAME)) {
                opts.put(BuilderActs.SLOT, acts.id());
            }
        } else if (kind == BuilderSpec.Kind.CREATURE || kind == BuilderSpec.Kind.RIDE) {
            // a body's named size; its old 1–4 size follows it (its name and behaviour read that)
            String m = values.get("mobsize");
            if (m == null && prior != null && !prior.opt("mobsize").isEmpty()) {
                m = prior.opt("mobsize");
            }
            // An edit of a spec from before named sizes whose size was left as the dialog showed it keeps its old
            // step exactly (Big was 1.6 then, 1.5 now): only a size he changed becomes a named one.
            boolean asItWas = m != null && prior != null && prior.opt("mobsize").isEmpty()
                    && m.equals(BuilderMobSizes.fromOldSize(prior.size()).id());
            if (m != null && !asItWas) {
                BuilderMobSizes.Level named = BuilderMobSizes.of(m);
                size = BuilderMobSizes.oldSize(named);
                if (!named.id().equals(BuilderMobSizes.NORMAL)) {
                    opts.put("mobsize", named.id());
                }
            }
        }
        if (kind == BuilderSpec.Kind.CREATURE || kind == BuilderSpec.Kind.RIDE) {
            int i = 1;
            for (String k : kits) {
                opts.put("kit" + i++, k);
            }
        }
        if (paced()) {
            int speed = Math.max(1, Math.min(3, num(values.get("speed"), prior == null ? 2 : num(prior.opt("speed"), 2))));
            if (speed != 2) {
                opts.put("speed", String.valueOf(speed));   // its own pace is written as nothing, so older specs read the same
            }
        }
        if (eventMadeUp()) {
            opts.put("motion", "motion:" + BuilderEvents.byId(eventMotion).motion());
            opts.put("event", eventMotion);
            opts.put("what", eventWhat);
            if (BuilderEvents.wrecks(eventMotion)) {
                BuilderEvents.WreckChoice wreck = BuilderEvents.wreck(values.getOrDefault("wreck",
                        prior == null ? suggested.get("wreck") : prior.opt("wreck")));
                if (wreck != null) {
                    opts.put("wreck", wreck.id());   // unsaid: it wrecks everything
                }
            }
            if (BuilderEvents.spins(eventMotion)) {
                String path = BuilderEvents.path(values.getOrDefault("path",
                        prior == null ? suggested.get("path") : prior.opt("path")));
                if (path != null) {
                    opts.put("path", path);   // Unsaid, its own way (the motion's default)
                }
            }
        }
        if (madeUp()) {
            BuilderItemPowers.Power w = BuilderItemPowers.power(itemPower);
            opts.put("stock", itemShape);   //
            opts.put("shape", "shape:" + BuilderItemPowers.stock(itemShape).shape());
            if (!w.effect().isEmpty()) {   //: "Just for fun" is no effect at all
                opts.put("effect", "effect:" + w.effect());
            }
            opts.put("power", itemPower);
            if (!itemLook.isEmpty()) {
                opts.put(BuilderLooks.SLOT, itemLook);   //
            }
            if (itemPower.equals(BuilderTurnInto.POWER)) {   //: what it turns, and into what
                opts.put(BuilderTurnInto.FROM_SLOT, String.join(",", turnFrom));
                opts.put(BuilderTurnInto.TO_SLOT, turnTo);
            }
            opts.put("strength", String.valueOf(Math.max(1, Math.min(3, num(values.get("strength"), 2)))));
            if (!allow.itemTier().equals(BuilderItemPowers.TIER_ANY)) {
                opts.put("tier", allow.itemTier());   //: the base he was shown
            }
        }
        if (snackComposed()) {
            opts.putAll(BuilderSnacks.opts(snackLook, new ArrayList<>(snackPowers),
                    Math.max(1, Math.min(3, num(values.get("strength"), 2))), snackIngredient,
                    Math.max(BuilderSnacks.FILL_MIN, Math.min(BuilderSnacks.FILL_MAX, num(values.get("fill"), BuilderSnacks.FILL_DEFAULT))),
                    BuilderSnacks.CONVERT.equals(values.get("how")) ? BuilderSnacks.CONVERT : BuilderSnacks.POUR));
            if (prior != null && !prior.opt("machine").isEmpty()) {
                opts.put("machine", prior.opt("machine"));   // a ready-made he named keeps its machine's name
            }
        }
        if (simpleBuilding()) {
            String door = values.getOrDefault("door", prior == null || prior.opt("door").isEmpty() ? "front" : prior.opt("door"));
            opts.putAll(BuilderHouses.opts(buildType, Math.max(1, Math.min(BuilderHouses.TALLEST, num(values.get("floors"),
                    prior == null ? 1 : num(prior.opt("floors"), 1)))), buildRoof,
                    BuilderHouses.DOORS.containsKey(door) ? door : "front", buildWindows, buildOutside));   //
        } else if (buildingComposed()) {
            opts.putAll(BuilderBuildings.opts(buildType, buildOutside));   //
        } else if (pictureComposed()) {
            int frame = Math.max(BuilderPictures.SMALLEST, Math.min(BuilderPictures.BIGGEST, num(values.get("frame"),
                    prior == null ? ownFrame() : num(prior.opt("frame"), ownFrame()))));
            opts.putAll(BuilderPictures.opts(pictSubject, pictBackground, frame));   //
        }
        if (blockComposed()) {
            opts.putAll(BuilderBlocks.opts(blockLook, blockWhen, blockDoes, Math.max(1, Math.min(3, num(values.get("strength"), 2)))));
            if (!allow.itemTier().equals(BuilderItemPowers.TIER_ANY)) {
                opts.put("tier", allow.itemTier());   //
            }
        }
        BuilderSupplies.Supply supply = supply();
        if (supply != null) {   //: how many, as the dialog sets it
            int top = BuilderSupplies.top(supply, allow.supplies());
            opts.putAll(BuilderSupplies.opts(top == 1 ? 1 : BuilderSupplies.fit(num(values.get("amount"), start("amount", supply.dflt(), 1, top)), top)));
        }
        if (kind == BuilderSpec.Kind.RULE) {
            opts.put("when", when);
            opts.put("does", does);
            if (mobTrigger() && !who.isEmpty()) {
                opts.put("who", who);
            }
        }
        BuilderSpec unnamed = new BuilderSpec(BuilderSpec.CURRENT, kind, kind == BuilderSpec.Kind.RULE ? "" : from,
                scheme.equals("as_is") ? "" : scheme.toLowerCase(Locale.ROOT), size, allPowers(),
                kind == BuilderSpec.Kind.CREATURE || kind == BuilderSpec.Kind.RIDE ? temper : "", count, "", "", opts);
        return unnamed.withName(nameFor(unnamed));   // Moddy names it
    }

    private List<String> allPowers() {
        List<String> out = new ArrayList<>(powers);
        if (kind == BuilderSpec.Kind.CREATURE || kind == BuilderSpec.Kind.RIDE) {
            out.addAll(abilities);
        }
        return out;
    }

    /** A made-up event: what happens and what it is made of, no ready-made. */
    boolean eventMadeUp() {
        BuilderEvents.Motion m = BuilderEvents.byId(eventMotion);
        return kind == BuilderSpec.Kind.EVENT && from.isEmpty() && m != null && BuilderEvents.what(m, eventWhat) != null;
    }

    /** A composed snack machine: a look, a power and an ingredient picked, no ready-made. */
    boolean snackComposed() {
        return kind == BuilderSpec.Kind.MACHINE && from.isEmpty() && BuilderSnacks.look(snackLook) != null
                && !snackPowers.isEmpty() && !snackIngredient.isEmpty();
    }

    /** a simple building from parts: a type, a roof, windows and what goes outside, all picked. */
    /** A ready-made building's type id (its sizes are measured), or null for anything else. */
    String readyType() {
        if (kind != BuilderSpec.Kind.BUILDING || simpleBuilding()) {
            return null;
        }
        BuilderBuildings.Type t = BuilderBuildings.type(buildType.isEmpty() ? from : buildType);
        return t == null ? null : t.id();
    }

    /** is this a working build (a farm plot, a pen, a nursery, a monster cave): one size, no colours? */
    boolean workingBuild() {
        BuilderBuildings.Type t = kind == BuilderSpec.Kind.BUILDING && readyType() != null ? BuilderBuildings.type(readyType()) : null;
        return t != null && t.working();
    }

    /** The sizes the tune dialog offers: a ready-made building's working ones, else 1–4. */
    List<Integer> sizes() {
        return readyType() == null ? List.of(1, 2, 3, 4) : BuilderSizes.offered(readyType());
    }

    boolean simpleBuilding() {
        return kind == BuilderSpec.Kind.BUILDING && from.isEmpty() && buildType.startsWith("simple:")
                && BuilderHouses.simple(buildType) != null && BuilderHouses.ROOFS.containsKey(buildRoof)
                && BuilderHouses.WINDOWS.containsKey(buildWindows) && !buildOutside.isEmpty()
                && BuilderBuildings.parse(buildOutside).stream().allMatch(o -> BuilderBuildings.outside(o) != null);
    }

    /** A composed picture: a subject and what is behind it, no ready-made. */
    boolean pictureComposed() {
        JsonObject drawing = pictSubject.isEmpty() ? null : presets.read(pictSubject);
        return kind == BuilderSpec.Kind.PICTURE && from.isEmpty() && drawing != null
                && BuilderPictures.fitting(drawing).contains(BuilderPictures.background(pictBackground));
    }

    /** the picture's own frame size, as its library drawing hangs. */
    private int ownFrame() {
        JsonObject drawing = pictSubject.isEmpty() ? null : presets.read(pictSubject);
        return drawing == null ? 2 : BuilderPictures.ownFrame(drawing);
    }

    /** A composed building: a type and what goes outside it, no ready-made. */
    boolean buildingComposed() {
        BuilderBuildings.Type t = BuilderBuildings.type(buildType);
        return kind == BuilderSpec.Kind.BUILDING && from.isEmpty() && t != null
                && !buildOutside.isEmpty() && BuilderBuildings.fits(t, buildOutside);
    }

    /** A composed magic block: a look, a trigger and what it does, no ready-made. */
    boolean blockComposed() {
        BuilderBlocks.Does d = BuilderBlocks.does(blockDoes);
        return kind == BuilderSpec.Kind.MAGIC_BLOCK && from.isEmpty() && BuilderBlocks.look(blockLook) != null
                && d != null && d.whens().contains(blockWhen);
    }

    /** A made-up item: a shape and a power picked, no ready-made. */
    boolean madeUp() {
        return kind == BuilderSpec.Kind.ITEM && from.isEmpty() && BuilderItemPowers.stock(itemShape) != null
                && BuilderItemPowers.power(itemPower) != null
                && (!itemPower.equals(BuilderTurnInto.POWER) || !turnFrom.isEmpty() && BuilderTurnInto.to(turnTo) != null);
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
