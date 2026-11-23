package com.moddingfromamod.world;

import java.util.Locale;
import java.util.Set;

/**
 * <b>What a wish can reach past the child's own base</b> — the closed vocabulary a
 * world's rules file denies, the world-rules grader answers in, and the reach backstop maps every
 * world-wide tool and command onto. Minecraft-free.
 *
 * <p>The eight keys are the ones the first shared world's analysis labelled its corpus with
 * (a labelled corpus of real rule-breaking wishes), so the eval
 * scores the grader in the world designer's own terms and a KPI per rule counts in them too.
 */
public enum WorldEffect {

    /** The time of day — {@code time}. */
    TIME("time", "the time of day", Set.of("time")),
    /** Rain, thunder, clear skies — {@code weather}. */
    WEATHER("weather", "the weather", Set.of("weather")),
    /**
     * Game rules, difficulty, the world border and spawn, the tick rate — and granting
     * advancements or unlocking recipes, which skip the game every child on the world plays: on an
     * island world the goal list is his to earn ("every island plays the same game").
     */
    GAMERULES_DIFFICULTY("gamerules_difficulty", "the game rules",
            Set.of("gamerule", "difficulty", "worldborder", "setworldspawn", "defaultgamemode", "tick",
                    "advancement", "recipe")),
    /** A datapack or standing rule that runs everywhere, forever ({@code set_rule}, {@code author_datapack}). */
    WORLDWIDE_DATAPACKS_STANDING_RULES("worldwide_datapacks_standing_rules", "a rule for the whole world",
            Set.of("datapack", "function", "schedule", "reload")),
    /** Crafting recipes and what mobs or blocks drop, for everyone. */
    RECIPES_DROPS("recipes_drops", "recipes and drops", Set.of()),
    /** Another player's base. */
    OTHERS_BASES("others_bases", "somebody else's base", Set.of()),
    /** Anywhere outside the child's own base. */
    OUTSIDE_OWN_BASE("outside_own_base", "outside your own base", Set.of()),
    /** The Nether and the End. */
    OTHER_DIMENSIONS("other_dimensions", "the Nether and the End", Set.of());

    private final String key;
    private final String words;
    private final Set<String> commands;

    WorldEffect(String key, String words, Set<String> commands) {
        this.key = key;
        this.words = words;
        this.commands = commands;
    }

    /** The file's and the grader's spelling, e.g. {@code gamerules_difficulty}. */
    public String key() {
        return key;
    }

    /** A child-facing noun phrase: "the weather". */
    public String words() {
        return words;
    }

    /** The world-wide command heads this effect covers ({@link CommandCoords#WORLD_WIDE}). */
    public Set<String> commands() {
        return commands;
    }

    /** By key, case-insensitive; null for an unknown key. */
    public static WorldEffect ofKey(String key) {
        if (key == null) {
            return null;
        }
        String k = key.strip().toLowerCase(Locale.ROOT);
        for (WorldEffect e : values()) {
            if (e.key.equals(k)) {
                return e;
            }
        }
        return null;
    }

    /** Which effect a world-wide command head changes; null when it is not one. */
    public static WorldEffect ofCommand(String head) {
        if (head == null) {
            return null;
        }
        String h = head.toLowerCase(Locale.ROOT);
        for (WorldEffect e : values()) {
            if (e.commands.contains(h)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Which effect a whole-world worker TOOL has ({@link BaseReach}'s {@code WORLD_WIDE} kind):
     * {@code set_rule} and {@code author_datapack} are standing rules and datapacks, {@code
     * change_drops} is recipes and drops. Null for any other tool.
     */
    public static WorldEffect ofTool(String tool) {
        if (tool == null) {
            return null;
        }
        return switch (tool) {
            case "set_rule", "author_datapack" -> WORLDWIDE_DATAPACKS_STANDING_RULES;
            case "change_drops" -> RECIPES_DROPS;
            default -> null;
        };
    }

    /** True for the three a base-reach rule enforces rather than a deny-world-effect rule. */
    public boolean isPlace() {
        return this == OTHERS_BASES || this == OUTSIDE_OWN_BASE || this == OTHER_DIMENSIONS;
    }
}
