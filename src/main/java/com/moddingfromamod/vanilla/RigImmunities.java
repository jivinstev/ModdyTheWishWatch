package com.moddingfromamod.vanilla;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.species.Species;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/**
 * <b>A creature's immunities, kept by its rig carrier</b>. The powers are
 * {@code Species.Power}'s {@code *PROOF} rows, added mechanically by {@code SelfHarmGuard} when a
 * creature is given an ability that could hurt itself, or named by the agent.
 *
 * <ul>
 *   <li><b>fireproof</b> is {@code SpeciesOnRig}'s hidden fire resistance; fire damage is
 *       also cancelled here, so the power holds even before a rebuild has given the effect (its
 *       flames are put out by {@code RigPowers});</li>
 *   <li><b>blast / lightning / frost / fall</b>: that damage is cancelled on the carrier;</li>
 *   <li><b>poison / wither</b>: the effect never takes hold, and wither damage is cancelled.</li>
 * </ul>
 *
 * <p>Only a rig carrier is looked at, and only when something hurts it, so this costs nothing on a
 * quiet world.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class RigImmunities {

    private RigImmunities() {
    }

    /** Does this carrier's species make it immune to this damage? */
    static boolean immune(Species sp, DamageSource src) {
        if (sp == null) {
            return false;
        }
        return (sp.has(Species.Power.FIREPROOF) && src.is(DamageTypeTags.IS_FIRE))
                || (sp.has(Species.Power.BLASTPROOF) && src.is(DamageTypeTags.IS_EXPLOSION))
                || (sp.has(Species.Power.LIGHTNINGPROOF) && src.is(DamageTypeTags.IS_LIGHTNING))
                || (sp.has(Species.Power.FROSTPROOF) && src.is(DamageTypeTags.IS_FREEZING))
                || (sp.has(Species.Power.FALLPROOF) && src.is(DamageTypeTags.IS_FALL))
                || (sp.has(Species.Power.WITHERPROOF) && src.is(DamageTypes.WITHER));
    }

    /**
     * Is this entity DRAWN on fire? That is the synced shared flag, which is what a client reads.
     * {@code isOnFire()} on the server reads the fire ticks instead, which a fire block sets again
     * during every move, so it says "burning" about a carrier no client ever sees burning (the
     * first GameTest read it and blamed a fix that worked).
     */
    public static boolean shownOnFire(Entity e) {
        return Shown.onFire(e);
    }

    /** Never made: a subclass is how {@code Entity}'s protected flag id is reached. */
    private abstract static class Shown extends Entity {
        private Shown() {
            super(null, null);
        }

        static boolean onFire(Entity e) {
            return (e.getEntityData().get(DATA_SHARED_FLAGS_ID) & (1 << FLAG_ONFIRE)) != 0;
        }
    }

    /** A rig's species, or a real mob's behaviour profile seen as one: one rule for both. */
    private static Species speciesOf(Entity e) {
        return com.moddingfromamod.mob.RealMobBehaviours.speciesFor(e);
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (immune(speciesOf(event.getEntity()), event.getSource())) {
            event.setCanceled(true);
            return;
        }
        // a fallproof mount spares its RIDER the landing too. Vanilla hands a vehicle's fall to
        // every passenger (Entity.causeFallDamage), and a rig rider sits on a seat riding the
        // carrier, so a leaping T-rex that never felt its own landings took 9 health off the child
        // on every jump he asked it to make. Only while he is aboard: a fall of his own still hurts.
        if (event.getEntity().isPassenger() && event.getSource().is(DamageTypeTags.IS_FALL)) {
            Species mount = speciesOf(event.getEntity().getRootVehicle());
            if (mount != null && mount.has(Species.Power.FALLPROOF)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onEffect(MobEffectEvent.Applicable event) {
        var inst = event.getEffectInstance();
        if (inst == null || event.getEntity().level().isClientSide()) {
            return;
        }
        boolean poison = inst.is(MobEffects.POISON);
        boolean wither = inst.is(MobEffects.WITHER);
        if (!poison && !wither) {
            return;
        }
        Species sp = speciesOf(event.getEntity());
        if (sp != null && ((poison && sp.has(Species.Power.POISONPROOF))
                || (wither && sp.has(Species.Power.WITHERPROOF)))) {
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
        }
    }
}
