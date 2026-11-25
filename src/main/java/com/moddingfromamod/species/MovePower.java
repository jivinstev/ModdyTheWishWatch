package com.moddingfromamod.species;

import java.util.Set;

import com.moddingfromamod.runtime.Abilities;

/**
 * <b>What a creature's moves do, per second</b> (#102). Pure. The numbers are the ones the rig lands
 * ({@code vanilla.RigAbilities}), each move at its own pace ({@link Abilities.Kind#cooldown}):
 * <ul>
 *   <li><b>ranged</b>: a ball for half its damage (at least half a point), every 2 seconds;</li>
 *   <li><b>slam</b>: half its damage (at least half a point) to each one near, every 4 seconds;</li>
 *   <li><b>charge</b>: its damage (at least 2), every 5 seconds;</li>
 *   <li><b>heal</b>: a tenth of its health (at least 2) back, every 5 seconds;</li>
 *   <li>its <b>bite</b>, only when it fights (its instinct, {@code RigInstinct}): its damage once a second
 *       ({@code RigInstinct.HIT_EVERY}); a friendly creature never bites.</li>
 * </ul>
 * Against one target, all at once. A move a compiled ability pack or an effect gives it (a dragon's breath) is not
 * counted: its numbers live in a datapack. So these read low for such a creature, never high.
 */
public final class MovePower {

    private MovePower() {
    }

    /** Health points a second its moves (ranged, slam, charge) deal one target; 0 for a creature with none. */
    public static double moveDamagePerSecond(double damage, Set<Abilities.Kind> abilities) {
        double d = Math.max(0, damage);
        double out = 0;
        if (abilities != null) {
            if (abilities.contains(Abilities.Kind.RANGED)) {
                out += Math.max(1.0, d) * 0.5 / seconds(Abilities.Kind.RANGED);
            }
            if (abilities.contains(Abilities.Kind.SLAM)) {
                out += Math.max(1.0, d) * 0.5 / seconds(Abilities.Kind.SLAM);
            }
            if (abilities.contains(Abilities.Kind.CHARGE)) {
                out += Math.max(2.0, d) / seconds(Abilities.Kind.CHARGE);
            }
        }
        return out;
    }

    /** Whether any of its moves hurts. */
    public static boolean hasDamagingMove(Set<Abilities.Kind> abilities) {
        return abilities != null && (abilities.contains(Abilities.Kind.RANGED) || abilities.contains(Abilities.Kind.SLAM)
                || abilities.contains(Abilities.Kind.CHARGE));
    }

    /** Its bite, a second: its damage once a second when it fights, else nothing. */
    public static double meleeDamagePerSecond(double damage, boolean fights) {
        return fights ? Math.max(0, damage) * 20.0 / com.moddingfromamod.vanilla.RigInstinct.HIT_EVERY : 0;
    }

    /** Health points a second it heals itself with the heal move; 0 for a creature without it. */
    public static double healPerSecond(double maxHealth, Set<Abilities.Kind> abilities) {
        if (abilities == null || !abilities.contains(Abilities.Kind.HEAL)) {
            return 0;
        }
        return Math.max(2.0, maxHealth * 0.1) / seconds(Abilities.Kind.HEAL);
    }

    private static double seconds(Abilities.Kind k) {
        return k.cooldown / 20.0;
    }
}
