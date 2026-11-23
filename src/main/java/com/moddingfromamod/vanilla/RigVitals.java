package com.moddingfromamod.vanilla;

/**
 * <b>What a rig carrier's combat statistics should be — decided without importing Minecraft.</b>
 *
 * <p>The bug this exists to fix is one nobody would find by reading the rig: a rig carrier was
 * given the creature's SHAPE and none of its NUMBERS. {@code DisplayRig.spawn} overrides exactly
 * one attribute ({@code minecraft:scale}, and only so the hitbox matches the model), so a wished
 * dragon with 200 health and 18 damage stood in the world with a <i>horse's</i> 15 health and no
 * attack damage at all. Meanwhile {@code WishMobEntity.applySpecies} sets five attributes from the
 * species. The two paths were never compared because nothing asked them the same question.
 *
 * <p>It stayed invisible because every gate asked something adjacent. B1 asks whether the rig
 * <i>can be hurt</i> — a horse can. B6 asks whether {@code modify_mob}'s resize <i>reaches</i> the
 * base — the test sets the attribute itself and then checks it took, which is true of a base with
 * no species stats whatsoever. **A control that is true of the bug is not a control** (ladder §2.2),
 * and "it has health" is true of every mob in the game.
 *
 * <p>Two rules live here, and both are judgements rather than arithmetic:
 *
 * <ol>
 *   <li><b>Health carries across the swap as a FRACTION, not as a number.</b> The bridge replaces a
 *       {@code WishMobEntity} that may be halfway through a fight, and the carrier's maximum is
 *       about to change from a horse's to the species'. Copying the raw value would heal a hurt
 *       creature to near-full when the maximum went up, and copying nothing at all would silently
 *       full-heal every creature in an existing save the first time it loaded on the new path.</li>
 *   <li><b>A fresh creature starts at full.</b> Its "previous health" is meaningless.</li>
 * </ol>
 */
public record RigVitals(double maxHealth, double health, double damage, double armor,
                        double knockbackResistance, double speed) {

    /** A creature spawning for the first time: full health, straight off the species. */
    public static RigVitals fresh(double speciesHealth, double speciesDamage, double speciesArmor,
                                  double speciesKnockback, double speciesSpeed) {
        return new RigVitals(speciesHealth, speciesHealth, speciesDamage, speciesArmor,
                speciesKnockback, speciesSpeed);
    }

    /**
     * A creature being SWAPPED from the legacy path, carrying whatever health it had.
     *
     * @param previousHealth    the entity's live health, before it is cancelled
     * @param previousMaxHealth the maximum that health was measured against
     */
    public static RigVitals carrying(double speciesHealth, double speciesDamage,
                                     double speciesArmor, double speciesKnockback,
                                     double speciesSpeed,
                                     double previousHealth, double previousMaxHealth) {
        double max = Math.max(1.0, speciesHealth);
        if (previousMaxHealth <= 0 || previousHealth <= 0) {
            // No usable reading — a creature we cannot measure is not a creature we may injure.
            return fresh(speciesHealth, speciesDamage, speciesArmor, speciesKnockback, speciesSpeed);
        }
        double fraction = Math.min(1.0, previousHealth / previousMaxHealth);
        // Never round a survivor down to nothing: a rig that spawns dead is a creature that
        // vanished, and the child's last memory of it is that it was still standing.
        double health = Math.max(1.0, fraction * max);
        return new RigVitals(max, health, speciesDamage, speciesArmor, speciesKnockback,
                speciesSpeed);
    }
}
