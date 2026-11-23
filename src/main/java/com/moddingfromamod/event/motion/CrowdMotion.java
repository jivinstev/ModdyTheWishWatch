package com.moddingfromamod.event.motion;

import com.moddingfromamod.event.EventMotion;
import com.moddingfromamod.event.EventWriter;
import com.moddingfromamod.event.shape.CrowdShape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * <b>A crowd</b>: N creatures or items standing at the spot, in a ring, or round him — "a
 * herd of horses here", "a ring of snow golems", "ten cats around me".
 *
 * <p>Every one goes through {@link EventWriter#spawn}, so it is tagged with the event (undo finds and
 * removes exactly the crowd), counted by the world guard, and kept: what he asked for is the
 * point, so a mob is told not to despawn and an item never times out. Nothing is written to the world
 * but the crowd itself; a spot whose column is not loaded is skipped, never generated.
 */
public final class CrowdMotion implements EventMotion {

    /** The crowd arrives over a second or so: a herd appearing, not a single pop. */
    public static final int ARRIVE_TICKS = 20;

    private final BlockPos centre;
    private final EntityType<?> type;
    private final ItemStack item;
    private final int n;
    private final boolean ring;
    private final boolean baby;
    private int next;

    /** A crowd of {@code n} of {@code type}, or of {@code item} when {@code type} is null. */
    public CrowdMotion(BlockPos centre, EntityType<?> type, ItemStack item, int n, boolean ring) {
        this(centre, type, item, n, ring, false);
    }

    /** As above; {@code baby}: young ones, where the creature has a young ("baby cows"). */
    public CrowdMotion(BlockPos centre, EntityType<?> type, ItemStack item, int n, boolean ring, boolean baby) {
        this.baby = baby;
        this.centre = centre;
        this.type = type;
        this.item = item;
        this.n = Math.max(1, n);
        this.ring = ring;
    }

    @Override
    public boolean step(EventWriter w, int age) {
        int due = CrowdShape.dueBy(age, n, ARRIVE_TICKS);
        for (; next < due; next++) {
            CrowdShape.Spot s = CrowdShape.spot(next, n, ring);
            double x = centre.getX() + 0.5 + s.dx(), z = centre.getZ() + 0.5 + s.dz();
            int g = w.surface((int) Math.floor(x), (int) Math.floor(z));
            if (g == Integer.MIN_VALUE) {
                continue;
            }
            Entity e;
            if (type != null) {
                e = type.create(w.level(), EntitySpawnReason.COMMAND);
                if (e == null) {
                    continue;
                }
                if (e instanceof Mob m) {
                    m.setPersistenceRequired();   // kept: a hostile crowd must not despawn at dusk
                    if (baby) {
                        m.setBaby(true);   // a no-op on a creature that has no young
                    }
                    m.setYHeadRot(s.yaw());
                }
            } else {
                ItemEntity it = new ItemEntity(w.level(), x, g + 1, z, item.copy());
                it.setUnlimitedLifetime();
                it.setDeltaMovement(0, 0, 0);
                e = it;
            }
            e.snapTo(x, g + 1, z, s.yaw(), 0);
            w.spawn(e);
        }
        return next >= n;
    }
}
