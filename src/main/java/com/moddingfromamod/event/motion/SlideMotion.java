package com.moddingfromamod.event.motion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.event.EventMotion;
import com.moddingfromamod.event.EventWriter;
import com.moddingfromamod.event.shape.SlideShape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * <b>A hillside let go</b> (E2). It finds the steepest way down from {@code centre}; if there
 * is no hill it does NOTHING and says so ({@link #moved()} stays 0) rather than claiming a landslide.
 *
 * <p>Tick 0 lifts the scar (journaled: ground to air) and throws a falling block per cell downhill as a
 * VISUAL — it can place nothing. Each block's heap cell is then written, journaled, when its flight
 * would land. So the rubble really moves, the heap is real, and undo puts the hill back.
 */
public final class SlideMotion implements EventMotion {

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private record Landing(int tick, BlockPos pos, BlockState state) {
    }

    private final BlockPos centre;
    private final List<Landing> landings = new ArrayList<>();
    private int moved;
    private int drop;

    public SlideMotion(BlockPos centre) {
        this.centre = centre;
    }

    /** How many blocks the slide moved; 0 means there was no hill to slide. */
    public int moved() {
        return moved;
    }

    public int drop() {
        return drop;
    }

    @Override
    public boolean step(EventWriter w, int age) {
        if (age == 0) {
            plan(w);
        }
        boolean left = false;
        for (Landing l : landings) {
            if (l.tick() == age) {
                w.put(l.pos(), l.state());
            } else if (l.tick() > age) {
                left = true;
            }
        }
        return !left;
    }

    private void plan(EventWriter w) {
        int g0 = w.surface(centre.getX(), centre.getZ());
        if (g0 == Integer.MIN_VALUE) {
            return;
        }
        int[] best = null;
        for (int[] d : DIRS) {
            double len = Math.hypot(d[0], d[1]);
            int x = centre.getX() + (int) Math.round(d[0] / len * SlideShape.SCAN);
            int z = centre.getZ() + (int) Math.round(d[1] / len * SlideShape.SCAN);
            int g = w.surface(x, z);
            if (g != Integer.MIN_VALUE && g0 - g > drop) {
                drop = g0 - g;
                best = d;
            }
        }
        if (best == null || !SlideShape.steepEnough(drop)) {
            return; // no hill: nothing moves, and moved() says so
        }
        double len = Math.hypot(best[0], best[1]);
        double hx = best[0] / len, hz = best[1] / len, px = -hz, pz = hx;
        int run = SlideShape.runOut(drop), width = SlideShape.width(drop), depth = SlideShape.depth(drop);
        int scarLen = SlideShape.SCAN / 2;
        // The foot: where the slope ends, and where the heap piles.
        int footX = centre.getX() + (int) Math.round(hx * SlideShape.SCAN);
        int footZ = centre.getZ() + (int) Math.round(hz * SlideShape.SCAN);
        Map<Long, Integer> heap = new HashMap<>();
        Map<Long, Integer> heapGround = new HashMap<>();
        List<BlockPos> lifted = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        List<Double> ts = new ArrayList<>();
        for (int s = 0; s < scarLen; s++) {
            double t = 1.0 - s / (double) scarLen;
            int half = SlideShape.scarHalfWidth(width, t);
            for (int c = -half; c <= half; c++) {
                int x = (int) Math.floor(centre.getX() + 0.5 + hx * s + px * c);
                int z = (int) Math.floor(centre.getZ() + 0.5 + hz * s + pz * c);
                int g = w.surface(x, z);
                if (g == Integer.MIN_VALUE) {
                    continue;
                }
                for (int d = 0; d < depth; d++) {
                    BlockPos p = new BlockPos(x, g - d, z);
                    BlockState was = w.level().getBlockState(p);
                    if (was.isAir() || !w.put(p, Blocks.AIR.defaultBlockState())) {
                        break;
                    }
                    lifted.add(p);
                    states.add(was);
                    ts.add(t);
                }
            }
        }
        int cap = SlideShape.pileHeight(lifted.size());
        for (int i = 0; i < lifted.size(); i++) {
            BlockPos p = lifted.get(i);
            double t = ts.get(i);
            int land = SlideShape.landing(run, t);
            int side = SlideShape.scatter(p.asLong());
            int lx = (int) Math.floor(footX + 0.5 + hx * land + px * side);
            int lz = (int) Math.floor(footZ + 0.5 + hz * land + pz * side);
            // A full heap column pushes the block on one further, so the heap is a fan, not a tower.
            for (int tries = 0; tries < 6; tries++) {
                long key = BlockPos.asLong(lx, 0, lz);
                if (heap.getOrDefault(key, 0) < cap) {
                    break;
                }
                lx += (int) Math.round(hx);
                lz += (int) Math.round(hz);
            }
            long key = BlockPos.asLong(lx, 0, lz);
            int ground = heapGround.computeIfAbsent(key, k -> w.surface((int) (BlockPos.of(k).getX()),
                    BlockPos.of(k).getZ()));
            if (ground == Integer.MIN_VALUE) {
                continue;
            }
            int n = heap.merge(key, 1, Integer::sum);
            BlockPos at = new BlockPos(lx, ground + n, lz);
            double dist = Math.hypot(lx - p.getX(), lz - p.getZ());
            int flight = 10 + (int) Math.round(dist / 0.8);
            landings.add(new Landing(flight, at, states.get(i)));
            // The visual: thrown hardest from the crown, with a kick to clear the step in front.
            FallingBlockEntity f = FallingBlockEntity.fall(w.level(), p, states.get(i));
            f.disableDrop();
            f.dropItem = false;
            w.adopt(f);
            double push = 0.35 + 0.45 * t;
            f.setDeltaMovement(new Vec3(hx * push + px * side * 0.05, 0.25, hz * push + pz * side * 0.05));
            moved++;
        }
    }
}
