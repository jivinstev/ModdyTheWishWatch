package com.moddingfromamod.redstone;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>The logic bridge</b>: a condition the mod reads from the world that switches a mechanism's wiring, for what plain
 * redstone cannot tell apart (who stepped up, whether he paid, which order the buttons went in, how long ago it last
 * rang). The mod sets a redstone block at the mechanism's buried logic port while the condition holds, so everything
 * downstream is the same wiring, the same proof and the same grader as a lever.
 *
 * <p>Pure: {@link #step} takes what was observed this tick and the rule's memory and says whether the power is on.
 */
public final class Logic {

    /** The kinds of condition. */
    public static final List<String> KINDS = List.of("player_near", "mob_near", "paid", "code", "every", "night", "day",
            "target_hit");

    /**
     * A rule. {@code who}: player names (player_near) or mob ids/"monster" (mob_near); {@code item}/{@code count}
     * (paid); {@code code} (the order of the code buttons, e.g. "2,0,1"); {@code period} and {@code onFor} in ticks
     * (every; onFor also how long paid, code and target_hit hold the power); {@code radius} in blocks.
     */
    public record Rule(String kind, List<String> who, String item, int count, List<Integer> code, int period,
                       int onFor, int radius) {
        public Rule {
            who = who == null ? List.of() : List.copyOf(who);
            code = code == null ? List.of() : List.copyOf(code);
        }
    }

    /**
     * What the mod saw this tick: player names and mob ids within the rule's radius, how many of the item the slot
     * holds, which code buttons went DOWN this tick (their index), whether it is night, the target's power, the game
     * tick.
     */
    public record Seen(Set<String> players, Set<String> mobs, int held, List<Integer> pressed, boolean night,
                       int targetPower, long tick) {
    }

    /** A rule's memory between ticks. */
    public static final class Memory {
        long onUntil = -1;
        final List<Integer> entered = new ArrayList<>();
        long startedAt = -1;
        /** How many of the item to take from the slot this tick (paid); the mod takes them and resets this. */
        public int take;
    }

    private Logic() {
    }

    /** Whether the power is on this tick. */
    public static boolean step(Rule r, Seen s, Memory m) {
        m.take = 0;
        switch (r.kind()) {
            case "player_near" -> {
                if (r.who().isEmpty()) {
                    return !s.players().isEmpty();
                }
                for (String p : s.players()) {
                    for (String w : r.who()) {
                        if (p.equalsIgnoreCase(w)) {
                            return true;
                        }
                    }
                }
                return false;
            }
            case "mob_near" -> {
                for (String mob : s.mobs()) {
                    String id = mob.toLowerCase(Locale.ROOT);
                    if (r.who().isEmpty() || r.who().contains("monster") && Monsters.IDS.contains(id)
                            || r.who().contains(id) || r.who().contains(id.replace("minecraft:", ""))) {
                        return true;
                    }
                }
                return false;
            }
            case "paid" -> {
                if (s.tick() < m.onUntil) {
                    return true;
                }
                if (s.held() >= Math.max(1, r.count())) {
                    m.take = Math.max(1, r.count());
                    m.onUntil = s.tick() + r.onFor();
                    return true;
                }
                return false;
            }
            case "code" -> {
                if (s.tick() < m.onUntil) {
                    return true;
                }
                for (int b : s.pressed()) {
                    m.entered.add(b);
                    // a wrong press starts again (and counts as the first press of a new try)
                    int i = m.entered.size() - 1;
                    if (i >= r.code().size() || !r.code().get(i).equals(b)) {
                        m.entered.clear();
                        if (!r.code().isEmpty() && r.code().get(0).equals(b)) {
                            m.entered.add(b);
                        }
                    }
                    if (m.entered.equals(r.code())) {
                        m.entered.clear();
                        m.onUntil = s.tick() + r.onFor();
                        return true;
                    }
                }
                return false;
            }
            case "every" -> {
                if (m.startedAt < 0) {
                    m.startedAt = s.tick();
                }
                long t = (s.tick() - m.startedAt) % Math.max(1, r.period());
                return t >= r.period() - r.onFor();
            }
            case "night" -> {
                return s.night();
            }
            case "day" -> {
                return !s.night();
            }
            case "target_hit" -> {
                if (s.targetPower() > 0) {
                    m.onUntil = s.tick() + r.onFor();
                    return true;
                }
                return s.tick() < m.onUntil;
            }
            default -> throw new IllegalArgumentException("unknown logic " + r.kind());
        }
    }

    /** What "monster" means for mob_near: the hostile mobs a child would call monsters. */
    static final class Monsters {
        static final Set<String> IDS = Set.of("minecraft:zombie", "minecraft:skeleton", "minecraft:creeper",
                "minecraft:spider", "minecraft:cave_spider", "minecraft:enderman", "minecraft:witch", "minecraft:husk",
                "minecraft:drowned", "minecraft:stray", "minecraft:zombie_villager", "minecraft:pillager",
                "minecraft:vindicator", "minecraft:slime", "minecraft:phantom", "minecraft:silverfish",
                "minecraft:bogged", "minecraft:breeze");
    }
}
