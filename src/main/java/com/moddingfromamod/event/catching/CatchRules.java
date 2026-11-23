package com.moddingfromamod.event.catching;

/**
 * <b>What an event does to what it catches — the feel, decided here rather than found in play</b>
 * (E3). Pure.
 *
 * <p>The numbers are an earlier mod's, where each one was paid for:
 * <ul>
 *   <li><b>A wave carries, it does not launch.</b> Its lift is far under its push, and nothing it does
 *       can throw you higher than {@link #MAX_LIFT} a tick.</li>
 *   <li><b>A push never compounds.</b> Every velocity here REPLACES the component it acts on, so a
 *       push applied every tick holds you at a speed instead of adding up. Its surf ride added a
 *       little every tick and a real client, which owns its own player's movement, overshot by 20
 *       blocks where a server-owned cow overshot by 4.</li>
 *   <li><b>A quake has a middle you can find</b>: it is strongest at the epicentre and exactly nothing
 *       at its reach (it once summoned one at exactly its reach, and it did nothing at all).</li>
 *   <li><b>Being thrown about by an event must not be what kills you.</b> Anything that moves you
 *       also buys {@link #FALL_SAFE_TICKS} of safe landing, because the drop that hurts comes AFTER
 *       the wave has taken its water away from under you.</li>
 *   <li><b>An event he wished for never kills him.</b> His hurt stops at {@link #PLAYER_FLOOR}:
 *       blocks come back with undo, and a lost inventory does not.</li>
 * </ul>
 */
public final class CatchRules {

    private CatchRules() {
    }

    /** Fastest an event carries anything along, blocks a tick (the earlier mod's wave). */
    public static final double MAX_CARRY = 1.15;
    /** Highest it lifts, blocks a tick. */
    public static final double MAX_LIFT = 0.52;
    /** Hardest it slams you down. */
    public static final double MAX_SLAM = 0.62;
    /** A quake's jolt at its epicentre, blocks a tick. */
    public static final double SHAKE = 0.16;
    /** How long a landing is safe after an event moved you. */
    public static final int FALL_SAFE_TICKS = 100;
    /** A player's health never goes below this from an event: one heart. */
    public static final float PLAYER_FLOOR = 2.0F;
    /** The longest an event sets anything alight for. */
    public static final int MAX_BURN_SECONDS = 8;

    /** Strength at {@code distance} from the middle of something {@code reach} across: 1 → 0. */
    public static double falloff(double distance, double reach) {
        if (reach <= 0 || distance >= reach) {
            return 0.0;
        }
        return Math.max(0.0, 1.0 - distance / reach);
    }

    private static double clamp01(double s) {
        return Math.max(0.0, Math.min(1.0, s));
    }

    /**
     * The speed along the heading after being carried: the carry sets a floor, it never adds. A
     * body already moving faster (thrown ahead, or running) keeps its own speed.
     */
    public static double carry(double alongNow, double strength) {
        return Math.max(alongNow, MAX_CARRY * clamp01(strength));
    }

    /**
     * The vertical speed after an event lifts ({@code slam} false) or slams ({@code slam} true):
     * again a floor or a ceiling, never a sum.
     */
    public static double vertical(double yNow, double strength, boolean slam) {
        double s = clamp01(strength);
        return slam ? Math.min(yNow, -MAX_SLAM * s) : Math.max(yNow, MAX_LIFT * s);
    }

    /** A shake: a jolt of this size in the direction {@code angle}, replacing the sideways speed. */
    public static double[] shake(double strength, double angle) {
        double s = SHAKE * clamp01(strength);
        return new double[] {Math.cos(angle) * s, s * 0.5, Math.sin(angle) * s};
    }

    /** How much of {@code amount} may land on a body with {@code health} left. */
    public static float hurt(float amount, float health, boolean player) {
        if (amount <= 0 || health <= 0) {
            return 0;
        }
        if (!player) {
            return amount;
        }
        return Math.max(0, Math.min(amount, health - PLAYER_FLOOR));
    }

    /** Seconds alight, from how hard it was caught. Never zero once caught at all. */
    public static int burnSeconds(double strength) {
        return Math.max(1, (int) Math.round(MAX_BURN_SECONDS * clamp01(strength)));
    }

    /** What sort of block a hailstone or a passing wave might break. */
    public enum Fragile {
        /** Glass, panes, ice: what shatters. */
        GLASS(0.40),
        /** Leaves, crops, flowers, grass: what gets stripped. */
        PLANT(0.40),
        /** Torches, lanterns, pots, carpets: small loose things. */
        CLUTTER(0.18),
        /** Everything else: never. */
        NONE(0.0);

        public final double chance;

        Fragile(double chance) {
            this.chance = chance;
        }
    }

    /** The chance a hit of this strength breaks a block of this sort. */
    public static double breakChance(Fragile kind, double strength) {
        return kind.chance * clamp01(strength);
    }

    /**
     * May a fire be lit in this cell? Only on solid ground, into open air, and only where nothing it
     * could spread to is flammable. A burnt house cannot be given back by undo (D1), so an event's
     * flames are for the ground it scorched, never for his build.
     */
    public static boolean mayLight(boolean open, boolean solidBelow, boolean flammableNearby) {
        return open && solidBelow && !flammableNearby;
    }
}
