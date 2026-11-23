package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * <b>What a Builder rule DOES</b>: the rule menu's effects, as {@code set_rule} arguments
 * (docs/WISH_BUILDER_PLAN.md §2, "the rule menu into set_rule commands"). A spec names one as
 * {@code rule:<id>}, so a spec never carries a command — only this reviewed table does. Pure.
 */
public final class RuleEffects {

    private RuleEffects() {
    }

    /**
     * What an effect can follow: something HE does or the world does, a mob APPEARING, or a mob DYING.
     * A mob that has just died cannot be made huge, and a boy who jumps should not be (a resized Bedrock
     * player's own view does not change), so each effect names the triggers it fits.
     */
    public enum On { PLAYER, SPAWN, DIES }

    /** One effect: its id, the tile's words, the vanilla item drawn, the set_rule arguments, what it follows. */
    public record Effect(String id, String label, String item, JsonObject args, java.util.Set<On> on, List<String> onlyMobs,
                         List<String> words) {
        public Effect(String id, String label, String item, JsonObject args, java.util.Set<On> on, List<String> words) {
            this(id, label, item, args, on, List.of(), words);
        }

        public boolean fits(String trigger) {
            return on.contains(onOf(trigger));
        }

        /** True when this effect shows on that mob ({@code mob:minecraft:cow} or {@code cow}); any mob when unlisted. */
        public boolean fitsMob(String mob) {
            String m = mob == null ? "" : mob.replace("mob:", "").replace("minecraft:", "");
            return onlyMobs.isEmpty() || m.isEmpty() || onlyMobs.contains(m);
        }
    }

    private static final java.util.Set<On> ANY = java.util.EnumSet.allOf(On.class);
    private static final java.util.Set<On> PLAYER = java.util.EnumSet.of(On.PLAYER);
    private static final java.util.Set<On> SPAWN = java.util.EnumSet.of(On.SPAWN);
    private static final java.util.Set<On> DIES = java.util.EnumSet.of(On.DIES);

    public static final List<Effect> ALL = List.of(
            new Effect("bounce", "Bounce up high", "minecraft:slime_ball", push("up", 1.2),
                    java.util.EnumSet.of(On.PLAYER, On.SPAWN), List.of("bounce", "bounce up high", "launch me", "super high", "boing", "i go flying")),
            new Effect("dash", "Dash forward", "minecraft:feather", push("forward", 1.0), PLAYER, List.of("dash", "dash forward", "zoom forward", "boost", "burst of speed")),
            new Effect("flowers", "Flowers grow around", "minecraft:poppy", grow(3), ANY, List.of("flowers grow", "flowers", "flower", "grow flowers", "flowers pop up", "bloom")),
            new Effect("sparkles", "Sparkles", "minecraft:glowstone_dust",
                    commands("particle minecraft:happy_villager ~ ~1 ~ 0.5 0.5 0.5 0 20"), ANY, List.of("sparkles", "sparkle", "glitter", "confetti", "twinkle")),
            // ──: more that set_rule already does, for him or the world ─────────────────────
            new Effect("fireworks", "Fireworks!", "minecraft:firework_rocket",
                    commands("summon minecraft:firework_rocket ~ ~1 ~ {LifeTime:15}"), ANY, List.of("fireworks", "firework", "rockets go off")),
            new Effect("zoom", "Super speed", "minecraft:sugar",
                    commands("effect give @s minecraft:speed 5 1 true"), java.util.EnumSet.of(On.PLAYER, On.SPAWN), List.of("super speed", "speed", "fast", "go fast", "run fast", "faster")),
            new Effect("hearts", "Hearts and healing", "minecraft:golden_apple",
                    commands("effect give @s minecraft:regeneration 5 1 true", "particle minecraft:heart ~ ~2 ~ 0.4 0.4 0.4 0 6"),
                    PLAYER, List.of("hearts", "heal", "healing", "heals me", "health")),
            new Effect("music", "A happy tune", "minecraft:note_block",
                    commands("playsound minecraft:block.note_block.bell master @a ~ ~ ~ 1 1.5",
                            "particle minecraft:note ~ ~2 ~ 0.6 0.3 0.6 1 6"), ANY, List.of("music", "tune", "song", "happy tune", "bell", "plays a sound")),
            new Effect("snow", "A little snow", "minecraft:snowball",
                    commands("particle minecraft:snowflake ~ ~2.5 ~ 1.5 0.5 1.5 0 60"), ANY, List.of("snow", "snows", "snowflakes", "a little snow", "snowing")),
            // ──: what happens to a MOB as it appears ──────────────────────────────────────
            new Effect("huge", "It is HUGE", "minecraft:red_mushroom_block",
                    commands("attribute @s minecraft:scale base set 2"), SPAWN, List.of("huge", "giant", "big", "bigger", "enormous")),
            new Effect("tiny", "It is tiny", "minecraft:brown_mushroom",
                    commands("attribute @s minecraft:scale base set 0.5"), SPAWN, List.of("tiny", "small", "little", "mini", "baby")),
            new Effect("hat", "It wears a gold hat", "minecraft:golden_helmet",
                    commands("item replace entity @s armor.head with minecraft:golden_helmet"), SPAWN,
                    List.of("zombie", "skeleton"), List.of("hat", "gold hat", "wears a hat", "helmet", "hats")),   // only a humanoid mob draws a helmet
            new Effect("glow", "It glows", "minecraft:glow_ink_sac",
                    commands("effect give @s minecraft:glowing infinite 0 true"), SPAWN, List.of("glow", "glows", "glowing", "shines")),
            // ──: extra drops, when a mob dies (the world's drop list) ─────────────────────
            drop("diamond", "Drops a diamond", "minecraft:diamond", "diamond", "diamonds", "drops a diamond"),
            drop("emerald", "Drops an emerald", "minecraft:emerald", "emerald", "emeralds", "drops an emerald"),
            drop("gold", "Drops gold", "minecraft:gold_ingot", "gold", "gold ingot", "drops gold"),
            drop("cake", "Drops a cake", "minecraft:cake", "cake", "cakes", "drops a cake"),
            drop("cookies", "Drops cookies", "minecraft:cookie", "cookies", "cookie", "drops cookies"),
            drop("apple", "Drops a golden apple", "minecraft:golden_apple", "golden apple", "golden apples", "gold apple", "drops a golden apple"));

    /** The triggers the Builder offers: things that happen to HIM, to the world, or to a mob he picks. */
    public static final List<String> TRIGGERS = List.of("jumps", "sneaks", "sprints", "lands", "sunrise", "rain_starts",
            "mob_spawns", "mob_dies", "sunset", "rain_stops", "fish_caught", "every");   //: the last four

    /** Triggers that happen to a MOB: the flow asks which one, and the rule's {@code who} is it. */
    public static final List<String> MOB_TRIGGERS = List.of("mob_spawns", "mob_dies");

    /** The mobs a rule may be about (vanilla ids, the {@code who} set_rule takes). */
    public static final List<String> RULE_MOBS = List.of("cow", "pig", "sheep", "chicken", "rabbit", "zombie",
            "skeleton", "spider", "creeper");

    public static On onOf(String trigger) {
        String t = trigger != null && trigger.startsWith("when:") ? trigger.substring(5) : trigger;
        return "mob_spawns".equals(t) ? On.SPAWN : "mob_dies".equals(t) ? On.DIES : On.PLAYER;
    }

    private static Effect drop(String id, String label, String item, String... words) {
        return new Effect(id, label, item, commands("summon minecraft:item ~ ~0.5 ~ {Item:{id:\"" + item + "\",count:1}}"), DIES,
                List.of(words));
    }

    public static Effect byId(String id) {
        String w = id != null && id.startsWith("rule:") ? id.substring(5) : id;
        for (Effect e : ALL) {
            if (e.id().equals(w)) {
                return e;
            }
        }
        return null;
    }

    private static JsonObject push(String way, double amount) {
        JsonObject p = new JsonObject();
        p.addProperty(way, amount);
        JsonObject a = new JsonObject();
        a.add("push", p);
        return a;
    }

    private static JsonObject grow(int radius) {
        JsonObject a = new JsonObject();
        a.addProperty("grow", radius);
        return a;
    }

    private static JsonObject commands(String... cmds) {
        JsonArray arr = new JsonArray();
        for (String c : cmds) {
            arr.add(c);
        }
        JsonObject a = new JsonObject();
        a.add("do", arr);
        return a;
    }
}
