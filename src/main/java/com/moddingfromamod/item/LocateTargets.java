package com.moddingfromamod.item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>What a finding compass can point at, the pure half.</b> One closed word per
 * place, looked up here — never an id the agent typed — so "a compass that finds villages", "points
 * to the nearest jungle" and "leads me to an ancient city" are one effect with a TARGET.
 *
 * <p>Two kinds, answered by vanilla's own searches ({@link Locate} runs them): a STRUCTURE by the
 * same search as {@code /locate structure}, a BIOME by the same as {@code /locate biome}. A cave is
 * a biome here — lush caves, dripstone caves and the deep dark are the three underground biomes, and
 * a 3-D biome search finds the nearest of them — so "the nearest cave" needs no sampling of air.
 * Each id is a tag ({@code #…}) or a key, spelled the same on 1.21.1 and 1.21.4.
 */
public final class LocateTargets {

    /**
     * {@code PLAYER}: another player's position, re-read at every click and only where the
     * world's {@code player_reach} is open — {@code Locate.askPlayer}. Not a place vanilla searches.
     */
    public enum Kind { STRUCTURE, BIOME, PLAYER }

    /** One place: its kind, the ids that count as it, and how the child says it. */
    public record Target(String word, Kind kind, List<String> ids, String said) {
    }

    private static final Map<String, Target> TARGETS = new LinkedHashMap<>();

    static {
        s("village", "a village", "#minecraft:village");
        s("ancient_city", "an ancient city", "minecraft:ancient_city");
        s("stronghold", "a stronghold", "#minecraft:eye_of_ender_located");
        s("mineshaft", "a mineshaft", "#minecraft:mineshaft");
        s("temple", "a temple", "minecraft:desert_pyramid", "minecraft:jungle_pyramid");
        s("desert_temple", "a desert temple", "minecraft:desert_pyramid");
        s("jungle_temple", "a jungle temple", "minecraft:jungle_pyramid");
        s("woodland_mansion", "a woodland mansion", "minecraft:mansion");
        s("ocean_monument", "an ocean monument", "minecraft:monument");
        s("shipwreck", "a shipwreck", "#minecraft:shipwreck");
        s("ruined_portal", "a ruined portal", "#minecraft:ruined_portal");
        s("pillager_outpost", "a pillager outpost", "minecraft:pillager_outpost");
        s("igloo", "an igloo", "minecraft:igloo");
        s("trial_chambers", "trial chambers", "minecraft:trial_chambers");
        s("buried_treasure", "buried treasure", "minecraft:buried_treasure");
        s("nether_fortress", "a nether fortress", "minecraft:fortress");
        s("bastion", "a bastion", "minecraft:bastion_remnant");
        b("plains", "the plains", "minecraft:plains", "minecraft:sunflower_plains");
        b("jungle", "a jungle", "#minecraft:is_jungle");
        b("desert", "a desert", "minecraft:desert");
        b("ocean", "the ocean", "#minecraft:is_ocean");
        b("mountain", "the mountains", "#minecraft:is_mountain");
        b("forest", "a forest", "#minecraft:is_forest");
        b("taiga", "a taiga", "#minecraft:is_taiga");
        b("savanna", "a savanna", "#minecraft:is_savanna");
        b("badlands", "the badlands", "#minecraft:is_badlands");
        b("swamp", "a swamp", "minecraft:swamp", "minecraft:mangrove_swamp");
        b("snow", "snowy land", "minecraft:snowy_plains", "minecraft:snowy_taiga", "minecraft:ice_spikes");
        b("mushroom_island", "a mushroom island", "minecraft:mushroom_fields");
        b("cherry_grove", "a cherry grove", "minecraft:cherry_grove");
        b("cave", "a cave", "minecraft:lush_caves", "minecraft:dripstone_caves", "minecraft:deep_dark");
        b("lush_cave", "a lush cave", "minecraft:lush_caves");
        TARGETS.put("player", new Target("player", Kind.PLAYER, List.of(), "another player"));
    }

    /** The PLAYER target's word; {@code player.<Name>} names one player, bare {@code player} the nearest. */
    public static final String PLAYER = "player";

    private static final java.util.regex.Pattern PLAYER_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");

    /**
     * The PLACE a compass at another player is written as, from however it arrived ("player",
     * "player:Bob", "player Bob", "player.Bob"), or null when it is not one. The name rides in the
     * tag, so it is held to Minecraft's own name characters.
     */
    public static String playerPlace(String word) {
        if (word == null) {
            return null;
        }
        String w = word.strip();
        if (!w.toLowerCase(Locale.ROOT).startsWith(PLAYER)) {
            return null;
        }
        String rest = w.substring(PLAYER.length()).strip();
        if (rest.isEmpty()) {
            return PLAYER;
        }
        if (rest.startsWith(":") || rest.startsWith(".")) {
            rest = rest.substring(1).strip();
        }
        return PLAYER_NAME.matcher(rest).matches() ? PLAYER + "." + rest : null;
    }

    /** The player a PLACE names ({@code player.Bob} → {@code Bob}), or null for the nearest. */
    public static String playerName(String place) {
        return place != null && place.startsWith(PLAYER + ".") ? place.substring(PLAYER.length() + 1) : null;
    }

    private static void s(String word, String said, String... ids) {
        TARGETS.put(word, new Target(word, Kind.STRUCTURE, List.of(ids), said));
    }

    private static void b(String word, String said, String... ids) {
        TARGETS.put(word, new Target(word, Kind.BIOME, List.of(ids), said));
    }

    private LocateTargets() {
    }

    /** Every word, for the catalogue's doc and the refusal. */
    public static List<String> words() {
        return List.copyOf(TARGETS.keySet());
    }

    /** The target for a word however it arrived ("Villages", "ancient city"), or null. */
    public static Target of(String word) {
        if (word == null) {
            return null;
        }
        String w = word.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (w.startsWith("minecraft:")) {
            w = w.substring(10);
        }
        Target t = TARGETS.get(w);
        if (t == null && w.endsWith("s")) {
            t = TARGETS.get(w.substring(0, w.length() - 1));
        }
        return t;
    }

    /** What he is told: which way and how far, in whole blocks. */
    public static String found(Target t, int blocks) {
        return "✨ This way to " + t.said() + " — about " + blocks + " blocks!";
    }

    public static String notFound(Target t) {
        return "✨ There's no " + t.said().replaceFirst("^(a|an|the) ", "") + " close enough to find from here.";
    }
}
