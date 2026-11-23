package com.moddingfromamod.mob;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Works out what a wish just created, so it can be given a handle.
 *
 * <p>Done by <b>diffing</b> the entities near the child before and after: snapshot the UUIDs, run
 * the work, snapshot again, and anything new is what the wish made. That is deliberately more
 * general than parsing command output — it works for {@code summon}, for a loot drop, for
 * {@code place}, and for a template that spawns a dozen things at once, without knowing anything
 * about the command that did it.
 *
 * <p>Bounded to a radius around the action so it stays cheap and can't attribute a naturally-spawned
 * mob halfway across the world to the child's wish.
 */
public final class EntityCapture {

    /** Wide enough for a big build, tight enough not to sweep in unrelated spawns. */
    private static final double RADIUS = 48;

    private EntityCapture() {
    }

    /** UUIDs of the living non-player entities near {@code origin} right now. */
    public static Set<UUID> snapshot(ServerLevel level, Vec3 origin) {
        Set<UUID> ids = new HashSet<>();
        if (level == null || origin == null) {
            return ids;
        }
        for (LivingEntity e : nearby(level, origin)) {
            ids.add(e.getUUID());
        }
        return ids;
    }

    /**
     * Register everything that appeared since {@code before}, newest handles last.
     *
     * @return the handle names assigned, in spawn order
     */
    public static List<String> rememberNew(ServerLevel level, Vec3 origin, Set<UUID> before) {
        List<String> names = new java.util.ArrayList<>();
        if (level == null || origin == null || before == null) {
            return names;
        }
        for (LivingEntity e : nearby(level, origin)) {
            if (before.contains(e.getUUID())) {
                continue;
            }
            // A wished creature is minted under ITS kind (wishmob:spikor → @spikor), never its
            // carrier's, and a rig's hit proxies are never minted at all: they are
            // invisible machinery, and production handed the agent @frog and @armadillo for them.
            names.add(EntityHandles.global().remember(e.getUUID(), Creatures.kindId(e),
                    Creatures.label(e)));
        }
        return names;
    }

    private static List<LivingEntity> nearby(ServerLevel level, Vec3 origin) {
        return Creatures.nearby(level, origin, RADIUS);
    }

    /** Find a remembered entity in the world, or null if it has died or unloaded. */
    public static LivingEntity find(ServerLevel level, UUID id) {
        if (level == null || id == null) {
            return null;
        }
        Entity e = level.getEntity(id);
        return e instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    /**
     * The same, but across every dimension.
     *
     * <p>Needed because a report of what a wish has touched must be able to say whether the animal
     * is still alive, and a mob he wished on in the Nether is not dead just because the question is
     * being asked from the Overworld. Answering "gone" there would produce exactly the wrong reply
     * to "make him bigger" — a refusal about a perfectly healthy mob.
     */
    public static LivingEntity findAnywhere(net.minecraft.server.MinecraftServer server, UUID id) {
        if (server == null || id == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            LivingEntity found = find(level, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
