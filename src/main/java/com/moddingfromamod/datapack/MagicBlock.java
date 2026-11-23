package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>A block he puts down whose job is an effect on the world around it</b> — the rules,
 * with no Minecraft in them.
 *
 * <p>The class is built from PARTS, never from products: WHEN it goes off, WHO it reaches, HOW FAR,
 * WHERE the effect lands, and what it looks like. A lucky block, a black hole and a bouncy cloud are
 * three settings of the same five dials, which is what lets a wish nobody has thought of yet be
 * granted without new code. Nothing in this file is named after a thing a child asked for.
 *
 * <p>Everything a dial can be set to is capped HERE, because the datapack the shape compiles to
 * cannot refuse anything: a radius of 200 is a lag machine, and "every 0 seconds" is a thing that
 * fires every tick forever.
 */
public final class MagicBlock {

    private MagicBlock() {
    }

    /** The shape's id in {@code make_item}. */
    public static final String SHAPE = "magic_block";

    /** When it goes off. */
    public enum When {
        /** On its own, on a steady beat. */
        EVERY,
        /** When one of WHO comes within the radius — then it rests before it can go again. */
        NEAR,
        /** When one of WHO is standing (or lands) on top of it. */
        STEPPED_ON,
        /** Once, when he breaks it. It is used up: no block comes back. */
        BROKEN
    }

    /** Who it reaches. */
    public enum Who { EVERYONE, PLAYERS, MOBS, HOSTILE, ANIMALS, ITEMS }

    /**
     * Where the effect happens. HERE runs it once at the block, reaching everything WHO names within
     * the radius — a pull, a fill, a heal. ON_EACH runs it at each one found, which is what a
     * lightning strike needs: a storm block that struck ITSELF would only hurt what was touching it.
     */
    public enum Where { HERE, ON_EACH }

    public static final double MIN_RADIUS = 1;
    public static final double MAX_RADIUS = 16;
    public static final double DEFAULT_RADIUS = 6;

    /** A quarter second: fast enough for a pull to feel like a pull. */
    public static final double MIN_SECONDS = 0.25;
    public static final double MAX_SECONDS = 60;

    /**
     * Actions that hurt. Aimed at "everyone" by default, one of these would kill the child's cows
     * and his dog, so a block carrying one reaches HOSTILE mobs unless the wish said otherwise
     * (a mod repo's storm block learned this: it zaps monsters and leaves animals alone).
     */
    public static final Set<String> HARMFUL = Set.of("hurt", "ignite", "lightning", "explode", "freeze");

    /** Effects that only make sense at the block, not at each thing it found. */
    public static final Set<String> ONLY_HERE = Set.of("pull", "fill_blocks");

    /** One whole configuration. */
    public record Spec(When when, double seconds, Who who, double radius, Where where) {

        /** The beat (EVERY) or the rest between goes (NEAR, STEPPED_ON), in ticks, never below 5. */
        public int periodTicks() {
            return Math.max(5, (int) Math.round(seconds * 20));
        }

        /**
         * The selector for what it reaches, measured from where the effect runs. STEPPED_ON runs one
         * block up (the top of the block), so its reach is only what is standing on it.
         */
        public String selector() {
            if (when == When.STEPPED_ON) {
                // A VOLUME, not a distance: a sphere round the top would also catch whoever stands
                // on the block next to it. Four blocks tall on purpose — a thing FALLING onto it
                // moves up to ~4 blocks a tick, and has to be caught before it lands, or a cushion
                // arrives after the fall damage it was for.
                return "@e[dx=0,dy=" + (ON_TOP_HEIGHT - 1) + ",dz=0," + whoFilter(who) + "]";
            }
            return "@e[distance=.." + trim(radius) + "," + whoFilter(who) + "]";
        }

        /**
         * What an EFFECT names as its TARGET. HERE: everything the block reaches. ON_EACH: the one
         * it is running at, because the effect is run once per thing found.
         */
        public String target() {
            return where == Where.ON_EACH ? "@s" : selector();
        }

        /** Where the effect runs from, relative to the anchor. */
        String positioned() {
            return when == When.STEPPED_ON ? "positioned ~-0.5 ~1 ~-0.5 " : "";
        }
    }

    /** How tall the "standing on it" reach is, in blocks above the top. */
    public static final int ON_TOP_HEIGHT = 4;

    /** Parsed, or a refusal to hand straight back to the agent. */
    public record Parsed(Spec spec, String refusal) {
        static Parsed no(String why) {
            return new Parsed(null, why);
        }
    }

    /**
     * Read the dials. Anything left out gets a default a child would expect; anything given and
     * not understood is REFUSED rather than guessed, because a wrong guess here is a block that
     * sits there doing the wrong thing and nothing says so.
     *
     * @param raw     the {@code block} object's fields, lower-case keys, may be empty
     * @param effects the effect ids it carries, for the default WHO and the WHERE checks
     */
    public static Parsed parse(Map<String, String> raw, List<String> effects) {
        Map<String, String> r = raw == null ? Map.of() : raw;
        When when = when(r.get("when"));
        if (when == null) {
            return Parsed.no("block.when '" + r.get("when") + "' is not one I know. Use every (on "
                    + "its own, on a beat), near (when something comes close), stepped_on (when "
                    + "something stands or lands on it) or broken (once, when he breaks it).");
        }
        Double seconds = seconds(r.get("seconds"), when);
        if (seconds == null) {
            return Parsed.no("block.seconds must be a number from " + trim(MIN_SECONDS) + " to "
                    + trim(MAX_SECONDS) + ", not '" + r.get("seconds") + "'. Faster than a quarter "
                    + "second is a block that never stops; slower than a minute looks broken.");
        }
        boolean harmful = effects.stream().anyMatch(HARMFUL::contains);
        Who who = r.get("who") == null || r.get("who").isBlank()
                ? (harmful ? Who.HOSTILE : Who.EVERYONE)
                : who(r.get("who"));
        if (who == null) {
            return Parsed.no("block.who '" + r.get("who") + "' is not one I know. Use everyone, "
                    + "players, mobs, hostile (monsters only), animals or items (dropped things).");
        }
        Double radius = radius(r.get("radius"));
        if (radius == null) {
            return Parsed.no("block.radius must be a number of blocks from " + trim(MIN_RADIUS) + " to "
                    + trim(MAX_RADIUS) + ", not '" + r.get("radius") + "'. Past 16 it reaches things "
                    + "he cannot see, and a block that pulls from off-screen reads as the world "
                    + "going wrong.");
        }
        Where where = where(r.get("where"));
        if (where == null) {
            return Parsed.no("block.where '" + r.get("where") + "' is not one I know. Use here "
                    + "(it happens at the block, to everything near) or on_each (it happens at each "
                    + "thing it finds — a lightning bolt on every monster).");
        }
        if (where == Where.ON_EACH) {
            for (String e : effects) {
                if (ONLY_HERE.contains(e)) {
                    return Parsed.no("`" + e + "` only makes sense where: here — it works TOWARD or "
                            + "AROUND the block itself, and at each thing it found it would pull "
                            + "things toward themselves.");
                }
            }
            // stepped_on already happens to the one standing on it, so on_each says the same thing
            // twice. Refusing a harmless redundancy cost a real grant a turn (smoke, the
            // bouncy cloud): fold it into `here` instead, the path the tests cover.
            if (when == When.STEPPED_ON) {
                where = Where.HERE;
            }
        }
        return new Parsed(new Spec(when, seconds, who, radius, where), null);
    }

    /** The default everything, which is what the effect matrix compiles against. */
    public static Spec defaults() {
        return new Spec(When.EVERY, 2, Who.EVERYONE, DEFAULT_RADIUS, Where.HERE);
    }

    private static final Map<String, When> WHENS = new LinkedHashMap<>();
    private static final Map<String, Who> WHOS = new LinkedHashMap<>();

    static {
        for (When w : When.values()) {
            WHENS.put(w.name().toLowerCase(Locale.ROOT), w);
        }
        WHENS.put("always", When.EVERY);
        WHENS.put("timer", When.EVERY);
        WHENS.put("close", When.NEAR);
        WHENS.put("nearby", When.NEAR);
        WHENS.put("comes_near", When.NEAR);
        WHENS.put("step", When.STEPPED_ON);
        WHENS.put("stepped", When.STEPPED_ON);
        WHENS.put("stand", When.STEPPED_ON);
        WHENS.put("standing_on", When.STEPPED_ON);
        WHENS.put("landed_on", When.STEPPED_ON);
        WHENS.put("land", When.STEPPED_ON);
        WHENS.put("touch", When.STEPPED_ON);
        WHENS.put("break", When.BROKEN);
        WHENS.put("mined", When.BROKEN);
        WHENS.put("on_break", When.BROKEN);

        for (Who w : Who.values()) {
            WHOS.put(w.name().toLowerCase(Locale.ROOT), w);
        }
        WHOS.put("all", Who.EVERYONE);
        WHOS.put("everything", Who.EVERYONE);
        WHOS.put("anyone", Who.EVERYONE);
        WHOS.put("player", Who.PLAYERS);
        WHOS.put("me", Who.PLAYERS);
        WHOS.put("mob", Who.MOBS);
        WHOS.put("creatures", Who.MOBS);
        WHOS.put("monsters", Who.HOSTILE);
        WHOS.put("monster", Who.HOSTILE);
        WHOS.put("hostiles", Who.HOSTILE);
        WHOS.put("enemies", Who.HOSTILE);
        WHOS.put("animal", Who.ANIMALS);
        WHOS.put("item", Who.ITEMS);
        WHOS.put("drops", Who.ITEMS);
    }

    static When when(String raw) {
        return raw == null || raw.isBlank() ? When.EVERY : WHENS.get(key(raw));
    }

    static Who who(String raw) {
        return WHOS.get(key(raw));
    }

    static Where where(String raw) {
        if (raw == null || raw.isBlank()) {
            return Where.HERE;
        }
        return switch (key(raw)) {
            case "here", "at_the_block", "block", "center" -> Where.HERE;
            case "on_each", "each", "on_them", "at_each" -> Where.ON_EACH;
            default -> null;
        };
    }

    static Double seconds(String raw, When when) {
        if (raw == null || raw.isBlank()) {
            return when == When.EVERY ? 2.0 : 1.0;
        }
        Double v = number(raw);
        return v == null || v < MIN_SECONDS || v > MAX_SECONDS ? null : v;
    }

    static Double radius(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_RADIUS;
        }
        Double v = number(raw);
        return v == null || v < MIN_RADIUS || v > MAX_RADIUS ? null : v;
    }

    /**
     * The selector filter for WHO. Never the block's own anchor (an armour stand) or its look (a
     * block display), which are inside every radius by construction.
     */
    public static String whoFilter(Who who) {
        String notOurs = "type=!minecraft:armor_stand,type=!minecraft:block_display,type=!minecraft:marker,"
                + "type=!minecraft:snowball";
        return switch (who) {
            case EVERYONE -> notOurs + ",type=!minecraft:item";
            case PLAYERS -> "type=minecraft:player";
            case MOBS -> notOurs + ",type=!minecraft:item,type=!minecraft:player";
            case HOSTILE -> "type=#moddingfromamod:hostile";
            case ANIMALS -> "type=#moddingfromamod:animals";
            case ITEMS -> "type=minecraft:item";
        };
    }

    // ── the shape's placeholders ─────────────────────────────────────────────────────────────

    /** A walk-through look cannot be broken, so it cannot be the thing that goes off when broken. */
    public static String walkThroughRefusal(When when) {
        return when == When.BROKEN
                ? "a walk-through look cannot be broken (there is nothing solid to break), so it "
                        + "cannot go off when broken. Give it a solid look, or a different `when`."
                : null;
    }

    /**
     * Everything {@code shape_magic_block.cbs} needs filled, from the settings and the look.
     *
     * @param look        a block id, already checked to be a real block with no block entity
     * @param walkThrough drawn as a display with no collision instead of a real block
     */
    public static Map<String, String> shapeArgs(Spec s, String feature, String look, boolean walkThrough) {
        return shapeArgs(s, feature, look, walkThrough, true);
    }

    /**
     * @param hasEffect false for a magic block with NO effect — just a new block he puts down and
     *                  breaks (a made-up block of his own). It never goes off, so it has
     *                  no beat and nothing to fire, and breaking it always hands it back.
     */
    public static Map<String, String> shapeArgs(Spec s, String feature, String look, boolean walkThrough,
                                                boolean hasEffect) {
        Map<String, String> out = new LinkedHashMap<>();
        String anchors = "@e[type=minecraft:armor_stand,tag=" + feature + "_built,scores={mfam_blk="
                + s.periodTicks() + "..}]";
        String lookTag = feature + "_look";
        if (walkThrough) {
            out.put("BUILD_LOOK", "    /summon minecraft:block_display ~-0.5 ~ ~-0.5 {block_state:{Name:\""
                    + look + "\"},Tags:[\"" + lookTag + "\"]}");
            out.put("CHECK_BODY", "    /execute unless entity @e[type=minecraft:block_display,tag="
                    + lookTag + ",distance=..1.5] run tag @s add " + feature + "_broken");
            out.put("CLEAR_LOOK", "    /kill @e[type=minecraft:block_display,tag=" + lookTag
                    + ",distance=..1.5]");
        } else {
            out.put("BUILD_LOOK", "    /execute if block ~ ~ ~ #minecraft:replaceable run setblock ~ ~ ~ "
                    + look);
            out.put("CHECK_BODY", "    /execute unless block ~ ~ ~ " + look + " run tag @s add "
                    + feature + "_broken");
            // The broken block's own drop is taken away: the block comes back WHOLE (refund) or
            // not at all (it went off), never as a free block of whatever it was made of.
            out.put("CLEAR_LOOK", "    /execute if block ~ ~ ~ " + look + " run setblock ~ ~ ~ minecraft:air\n"
                    + "    /kill @e[type=minecraft:item,distance=..1.5,nbt={Item:{id:\"" + look + "\"}}]");
        }
        String fire = s.where() == Where.ON_EACH
                ? "    /execute as " + s.selector() + " at @s run function $namespace:effect"
                : "    /execute " + s.positioned() + "run function $namespace:effect";
        out.put("FIRE", hasEffect ? fire.replace("/execute run ", "/") : "");
        out.put("ON_BROKEN", hasEffect && s.when() == When.BROKEN
                ? "    /function $namespace:fire"
                : "    /execute if entity @p[distance=..24] run function $namespace:refund");
        out.put("TRIGGER", !hasEffect ? "" : switch (s.when()) {
            case EVERY -> "    /execute as " + anchors + " at @s run function $namespace:fire";
            case NEAR -> "    /execute as " + anchors + " at @s if entity " + s.selector()
                    + " run function $namespace:fire";
            case STEPPED_ON -> "    /execute as " + anchors + " at @s positioned ~-0.5 ~1 ~-0.5 if entity "
                    + s.selector() + " positioned ~0.5 ~-1 ~0.5 run function $namespace:fire";
            // Goes off when broken, and on no beat at all.
            case BROKEN -> "";
        });
        return out;
    }

    // ── pull ─────────────────────────────────────────────────────────────────────────────────

    /** One ring of a pull: things between {@code from} and {@code to} blocks away move {@code step}. */
    public record PullBand(double from, double to, double step) {
    }

    /** How many rings a pull is cut into. A datapack cannot compute a smooth falloff; rings can. */
    public static final int PULL_BANDS = 4;

    /** Stops a pull this close, or a thing drawn to the block would be stood inside it. */
    public static final double PULL_STOP = 1.5;

    /**
     * Pull strength, strongest close in and falling to NOTHING at the radius — the difference
     * between a black hole with an edge and one that tugs faintly at the whole world.
     *
     * @param step how far one go moves a thing in the innermost ring, in blocks
     */
    public static List<PullBand> pullBands(double radius, double step) {
        List<PullBand> out = new ArrayList<>();
        for (int i = 0; i < PULL_BANDS; i++) {
            double from = Math.max(PULL_STOP, radius * i / PULL_BANDS);
            double to = radius * (i + 1) / PULL_BANDS;
            if (to <= from) {
                continue;
            }
            double s = step * (PULL_BANDS - i) / PULL_BANDS;
            out.add(new PullBand(round2(from), round2(to), round2(s)));
        }
        return out;
    }

    /** How hard a pull is, by the child's word. */
    public static Double pullStep(String word) {
        return switch (key(word == null ? "" : word)) {
            case "gentle", "soft", "slow" -> 0.3;
            case "strong", "medium", "normal", "" -> 0.6;
            case "huge", "super", "mega" -> 1.0;
            default -> null;
        };
    }

    // ── fill ─────────────────────────────────────────────────────────────────────────────────

    /** A fill reaches this far each way, and no further: 7x7x7 is a pond, 33x33x33 is a flood. */
    public static final int MAX_FILL_RADIUS = 3;

    // ── outcome table ────────────────────────────────────────────────────────────────────────

    /** One row of a surprise table: an outcome and its weight out of 100. */
    public record Outcome(String kind, String what, int count, int weight) {
    }

    /** Presents' share. A mod repo's feel rule: at least three in four goes are a present. */
    public static final int PRESENT_SHARE = 80;
    /** The boom, when there is one. That mod repo's rule: under one in twenty. */
    public static final int BOOM_SHARE = 4;

    /**
     * The table, weighted so it FEELS like a lucky block: mostly presents, never entirely safe, and
     * an explosion rare. Refuses a table with nothing but presents — that is a vending machine, not
     * a gamble — and one with no presents at all.
     *
     * @param presents items, each "id" or "id*count"
     * @param surprises creature ids that can pop out instead
     * @param boom whether a small explosion is one of the outcomes
     */
    public static Object table(List<String> presents, List<String> surprises, boolean boom) {
        if (presents.isEmpty()) {
            return "outcome_table needs at least one PRESENT (an item it can give). A table of only "
                    + "surprises is a trap, not a lucky block.";
        }
        if (presents.size() > 8 || surprises.size() > 4) {
            return "outcome_table takes up to 8 PRESENTS and up to 4 SURPRISES — past that each one "
                    + "comes up so rarely he never finds out it was there.";
        }
        if (surprises.isEmpty() && !boom) {
            return "outcome_table must not be 100% safe: give it at least one SURPRISE (a creature "
                    + "that pops out — a chicken is a fine silly one, a zombie a scary one) or "
                    + "BOOM: yes. A lucky block that always pays out is a vending machine.";
        }
        int boomShare = boom ? BOOM_SHARE : 0;
        int surpriseShare = surprises.isEmpty() ? 0 : 100 - PRESENT_SHARE - boomShare;
        int presentShare = 100 - surpriseShare - boomShare;
        List<Outcome> out = new ArrayList<>();
        int[] pw = split(presentShare, presents.size());
        for (int i = 0; i < presents.size(); i++) {
            String p = presents.get(i).trim();
            int star = p.indexOf('*');
            String id = star < 0 ? p : p.substring(0, star).trim();
            Integer n = star < 0 ? Integer.valueOf(1) : intOf(p.substring(star + 1).trim());
            if (n == null || n < 1 || n > 64) {
                return "outcome_table's present '" + p + "' needs a count from 1 to 64, written "
                        + "like minecraft:diamond*3.";
            }
            out.add(new Outcome("present", id, n, pw[i]));
        }
        int[] sw = split(surpriseShare, surprises.size());
        for (int i = 0; i < surprises.size(); i++) {
            out.add(new Outcome("surprise", surprises.get(i).trim(), 1, sw[i]));
        }
        if (boom) {
            out.add(new Outcome("boom", "", 1, boomShare));
        }
        return out;
    }

    /** {@code total} split into {@code n} near-equal whole parts, the remainder to the first. */
    static int[] split(int total, int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = total / n + (i < total % n ? 1 : 0);
        }
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static String key(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    private static Double number(String raw) {
        try {
            double v = Double.parseDouble(raw.trim());
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOf(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** 6.0 -> "6", 1.25 -> "1.25". */
    public static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(round2(v));
    }

    /**
     * A block that goes off WHEN BROKEN does the same thing every time unless it rolls an
     * outcome_table. That is right for "a block that explodes when I break it" and wrong for a
     * lucky block, and the tool cannot tell which from the settings, so it SAYS so and lets the
     * agent check against his words. Measured: a smoke grant on a second model made a "lucky"
     * block that gave 5 diamonds every time, with every check green. Empty otherwise.
     */
    public static String sameEveryTimeNote(Spec spec, java.util.Collection<String> effects) {
        if (spec == null || spec.when() != When.BROKEN || effects.contains("outcome_table")) {
            return "";
        }
        return "\n  NOTE: breaking it does the SAME thing every time. If he wanted a surprise (lucky, "
                + "mystery, random, \"you never know what you get\"), remake it with effect "
                + "outcome_table instead, and keep it only if he asked for this one thing.";
    }
}
