package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.wishbuilder.MakeFacts;
import com.moddingfromamod.wishbuilder.MakeFit;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A creature fitted to a world's limits on the LIVE entity</b> (#14): the last step for health and attack damage,
 * when no size or strength step of its make lowers them ({@link MakeFit#LIVE}). One attribute modifier per stat, each
 * with its own FIXED id ({@link #MAX_HEALTH_ID}, {@link #ATTACK_DAMAGE_ID}), worked out to bring the value to the limit.
 * It is never stacked: fitting again removes it first and works it out afresh from what the creature has without it.
 *
 * <p>Put on at spawn, on the entity that holds the stats (a wish creature, or a rig's carrier; never a display part),
 * after the species has set its numbers. It is the entity's own: its save keeps it in this world, and nothing else does.
 * It is NEVER written into the spec, the shelf or any wish store, so the same make comes out at its own strength in
 * another world. A world module's own modifiers have their own ids and are left alone; the fit is worked out from the
 * value with them, so it brings the total to the limit. After it is put on the value is read again, as every fit is
 * re-derived. With no world answering, nothing is asked and nothing is put on.
 */
public final class LiveFit {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private LiveFit() {
    }

    /** The modifier a world's health limit puts on a creature. Documented in {@code docs/EXTENDING.md}. */
    public static final ResourceLocation MAX_HEALTH_ID =
            ResourceLocation.fromNamespaceAndPath(ModdingFromAModMod.MODID, "world_fit/max_health");
    /** The modifier a world's attack damage limit puts on a creature. */
    public static final ResourceLocation ATTACK_DAMAGE_ID =
            ResourceLocation.fromNamespaceAndPath(ModdingFromAModMod.MODID, "world_fit/attack_damage");

    /**
     * Fit the creature {@code mob}, of species {@code speciesId}, to this world's limits: the numbers it was fitted in
     * (empty when none). Server thread. Call after the species' own numbers are on it, at spawn or reload.
     */
    public static List<String> apply(LivingEntity mob, String speciesId) {
        return apply(mob, speciesId, false);
    }

    /**
     * …and, with {@code tell}, its owner told once when this is its first fit (a creature newly made: a Builder or
     * Apprentice make, "Make another one", the Magician's), unless a fitted line was said to him a moment ago for the
     * make it is. Only a fresh spawn tells (a creature loaded or rebuilt is never told again), and a rig that replaces
     * a wish creature does not.
     */
    public static List<String> apply(LivingEntity mob, String speciesId, boolean tell) {
        if (mob == null || speciesId == null || !MakeGate.anyWorld()) {
            return List.of();
        }
        var species = com.moddingfromamod.species.SpeciesRegistry.get(speciesId);
        UUID owner;
        try {
            owner = species == null || species.ownerId() == null || species.ownerId().isBlank()
                    ? new UUID(0L, 0L) : UUID.fromString(species.ownerId());
        } catch (IllegalArgumentException notAUuid) {
            owner = new UUID(0L, 0L);
        }
        MakeFacts facts = MakeFactsLookup.forThing(mob.level().getServer(), "creature", speciesId);
        MakeVerdict v = MakeGate.ask(facts, WishAsk.replay(owner, "").withText(species == null ? "" : species.name()));
        Map<String, Double> limits = v.makes() ? v.limits() : Map.of();
        boolean had = modifierOn(mob, Attributes.MAX_HEALTH, MAX_HEALTH_ID) != null
                || modifierOn(mob, Attributes.ATTACK_DAMAGE, ATTACK_DAMAGE_ID) != null;
        List<String> fitted = new ArrayList<>();
        if (fit(mob, Attributes.MAX_HEALTH, MAX_HEALTH_ID, limits.get(MakeFacts.HEALTH_HALF_HEARTS))) {
            fitted.add(MakeFacts.HEALTH_HALF_HEARTS);
            mob.setHealth(Math.min(mob.getHealth(), mob.getMaxHealth()));
        }
        if (fit(mob, Attributes.ATTACK_DAMAGE, ATTACK_DAMAGE_ID, limits.get(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS))) {
            fitted.add(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS);
        }
        if (tell && !had && !fitted.isEmpty() && mob.level().getServer() != null && !MakeGate.toldLately(owner)) {
            MakeGate.sayFitted(mob.level().getServer().getPlayerList().getPlayer(owner), fitted);
        }
        return fitted;
    }

    /** One stat: its fit removed, then put back worked out from the value without it. True when one is on. */
    static boolean fit(LivingEntity mob, Holder<Attribute> attribute, ResourceLocation id, Double limit) {
        var inst = mob.getAttribute(attribute);
        if (inst == null) {
            return false;   // the carrier does not hold this stat (a horse holds no attack damage)
        }
        inst.removeModifier(id);
        if (limit == null) {
            return false;
        }
        double value = inst.getValue();
        if (value <= limit + 1e-9) {
            return false;
        }
        inst.addPermanentModifier(new AttributeModifier(id, limit - value, AttributeModifier.Operation.ADD_VALUE));
        double after = inst.getValue();   // re-derived, as every fit is
        if (after > limit + 1e-6) {
            LOGGER.warn("live fit of {} to {} left {} (another modifier multiplies it)", id, limit, after);
        }
        return true;
    }

    /** The modifier on {@code mob} for this stat's fit, or null: for a test that it is there once, and only there. */
    public static AttributeModifier modifierOn(LivingEntity mob, Holder<Attribute> attribute, ResourceLocation id) {
        var inst = mob.getAttribute(attribute);
        return inst == null ? null : inst.getModifier(id);
    }
}
