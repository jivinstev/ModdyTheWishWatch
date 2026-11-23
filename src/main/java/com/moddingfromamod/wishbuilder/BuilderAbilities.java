package com.moddingfromamod.wishbuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

/**
 * <b>Creature abilities in the Builder</b>: the reviewed slice of the behaviour effects the wish
 * worker gives a creature through {@code apply_effect} ({@code effects/catalogue.json}). Each is one effect
 * with its parameters fixed, in a kid's words: "Breathes fire" is {@code breath_cone} with a fire command and
 * flame particles, "Leaves flowers" is {@code trail} with poppies.
 *
 * <p><b>Nothing here lands on a player</b> (WISH_BUILDER_PLAN §6.2): every one that touches anybody names
 * {@code TARGET monsters}, so a fire-breathing pet scorches zombies and never him or a friend.
 *
 * <p>Pure. {@code BuilderAbilitiesTest} checks every parameter against the catalogue;
 * {@code BuilderCreatureAbilityGameTest} makes a creature with each through the Builder and watches it work.
 */
public final class BuilderAbilities {

    private BuilderAbilities() {
    }

    /** One ability: its Builder id, the catalogue effect, the tile's words and icon, and the effect's parameters. */
    public record Ability(String id, String effect, String label, String tile, Map<String, String> params,
                          List<String> words) {
    }

    private static Map<String, String> p(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    public static final List<Ability> ALL = List.of(
            new Ability("fire_breath", "breath_cone", "Breathes fire", "minecraft:blaze_powder",
                    p("COMMAND", "data modify entity @s Fire set value 100s", "PARTICLE", "minecraft:flame",
                            "EVERY_SECONDS", "6", "TARGET", "monsters"), List.of("breathes fire", "fire breath", "breathe fire", "shoots fire", "spits fire", "fire breathing", "shoots fireballs", "fireballs", "fire", "sneezes fire", "blows fire", "burps fire", "spits flames", "breathes flames", "shoots flames", "flame breath")),
            new Ability("frost_breath", "breath_cone", "Frosty breath", "minecraft:snowball",
                    p("COMMAND", "effect give @s minecraft:slowness 4 2", "PARTICLE", "minecraft:snowflake",
                            "SOUND", "minecraft:block.powder_snow.place", "EVERY_SECONDS", "6", "TARGET", "monsters"), List.of("frost breath", "ice breath", "breathes ice", "breathes frost", "shoots ice", "freezes monsters", "snow breath", "icy breath", "cold breath", "frosty breath", "freezing breath")),
            new Ability("flower_trail", "trail", "Leaves flowers", "minecraft:poppy",
                    p("BLOCK", "minecraft:poppy", "PARTICLE", "minecraft:happy_villager"), List.of("leaves flowers", "flower trail", "trail of flowers", "flowers behind", "plants flowers", "grows flowers", "makes flowers", "flowers where it walks", "flowers grow where it walks")),
            new Ability("snow_trail", "trail", "Leaves snow", "minecraft:snow",
                    p("BLOCK", "minecraft:snow", "PARTICLE", "minecraft:snowflake"), List.of("leaves snow", "snow trail", "trail of snow", "snow behind", "drops snow", "makes snow", "snow where it walks", "snows where it walks")),
            new Ability("treats", "drops", "Drops treats", "minecraft:cookie",
                    p("ITEM", "minecraft:cookie", "EVERY_SECONDS", "15"), List.of("drops treats", "drops cookies", "gives cookies", "gives treats", "gives me cookies", "poops cookies", "makes treats", "treats fall out", "drops snacks", "gives snacks", "gives me treats")),
            new Ability("honey", "drops", "Makes honey", "minecraft:honey_bottle",
                    p("ITEM", "minecraft:honey_bottle", "EVERY_SECONDS", "20"), List.of("makes honey", "honey", "drops honey", "gives honey")),
            new Ability("spikes", "erupt_blocks", "Crystal spikes", "minecraft:amethyst_shard",
                    p("BLOCK", "minecraft:amethyst_block", "EVERY_SECONDS", "8",
                            // erupt_blocks has no TARGET, so its command names the victims itself: MONSTERS only (§6.2 —
                            // never him or a friend). It said "anything but a player", and a child's other creature beside
                            // it was killed by magic (BuilderSpikesGameTest).
                            "COMMAND", "damage @s[type=" + com.moddingfromamod.datapack.EffectVictims.MONSTERS_TAG + "] 2 minecraft:magic"), List.of("crystal spikes", "shoots spikes", "amethyst spikes", "spikes from the ground", "crystals pop up", "sharp crystals", "shoots crystals", "crystal spike")),
            new Ability("knock_away", "impulse", "Knocks monsters away", "minecraft:piston",
                    p("TRIGGER", "periodic", "EVERY_SECONDS", "4", "DIRECTION", "away", "STRENGTH", "shove",
                            "TARGET", "monsters"), List.of("knocks monsters away", "knocks back", "knockback", "pushes monsters", "pushes away", "shoves monsters", "bounces monsters", "knocks monsters back")));

    public static Ability byId(String id) {
        for (Ability a : ALL) {
            if (a.id().equals(id)) {
                return a;
            }
        }
        return null;
    }

    /** The apply_effect arguments for one ability on the creature tagged {@code tag}. */
    public static JsonObject args(Ability a, String tag) {
        JsonObject o = new JsonObject();
        o.addProperty("effect", a.effect());
        o.addProperty("tag", tag);
        JsonObject params = new JsonObject();
        a.params().forEach(params::addProperty);
        o.add("params", params);
        return o;
    }
}
