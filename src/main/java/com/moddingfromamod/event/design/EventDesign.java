package com.moddingfromamod.event.design;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.event.EventScale;
import com.moddingfromamod.event.shape.StampShape;

/**
 * <b>An event, written down</b> (E4). Pure: parsed, clamped and described here.
 *
 * <p>A motion is code and cannot be saved, but an event that happens every five days, or when he
 * beats the boss, has to outlive a restart. So an event the world keeps is a DESIGN — which motion,
 * what it is made of, what it does to what it catches, what happens after, what follows it — and is
 * turned into a motion only at the moment it is staged ({@code EventBuild}).
 *
 * <p>Every field is an enum or a bounded number, except block and entity ids (resolved against the
 * live registry, refused if unknown) and one title string (swept by the safety layer in E5). A design
 * that does not parse is REFUSED with the reasons, never quietly repaired into something else.
 */
public record EventDesign(String name, Motion motion, EventScale scale, String material, String core,
                          String rim, StampShape.Form form, String drop, String replaces, int count,
                          Crowd pattern, boolean baby, boolean breaks,
                          List<CatchSpec> catches,
                          List<BlockSpec> blocks, boolean restore, int seconds, String atZero,
                          List<Then> then, String title, String traits,
                          com.moddingfromamod.event.shape.Vortex.Shape shape,
                          com.moddingfromamod.event.shape.Vortex.Path path, java.util.Set<Spare> spares,
                          com.moddingfromamod.event.shape.VortexLook.Style look) {

    public EventDesign {
        traits = traits == null ? "" : traits;
        spares = spares == null || spares.isEmpty() ? java.util.Set.of() : java.util.Set.copyOf(spares);
    }

    /** Every design from before the looks: drawn in the look its material's table gives it. */
    public EventDesign(String name, Motion motion, EventScale scale, String material, String core,
                       String rim, StampShape.Form form, String drop, String replaces, int count,
                       Crowd pattern, boolean baby, boolean breaks, List<CatchSpec> catches,
                       List<BlockSpec> blocks, boolean restore, int seconds, String atZero,
                       List<Then> then, String title, String traits,
                       com.moddingfromamod.event.shape.Vortex.Shape shape,
                       com.moddingfromamod.event.shape.Vortex.Path path, java.util.Set<Spare> spares) {
        this(name, motion, scale, material, core, rim, form, drop, replaces, count, pattern, baby, breaks,
                catches, blocks, restore, seconds, atZero, then, title, traits, shape, path, spares, null);
    }

    /** Every design from before the wreck choice: it spares nothing it did not already. */
    public EventDesign(String name, Motion motion, EventScale scale, String material, String core,
                       String rim, StampShape.Form form, String drop, String replaces, int count,
                       Crowd pattern, boolean baby, boolean breaks, List<CatchSpec> catches,
                       List<BlockSpec> blocks, boolean restore, int seconds, String atZero,
                       List<Then> then, String title, String traits,
                       com.moddingfromamod.event.shape.Vortex.Shape shape,
                       com.moddingfromamod.event.shape.Vortex.Path path) {
        this(name, motion, scale, material, core, rim, form, drop, replaces, count, pattern, baby, breaks,
                catches, blocks, restore, seconds, atZero, then, title, traits, shape, path, java.util.Set.of());
    }

    /**
     * What a wrecking event leaves alone: a tornado, a wave, a hurricane WRECK by default — they
     * smash through his builds and leave the damage until undo, and they hurt (and may kill) what they catch, never his
     * pets, his wished creatures, or him below one heart. {@code builds}: it only tears up natural ground and the world
     * comes back when it passes; {@code creatures}: it carries them, never hurts them.
     */
    public enum Spare { BUILDS, CREATURES;
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The motions that wreck by default and take {@code spares}: the ones that sweep THROUGH things. */
    public static boolean wrecks(Motion m) {
        return m == Motion.MOVING_FRONT || m == Motion.VORTEX;
    }

    /** Does it smash his builds (a wrecking motion that does not spare them)? */
    public boolean wrecksBuilds() {
        return wrecks(motion) && !spares.contains(Spare.BUILDS);
    }

    /** Does it hurt what it catches (a wrecking motion that does not spare creatures)? */
    public boolean hurtsCreatures() {
        return wrecks(motion) && !spares.contains(Spare.CREATURES);
    }

    /** Every design from before vortexes: no vortex shape or path (the motion's own defaults stand). */
    public EventDesign(String name, Motion motion, EventScale scale, String material, String core,
                       String rim, StampShape.Form form, String drop, String replaces, int count,
                       Crowd pattern, boolean baby, boolean breaks, List<CatchSpec> catches,
                       List<BlockSpec> blocks, boolean restore, int seconds, String atZero,
                       List<Then> then, String title, String traits) {
        this(name, motion, scale, material, core, rim, form, drop, replaces, count, pattern, baby, breaks,
                catches, blocks, restore, seconds, atZero, then, title, traits, null, null);
    }

    /** A vortex's shape, its own default (a funnel) when his words named none; null for any other motion. */
    public com.moddingfromamod.event.shape.Vortex.Shape vortexShape() {
        if (motion != Motion.VORTEX) {
            return null;
        }
        return shape != null ? shape : com.moddingfromamod.event.shape.Vortex.Shape.FUNNEL;
    }

    /** How a vortex moves — what he said, else chosen from its shape and size (EVENTS_PLAN §16.1). */
    public com.moddingfromamod.event.shape.Vortex.Path vortexPath() {
        if (motion != Motion.VORTEX) {
            return null;
        }
        return path != null ? path : com.moddingfromamod.event.shape.Vortex.defaultPath(vortexShape(), scale);
    }

    /** Every design from: its creatures arrive as plain as vanilla makes them. */
    public EventDesign(String name, Motion motion, EventScale scale, String material, String core,
                       String rim, StampShape.Form form, String drop, String replaces, int count,
                       Crowd pattern, boolean baby, boolean breaks, List<CatchSpec> catches,
                       List<BlockSpec> blocks, boolean restore, int seconds, String atZero,
                       List<Then> then, String title) {
        this(name, motion, scale, material, core, rim, form, drop, replaces, count, pattern, baby, breaks,
                catches, blocks, restore, seconds, atZero, then, title, "");
    }

    /**
     * The keys {@code traits} may carry: {@code modify_mob}'s own, minus the ones that say
     * WHICH mob ({@code target}, {@code scope}, {@code radius}, {@code count}) — a crowd's traits are
     * for every creature it brings.
     */
    public static final java.util.Set<String> TRAIT_KEYS = java.util.Set.of("scale", "health", "damage",
            "speed", "glow", "name", "invulnerable", "persistent", "follow", "friendly", "rideable", "finds",
            "stay", "base", "powers", "abilities");

    /**
     * The same event as {@code o} in everything the world and the camera see — its {@code title}, the
     * words on his screen, aside: a kept volcano staged again under a new headline is not a new
     * volcano, and is not filmed again.
     */
    public boolean sameEventAs(EventDesign o) {
        return o != null && equals(new EventDesign(o.name, o.motion, o.scale, o.material, o.core, o.rim, o.form,
                o.drop, o.replaces, o.count, o.pattern, o.baby, o.breaks, o.catches, o.blocks, o.restore, o.seconds, o.atZero, o.then, title,
                o.traits, o.shape, o.path, o.spares, o.look));
    }

    public static final int MAX_CATCHES = 4;
    public static final int MAX_EFFECTS = 4;
    public static final int MAX_BLOCK_HITS = 2;
    public static final int MAX_THEN = 3;
    public static final int MAX_REACH = 64;
    public static final int MAX_HURT = 20;
    public static final int MAX_TITLE = 40;

    /** What the event DOES over time: the eight motions, and none (effects only — a quake). */
    public enum Motion {
        MOVING_FRONT, LAYER, IMPACT_STAMP, FALLING_BODY, FROM_THE_SKY, SPREADING_CRACKS, SLIDE,
        COUNTDOWN, NONE, TRANSFORM, CROWD,
        /** Something that SPINS — a tornado, a whirlpool, a hurricane (EVENTS_PLAN §16). */
        VORTEX;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Whether the world is given back by itself when it ends, unless the design (his words) says.
         * The rule: what an event LEAVES stays until undo, what only PASSES goes.
         * The E7 smoke had a quake and a rain of cats tidy themselves away, which is not the wish:
         * the cracks and the cats are the point. Undo always gives everything back (D1).
         */
        public boolean restoresByDefault() {
            return switch (this) {
                case MOVING_FRONT, LAYER, FALLING_BODY, NONE, COUNTDOWN, VORTEX -> true;
                case IMPACT_STAMP, SPREADING_CRACKS, SLIDE, FROM_THE_SKY, TRANSFORM, CROWD -> false;
            };
        }
    }

    public enum Where { INSIDE, NEAR }

    /** How a crowd stands: bunched at the spot, in a ring round it, or round him. */
    public enum Crowd { CLUSTER, RING, AROUND_ME }

    /** The most a crowd may ask for; the spawn budget may give fewer. */
    public static final int MAX_CROWD = 100;

    public enum Does { CARRY, LIFT, SHAKE, IGNITE, HURT, STATUS }

    /** One effect, with its number (hurt's amount, status's seconds) and name (status's effect). */
    public record EffectSpec(Does does, int amount, String status) {
    }

    /** Where it catches things, when, and what it does to them. */
    public record CatchSpec(Where where, int reach, int every, int from, int to, List<EffectSpec> does) {
    }

    public enum Hit { BREAK_FRAGILE, FIRE }

    public record BlockSpec(Hit hit, int reach, int perTick, double strength) {
    }

    /** What decides a follow-on: the spot the event ended at. */
    public enum When { WATER_NEAR, SLOPE_NEAR, ON_LAND, ALWAYS }

    public record Then(When when, String design) {
    }

    /** A design, or the reasons it was refused. Exactly one of the two is set. */
    public record Parsed(EventDesign design, List<String> errors) {
        public boolean ok() {
            return design != null;
        }
    }

    /** Anything that is not a lowercase id character becomes '_', so a name is a safe key. */
    public static String clean(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (char c : t.toCharArray()) {
            b.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' ? c : '_');
        }
        return b.toString();
    }

    public static Parsed parse(String json) {
        try {
            return parse(JsonParser.parseString(json).getAsJsonObject());
        } catch (RuntimeException e) {
            return new Parsed(null, List.of("not a JSON object: " + e.getMessage()));
        }
    }

    public static Parsed parse(JsonObject o) {
        List<String> err = new ArrayList<>();
        String name = clean(str(o, "name"));
        if (name.isEmpty() || name.length() > 48) {
            err.add("name: 1-48 characters");
        }
        Motion motion = enumOf(Motion.class, str(o, "motion"), "motion", err);
        EventScale scale = EventScale.parse(str(o, "size"));
        if (scale == null) {
            err.add("size: little, medium, big or gigantic");
        }
        String material = id(o, "material", err);
        String core = id(o, "core", err);
        String rim = id(o, "rim", err);
        String drop = id(o, "drop", err);
        // what a transform changes — "ground" (the top layer, the default), a block id, or a
        // block tag ("#minecraft:leaves"). Checked against the registries when it is staged.
        String replaces = replacesOf(o, err);
        StampShape.Form form = o.has("form")
                ? enumOf(StampShape.Form.class, str(o, "form"), "form", err) : StampShape.Form.CRATER;
        if (motion != null && material == null && needsMaterial(motion)) {
            err.add("material: " + motion.id() + " is made of something (a block id)");
        }
        // a crowd — how many, and how they stand. 0 = not said: the size decides.
        int count = o.has("count") ? clamp(num(o, "count", 0), 1, MAX_CROWD) : 0;
        Crowd pattern = o.has("pattern") ? enumOf(Crowd.class, str(o, "pattern"), "pattern", err) : Crowd.CLUSTER;
        if (motion == Motion.CROWD && drop == null) {
            err.add("drop: a crowd needs what it is made of (a creature or an item id)");
        }
        if (motion == Motion.FROM_THE_SKY && drop == null) {
            err.add("drop: from_the_sky needs what falls (an entity or a block id)");
        }
        List<CatchSpec> catches = new ArrayList<>();
        JsonArray ca = arr(o, "catch");
        if (ca.size() > MAX_CATCHES) {
            err.add("catch: at most " + MAX_CATCHES);
        }
        for (int i = 0; i < Math.min(ca.size(), MAX_CATCHES); i++) {
            CatchSpec c = catchOf(ca.get(i), i, err);
            if (c != null) {
                catches.add(c);
            }
        }
        List<BlockSpec> blocks = new ArrayList<>();
        JsonArray ba = arr(o, "blocks");
        if (ba.size() > MAX_BLOCK_HITS) {
            err.add("blocks: at most " + MAX_BLOCK_HITS);
        }
        for (int i = 0; i < Math.min(ba.size(), MAX_BLOCK_HITS); i++) {
            JsonObject b = ba.get(i).isJsonObject() ? ba.get(i).getAsJsonObject() : new JsonObject();
            Hit hit = enumOf(Hit.class, str(b, "hit"), "blocks[" + i + "].hit", err);
            if (hit != null) {
                blocks.add(new BlockSpec(hit, clamp(num(b, "reach", 8), 1, MAX_REACH),
                        clamp(num(b, "per_tick", 6), 1, 40), clampD(dbl(b, "strength", 1.0), 0, 1)));
            }
        }
        // A vortex's shape and path, both optional (each has a default from the others).
        com.moddingfromamod.event.shape.Vortex.Shape vShape = null;
        com.moddingfromamod.event.shape.Vortex.Path vPath = null;
        if (o.has("shape") && !str(o, "shape").isBlank()) {
            vShape = enumOf(com.moddingfromamod.event.shape.Vortex.Shape.class, str(o, "shape"), "shape", err);
        }
        if (o.has("path") && !str(o, "path").isBlank()) {
            vPath = enumOf(com.moddingfromamod.event.shape.Vortex.Path.class, str(o, "path"), "path", err);
        }
        // How a vortex is drawn, only when his words name a look ("a sparkly tornado"); else its
        // material's table (VortexLook.forVortex).
        com.moddingfromamod.event.shape.VortexLook.Style vLook = null;
        if (o.has("look") && !str(o, "look").isBlank()) {
            vLook = enumOf(com.moddingfromamod.event.shape.VortexLook.Style.class, str(o, "look"), "look", err);
        }
        if (motion != Motion.VORTEX && (vShape != null || vPath != null || vLook != null)) {
            err.add("shape, path and look are a vortex's (motion: vortex)");
        }
        // What a wrecking motion spares ("spares": ["builds", "creatures"]); nothing, unsaid.
        java.util.Set<Spare> spares = java.util.EnumSet.noneOf(Spare.class);
        for (var e : arr(o, "spares")) {
            Spare sp = e.isJsonPrimitive() ? enumOf(Spare.class, e.getAsString(), "spares", err) : null;
            if (sp != null) {
                spares.add(sp);
            }
        }
        if (!spares.isEmpty() && motion != null && !wrecks(motion)) {
            err.add("spares: only a wave or a vortex wrecks what it passes (" + motion.id() + " does not)");
        }
        String afterwards = str(o, "afterwards").toLowerCase(Locale.ROOT);
        boolean restore;
        if (afterwards.isEmpty()) {
            // A wave or a vortex WRECKS by default: what it smashed stays until undo; sparing his builds, it passes and
            // the world comes back. A cyclone of cats LEAVES its cats either way.
            restore = motion == null ? true
                    : wrecks(motion) ? spares.contains(Spare.BUILDS) && !(motion == Motion.VORTEX && drop != null)
                    : motion.restoresByDefault();
        } else if (afterwards.equals("restore")) {
            restore = true;
        } else if (afterwards.equals("until_undo")) {
            restore = false;
        } else {
            restore = true;
            err.add("afterwards: restore or until_undo");
        }
        // A countdown and "none" are MADE of seconds (30 when unsaid). On any other motion seconds is
        // optional and means "keep it going at least this long" (shaking, burning, what it catches);
        // 0 = not said, and the motion's own length stands (C3: both models set seconds on an
        // earthquake to make it last longer, and nothing read it).
        boolean timed = motion == Motion.COUNTDOWN || motion == Motion.NONE;
        int seconds = o.has("seconds") ? clamp(num(o, "seconds", 30), 5, 120) : (timed ? 30 : 0);
        String atZero = o.has("at_zero") ? clean(str(o, "at_zero")) : null;
        if (motion == Motion.COUNTDOWN && (atZero == null || atZero.isEmpty())) {
            err.add("at_zero: a countdown names the design that happens when it runs out");
        }
        List<Then> then = new ArrayList<>();
        JsonArray ta = arr(o, "then");
        if (ta.size() > MAX_THEN) {
            err.add("then: at most " + MAX_THEN);
        }
        for (int i = 0; i < Math.min(ta.size(), MAX_THEN); i++) {
            JsonObject t = ta.get(i).isJsonObject() ? ta.get(i).getAsJsonObject() : new JsonObject();
            When w = t.has("if") ? enumOf(When.class, str(t, "if"), "then[" + i + "].if", err) : When.ALWAYS;
            String d = clean(str(t, "design"));
            if (d.isEmpty()) {
                err.add("then[" + i + "].design: which design follows");
            } else if (d.equals(name)) {
                err.add("then[" + i + "]: a design cannot follow itself");
            } else if (w != null) {
                then.add(new Then(w, d));
            }
        }
        String title = str(o, "title").trim();
        if (title.length() > MAX_TITLE) {
            err.add("title: at most " + MAX_TITLE + " characters");
        }
        String traits = traits(o, err);
        if (!err.isEmpty()) {
            return new Parsed(null, List.copyOf(err));
        }
        return new Parsed(new EventDesign(name, motion, scale, material, core, rim, form, drop, replaces,
                count, pattern, o.has("baby") && o.get("baby").isJsonPrimitive()
                && "true".equalsIgnoreCase(o.get("baby").getAsString()), !o.has("breaks") || o.get("breaks").getAsBoolean(), List.copyOf(catches),
                List.copyOf(blocks), restore, seconds, atZero, List.copyOf(then), title, traits, vShape, vPath, spares, vLook), List.of());
    }

    /**
     * what every creature the event brings IS, from the moment it arrives — {@code modify_mob}'s
     * JSON (the same {@code MobTweak} vocabulary, so "friendly", "follow", "base: guardian" mean what
     * they mean there), kept as canonical JSON. Applied as each one is spawned, so a crowd of
     * teleporters is never a crowd of wild ones first, and none can wander out of reach of a change
     * made afterwards. "" when it names none.
     */
    private static String traits(JsonObject o, List<String> err) {
        if (!o.has("traits") || o.get("traits").isJsonNull()) {
            return "";
        }
        JsonObject t = null;
        if (o.get("traits").isJsonObject()) {
            t = o.getAsJsonObject("traits");
        } else if (o.get("traits").isJsonPrimitive()) {
            // A tool client that sends a nested object as a JSON STRING (seen once, 2026-11-02, the
            // arm): the same object, read rather than refused.
            try {
                com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(o.get("traits").getAsString());
                t = el.isJsonObject() ? el.getAsJsonObject() : null;
            } catch (RuntimeException notJson) {
                t = null;
            }
        }
        if (t == null) {
            err.add("traits: an object of modify_mob's fields, e.g. {\"friendly\":true,\"base\":\"guardian\"}");
            return "";
        }
        JsonObject kept = new JsonObject();
        for (String k : new java.util.TreeSet<>(t.keySet())) {
            if (TRAIT_KEYS.contains(k)) {
                kept.add(k, t.get(k));
            } else {
                err.add("traits: '" + k + "' is not one (any of " + new java.util.TreeSet<>(TRAIT_KEYS) + ")");
            }
        }
        for (String w : com.moddingfromamod.mob.BehaviourProfile.unknownWords(kept)) {
            err.add("traits: not a known " + w);
        }
        if (com.moddingfromamod.mob.MobTweak.fromJson(kept).isEmpty()) {
            return "";
        }
        return kept.toString();
    }

    private static boolean needsMaterial(Motion m) {
        return m == Motion.MOVING_FRONT || m == Motion.LAYER || m == Motion.IMPACT_STAMP
                || m == Motion.FALLING_BODY || m == Motion.TRANSFORM;
    }

    /** {@code replaces}: "ground", a block id, or a {@code #tag}; null when not given. */
    private static String replacesOf(JsonObject o, List<String> err) {
        String s = str(o, "replaces").trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("ground")) {
            return s.isEmpty() ? null : "ground";
        }
        boolean tag = s.startsWith("#");
        String body = tag ? s.substring(1) : s;
        if (!body.contains(":")) {
            body = "minecraft:" + body;
        }
        if (!body.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            err.add("replaces: ground, a block id or a #tag (" + s + ")");
            return null;
        }
        if (!tag && body.matches("minecraft:(cave_|void_)?air")) {
            //, measured: "fill this cave" came as replaces air — every empty cell in the disc,
            // up to a tree's height. A transform changes what is THERE; filling is `layer`.
            err.add("replaces: air is nothing to change — a transform turns blocks that are there into "
                    + "the material; to fill a space with something use layer");
            return null;
        }
        return (tag ? "#" : "") + body;
    }

    private static CatchSpec catchOf(JsonElement e, int i, List<String> err) {
        String at = "catch[" + i + "]";
        if (!e.isJsonObject()) {
            err.add(at + ": an object");
            return null;
        }
        JsonObject c = e.getAsJsonObject();
        Where where = enumOf(Where.class, str(c, "where"), at + ".where", err);
        List<EffectSpec> does = new ArrayList<>();
        JsonArray da = arr(c, "do");
        if (da.isEmpty()) {
            err.add(at + ".do: at least one effect");
        }
        if (da.size() > MAX_EFFECTS) {
            err.add(at + ".do: at most " + MAX_EFFECTS);
        }
        for (int j = 0; j < Math.min(da.size(), MAX_EFFECTS); j++) {
            String word = da.get(j).isJsonPrimitive() ? da.get(j).getAsString().trim().toLowerCase(Locale.ROOT) : "";
            String[] parts = word.split(":");
            Does d = enumOf(Does.class, parts[0], at + ".do[" + j + "]", err);
            if (d == null) {
                continue;
            }
            switch (d) {
                case HURT -> does.add(new EffectSpec(d,
                        clamp(parts.length > 1 ? intOr(parts[1], 4) : 4, 1, MAX_HURT), null));
                case STATUS -> {
                    if (parts.length < 2 || parts[1].isBlank()) {
                        err.add(at + ".do[" + j + "]: status:<effect>[:seconds]");
                    } else {
                        does.add(new EffectSpec(d, clamp(parts.length > 2 ? intOr(parts[2], 10) : 10, 1, 600),
                                parts[1]));
                    }
                }
                default -> does.add(new EffectSpec(d, 0, null));
            }
        }
        if (where == null) {
            return null;
        }
        int from = Math.max(0, num(c, "from", 0));
        int to = num(c, "to", Integer.MAX_VALUE);
        if (to <= from) {
            err.add(at + ": to must be after from");
        }
        return new CatchSpec(where, clamp(num(c, "reach", 12), 1, MAX_REACH), clamp(num(c, "every", 1), 1, 100),
                from, to, List.copyOf(does));
    }

    /** One sentence of what will happen, for the tool's reply and the tests. */
    public String describe() {
        StringBuilder b = new StringBuilder();
        b.append(scale.name().toLowerCase(Locale.ROOT)).append(' ').append(motion == Motion.IMPACT_STAMP
                ? form.name().toLowerCase(Locale.ROOT) : motion.id().replace('_', ' '));
        if (material != null) {
            b.append(" of ").append(material);
        }
        if (motion == Motion.CROWD) {
            b.append(" of ").append(count > 0 ? count + " " : "").append(baby ? "baby " : "").append(drop).append(switch (pattern) {
                case CLUSTER -> " bunched at the spot";
                case RING -> " in a ring round the spot";
                case AROUND_ME -> " all round him";
            });
        } else if (drop != null) {
            b.append(", dropping ").append(drop);
        }
        if (motion == Motion.VORTEX) {
            b.append(" (").append(vortexShape().id()).append(", it ").append(switch (vortexPath()) {
                case STAY -> "stays where it is";
                case WANDER -> "wanders about";
                case LINE -> "cuts a straight swath across his view";
                case CIRCLE -> "goes round and round the spot";
                case FOLLOW -> "follows him";
            }).append(count > 1 ? ", " + com.moddingfromamod.event.shape.Vortex.funnels(count) + " of them" : "")
                    .append(look == null ? "" : switch (look) {
                        case SOLID -> ", drawn in solid blocks";
                        case MIST -> ", drawn as see-through mist";
                        case STREAKS -> ", drawn as whipping streaks";
                    })
                    .append("; it picks up ").append(switch (scale) {
                        case LITTLE -> "light things (items, chickens, cats) and tears up flowers and grass";
                        case MEDIUM -> "people, animals, boats and minecarts and tears up leaves, sand and glass";
                        case BIG -> "horses and rides too and tears up logs, dirt and fences";
                        case GIGANTIC -> "even iron golems and tears up anything that is not a chest, his builds "
                                + "too (given back when it passes)";
                    }).append(")");
        }
        if (wrecks(motion)) {
            b.append(wrecksBuilds() ? "; it SMASHES through his builds and leaves the damage until undo (a chest never)"
                    : "; it spares his builds (only natural ground, given back)");
            b.append(hurtsCreatures() ? "; it hurts what it catches and may kill it — never his pets, his wished "
                    + "creatures, or him below one heart" : "; it never hurts what it catches");
        }
        if (motion == Motion.TRANSFORM) {
            b.append(", turning ").append(replaces == null ? "ground" : replaces).append(" into it");
        }
        for (CatchSpec c : catches) {
            b.append("; whatever is ").append(c.where() == Where.INSIDE ? "caught in it" : "within " + c.reach())
                    .append(" is ");
            List<String> w = new ArrayList<>();
            for (EffectSpec e : c.does()) {
                w.add(switch (e.does()) {
                    case CARRY -> "carried";
                    case LIFT -> "lifted";
                    case SHAKE -> "shaken";
                    case IGNITE -> "set alight";
                    case HURT -> "hurt (never below one heart for him)";
                    case STATUS -> "given " + e.status();
                });
            }
            b.append(String.join(", ", w));
        }
        for (BlockSpec h : blocks) {
            b.append(h.hit() == Hit.FIRE ? "; fires on bare ground" : "; glass and plants smashed");
        }
        if (motion == Motion.COUNTDOWN) {
            b.append("; after ").append(seconds).append(" seconds, ").append(atZero);
        }
        else if (motion != Motion.NONE && seconds > 0) {
            b.append("; keeps going for at least ").append(seconds).append(" seconds");
        }
        for (Then t : then) {
            b.append("; then ").append(t.design()).append(t.when() == When.ALWAYS ? ""
                    : " if " + t.when().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        if (motion == Motion.FALLING_BODY && then.isEmpty()) {
            // Muse staged a rock with no `then` twice in the E7b re-smoke and told him a crater was
            // coming: the reply is the one place it hears, before the reveal, that nothing will be left.
            b.append("; it lands and leaves NOTHING behind — no crater, no splash. What a falling body "
                    + "leaves is its own design (an impact_stamp crater staged first with now: false), "
                    + "named in `then`");
        }
        b.append(restore ? "; the world puts itself back afterwards" : "; it stays until undo");
        return b.toString();
    }

    /** The first follow-on whose condition holds at the spot, or null. */
    public String follow(boolean waterNear, boolean slopeNear) {
        for (Then t : then) {
            boolean holds = switch (t.when()) {
                case WATER_NEAR -> waterNear;
                case SLOPE_NEAR -> slopeNear;
                case ON_LAND -> !waterNear;
                case ALWAYS -> true;
            };
            if (holds) {
                return t.design();
            }
        }
        return null;
    }

    // ── json helpers ────────────────────────────────────────────────────────────────────────

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static int num(JsonObject o, String k, int dflt) {
        try {
            return o.has(k) ? o.get(k).getAsInt() : dflt;
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    private static double dbl(JsonObject o, String k, double dflt) {
        try {
            return o.has(k) ? o.get(k).getAsDouble() : dflt;
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    private static int intOr(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampD(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** A namespaced id, lowercased, or null when absent; a malformed one is an error. */
    private static String id(JsonObject o, String k, List<String> err) {
        String s = str(o, k).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        if (!s.contains(":")) {
            s = "minecraft:" + s;
        }
        if (!s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            err.add(k + ": not an id (" + s + ")");
            return null;
        }
        return s;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw, String field, List<String> err) {
        String t = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(t)) {
                return e;
            }
        }
        List<String> names = new ArrayList<>();
        for (E e : type.getEnumConstants()) {
            names.add(e.name().toLowerCase(Locale.ROOT));
        }
        err.add(field + ": one of " + String.join(", ", names) + (t.isEmpty() ? "" : " (not " + raw + ")"));
        return null;
    }

    /** The design as JSON, the form it is saved in. Round-trips through {@link #parse}. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("motion", motion.id());
        o.addProperty("size", scale.name().toLowerCase(Locale.ROOT));
        put(o, "material", material);
        put(o, "core", core);
        put(o, "rim", rim);
        o.addProperty("form", form.name().toLowerCase(Locale.ROOT));
        put(o, "drop", drop);
        put(o, "replaces", replaces);
        if (count > 0) {
            o.addProperty("count", count);
        }
        o.addProperty("pattern", pattern.name().toLowerCase(Locale.ROOT));
        if (baby) {
            o.addProperty("baby", true);
        }
        o.addProperty("breaks", breaks);
        if (traits != null && !traits.isEmpty()) {
            o.add("traits", com.google.gson.JsonParser.parseString(traits));
        }
        JsonArray ca = new JsonArray();
        for (CatchSpec c : catches) {
            JsonObject j = new JsonObject();
            j.addProperty("where", c.where().name().toLowerCase(Locale.ROOT));
            j.addProperty("reach", c.reach());
            j.addProperty("every", c.every());
            j.addProperty("from", c.from());
            if (c.to() != Integer.MAX_VALUE) {
                j.addProperty("to", c.to());
            }
            JsonArray d = new JsonArray();
            for (EffectSpec e : c.does()) {
                String w = e.does().name().toLowerCase(Locale.ROOT);
                d.add(switch (e.does()) {
                    case HURT -> w + ":" + e.amount();
                    case STATUS -> w + ":" + e.status() + ":" + e.amount();
                    default -> w;
                });
            }
            j.add("do", d);
            ca.add(j);
        }
        o.add("catch", ca);
        JsonArray ba = new JsonArray();
        for (BlockSpec b : blocks) {
            JsonObject j = new JsonObject();
            j.addProperty("hit", b.hit().name().toLowerCase(Locale.ROOT));
            j.addProperty("reach", b.reach());
            j.addProperty("per_tick", b.perTick());
            j.addProperty("strength", b.strength());
            ba.add(j);
        }
        o.add("blocks", ba);
        o.addProperty("afterwards", restore ? "restore" : "until_undo");
        if (seconds > 0) {
            o.addProperty("seconds", seconds);
        }
        put(o, "at_zero", atZero);
        JsonArray ta = new JsonArray();
        for (Then t : then) {
            JsonObject j = new JsonObject();
            j.addProperty("if", t.when().name().toLowerCase(Locale.ROOT));
            j.addProperty("design", t.design());
            ta.add(j);
        }
        o.add("then", ta);
        if (!title.isEmpty()) {
            o.addProperty("title", title);
        }
        if (shape != null) {
            o.addProperty("shape", shape.id());
        }
        if (path != null) {
            o.addProperty("path", path.id());
        }
        if (look != null) {
            o.addProperty("look", look.id());
        }
        if (!spares.isEmpty()) {
            JsonArray sp = new JsonArray();
            for (Spare x : Spare.values()) {
                if (spares.contains(x)) {
                    sp.add(x.id());
                }
            }
            o.add("spares", sp);
        }
        return o;
    }

    private static void put(JsonObject o, String k, String v) {
        if (v != null) {
            o.addProperty(k, v);
        }
    }
}
