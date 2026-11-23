package com.moddingfromamod.event.motion;

import java.util.List;

import com.moddingfromamod.compat.LevelHeights;
import com.moddingfromamod.event.EventMotion;
import com.moddingfromamod.event.EventWriter;
import com.moddingfromamod.event.shape.CrackShape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * <b>{@link CrackShape} opening in the world</b> (E2): {@code stepsPerTick} steps of every
 * crack a tick, so the ground visibly splits outward from the centre. Opened cells are air; under the
 * event's TERRAIN policy it cuts ground and stops at a chest, bedrock or his wall.
 */
public final class CrackMotion implements EventMotion {

    private final List<CrackShape.Step> steps;
    private final BlockPos centre;
    private final int stepsPerTick;
    private int opened;

    public CrackMotion(CrackShape shape, long seed, BlockPos centre, int stepsPerTick) {
        this.steps = shape.steps(seed);
        this.centre = centre;
        this.stepsPerTick = Math.max(1, stepsPerTick);
    }

    /** How many cells the cracks have opened so far. */
    public int opened() {
        return opened;
    }

    @Override
    public boolean step(EventWriter w, int age) {
        int reach = (age + 1) * stepsPerTick;
        int bottom = LevelHeights.bottom(w.level());
        boolean all = true;
        for (CrackShape.Step s : steps) {
            if (s.order() >= reach) {
                all = false;
                continue;
            }
            if (w.full()) {
                return false;
            }
            int x = centre.getX() + s.dx(), z = centre.getZ() + s.dz();
            int g = w.surface(x, z);
            if (g == Integer.MIN_VALUE) {
                continue;
            }
            for (int d = 0; d < s.depth() && g - d > bottom + 1; d++) {
                BlockPos p = new BlockPos(x, g - d, z);
                if (w.ours(p)) {
                    continue;
                }
                if (!w.put(p, Blocks.AIR.defaultBlockState())) {
                    break; // a chest, bedrock, his wall: the crack stops, it never goes round
                }
                opened++;
            }
        }
        return all;
    }
}
