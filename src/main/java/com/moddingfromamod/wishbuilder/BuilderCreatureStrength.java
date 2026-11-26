package com.moddingfromamod.wishbuilder;

import java.util.Map;

import com.moddingfromamod.species.PowerBand;
import com.moddingfromamod.species.Species;

/**
 * <b>How a Builder creature's size and "How strong" move its health and attack damage</b> (#103). Pure.
 *
 * <p>Before #103 a creature's numbers came only from its body's file: size changed how it looked and nothing else (a
 * colossal hamster had the hamster's health), there was no strength choice, and the guard temper made a creature fight
 * on its body's 0 damage. Now:
 * <ul>
 *   <li><b>size</b> ({@link BuilderMobSizes}) scales health from Teeny ×0.4 to Colossal ×5 and damage from ×0.6 to
 *       ×2.5: bigger is tougher faster than it hits harder;</li>
 *   <li><b>How strong</b> (1 gentle, 2 its own, 3 strong) scales health ×0.75 / ×1 / ×1.5 and damage ×0.5 / ×1 /
 *       ×1.5;</li>
 *   <li>a <b>guard</b> always hits for at least its size band's damage ({@link PowerBand}), so it can hurt what it
 *       guards against; a vehicle never hits, guard or not;</li>
 *   <li>everything stays inside what the game allows (1 to 500 health, 0 to 40 damage), to the half point.</li>
 * </ul>
 * A Normal-sized creature at its own strength is made exactly as before.
 */
public final class BuilderCreatureStrength {

    /** health ×, damage × for each named size. */
    static final Map<String, double[]> SIZE = Map.of(
            "1", new double[] {0.4, 0.6},     // Teeny
            "2", new double[] {0.5, 0.7},     // Tiny
            "3", new double[] {0.7, 0.85},    // Small
            "4", new double[] {1.0, 1.0},     // Normal
            "5", new double[] {1.5, 1.25},    // Big
            "6", new double[] {2.5, 1.6},     // Huge
            "7", new double[] {3.5, 2.0},     // Giant
            "8", new double[] {5.0, 2.5});    // Colossal

    /** health ×, damage × for How strong 1, 2, 3. */
    static final double[][] STRENGTH = {{0.75, 0.5}, {1.0, 1.0}, {1.5, 1.5}};

    /** The step a creature is at when it states none: its own. */
    public static final int OWN = 2;

    private BuilderCreatureStrength() {
    }

    /** A creature's How strong step, 1 to 3: its own (2) when unstated or unreadable. */
    public static int strengthOf(BuilderSpec spec) {
        try {
            return Math.max(1, Math.min(3, Integer.parseInt(spec.opt("strength").strip())));
        } catch (NumberFormatException none) {
            return OWN;
        }
    }

    /** The named size a creature is made at: its {@code mobsize}, else the one its old 1-4 size was. */
    public static BuilderMobSizes.Level sizeOf(BuilderSpec spec) {
        return spec.opt("mobsize").isEmpty() ? BuilderMobSizes.fromOldSize(spec.size()) : BuilderMobSizes.of(spec.opt("mobsize"));
    }

    /** Its health, from its body's at this size and strength. */
    public static double health(double bodyHealth, BuilderMobSizes.Level size, int strength) {
        double h = bodyHealth * SIZE.getOrDefault(size.id(), SIZE.get("4"))[0] * STRENGTH[clamp(strength) - 1][0];
        return half(Math.max(Species.MIN_HEALTH, Math.min(Species.MAX_HEALTH, h)));
    }

    /**
     * Its attack damage, from its body's at this size and strength; a guard's at least {@code bandDamage} (its size
     * band's), a vehicle's always 0.
     */
    public static double damage(double bodyDamage, BuilderMobSizes.Level size, int strength, boolean guard,
                                double bandDamage, boolean vehicle) {
        if (vehicle) {
            return 0;
        }
        double d = bodyDamage * SIZE.getOrDefault(size.id(), SIZE.get("4"))[1] * STRENGTH[clamp(strength) - 1][1];
        if (guard) {
            d = Math.max(d, bandDamage);
        }
        return half(Math.max(0, Math.min(Species.MAX_DAMAGE, d)));
    }

    private static int clamp(int strength) {
        return Math.max(1, Math.min(3, strength));
    }

    private static double half(double v) {
        return Math.round(v * 2) / 2.0;
    }
}
