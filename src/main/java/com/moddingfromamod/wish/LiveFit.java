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
    /** The modifier a world's armour limit puts on a creature. */
    public static final ResourceLocation ARMOR_ID =
            ResourceLocation.fromNamespaceAndPath(ModdingFromAModMod.MODID, "world_fit/armor");
    /** The modifier a world's knockback resistance limit puts on a creature. */
    public static final ResourceLocation KNOCKBACK_RESISTANCE_ID =
            ResourceLocation.fromNamespaceAndPath(ModdingFromAModMod.MODID, "world_fit/knockback_resistance");

    /** One stat fitted live: its fact, the attribute that holds it, and the fixed id of its modifier. */
    record Stat(String fact, Holder<Attribute> attribute, ResourceLocation id) {
    }

    /** Every stat fitted live, one per {@code MakeFit.LIVE} fact. */
    static final List<Stat> STATS = List.of(
            new Stat(MakeFacts.HEALTH_HALF_HEARTS, Attributes.MAX_HEALTH, MAX_HEALTH_ID),
            new Stat(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS, Attributes.ATTACK_DAMAGE, ATTACK_DAMAGE_ID),
            new Stat(MakeFacts.ARMOR_POINTS, Attributes.ARMOR, ARMOR_ID),
            new Stat(MakeFacts.KNOCKBACK_RESISTANCE_FRACTION, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_RESISTANCE_ID));

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
        boolean had = false;
        for (Stat st : STATS) {
            had |= modifierOn(mob, st.attribute(), st.id()) != null;
        }
        List<String> fitted = new ArrayList<>();
        for (Stat st : STATS) {
            if (fit(mob, st.attribute(), st.id(), limits.get(st.fact()))) {
                fitted.add(st.fact());
            }
        }
        mob.setHealth(Math.min(mob.getHealth(), mob.getMaxHealth()));
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

    /** What a re-fit did to one player's creatures: how many were raised, lowered, or left as they were. */
    public record Refit(int raised, int lowered, int same) {
        /** The one line the player is told for this change, or empty when nothing moved. */
        public String line() {
            if (lowered > 0) {
                return HELD_DOWN;
            }
            return raised > 0 ? UNLEASHED : "";
        }
    }

    /** Said once when a world's limits hold a player's creatures down. */
    public static final String HELD_DOWN = "✨ Your creatures are held back a little in this world for now.";
    /** Said once when a world's limits let a player's creatures be stronger. */
    public static final String UNLEASHED = "✨ Your creatures are stronger now! This world lets them do more.";

    /**
     * <b>This player's limits changed</b> ({@code WorldMakes.limitsChanged}): every living creature his wishes made
     * is fitted again, from its own UNFITTED numbers (each fit removed and worked out afresh). A creature whose
     * health rises keeps how hurt it was (half its hearts before, half after: never healed to full, never stuck
     * half-empty); one whose health falls keeps what it has, up to its new most. He is told once for the change, not
     * once per creature. Server thread.
     */
    public static Refit refit(net.minecraft.server.MinecraftServer server, UUID player) {
        int raised = 0;
        int lowered = 0;
        int same = 0;
        if (server == null || player == null) {
            return new Refit(0, 0, 0);
        }
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                if (!(e instanceof LivingEntity mob) || !e.isAlive()) {
                    continue;
                }
                String speciesId = speciesOf(mob);
                if (speciesId == null || !player.equals(ownerOf(speciesId))) {
                    continue;
                }
                double[] before = values(mob);
                double oldMax = mob.getMaxHealth();
                double oldHealth = mob.getHealth();
                apply(mob, speciesId, false);
                double newMax = mob.getMaxHealth();
                mob.setHealth((float) RefitWords.healthAfter(oldHealth, oldMax, newMax));
                double[] after = values(mob);
                int moved = RefitWords.compare(before, after);
                if (moved < 0) {
                    lowered++;
                } else if (moved > 0) {
                    raised++;
                } else {
                    same++;
                }
            }
        }
        Refit r = new Refit(raised, lowered, same);
        String line = r.line();
        net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p != null && !line.isEmpty() && WishFeedback.sweeps(line, "")) {
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
        }
        LOGGER.info("world limits changed for {}: {} creature(s) raised, {} lowered, {} as they were; told: {}", player,
                raised, lowered, same, p == null ? "not online" : line.isEmpty() ? "nothing moved" : line);
        return r;
    }

    private static double[] values(LivingEntity mob) {
        double[] v = new double[STATS.size()];
        for (int i = 0; i < STATS.size(); i++) {
            var inst = mob.getAttribute(STATS.get(i).attribute());
            v[i] = inst == null ? Double.NaN : inst.getValue();
        }
        return v;
    }

    /** The species a living wish creature is, or null: a wish creature's own, or the one a rig's carrier was built from. */
    public static String speciesOf(LivingEntity mob) {
        if (mob instanceof com.moddingfromamod.entity.WishMobEntity w) {
            return w.speciesId() == null || w.speciesId().isBlank() ? null : w.speciesId();
        }
        var built = com.moddingfromamod.vanilla.VanillaRigAnimator.builtFrom(mob.getUUID());
        return built == null ? null : built.id();
    }

    private static UUID ownerOf(String speciesId) {
        var species = com.moddingfromamod.species.SpeciesRegistry.get(speciesId);
        try {
            return species == null || species.ownerId() == null || species.ownerId().isBlank()
                    ? null : UUID.fromString(species.ownerId());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    // ── a creature's size, fitted where it stands (C) ─────────────────────────────────────────────────────────────

    private static final Map<String, Double> SCALES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The size {@code species} stands at in this world now: its own (asked) scale, fitted to this world's limits at
     * every spawn and never kept in its record or its make. A Builder creature is fitted the way its kept make is
     * (its asked spec through the one gate and fitting a make goes through, the size it would be made at now); any
     * creature is held to a {@code scaleTimes} limit. With no world answering, its own scale. Cheap: remembered per
     * species, limits stamp and reader generation. Server thread.
     */
    public static double drawnScale(com.moddingfromamod.species.Species species) {
        if (species == null) {
            return 1.0;
        }
        if (!MakeGate.anyWorld()) {
            return species.scale();
        }
        UUID owner = ownerOf(species.id());
        var limits = MakeGate.limits(owner);
        String key = species.id() + "|" + species.scale() + "|" + owner + "|" + limits.stamp() + "|"
                + MakeFacts.generation() + "|" + (com.moddingfromamod.wishbuilder.BuilderMake.askedWhileMaking(species.id())
                != null);
        Double hit = SCALES.get(key);
        if (hit != null) {
            return hit;
        }
        double scale = species.scale();
        try {
            scale = fittedScale(species, owner);
        } catch (RuntimeException e) {
            LOGGER.warn("live fit: {}'s size could not be fitted, standing at its own: {}", species.id(), e.toString());
        }
        if (SCALES.size() > 4096) {
            SCALES.clear();
        }
        SCALES.put(key, scale);
        return scale;
    }

    private static double fittedScale(com.moddingfromamod.species.Species species, UUID owner) {
        double scale = species.scale();
        var kept = owner == null ? null
                : com.moddingfromamod.wishbuilder.BuilderSpecStore.get(owner.toString(), species.id());
        var making = com.moddingfromamod.wishbuilder.BuilderMake.askedWhileMaking(species.id());
        if (owner != null && (making != null || kept != null && !kept.magicianTouched())) {
            var asked = making != null ? making : kept.read();
            if (asked != null && (asked.kind() == com.moddingfromamod.wishbuilder.BuilderSpec.Kind.CREATURE
                    || asked.kind() == com.moddingfromamod.wishbuilder.BuilderSpec.Kind.RIDE)) {
                var allow = com.moddingfromamod.wishbuilder.BuilderSettings.forPlayer(owner).allow();
                var built = MakeGate.build(asked, allow, WishAsk.replay(owner, ""));
                double was = MakeFacts.of(allow.capped(asked)).number(MakeFacts.SCALE_TIMES).orElse(0);
                double now = MakeFacts.of(built.spec()).number(MakeFacts.SCALE_TIMES).orElse(0);
                if (built.makes() && was > 0 && now > 0) {
                    scale = species.scale() * now / was;   // as much smaller (or as large again) as its make is fitted
                }
            }
        }
        // any creature: no bigger than this world's most size, whoever made it
        MakeFacts facts = new MakeFacts(com.moddingfromamod.wishbuilder.BuilderSpec.Kind.CREATURE.id(),
                Map.of(MakeFacts.SCALE_TIMES, scale), java.util.Set.of(), true);
        MakeVerdict v = MakeGate.ask(facts, WishAsk.replay(owner == null ? new UUID(0L, 0L) : owner, ""));
        Double most = v.makes() ? v.limits().get(MakeFacts.SCALE_TIMES) : null;
        return most == null ? scale : Math.min(scale, most);
    }
}
