package com.moddingfromamod.vanilla;

import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;

/**
 * <b>Put one wished creature in the world and say what is standing there</b> ().
 *
 * <p>The one spawn path for {@code wishmob spawn}, {@code define_creature}, {@code sculpt_creature}
 * and the spawn egg. Each of them used to create a {@code WishMobEntity}, add it, and count
 * {@code addFreshEntity} returning true — which is false every time, because the
 * bridge cancels that join and builds the rig a tick later. So production read
 * <i>"✨ Spawned 0× Spikor"</i> on every creature, the agent spawned him again (two Spikors), and a
 * wish whose creature genuinely did not appear ended DONE.
 *
 * <p>The wish mob is still made — it is where {@code finalizeSpawn} puts the creature's starting
 * health and where the bridge reads its vitals from — but it is never added: the bridge builds the
 * rig from it synchronously and hands back the carrier.
 */
public final class CreatureSpawn {

    private CreatureSpawn() {
    }

    /**
     * @return the carrier, alive in {@code level}; null when nothing is standing there
     */
    public static LivingEntity spawn(ServerLevel level, String speciesId, double x, double y,
                                     double z, float yaw, EntitySpawnReason reason) {
        return spawn(level, speciesId, x, y, z, yaw, reason, true);
    }

    /**
     * @param forTheWish record the attempt on the wish run in flight. False for a spawn egg: a
     *                   child using an egg while some wish is being granted did not spawn that
     *                   wish's creature.
     */
    public static LivingEntity spawn(ServerLevel level, String speciesId, double x, double y,
                                     double z, float yaw, EntitySpawnReason reason,
                                     boolean forTheWish) {
        if (level == null || speciesId == null || speciesId.isBlank()) {
            return null;
        }
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        if (mob == null) {
            return null;
        }
        mob.snapTo(x, y, z, yaw, 0);
        mob.setSpeciesId(speciesId);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), reason, null);
        LivingEntity carrier = VanillaRigBridge.manifestNow(level, mob);
        // ON THE WISH'S OWN RUN: finish_wish asks whether a creature it spawned is standing,
        // and an undo removes exactly what it spawned. A spawn egg has no run — nothing
        // is recorded, which is right: no wish made it.
        var run = forTheWish ? com.moddingfromamod.agent.AgentContext.currentRun() : null;
        if (run != null) {
            run.creatureSpawned(speciesId, carrier == null ? null : carrier.getUUID());
            // WHOSE IT IS: "my dragon", and the operator's "who made what".
            if (run.playerId() != null) {
                com.moddingfromamod.mob.Creatures.stampSpawner(carrier, run.playerId().toString());
            }
        }
        return carrier;
    }

    /** The handle a spawn hands the agent — minted from the CARRIER (the kind, never its type). */
    public static String remember(LivingEntity carrier) {
        if (carrier == null) {
            return "";
        }
        return com.moddingfromamod.mob.EntityHandles.global().remember(carrier.getUUID(),
                com.moddingfromamod.mob.Creatures.kindId(carrier),
                com.moddingfromamod.mob.Creatures.label(carrier));
    }

    /**
     * <b>Half its drawn WIDTH, in blocks, scale included</b>: how far its flank stands from
     * its middle, the short horizontal side of what is drawn. 0 for a species nobody registered.
     * What a trigger's reach is counted from, so "near its feet" means near its feet at any size.
     */
    public static double halfWidth(String speciesId) {
        var sp = com.moddingfromamod.species.SpeciesRegistry.get(speciesId);
        if (sp == null) {
            return 0.0;
        }
        var sculpted = sp.hasCustomModel()
                ? com.moddingfromamod.runtime.ModelRepository.drawnParts(sp.modelKey())
                : java.util.List.<com.moddingfromamod.runtime.CreatureSculpt.Part>of();
        var e = com.moddingfromamod.runtime.CreatureAnatomy.extents(
                RigGeometry.of(StandInSculpt.manifest(sp, sculpted)));
        double w = Math.min(e.maxX() - e.minX(), e.maxZ() - e.minZ()) / 16.0 * sp.scale();
        return Double.isFinite(w) && w > 0 ? w / 2.0 : 0.0;
    }

    /**
     * <b>Where the {@code index}th creature of a spawn stands for this child</b>: where the
     * wish was aimed, or in front of him, and never so close that his eyes are inside it. The rule
     * is {@link com.moddingfromamod.runtime.SpawnSpot}; this measures the creature it applies to.
     */
    public static Vec3 spotFor(ServerPlayer player, String speciesId, int index) {
        var sp = com.moddingfromamod.species.SpeciesRegistry.get(speciesId);
        double scale = sp == null ? 1.0 : sp.scale();
        var sculpted = sp != null && sp.hasCustomModel()
                ? com.moddingfromamod.runtime.ModelRepository.drawnParts(sp.modelKey())
                : java.util.List.<com.moddingfromamod.runtime.CreatureSculpt.Part>of();
        var e = com.moddingfromamod.runtime.CreatureAnatomy.extents(
                RigGeometry.of(StandInSculpt.manifest(sp, sculpted)));
        double height = (e.maxY() - e.minY()) / 16.0 * scale;
        double span = Math.max(e.maxX() - e.minX(), e.maxZ() - e.minZ()) / 16.0 * scale;
        var anchor = com.moddingfromamod.wish.WishProgress.manifestAnchor(player);
        var aimed = anchor == null ? null : new com.moddingfromamod.runtime.SpawnSpot.Spot(
                anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
        var spot = com.moddingfromamod.runtime.SpawnSpot.choose(player.getX(), player.getY(),
                player.getZ(), player.getYRot(), aimed,
                com.moddingfromamod.runtime.SpawnSpot.standOff(height, span), span, index);
        return new Vec3(spot.x(), spot.y(), spot.z());
    }
}
