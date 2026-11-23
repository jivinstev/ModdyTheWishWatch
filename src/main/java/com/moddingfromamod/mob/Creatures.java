package com.moddingfromamod.mob;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.moddingfromamod.species.Species;
import com.moddingfromamod.vanilla.DisplayRig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * <b>The Minecraft side of "which creature do you mean"</b>. Every rule is in
 * {@link CreatureMatch}; this reads the facts it needs off an entity and runs the world queries
 * the tools share, so {@code modify_mob}, {@code look_around}, handle minting and
 * {@code ArtifactIndex} all see the same creatures and none of them sees a hit proxy.
 *
 * <p>⚠ <b>If you are about to write {@code getEntitiesOfClass(LivingEntity.class, …)} for a list
 * the agent will read or act on, call {@link #nearby} instead.</b> Each of the four call sites
 * this replaced had its own copy of that query, and none of them knew a creature's carrier from
 * a real llama.
 */
public final class Creatures {

    private Creatures() {
    }

    /** The facts {@link CreatureMatch} decides on, read off a live entity. */
    public static CreatureMatch.Seen seen(Entity e) {
        Species sp = DisplayRig.speciesOn(e);
        return new CreatureMatch.Seen(
                EntityType.getKey(e.getType()).toString(),
                sp == null ? null : sp.id(),
                sp == null ? null : sp.name(),
                e.getTags(),
                e.getCustomName() == null ? null : e.getCustomName().getString());
    }

    /** A rig's machinery (a hit proxy, or a display hanging off a carrier) — never a referent. */
    public static boolean isRigInternal(Entity e) {
        if (e == null) {
            return false;
        }
        if (CreatureMatch.isRigInternal(e.getTags())) {
            return true;
        }
        return e instanceof net.minecraft.world.entity.Display && DisplayRig.ownerOf(e) != null;
    }

    /** Is this a creature a wish made (its carrier), as opposed to a real mob or a rig's part? */
    public static boolean isCreature(Entity e) {
        return e != null && CreatureMatch.what(seen(e)) == CreatureMatch.What.CREATURE;
    }

    /** Does this entity answer to what the agent typed? See {@link CreatureMatch#matches}. */
    public static boolean matches(Entity e, String query) {
        return e != null && CreatureMatch.matches(seen(e), query);
    }

    /** The kind a handle should record — {@code wishmob:spikor} for a creature, never its carrier. */
    public static String kindId(Entity e) {
        return CreatureMatch.kindId(seen(e));
    }

    /** What to call it in prose (a creature's species name, or the name he gave it); may be null. */
    public static String label(Entity e) {
        return CreatureMatch.label(seen(e));
    }

    /** The word a list the agent reads uses for it — a creature by species, a real mob by type. */
    public static String word(Entity e) {
        return CreatureMatch.word(seen(e));
    }

    /** The species a creature's carrier wears, or null for anything else. */
    public static Species speciesOf(Entity e) {
        return DisplayRig.speciesOn(e);
    }

    /**
     * Every living thing within {@code radius} of {@code origin} a child could mean: alive, not a
     * player, and not a rig's internals. Nearest first.
     */
    public static List<LivingEntity> nearby(ServerLevel level, Vec3 origin, double radius) {
        List<LivingEntity> out = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class,
                new AABB(origin, origin).inflate(radius),
                e -> e.isAlive() && !(e instanceof Player) && !isRigInternal(e)));
        out.sort(Comparator.comparingDouble(e -> e.distanceToSqr(origin)));
        return out;
    }

    /**
     * Every live carrier of this species in one level — "where is Spikor" answered from the world,
     * not from the entity type deleted.
     */
    public static List<LivingEntity> carriersOf(ServerLevel level, String speciesId) {
        List<LivingEntity> out = new ArrayList<>();
        if (level == null || speciesId == null || speciesId.isBlank()) {
            return out;
        }
        for (Entity e : level.getEntities().getAll()) {
            if (e instanceof LivingEntity living && living.isAlive() && !e.isRemoved()
                    && e.getTags().contains(DisplayRig.RIG_TAG)) {
                Species sp = DisplayRig.speciesOn(e);
                if (sp != null && speciesId.equalsIgnoreCase(sp.id())) {
                    out.add(living);
                }
            }
        }
        return out;
    }

    /**
     * Every live carrier in one level, by species id (lower case). One walk of the level for all
     * species at once — what {@code ArtifactIndex} needs, and it asks for every species per call.
     */
    public static java.util.Map<String, List<LivingEntity>> carriersBySpecies(ServerLevel level) {
        java.util.Map<String, List<LivingEntity>> out = new java.util.HashMap<>();
        if (level == null) {
            return out;
        }
        for (Entity e : level.getEntities().getAll()) {
            if (e instanceof LivingEntity living && living.isAlive() && !e.isRemoved()
                    && e.getTags().contains(DisplayRig.RIG_TAG)) {
                Species sp = DisplayRig.speciesOn(e);
                if (sp != null) {
                    out.computeIfAbsent(sp.id().toLowerCase(java.util.Locale.ROOT),
                            k -> new ArrayList<>()).add(living);
                }
            }
        }
        return out;
    }

    /** Stamp the child this creature was spawned for; nothing when there is nobody. */
    public static void stampSpawner(Entity e, String playerId) {
        String tag = CreatureMatch.spawnerTag(playerId);
        if (e != null && !tag.isEmpty()) {
            e.addTag(tag);
        }
    }

    /** {@link CreatureMatch#hisFirst} over live entities, nearest first in, nearest first out. */
    public static <T extends Entity> List<T> hisFirst(List<T> nearestFirst, String playerId) {
        return CreatureMatch.hisFirst(nearestFirst, Entity::getTags, playerId);
    }

    /**
     * <b>Make this one creature a carrier of {@code speciesId}</b> — HIS copy, after a fork
     * (). The rig says which species a carrier is by its tags, so it takes the fork's (and
     * keeps its ancestor's, {@link DisplayRig#speciesTags}); the animator then rebuilds it from the
     * fork on its next tick, because it no longer matches what it was built from. Only this one
     * moves: the other creatures of its kind are somebody's, or the library's.
     */
    public static void wearSpecies(Entity e, String speciesId) {
        if (e instanceof com.moddingfromamod.entity.WishMobEntity wish) {
            wish.setSpeciesId(speciesId);
        } else if (e != null && e.getTags().contains(DisplayRig.RIG_TAG)) {
            for (String t : DisplayRig.speciesTags(speciesId)) {
                e.addTag(t);
            }
            // AND MOVE THE REVISION, which is what makes the animator look again. A caller that
            // registers the fork in one server task and moves the creature in the next (
            // sculpt_creature) gives the animator a tick in between: it sees the new revision,
            // finds the creature still wearing the base, and marks that revision seen — so the
            // retag alone would never be rebuilt.
            com.moddingfromamod.species.SpeciesRegistry.touch();
        }
    }

    /**
     * <b>Take one creature out of the world — carrier, display parts and hit proxies</b>.
     * The one removal {@code remove_creature} and an undo share: two copies of "what a creature is
     * made of" is how an earlier issue's undo came to discard only an entity type had deleted.
     *
     * @return true if it was standing there to remove
     */
    public static boolean remove(ServerLevel level, LivingEntity carrier) {
        if (level == null || carrier == null || carrier.isRemoved()) {
            return false;
        }
        com.moddingfromamod.vanilla.VanillaRigAnimator.forget(carrier.getUUID());
        DisplayRig.reap(level, carrier.getUUID());      // its displays and its hit proxies
        carrier.discard();
        return true;
    }
}
