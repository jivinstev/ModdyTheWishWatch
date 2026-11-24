package com.moddingfromamod.vanilla;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * <b>What a creature's rig shows, read off its displays</b>: its dye colours and its drawn size. Shared by
 * {@code RigPaintGameTest}/{@code RigForkGameTest} (Gate B) and the rebuild film ({@code client/test/RebuildFilmClientTest});
 * outside the GameTests because GameTests never ship and the film's client test does.
 */
public final class RigReadout {

    private RigReadout() {
    }

    /** How many of this carrier's displays wear each dye colour. */
    public static Map<Integer, Integer> dyes(ServerLevel level, Entity carrier) {
        String own = DisplayRig.RIG_OWNER_PREFIX + carrier.getUUID();
        Map<Integer, Integer> out = new HashMap<>();
        for (Entity d : level.getEntities().getAll()) {
            if (d instanceof Display.ItemDisplay && !d.isRemoved() && d.entityTags().contains(own)) {
                CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(d);
                ItemStack stack = com.moddingfromamod.compat.EntityNbt.parseStack(level.registryAccess(), tag.getCompoundOrEmpty("item"))
                        .orElse(ItemStack.EMPTY);
                var dye = stack.get(DataComponents.DYED_COLOR);
                out.merge(dye == null ? -1 : dye.rgb() & 0xFFFFFF, 1, Integer::sum);
            }
        }
        return out;
    }

    /**
     * How big the creature is DRAWN: the cube root of its own displays' total volume, read off
     * their saved transforms — what a stock child sees, and not the carrier's hitbox, which a
     * SCALE attribute changes on its own.
     */
    public static double drawnSize(ServerLevel level, Entity carrier) {
        String own = DisplayRig.RIG_OWNER_PREFIX + carrier.getUUID();
        double v = 0;
        for (Entity d : level.getEntities().getAll()) {
            if (d instanceof Display && !d.isRemoved() && d.entityTags().contains(own)) {
                CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(d);
                var tf = com.mojang.math.Transformation.EXTENDED_CODEC
                        .parse(NbtOps.INSTANCE, tag.get("transformation")).result().orElse(null);
                if (tf != null) {
                    var s = tf.scale();
                    v += Math.abs((double) s.x() * s.y() * s.z());
                }
            }
        }
        return Math.cbrt(v);
    }
}
