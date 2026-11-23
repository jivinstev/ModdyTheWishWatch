package com.moddingfromamod.event.motion;

import com.moddingfromamod.event.EventMotion;
import com.moddingfromamod.event.EventWriter;
import com.moddingfromamod.event.shape.SkyDrop;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>A {@link SkyDrop} over the world</b> (E2). Each drop is EITHER an entity of a type
 * (chickens, snowballs for hail) OR a block that falls and settles in a journaled layer (snow).
 *
 * <p>Every entity is TAGGED with the event, so the undo can find the chickens again, and so an effect
 * that reacts to "a hailstone landed" never mistakes his own snowball for one — an earlier mod counted any
 * snowball that landed during hail as hail.
 */
public final class SkyDropMotion implements EventMotion {

    private final SkyDrop shape;
    private final BlockPos centre;
    private final long seed;
    private final EntityType<?> entity;
    private final BlockState settle;
    private int spawned;

    /** Drops entities of {@code type}: every drop the shape makes. */
    public static SkyDropMotion entities(SkyDrop shape, BlockPos centre, long seed,
                                         EntityType<?> type) {
        return new SkyDropMotion(shape, centre, seed, type, null, Integer.MAX_VALUE);
    }

    /**
     * Drops at most {@code most} entities of {@code type}, spread evenly over the whole shower. Blocks
     * are budgeted by the director; creatures are not, and each one is a thinking thing the server
     * ticks: a "little" chicken rain used to drop two hundred.
     */
    public static SkyDropMotion entities(SkyDrop shape, BlockPos centre, long seed,
                                         EntityType<?> type, int most) {
        return new SkyDropMotion(shape, centre, seed, type, null, Math.max(1, most));
    }

    /** Drops {@code block}s that settle, one layer deep, where they land. */
    public static SkyDropMotion settling(SkyDrop shape, BlockPos centre, long seed,
                                         BlockState block) {
        return new SkyDropMotion(shape, centre, seed, null, block, Integer.MAX_VALUE);
    }

    private final int most;

    private SkyDropMotion(SkyDrop shape, BlockPos centre, long seed, EntityType<?> entity,
                          BlockState settle, int most) {
        this.most = most;
        this.shape = shape;
        this.centre = centre;
        this.seed = seed;
        this.entity = entity;
        this.settle = settle;
    }

    public int spawned() {
        return spawned;
    }

    @Override
    public boolean step(EventWriter w, int age) {
        if (age >= shape.ticks) {
            return true;
        }
        SkyDrop.Drop[] drops = shape.at(seed, age);
        int take = entity == null ? drops.length : Math.min(drops.length, SkyDrop.dueAt(most, shape.ticks, age));
        for (int i = 0; i < take; i++) {
            SkyDrop.Drop d = drops[i];
            int x = (int) Math.floor(centre.getX() + 0.5 + d.dx());
            int z = (int) Math.floor(centre.getZ() + 0.5 + d.dz());
            int g = w.surface(x, z);
            if (g == Integer.MIN_VALUE) {
                continue;
            }
            if (entity != null) {
                Entity e = entity.create(w.level(), EntitySpawnReason.COMMAND);
                if (e == null) {
                    continue;
                }
                e.snapTo(x + 0.5, g + 1 + SkyDrop.HEIGHT, z + 0.5, e.getYRot(), 0);
                w.spawn(e);
                spawned++;
            } else {
                // The flake is a visual that places nothing; the layer it leaves is the journaled truth.
                BlockPos top = new BlockPos(x, g + 1, z);
                BlockPos from = top.above(SkyDrop.HEIGHT);
                // fall() blanks the block it starts in, outside any journal: only ever start in air.
                if (w.level().getBlockState(from).isAir() && w.put(top, settle)) {
                    FallingBlockEntity f = FallingBlockEntity.fall(w.level(), from, settle);
                    f.disableDrop();
                    f.dropItem = false;
                    w.adopt(f);
                    spawned++;
                }
            }
        }
        return false;
    }
}
