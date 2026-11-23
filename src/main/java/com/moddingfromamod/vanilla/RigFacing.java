package com.moddingfromamod.vanilla;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * <b>{@code rig.look_at}: hold a rig turned to face a point or an entity</b> (Moddy Media
 * Stage 2), set by {@code /wishmob face <target> <x y z | entity | free>}.
 *
 * <p>It does not draw anything itself. Each tick, just before the animator draws the rig, it turns
 * the CARRIER (body, head and pitch) towards the target and stops its walking, so he stands where he
 * is (its goals' MOVE flag off, so its own AI takes no step either). The rig then turns to the
 * carrier's yaw the way it always does ({@link RigTurn}: at a creature's pace, the head leading),
 * and the head pitches through the same look the clip write already carries. One writer, no second pose path. An entity target is followed as it moves (the
 * camera player); a target that is gone releases the hold.
 *
 * <p>Held in memory, not saved: a restart forgets it, the way a take is set up again from its
 * timeline anyway.
 *
 * <p><b>It is applied twice a tick.</b> Before the animator draws, and right after the carrier's
 * own tick ({@link #onEntityTick}). A server tick runs the level (the carrier's AI looks about and
 * its body follows its head, ten degrees a step), then the GameTest ticker and everything else that
 * reads the world, and only then {@code ServerTickEvent.Post}, where the animator runs. Applied only
 * there, the hold was true for the drawn rig and ten degrees out for every reader in between. A test
 * caught it 7 times in 20. Re-applying straight after the carrier's own tick makes the hold true
 * everywhere outside that mob's AI.
 */
@EventBusSubscriber(modid = com.moddingfromamod.ModdingFromAModMod.MODID)
public final class RigFacing {

    /** A point, or an entity followed by its eyes. Exactly one is set. */
    record Target(Vec3 point, UUID entity) {
    }

    private static final Map<UUID, Target> HOLDS = new ConcurrentHashMap<>();

    private RigFacing() {
    }

    public static void facePoint(Entity carrier, Vec3 point) {
        HOLDS.put(carrier.getUUID(), new Target(point, null));
    }

    public static void faceEntity(Entity carrier, Entity target) {
        HOLDS.put(carrier.getUUID(), new Target(null, target.getUUID()));
    }

    /** @return true when a hold was released */
    public static boolean release(UUID carrier) {
        return HOLDS.remove(carrier) != null;
    }

    public static void forget(UUID carrier) {
        HOLDS.remove(carrier);
    }

    public static boolean holding(UUID carrier) {
        return HOLDS.containsKey(carrier);
    }

    /** Re-apply a hold straight after its carrier's own tick, so its AI's turn never shows. */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!HOLDS.isEmpty() && event.getEntity() instanceof Mob m && !m.level().isClientSide()) {
            apply(m);
        }
    }

    /**
     * Turn the carrier to its target, if it has one. Called by the animator before it reads the
     * carrier's yaw for this tick's frame.
     */
    static void apply(Mob carrier) {
        Target t = HOLDS.get(carrier.getUUID());
        if (t == null) {
            return;
        }
        Vec3 at = t.point();
        if (at == null) {
            Entity e = carrier.level() instanceof ServerLevel level ? level.getEntity(t.entity()) : null;
            if (e == null || e.isRemoved() || e.level() != carrier.level()) {
                HOLDS.remove(carrier.getUUID());
                return;
            }
            at = e.getEyePosition();
        }
        carrier.getNavigation().stop();
        // (2026-11-20): and its own goals take no step. A stock carrier (a horse) still strolls, and
        // a stroll STARTS inside its AI step and moves it that very tick, before any stop here: 4 runs
        // in 40 the held creature crept a few hundredths of a block, and a point five blocks off turned
        // 2.8 degrees. Disabling MOVE is vanilla's own lever (a mob ridden by a mob); Mob.tick turns it
        // back on every 5 ticks AFTER the AI step, and this runs after that, so the next AI step always
        // sees it off. Released, Mob.updateControlFlags restores it within 5 ticks. A MOVE goal already
        // running is stopped here too, which is what the selector's own cleanup does on its next AI
        // tick, except that a horse eating or rearing is "immobile" and runs no AI tick at all, so a
        // stroll begun before the hold sat "running" for as long as it ate (caught 1 run in 150).
        carrier.goalSelector.disableControlFlag(net.minecraft.world.entity.ai.goal.Goal.Flag.MOVE);
        for (var w : carrier.goalSelector.getAvailableGoals()) {
            if (w.isRunning() && w.getFlags().contains(net.minecraft.world.entity.ai.goal.Goal.Flag.MOVE)) {
                w.stop();
            }
        }
        double dx = at.x - carrier.getX();
        double dz = at.z - carrier.getZ();
        double dy = at.y - carrier.getEyeY();
        if (dx * dx + dz * dz < 1.0E-4) {
            return;   // standing on it: no direction to face
        }
        float yaw = yawTo(dx, dz);
        carrier.setYRot(yaw);
        carrier.setYBodyRot(yaw);
        carrier.setYHeadRot(yaw);
        carrier.setXRot(pitchTo(dx, dy, dz));
    }

    /** Minecraft's yaw (degrees, 0 = +Z, 90 = -X) that faces along {@code dx, dz}. */
    static float yawTo(double dx, double dz) {
        return (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
    }

    /** Minecraft's pitch (degrees, negative = up) that looks along {@code dx, dy, dz}. */
    static float pitchTo(double dx, double dy, double dz) {
        return (float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);
    }
}
