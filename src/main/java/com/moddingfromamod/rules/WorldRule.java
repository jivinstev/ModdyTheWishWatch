package com.moddingfromamod.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>"When this happens, do that", forever.</b> One rule of the world (wave 2).
 *
 * <p>E1 found about ten small rule parts with one shape: <i>when X happens, run Y</i>. A mob
 * spawns, dies or lands; he jumps, sneaks or sprints; something comes near; the sun comes up; it
 * starts to rain; a snowball hits; a fish is caught; a creeper goes off; every few seconds. Built
 * one at a time each serves two wishes. As one tested part they serve the whole family, and the
 * agent stops hand-writing a tick-loop datapack for each.
 *
 * <p>Pure: parsed, clamped and described here, with no Minecraft. {@link WorldRules} carries it out.
 *
 * <p><b>A rule does three kinds of thing</b>, because commands alone cannot do two of them:
 * <ul>
 *   <li>{@link #commands}, run AS the thing it happened to and AT it ({@code @s}, {@code ~ ~ ~});</li>
 *   <li>{@link #push}, a shove (no vanilla command sets a mob's velocity);</li>
 *   <li>{@link #grow}, bone meal around the spot (no command applies bone meal).</li>
 * </ul>
 * And an explosion rule can be {@link #harmless}: it still goes off, but it breaks no blocks.
 */
public record WorldRule(String name, When when, String who, String block, String projectile,
                        int radius, int seconds, double chance, Only only, List<String> commands,
                        Push push, int grow, boolean harmless) {

    /** What makes the rule go off. */
    public enum When {
        MOB_SPAWNS("mob_spawns", "a %s appears"),
        MOB_DIES("mob_dies", "a %s dies"),
        LANDS("lands", "a %s lands after a fall"),
        JUMPS("jumps", "%s jumps"),
        SNEAKS("sneaks", "he starts sneaking"),
        SPRINTS("sprints", "while he sprints"),
        NEAR_PLAYER("near_player", "a %s is near him"),
        NEAR_BLOCK("near_block", "he is near %s"),
        EVERY("every", "every %s seconds"),
        SUNRISE("sunrise", "the sun comes up"),
        SUNSET("sunset", "the sun goes down"),
        RAIN_STARTS("rain_starts", "it starts to rain"),
        RAIN_STOPS("rain_stops", "the rain stops"),
        PROJECTILE_HITS("projectile_hits", "a %s hits something"),
        FISH_CAUGHT("fish_caught", "he catches a fish"),
        EXPLODES("explodes", "a %s explodes");

        public final String id;
        final String words;

        When(String id, String words) {
            this.id = id;
            this.words = words;
        }

        public static When of(String s) {
            String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            for (When w : values()) {
                if (w.id.equals(t)) {
                    return w;
                }
            }
            return null;
        }

        /** Events that happen to ONE thing, so `who` filters that thing. */
        boolean hasSubject() {
            return this != EVERY && this != SUNRISE && this != SUNSET && this != RAIN_STARTS
                    && this != RAIN_STOPS;
        }

        /** Triggers only a player can do. */
        boolean playerOnly() {
            return this == SNEAKS || this == SPRINTS || this == FISH_CAUGHT || this == NEAR_BLOCK;
        }
    }

    /** A condition on the world at the moment the rule would go off. */
    public enum Only {
        ANY, DAY, NIGHT, RAIN, CLEAR;

        public static Only of(String s) {
            if (s == null || s.isBlank()) {
                return ANY;
            }
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        public boolean holds(boolean isDay, boolean isRaining) {
            return switch (this) {
                case ANY -> true;
                case DAY -> isDay;
                case NIGHT -> !isDay;
                case RAIN -> isRaining;
                case CLEAR -> !isRaining;
            };
        }
    }

    /**
     * A shove, in blocks per tick. {@code up} is straight up; {@code away} is away from whatever
     * caused it (the player he came near, the snowball that hit); {@code forward} is the way the
     * thing is facing. 1.0 is a big launch.
     */
    public record Push(double up, double away, double forward) {
        public static final double MAX = 4.0;

        public boolean isNone() {
            return up == 0 && away == 0 && forward == 0;
        }
    }

    /** "Who" meaning the child (or any player). */
    public static final String PLAYER = "player";
    /** "Who" meaning every mob. */
    public static final String ANY = "any";
    /** "Who" for a timed rule that happens ONCE, to the world, not once per player. */
    public static final String WORLD = "world";

    public static final int MAX_COMMANDS = 8;
    public static final int MAX_RADIUS = 16;
    public static final int MAX_GROW = 8;
    public static final int MIN_SECONDS = 1;
    public static final int MAX_SECONDS = 3600;

    /** Anything that is not a lowercase id character becomes '_', so a name is a safe key. */
    public static String clean(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (char c : t.toCharArray()) {
            b.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ? c : '_');
        }
        String s = b.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    /** An id with its namespace: "zombie" → "minecraft:zombie"; a #tag keeps its #. */
    public static String id(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (t.isEmpty()) {
            return null;
        }
        boolean tag = t.startsWith("#");
        String body = tag ? t.substring(1) : t;
        if (!body.contains(":")) {
            body = "minecraft:" + body;
        }
        return (tag ? "#" : "") + body;
    }

    /**
     * The tool's arguments as a rule. Refuses what cannot work, in words the agent can act on: a
     * rule that silently does nothing is worse than no rule, because the child is told it exists.
     */
    public static WorldRule parse(JsonObject a) {
        When when = When.of(str(a, "when"));
        if (when == null) {
            throw new IllegalArgumentException("`when` must be one of: " + whenList() + ".");
        }
        String who = str(a, "who");
        if (who != null) {
            String w = who.trim().toLowerCase(Locale.ROOT);
            who = w.equals(WORLD) ? WORLD : w.equals(PLAYER) || w.equals("me") || w.equals("players") ? PLAYER
                    : w.equals(ANY) || w.equals("all") || w.equals("mob") || w.equals("mobs") ? ANY
                    : w.startsWith(TAG) ? entityTag(w)
                    : id(w);
        }
        if (when.playerOnly()) {
            who = PLAYER;
        } else if (who == null) {
            who = when == When.JUMPS || when == When.EVERY || when == When.SUNRISE
                    || when == When.SUNSET || when == When.RAIN_STARTS || when == When.RAIN_STOPS
                    ? PLAYER : when == When.NEAR_PLAYER || when == When.PROJECTILE_HITS ? null : ANY;
        }
        if (WORLD.equals(who) && when.hasSubject()) {
            throw new IllegalArgumentException("`who: world` is only for the timed triggers "
                    + "(every, sunrise, sunset, rain_starts, rain_stops).");
        }
        if (when == When.NEAR_PLAYER && (who == null || PLAYER.equals(who))) {
            throw new IllegalArgumentException("near_player needs `who`: the mob that comes near "
                    + "him ('villager', '#minecraft:skeletons', or 'any').");
        }
        if ((when == When.MOB_SPAWNS || when == When.MOB_DIES || when == When.EXPLODES)
                && PLAYER.equals(who)) {
            throw new IllegalArgumentException("`" + when.id + "` is about mobs; `who` cannot be "
                    + "the player. Use a mob id, a tag, or 'any'.");
        }
        String block = id(str(a, "block"));
        if (when == When.NEAR_BLOCK && block == null) {
            throw new IllegalArgumentException("near_block needs `block`: what he comes near "
                    + "('torch', 'minecraft:slime_block', '#minecraft:flowers').");
        }
        String projectile = id(str(a, "projectile"));
        int radius = clampInt(a, "radius", when == When.NEAR_BLOCK ? 0 : 4, 0, MAX_RADIUS);
        int seconds = clampInt(a, "seconds", 10, MIN_SECONDS, MAX_SECONDS);
        double chance = a.has("chance") ? a.get("chance").getAsDouble() : 1.0;
        if (!(chance > 0) || chance > 1) {
            throw new IllegalArgumentException("`chance` must be above 0 and at most 1 "
                    + "('sometimes' is about 0.1).");
        }
        Only only = Only.of(str(a, "only"));
        if (only == null) {
            throw new IllegalArgumentException("`only` must be day, night, rain or clear.");
        }
        List<String> commands = new ArrayList<>();
        JsonElement d = a.get("do");
        if (d != null && d.isJsonArray()) {
            for (JsonElement e : d.getAsJsonArray()) {
                if (e.isJsonPrimitive() && !e.getAsString().isBlank()) {
                    commands.add(stripSlash(e.getAsString()));
                }
            }
        } else if (d != null && d.isJsonPrimitive() && !d.getAsString().isBlank()) {
            commands.add(stripSlash(d.getAsString()));
        }
        if (commands.size() > MAX_COMMANDS) {
            throw new IllegalArgumentException("At most " + MAX_COMMANDS + " commands per rule.");
        }
        Push push = null;
        if (a.has("push") && a.get("push").isJsonObject()) {
            JsonObject p = a.getAsJsonObject("push");
            push = new Push(clampD(p, "up"), clampD(p, "away"), clampD(p, "forward"));
            if (push.isNone()) {
                push = null;
            }
        }
        int grow = clampInt(a, "grow", 0, 0, MAX_GROW);
        boolean harmless = a.has("harmless") && a.get("harmless").getAsBoolean();
        if (harmless && when != When.EXPLODES) {
            throw new IllegalArgumentException("`harmless` is only for `when: explodes`.");
        }
        if (commands.isEmpty() && push == null && grow == 0 && !harmless) {
            throw new IllegalArgumentException("The rule does nothing: give it `do` (commands), "
                    + "`push`, `grow`, or (for explosions) `harmless`.");
        }
        String name = clean(str(a, "name"));
        if (name.isEmpty()) {
            name = clean(when.id + "_" + (who == null ? "" : who.replace("minecraft:", "")));
        }
        return new WorldRule(name, when, who, block, projectile, radius, seconds, chance, only,
                List.copyOf(commands), push, grow, harmless);
    }

    /**
     * <b>Why this {@code who} can never match anything</b>, or null when it can. A `who` is
     * compared with the dying (spawning, landing…) entity's REGISTRY TYPE, so a name that is no
     * entity type is a rule that never fires — saved, reported as set, and silent forever. The one
     * that happened: a wished creature's id ({@code zorbit}, {@code wishmob_zorbit}), whose
     * type is the vanilla mob it is built on, never its species. Tags ({@code #…}), {@code any},
     * {@code player} and {@code world} are always fine.
     *
     * @param isEntityType does the server know this namespaced id as an entity type
     * @param isWished     is this bare id one of the wished creatures (species)
     */
    public static String whoProblem(String who, java.util.function.Predicate<String> isEntityType,
                                    java.util.function.Predicate<String> isWished) {
        if (who == null || who.startsWith("#") || who.startsWith(TAG) || ANY.equals(who) || PLAYER.equals(who) || WORLD.equals(who)
                || isEntityType.test(who)) {
            return null;
        }
        String bare = who.substring(who.indexOf(':') + 1);
        String species = bare.startsWith("wishmob_") ? bare.substring("wishmob_".length()) : bare;
        if (isWished.test(species)) {
            return "`who: " + bare + "` is a WISHED creature, not a kind of mob — a rule's `who` is "
                    + "matched against the mob's TYPE, and a wished creature's type is the mob it is "
                    + "built on, so this rule would never fire. For what " + species + " does (when it "
                    + "dies, when it hits, every few seconds), use apply_effect with tag:\"" + species
                    + "\" (on_death, on_hit, periodic…). A rule for it names it by its tag: `who: tag=wishmob_"
                    + species + "`.";
        }
        return "`who: " + bare + "` is not a kind of mob this game has, so the rule would never fire. "
                + "Use a mob id ('cow', 'minecraft:creeper'), a tag ('#minecraft:skeletons'), 'any' "
                + "or 'player'.";
    }

    /**
     * <b>{@code who: "tag=<t>"}</b>: the entities that CARRY the tag {@code t} — how a rule names one of his
     * own wished creatures ({@code tag=wishmob_zorbit}), whose type is the mob it is built on. A mob id or a {@code #}
     * type tag never could, which is why {@link #whoProblem} steered that wish to {@code apply_effect}.
     */
    public static final String TAG = "tag=";

    static String entityTag(String w) {
        String t = w.substring(TAG.length()).trim();
        if (!t.matches("[a-z0-9_.+\\-]{1,64}")) {
            throw new IllegalArgumentException("`who: tag=…` needs a tag his creatures carry (letters, digits, _ . + -), "
                    + "like tag=wishmob_zorbit.");
        }
        return TAG + t;
    }

    /** Does this rule's `who` take this entity? {@code typeId} like "minecraft:zombie". */
    public boolean whoMatches(boolean isPlayer, String typeId, java.util.function.Predicate<String> inTag) {
        return whoMatches(isPlayer, typeId, inTag, t -> false);
    }

    /** …and {@code carries}: does the entity carry this tag of its own ({@code who: tag=…}). */
    public boolean whoMatches(boolean isPlayer, String typeId, java.util.function.Predicate<String> inTag,
                              java.util.function.Predicate<String> carries) {
        if (who != null && who.startsWith(TAG)) {
            return !isPlayer && carries.test(who.substring(TAG.length()));
        }
        if (who == null) {
            return true;
        }
        if (PLAYER.equals(who)) {
            return isPlayer;
        }
        if (isPlayer) {
            return false;
        }
        if (ANY.equals(who)) {
            return true;
        }
        return who.startsWith("#") ? inTag.test(who.substring(1)) : who.equals(typeId);
    }

    /** In words, for the agent to tell the child exactly what now happens. */
    public String describe() {
        String subject = who == null || ANY.equals(who) ? "mob"
                : PLAYER.equals(who) ? "he"
                : who.startsWith(TAG) ? "his " + who.substring(TAG.length()).replace("wishmob_", "").replace('_', ' ')
                : who.replace("minecraft:", "").replace('_', ' ');
        String trigger = when == When.EVERY ? String.format(when.words, seconds)
                : when == When.NEAR_BLOCK ? String.format(when.words, block.replace("minecraft:", ""))
                : when == When.PROJECTILE_HITS ? String.format(when.words,
                projectile == null ? "thrown thing" : projectile.replace("minecraft:", ""))
                : when.words.contains("%s") ? String.format(when.words, subject) : when.words;
        List<String> does = new ArrayList<>();
        if (!commands.isEmpty()) {
            does.add(commands.size() + " command(s)");
        }
        if (push != null) {
            does.add("a push");
        }
        if (grow > 0) {
            does.add("plants grow within " + grow + " blocks");
        }
        if (harmless) {
            does.add("the blast breaks no blocks");
        }
        StringBuilder b = new StringBuilder("When ").append(trigger);
        if (block != null && when != When.NEAR_BLOCK) {
            b.append(" standing on ").append(block.replace("minecraft:", ""));
        }
        if (only != Only.ANY) {
            b.append(", only ").append(only == Only.RAIN ? "while it rains"
                    : only == Only.CLEAR ? "when it is not raining"
                    : "at " + only.name().toLowerCase(Locale.ROOT));
        }
        if (chance < 1) {
            b.append(", ").append(Math.round(chance * 100)).append("% of the time");
        }
        return b.append(": ").append(String.join(", ", does)).append('.').toString();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("when", when.id);
        if (who != null) {
            o.addProperty("who", who);
        }
        if (block != null) {
            o.addProperty("block", block);
        }
        if (projectile != null) {
            o.addProperty("projectile", projectile);
        }
        o.addProperty("radius", radius);
        o.addProperty("seconds", seconds);
        o.addProperty("chance", chance);
        if (only != Only.ANY) {
            o.addProperty("only", only.name().toLowerCase(Locale.ROOT));
        }
        JsonArray c = new JsonArray();
        commands.forEach(c::add);
        o.add("do", c);
        if (push != null) {
            JsonObject p = new JsonObject();
            p.addProperty("up", push.up());
            p.addProperty("away", push.away());
            p.addProperty("forward", push.forward());
            o.add("push", p);
        }
        if (grow > 0) {
            o.addProperty("grow", grow);
        }
        if (harmless) {
            o.addProperty("harmless", true);
        }
        return o;
    }

    /** The stored copy back; it went through {@link #parse} once, so it parses again. */
    public static WorldRule fromJson(JsonObject o) {
        return parse(o);
    }

    public static String whenList() {
        List<String> ids = new ArrayList<>();
        for (When w : When.values()) {
            ids.add(w.id);
        }
        return String.join(", ", ids);
    }

    /** The parts this kit IS (rule.mechanism:*), read by {@code tools/parts-coverage.py}. */
    public static final Set<String> PARTS = Set.of("on_mob_spawn", "on_mob_death", "on_mob_land",
            "on_player_jump", "on_player_sneak", "on_player_sprint", "on_player_near",
            "on_block_nearby", "time_of_day", "weather_trigger", "random_tick", "mob_drop_timer",
            "on_projectile_hit", "on_fish_caught", "on_explode", "day_length", "mob_hats", "grow");

    private static String stripSlash(String c) {
        String t = c.trim();
        return t.startsWith("/") ? t.substring(1) : t;
    }

    private static String str(JsonObject a, String k) {
        return a.has(k) && a.get(k).isJsonPrimitive() && !a.get(k).getAsString().isBlank()
                ? a.get(k).getAsString() : null;
    }

    private static int clampInt(JsonObject a, String k, int dflt, int lo, int hi) {
        if (!a.has(k) || !a.get(k).isJsonPrimitive()) {
            return dflt;
        }
        return Math.max(lo, Math.min(hi, (int) Math.round(a.get(k).getAsDouble())));
    }

    private static double clampD(JsonObject p, String k) {
        if (!p.has(k) || !p.get(k).isJsonPrimitive()) {
            return 0;
        }
        return Math.max(-Push.MAX, Math.min(Push.MAX, p.get(k).getAsDouble()));
    }
}
