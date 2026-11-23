package com.moddingfromamod.event.motion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import com.moddingfromamod.event.EventMotion;
import com.moddingfromamod.event.EventWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>Change what is already there, in place</b>: "turn the ground here into gold", "make the
 * trees around me pink", "turn this hill into sand". Nothing is added on top and nothing moves; the
 * blocks the design names become its material, rippling out from where he aimed so it reads as
 * something HAPPENING rather than a swap.
 *
 * <p>Two ways to say what changes, both decided by the design, never guessed here:
 * <ul>
 *   <li><b>the ground</b> ({@code replaces} left out, or "ground"): the top solid block of every
 *       column in the disc;</li>
 *   <li><b>a kind of block</b> (a block id or a tag): every such block in the disc, from a few below
 *       the ground to {@link #ABOVE} over it — so the leaves of the trees around him, not only the
 *       ones touching the floor.</li>
 * </ul>
 *
 * <p>Every block goes through the {@link EventWriter}, so the journal gives it back on undo and the
 * guard keeps his own builds out of it. What it leaves stays until undo (the events rule: what an
 * event LEAVES stays, what only passes goes).
 */
public final class TransformMotion implements EventMotion {

    /** How far over the ground a kind-of-block transform looks: a tall tree's crown. */
    public static final int ABOVE = 24;
    /** How far under the ground it looks: the dirt under the grass, the ore in the hillside. */
    public static final int BELOW = 3;

    private final BlockPos centre;
    private final int radius;
    private final Predicate<BlockState> what;
    private final boolean groundOnly;
    private final BlockState material;
    private final int spreadTicks;
    private List<long[]> cells;   // {pos, distance²}, nearest first
    private int next;

    /**
     * @param what        the blocks to change, or null for "the ground" (any solid top block)
     * @param spreadTicks how long the ripple takes to reach the edge
     */
    public TransformMotion(BlockPos centre, int radius, Predicate<BlockState> what, BlockState material,
                           int spreadTicks) {
        this.centre = centre;
        this.radius = Math.max(1, radius);
        this.what = what;
        this.groundOnly = what == null;
        this.material = material;
        this.spreadTicks = Math.max(1, spreadTicks);
    }

    /** How far (in blocks) the ripple has reached at this age. Pure. */
    public static double reached(int age, int radius, int spreadTicks) {
        return radius * Math.min(1.0, (age + 1) / (double) Math.max(1, spreadTicks));
    }

    @Override
    public boolean step(EventWriter w, int age) {
        if (cells == null) {
            collect(w);
        }
        double r = reached(age, radius, spreadTicks);
        double r2 = r * r;
        while (next < cells.size() && !w.full()) {
            long[] c = cells.get(next);
            if (c[1] > r2) {
                break;   // the ripple has not got there yet
            }
            BlockPos p = BlockPos.of(c[0]);
            if (!w.level().getBlockState(p).equals(material)) {
                w.put(p, material);   // a refused cell (his wall) is stepped over, never retried
            }
            next++;
        }
        return next >= cells.size();
    }

    private void collect(EventWriter w) {
        cells = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                long d2 = (long) dx * dx + (long) dz * dz;
                if (d2 > (long) radius * radius) {
                    continue;
                }
                int x = centre.getX() + dx, z = centre.getZ() + dz;
                int ground = w.surface(x, z);
                if (ground == Integer.MIN_VALUE) {
                    continue;   // not loaded: nothing to change
                }
                if (groundOnly) {
                    BlockPos top = new BlockPos(x, ground, z);
                    BlockState s = w.level().getBlockState(top);
                    if (!s.isAir() && s.getFluidState().isEmpty()) {
                        cells.add(new long[]{top.asLong(), d2});
                    }
                    continue;
                }
                for (int y = ground - BELOW; y <= ground + ABOVE; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (what.test(w.level().getBlockState(p))) {
                        cells.add(new long[]{p.asLong(), d2});
                    }
                }
            }
        }
        cells.sort(Comparator.comparingLong(c -> c[1]));
    }
}
