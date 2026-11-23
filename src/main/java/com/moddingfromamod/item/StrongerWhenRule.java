package com.moddingfromamod.item;

import java.util.List;
import java.util.Locale;

/**
 * <b>"Stronger at certain times", the pure half.</b> A closed set of CONDITIONS
 * (night, day, a thunderstorm, rain, deep underground, the Nether, underwater), a closed set of
 * STATS (how hard it hits, how fast it digs) and a bounded AMOUNT — so "a sword that gets stronger
 * at night", "SUPER strong during thunderstorms" and "a pickaxe that mines way faster really deep
 * underground" are one property with three settings rather than three one-off datapacks.
 *
 * <p>Minecraft-free: which condition holds is asked of a {@link World} snapshot, and what the stat
 * becomes is a number. {@link StrongerWhen} reads the stack, builds the snapshot and puts the
 * attribute modifier on or takes it off.
 */
public final class StrongerWhenRule {

    public static final List<String> CONDITIONS =
            List.of("night", "day", "thunder", "rain", "below_y", "in_nether", "underwater");
    public static final List<String> STATS = List.of("attack_damage", "mining_speed");

    /** Attack damage in damage points (2 is one heart); mining speed in 100% steps (1 = twice as fast). */
    public static final int MAX_DAMAGE = 20;
    public static final int MAX_MINING = 10;
    /** "Deep underground" when he says nothing more: below the deepslate line. */
    public static final int DEFAULT_Y = 0;

    /** What the world is like where he is standing, right now. */
    public record World(boolean night, boolean thundering, boolean raining, int y, boolean nether,
                        boolean underwater) {
    }

    /** One item's setting, already validated. */
    public record Setting(String when, String stat, int amount, int belowY) {
        public boolean holds(World w) {
            return switch (when) {
                case "night" -> w.night() && !w.nether();
                case "day" -> !w.night() && !w.nether();
                case "thunder" -> w.thundering();
                case "rain" -> w.raining();
                case "below_y" -> w.y() < belowY;
                case "in_nether" -> w.nether();
                case "underwater" -> w.underwater();
                default -> false;
            };
        }

        /** The modifier's amount: damage points added, or the fraction of base speed added. */
        public double modifier() {
            return amount;
        }
    }

    private StrongerWhenRule() {
    }

    /** What is wrong with this setting, in the agent's terms, or null when it is fine. */
    public static String refusal(String when, String stat, Integer amount) {
        String w = norm(when);
        String s = norm(stat);
        if (!CONDITIONS.contains(w)) {
            return "stronger_when.when is '" + when + "', and it has to be one of: " + String.join(", ", CONDITIONS)
                    + ". If the time he named is not one of those, say so rather than picking one for him.";
        }
        if (!STATS.contains(s)) {
            return "stronger_when.stat is '" + stat + "', and it has to be attack_damage (how hard it hits) "
                    + "or mining_speed (how fast it digs).";
        }
        int max = "attack_damage".equals(s) ? MAX_DAMAGE : MAX_MINING;
        if (amount == null || amount < 1 || amount > max) {
            return "stronger_when.amount is " + amount + ", and for " + s + " it has to be 1 to " + max
                    + ("attack_damage".equals(s) ? " damage points (2 is one heart)"
                            : " (1 = twice as fast, 4 = five times as fast)") + ".";
        }
        return null;
    }

    public static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
