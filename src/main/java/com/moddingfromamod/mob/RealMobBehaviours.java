package com.moddingfromamod.mob;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.RigAbilities;
import com.moddingfromamod.vanilla.RigInstincts;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>A real mob's behaviour profile, run by the SAME code a sculpted creature's is</b>.
 *
 * <p>A sculpted creature's temperament, powers and moves live on its {@link Species} and are acted on
 * every tick by {@link RigInstincts}, {@link RigAbilities} and the immunity handler — all of which
 * take any {@link Mob}: a rig carrier IS a vanilla mob. So a real mob (a vanilla enderman, a modded
 * mob) is given a {@link BehaviourProfile}, stored on it like its {@link PetBehaviours} pet behaviour,
 * and {@link #speciesFor} hands that code a {@link Species} VIEW of it. Nothing about how a guardian
 * fights, what a coward runs from or how a slam lands is written twice; the one difference is the
 * pure rule {@code RigInstinct.forRealMob} (a real mob's own nature stands in for "wild").
 *
 * <p>A real mob keeps everything vanilla gave it: an enderman still teleports and still hates
 * water. Its own anger at a player is the pet behaviour's {@code friendly}, as it always was.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class RealMobBehaviours {

    /** Where the profile lives on the mob: saved with it, like {@code PetBehaviours.KEY}. */
    public static final String KEY = "mfam_behaviour";

    private static final Map<ServerLevel, Set<Mob>> DRIVEN = new ConcurrentHashMap<>();
    /** The species view per mob, rebuilt only when what it is built from changes. */
    private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();

    private record View(String stored, double damage, Species species) {
    }

    private RealMobBehaviours() {
    }

    /** What was set on this mob; {@code NONE} for a mob with no profile, or any rig carrier. */
    public static BehaviourProfile profileOf(Entity e) {
        if (e == null || e.getTags().contains(DisplayRig.RIG_TAG)) {
            return BehaviourProfile.NONE;
        }
        return BehaviourProfile.fromStored(e.getPersistentData().getStringOr(KEY, ""));
    }

    /** The raw stored copy (empty when it has none), for undo and for copying onto a helper. */
    public static String stored(Entity e) {
        return e == null ? "" : e.getPersistentData().getStringOr(KEY, "");
    }

    /** A real mob this class drives: it has a profile and is not a rig carrier. */
    public static boolean isDriven(Entity e) {
        // Cheap on purpose: an instinct asks this of every mob it can see, every step. A stored
        // profile is never written empty (restore removes the key), so present means non-empty.
        return e instanceof Mob && !e.getTags().contains(DisplayRig.RIG_TAG)
                && !e.getPersistentData().getString(KEY).isEmpty();
    }

    /**
     * <b>The species the shared behaviour code should read for this entity</b>: a rig carrier's own
     * species, else a real mob's profile seen as a species, else null. Its damage is the mob's own
     * attack damage (a chicken has none: 0), because a real mob hits with what vanilla gave it.
     */
    public static Species speciesFor(Entity e) {
        if (e == null) {
            return null;
        }
        if (e.getTags().contains(DisplayRig.RIG_TAG)) {
            return DisplayRig.speciesOn(e);
        }
        if (!(e instanceof Mob mob)) {
            return null;
        }
        String stored = stored(mob);
        if (stored.isEmpty()) {
            VIEWS.remove(mob.getUUID());
            return null;
        }
        double damage = attackDamage(mob);
        View v = VIEWS.get(mob.getUUID());
        if (v != null && v.stored.equals(stored) && v.damage == damage) {
            return v.species;
        }
        BehaviourProfile p = BehaviourProfile.fromStored(stored);
        if (p.isEmpty()) {
            return null;
        }
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
        Species sp = new Species("real_" + type, mob.getName().getString(), null, null, 0, 1.0,
                mob.getMaxHealth(), 0.25, damage, 0, 0, p.powers(), "",
                p.base() == null ? Species.Base.WILD : p.base(), p.abilities(), Set.of());
        VIEWS.put(mob.getUUID(), new View(stored, damage, sp));
        return sp;
    }

    private static double attackDamage(Mob mob) {
        var attr = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        return attr == null ? 0 : attr.getValue();
    }

    /**
     * Lay {@code asked} over what the mob already has, and start driving it.
     *
     * @return notes for the agent: the power words a real mob cannot take, by name
     */
    public static List<String> apply(LivingEntity e, BehaviourProfile asked) {
        List<String> notes = new ArrayList<>();
        if (asked == null || asked.isEmpty()) {
            return notes;
        }
        if (!(e instanceof Mob mob)) {
            notes.add("(a " + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())
                    + " cannot be given a temperament, powers or moves.)");
            return notes;
        }
        BehaviourProfile.ForRealMob split = asked.forRealMob();
        if (asked.behaves() != null) {
            // a real mob already lives its own mob's life — the archetypes are modelled on it.
            String kind = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            notes.add("(this is a real " + kind + ", so it already lives a " + kind + "'s life: behaves_like "
                    + "changes how a SCULPTED creature lives. To have one live like a "
                    + asked.behaves().key().replace('_', ' ') + ", sculpt it.)");
        }
        if (!split.refused().isEmpty()) {
            notes.add("(" + String.join(", ", split.refused()) + " belong to a sculpted creature's body; "
                    + "this real " + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath()
                    + " keeps its own. Everything else was given.)");
        }
        BehaviourProfile now = split.kept().over(profileOf(mob));
        restore(mob, now.toStored());
        return notes;
    }

    /** Put a stored profile back exactly; an empty string takes it off. */
    public static void restore(Mob mob, String stored) {
        BehaviourProfile now = BehaviourProfile.fromStored(stored);
        if (now.isEmpty()) {
            mob.getPersistentData().remove(KEY);
            untrack(mob);
            RigInstincts.forget(mob.getUUID());
            RigAbilities.forget(mob.getUUID());
            VIEWS.remove(mob.getUUID());
            return;
        }
        mob.getPersistentData().putString(KEY, now.toStored());
        // A soldier that despawns at dusk is a broken soldier.
        mob.setPersistenceRequired();
        if (now.powers().contains(Species.Power.FRIENDLY) && mob.getTarget() instanceof Player) {
            mob.setTarget(null);
        }
        track(mob);
        // A companion follows, a friendly or rideable one is a pet: the same mapping a species
        // power gets on a rig carrier (SpeciesOnRig), now that the profile exists to be read.
        PetBehaviours.track(mob);
    }

    private static void track(Mob mob) {
        if (mob.level() instanceof ServerLevel level && !mob.getTags().contains(DisplayRig.RIG_TAG)) {
            DRIVEN.computeIfAbsent(level, l -> ConcurrentHashMap.newKeySet()).add(mob);
        }
    }

    private static void untrack(Mob mob) {
        for (Set<Mob> set : DRIVEN.values()) {
            set.remove(mob);
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel && event.getEntity() instanceof Mob mob
                && isDriven(mob)) {
            track(mob);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            untrack(mob);
            VIEWS.remove(mob.getUUID());
        }
    }

    /** Each tick, what each driven mob's profile asks for — through the rig's own code. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (Map.Entry<ServerLevel, Set<Mob>> entry : DRIVEN.entrySet()) {
            ServerLevel level = entry.getKey();
            for (Mob mob : new HashSet<>(entry.getValue())) {
                if (!mob.isAlive() || mob.level() != level || mob.getTags().contains(DisplayRig.RIG_TAG)) {
                    entry.getValue().remove(mob);
                    continue;
                }
                Species sp = speciesFor(mob);
                if (sp == null) {
                    entry.getValue().remove(mob);
                    continue;
                }
                if (mob.deathTime == 0) {
                    RigInstincts.tickReal(mob, sp);
                }
                RigAbilities.tick(mob, sp);
            }
        }
    }
}
