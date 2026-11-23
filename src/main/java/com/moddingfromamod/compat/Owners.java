package com.moddingfromamod.compat;

import java.util.UUID;

import net.minecraft.world.entity.OwnableEntity;

/** 1.21.5's OwnableEntity answers getOwnerReference() (a nullable EntityReference), not getOwnerUUID(). */
public final class Owners {

    private Owners() {
    }

    /** What getOwnerUUID() returned: the owner's UUID, or null for an unowned entity. */
    public static UUID uuid(OwnableEntity entity) {
        var ref = entity.getOwnerReference();
        return ref == null ? null : ref.getUUID();
    }

    /** What setOwnerUUID(id) did on a tamable animal: point its owner at {@code id} (null clears it). */
    public static void set(net.minecraft.world.entity.TamableAnimal animal, UUID id) {
        animal.setOwnerReference(id == null ? null : new net.minecraft.world.entity.EntityReference<>(id));
    }
}
