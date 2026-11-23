package com.moddingfromamod.datapack;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>Who a creature's effect lands on</b>, in the words a wish uses (chunk 2). One rule for
 * every ready-made effect that picks its own victims ({@code on_hit}, {@code breath_cone},
 * {@code inflict_nearby}, {@code lingering_zone}, {@code impulse}), turned into the selector
 * arguments the template appends after its own {@code distance} and {@code tag=!} arguments.
 *
 * <p><b>Why a rule rather than a parameter per template.</b> The baseline graded who got hurt
 * and the child lost every time: a fire breath that burned him, a "bite the monsters" that
 * poisoned him, a "knock the monsters back" that threw him 33 blocks. The agent had TRIED to say
 * who — {@code TARGET: hostile}, {@code TARGET: mobs}, {@code @s[tag=!…]} written into the
 * command — and only {@code impulse} had a word to say it with, defaulting to everyone.
 *
 * <p><b>The default is {@link #DEFAULT}, everything but players.</b> The rule for the
 * track: never the child unless the wish asks. A wish that does ask ("launch ME into the sky",
 * "a fountain that heals me") says so, and {@code players} or {@code everyone} is the word.
 *
 * <p>Always left out, whatever the word: the creature's hit boxes ({@link #PROXY_TAG}), and for the
 * words that cast wide, things that are not creatures (items, arrows, armour stands, the display
 * entities a creature is drawn with, interaction boxes). Pure: no Minecraft here.
 */
public final class EffectVictims {

    /** Everything but players: the child is never caught unless the wish asks. */
    public static final String DEFAULT = "mobs";

    /** The tag a rig's hit boxes wear ({@code CreatureMatch.PROXY_TAG}, inlined at compile time). */
    static final String PROXY_TAG = com.moddingfromamod.mob.CreatureMatch.PROXY_TAG;

    /** The monsters, shipped in this mod's own data — the same list a magic item's "hostile" uses. */
    public static final String MONSTERS_TAG = "#moddingfromamod:hostile";
    /** The animals, likewise ({@code MagicBlock}'s ANIMALS). */
    public static final String ANIMALS_TAG = "#moddingfromamod:animals";

    /** What is never a victim when the word casts wide. */
    static final List<String> NOT_CREATURES = List.of("minecraft:item", "minecraft:arrow",
            "minecraft:armor_stand", "minecraft:block_display", "minecraft:item_display",
            "minecraft:text_display", "minecraft:interaction", "minecraft:marker");

    static final Set<String> EVERYONE = Set.of("everyone", "everybody", "everything", "all", "anyone",
            "anything");
    static final Set<String> PLAYERS = Set.of("players", "player", "me", "kids", "child", "the child",
            "people", "him", "her", "them");
    static final Set<String> MOBS = Set.of("mobs", "mob", "creatures", "others", "not players",
            "everyone else", "non-players", "not me");
    static final Set<String> ANIMALS = Set.of("animals", "animal", "critters", "farm animals");
    static final Set<String> MONSTERS = Set.of("monsters", "monster", "hostile", "hostiles",
            "hostile mobs", "enemies", "enemy", "baddies", "bad guys", "bad mobs");

    /** The words, for an error that lists them. */
    public static final String WORDS = "mobs (the default: everything but players), monsters, "
            + "animals, players, everyone, or a creature like zombies or cows";

    private EffectVictims() {
    }

    /** Is this one of the words (rather than a creature's name)? */
    public static boolean isWord(String word) {
        String w = norm(word);
        return EVERYONE.contains(w) || PLAYERS.contains(w) || MOBS.contains(w) || MONSTERS.contains(w)
                || ANIMALS.contains(w);
    }

    /**
     * The selector arguments, each starting with a comma, for {@code word}: one of the words, or a
     * namespaced entity id ({@code minecraft:zombie}). Null for anything else — the caller turns a
     * creature's plain name into its id first ({@code ApplyEffectTool.resolveCreatureId}).
     *
     * <p>A POSITIVE {@code type=} cannot sit beside any other {@code type=} argument (the selector
     * parser refuses it), so the not-a-creature exclusions ride only on the wide words.
     */
    public static String clause(String word) {
        String w = norm(word == null || word.isBlank() ? DEFAULT : word);
        String proxies = ",tag=!" + PROXY_TAG;
        if (EVERYONE.contains(w)) {
            return notCreatures() + proxies;
        }
        if (MOBS.contains(w)) {
            return ",type=!minecraft:player" + notCreatures() + proxies;
        }
        if (PLAYERS.contains(w)) {
            return ",type=minecraft:player" + proxies;
        }
        if (MONSTERS.contains(w)) {
            return ",type=" + MONSTERS_TAG + proxies;
        }
        if (ANIMALS.contains(w)) {
            return ",type=" + ANIMALS_TAG + proxies;
        }
        if (w.matches("#?[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            return ",type=" + w + proxies;
        }
        return null;
    }

    private static String notCreatures() {
        StringBuilder sb = new StringBuilder();
        for (String t : NOT_CREATURES) {
            sb.append(",type=!").append(t);
        }
        return sb.toString();
    }

    private static String norm(String word) {
        return word.strip().toLowerCase(Locale.ROOT);
    }
}
