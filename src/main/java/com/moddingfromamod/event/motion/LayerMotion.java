package com.moddingfromamod.event.motion;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import com.moddingfromamod.event.EventMotion;
import com.moddingfromamod.event.EventWriter;
import com.moddingfromamod.event.shape.LayerShape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>A {@link LayerShape} played in the world</b> (E2): the level rises a block at a time
 * across the disc — filling every lower column up to it, so a valley floods first — holds, then
 * drains from the top. A level too big for one tick's budget carries on where it stopped the next
 * tick, so a gigantic flood simply rises a little more slowly instead of leaving gaps.
 */
public final class LayerMotion implements EventMotion {

    private final LayerShape shape;
    private final BlockState material;
    private final BlockPos centre;
    /** The level the layer is measured from: the ground at the centre, read once. */
    private Integer base;
    /** Every column in the disc, with its ground read once; null entries are unloaded. */
    private List<int[]> columns;
    /** The level each column has been filled to, parallel to {@link #columns}. */
    private int[] filledTo;
    /** Cells by height, so draining takes the top off first. */
    private final TreeMap<Integer, List<Long>> byHeight = new TreeMap<>();
    /** Where the rising pass stopped when the tick's budget ran out. */
    private int cursor;

    public LayerMotion(LayerShape shape, BlockState material, BlockPos centre) {
        this.shape = shape;
        this.material = material;
        this.centre = centre;
    }

    /** The level (an absolute Y) the layer should stand at this age, or {@code base} when it is gone. */
    int targetLevel(int age) {
        return base + shape.layersAt(age);
    }

    @Override
    public boolean step(EventWriter w, int age) {
        if (base == null) {
            int g = w.surface(centre.getX(), centre.getZ());
            if (g == Integer.MIN_VALUE) {
                return true; // nowhere loaded to stand: nothing to do
            }
            // Never ABOVE the centre it was given: a flood around a tower is centred on the tower,
            // whose own column's surface is its roof — the level stood 40 blocks up and the water
            // hung over the ground (measured, the at-e3 grant). Staged anywhere else the centre IS
            // the surface, so this changes nothing there.
            base = Math.min(g, centre.getY());
            columns = new ArrayList<>();
            for (int dx = -shape.reach; dx <= shape.reach; dx++) {
                for (int dz = -shape.reach; dz <= shape.reach; dz++) {
                    if (shape.inside(dx, dz)) {
                        int x = centre.getX() + dx, z = centre.getZ() + dz;
                        columns.add(new int[]{x, z, w.surface(x, z)});
                    }
                }
            }
            filledTo = new int[columns.size()];
            for (int i = 0; i < filledTo.length; i++) {
                // Never dig below the deepest the layer is allowed to go under its own base.
                filledTo[i] = Math.max(columns.get(i)[2], base - shape.depth);
            }
        }
        int layers = shape.layersAt(age);
        // When the last layer goes, everything goes, including what filled the low ground under
        // the base level (a pit, a valley): otherwise that water outlives the flood.
        int level = layers == 0 ? Integer.MIN_VALUE : targetLevel(age);
        // Drain whatever stands above the level, top first.
        while (!byHeight.isEmpty() && byHeight.lastKey() > level) {
            for (Long c : byHeight.pollLastEntry().getValue()) {
                w.release(BlockPos.of(c));
            }
            for (int i = 0; i < filledTo.length; i++) {
                filledTo[i] = Math.min(filledTo[i], level);
            }
        }
        // Rise: fill every column up to the level, resuming where the last tick's budget ran out.
        for (int n = 0; layers > 0 && n < columns.size() && !w.full(); n++) {
            int i = (cursor + n) % columns.size();
            int[] col = columns.get(i);
            if (col[2] == Integer.MIN_VALUE) {
                continue;
            }
            while (filledTo[i] < level && !w.full()) {
                int y = filledTo[i] + 1;
                BlockPos p = new BlockPos(col[0], y, col[1]);
                if (w.put(p, material)) {
                    byHeight.computeIfAbsent(y, k -> new ArrayList<>()).add(p.asLong());
                }
                filledTo[i] = y; // a refused cell (his wall) is stepped over, not retried forever
            }
            if (w.full()) {
                cursor = i;
                break;
            }
        }
        return layers == 0 && byHeight.isEmpty();
    }
}
