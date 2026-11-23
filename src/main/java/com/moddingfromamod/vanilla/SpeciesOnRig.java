package com.moddingfromamod.vanilla;

import java.util.Set;

import com.moddingfromamod.mob.PetBehaviour;
import com.moddingfromamod.species.Species;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * <b>A creature's species, on its atom-rig carrier</b>.
 *
 * <p>A species' {@code base} and {@code powers} used to be read only inside {@code WishMobEntity}
 * ({@code rebuildGoals}, {@code applySpecies}, {@code fireImmune}). The bridge cancels that entity
 * the moment it joins and hangs the creature on a vanilla carrier instead. So on the rig, which is
 * every creature a stock client sees, a {@code companion} wandered like the carrier it rode,
 * {@code fireproof} burned, and {@code glowing} did not glow. That included the first starter,
 * whose template promises <i>"It follows you"</i>.
 *
 * <p>This class maps them onto the carrier with parts that already work on any vanilla mob:
 * <ul>
 *   <li><b>behaviour</b> ({@code companion}, {@code friendly}, {@code rideable}) is a {@link PetBehaviour}, the
 *       kit. It is a DEFAULT, read live from the species through
 *       {@code PetBehaviours.of}, and never copied onto the carrier. So a re-sculpt that changes
 *       the species changes what the creature does, a world loaded from disk needs nothing
 *       re-applied, and whatever the child sets with {@code modify_mob} ("stop following me") is
 *       stored on the carrier and wins;</li>
 *   <li><b>{@code fireproof}</b> is a hidden, infinite fire resistance, which is what makes fire
 *       and lava cost nothing (a vanilla mob's {@code fireImmune} is its type's, and cannot be
 *       set);</li>
 *   <li><b>{@code glowing}</b> is the carrier's glowing tag, which mirrors onto the
 *       displays;</li>
 *   <li><b>{@code persistent}</b> needs nothing: every carrier is already
 *       {@code setPersistenceRequired} ({@code DisplayRig.spawn}).</li>
 * </ul>
 *
 * <p>What it hunts and runs from ({@code coward}, {@code guardian}, {@code hunter}, hostile,
 * {@code skittish}, {@code leaps}) is {@link RigInstinct}/{@link RigInstincts}, driven
 * from the animator's tick; {@code flies}, {@code boss} and {@code explodes} are
 * {@link RigPowers}.
 */
public final class SpeciesOnRig {

    private SpeciesOnRig() {
    }

    /**
     * What the species asks its carrier to DO, as a pet behaviour, or {@link PetBehaviour#NONE}.
     * Pure: a companion follows the nearest player and never attacks one; a {@code friendly}
     * creature never attacks one either.
     */
    public static PetBehaviour behaviour(Species.Base base, Set<Species.Power> powers) {
        boolean companion = base == Species.Base.COMPANION;
        boolean friendly = companion || (powers != null && powers.contains(Species.Power.FRIENDLY));
        boolean rideable = powers != null && powers.contains(Species.Power.RIDEABLE);
        if (!companion && !friendly && !rideable) {
            return PetBehaviour.NONE;
        }
        return new PetBehaviour(companion ? Boolean.TRUE : null, friendly ? Boolean.TRUE : null,
                rideable ? Boolean.TRUE : null, null);
    }

    public static PetBehaviour behaviour(Species sp) {
        return sp == null ? PetBehaviour.NONE : behaviour(sp.base(), sp.powers());
    }

    /**
     * The species default for this entity, when it is a rig carrier; for a real mob with a behaviour
     * profile, the same mapping from its profile — companion follows, friendly and rideable
     * are a pet's — so a word means one thing on both; {@code NONE} otherwise.
     */
    public static PetBehaviour defaultFor(net.minecraft.world.entity.Entity e) {
        Species sp = DisplayRig.speciesOn(e);
        if (sp != null) {
            return behaviour(sp);
        }
        if (!com.moddingfromamod.mob.RealMobBehaviours.isDriven(e)) {
            return PetBehaviour.NONE;
        }
        var p = com.moddingfromamod.mob.RealMobBehaviours.profileOf(e);
        return behaviour(p.base(), p.powers());
    }

    /**
     * A fire resistance nobody drank: infinite, and with no particles and no icon. Only one shaped
     * exactly like this is ours to take away again, so a potion the child gave the creature is
     * never touched.
     */
    static boolean isOurs(MobEffectInstance e) {
        return e.is(MobEffects.FIRE_RESISTANCE) && e.isInfiniteDuration() && !e.isVisible()
                && !e.showIcon();
    }

    /**
     * Put the species' STATE on a carrier that was just born: at the bridge, or rebuilt after a
     * re-wish. Behaviour needs nothing here, because it is read live.
     *
     * @param glowingBefore whether the carrier being replaced was glowing (a rebuild carries a
     *                      child's own glow over); false at the first birth
     */
    public static void applyAtBirth(Mob carrier, Species sp, boolean glowingBefore) {
        if (carrier == null || sp == null) {
            return;
        }
        fireproof(carrier, sp.has(Species.Power.FIREPROOF));
        carrier.setGlowingTag(glowingBefore || sp.has(Species.Power.GLOWING));
        com.moddingfromamod.mob.PetBehaviours.track(carrier);
    }

    /** Fire resistance on or off, touching only our own. */
    static void fireproof(LivingEntity carrier, boolean on) {
        MobEffectInstance now = carrier.getEffect(MobEffects.FIRE_RESISTANCE);
        if (on) {
            if (now == null || !now.isInfiniteDuration()) {
                carrier.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,
                        MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
            }
        } else if (now != null && isOurs(now)) {
            carrier.removeEffect(MobEffects.FIRE_RESISTANCE);
        }
    }
}
